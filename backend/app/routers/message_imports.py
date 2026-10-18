import hashlib
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user, require_institution_scope
from app.core.limiter import limiter
from app.models.message_import import MessageImport
from app.models.transaction import Transaction, TransactionMethod, TransactionType
from app.models.user import User, UserRole
from app.routers.transactions import _check_line, _sale_cost
from app.schemas.message_import import ApproveRequest, ImportRequest, ImportResult, MessageImportOut
from app.utils.ledger import post_transaction
from app.utils.mpesa_parser import parse_message

router = APIRouter(prefix="/api/imports", tags=["imports"])

OPEN_STATUSES = ("detected", "needs_review")


def _code_exists(db: Session, institution_id: int, code: str) -> Transaction | None:
    return (
        db.query(Transaction)
        .filter(Transaction.institution_id == institution_id, func.upper(Transaction.mpesa_code) == code)
        .order_by(Transaction.id.desc())
        .first()
    )


def _scoped(db: Session, user: User):
    q = db.query(MessageImport).filter(MessageImport.institution_id == user.institution_id)
    return q if user.role == UserRole.INSTITUTION_ADMIN else q.filter(MessageImport.recorded_by_id == user.id)


def _get_open(db: Session, user: User, import_id: int) -> MessageImport:
    row = _scoped(db, user).filter(MessageImport.id == import_id).first()
    if not row:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Import not found")
    if row.status not in OPEN_STATUSES:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="This message was already handled")
    return row


@router.post("", response_model=ImportResult, status_code=status.HTTP_201_CREATED)
@limiter.limit("20/minute")
def import_messages(payload: ImportRequest, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """Parses pasted or shared M-Pesa messages into a review queue; nothing is posted to the books here."""
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff record transactions")
    items, skipped = [], 0
    for text in payload.messages:
        parsed = parse_message(text)
        if not parsed["matched"] or not parsed["code"]:
            skipped += 1
            continue
        existing = _scoped(db, current_user).filter(MessageImport.code == parsed["code"]).first()
        if existing:
            items.append(existing)
            continue
        txn = _code_exists(db, current_user.institution_id, parsed["code"])
        if txn:
            row_status, reason = "matched", "already_recorded"
        elif parsed["confidence"] == "high" and parsed["kind"] != "reversal" and not parsed["bank_hint"]:
            row_status, reason = "detected", None
        else:
            row_status, reason = "needs_review", ("bank_bound" if parsed["bank_hint"] else parsed["reason"])
        row = MessageImport(
            institution_id=current_user.institution_id,
            recorded_by_id=current_user.id,
            code=parsed["code"],
            kind=parsed["kind"],
            direction=parsed["direction"],
            amount=parsed["amount"],
            fee=parsed["fee"],
            balance=parsed["balance"],
            counterparty=(parsed["counterparty"] or None) and parsed["counterparty"][:150],
            account_ref=(parsed["account_ref"] or None) and parsed["account_ref"][:60],
            bank_hint=parsed["bank_hint"],
            message_time=parsed["when"],
            status=row_status,
            reason=reason,
            text_hash=hashlib.sha256(" ".join(text.split()).encode()).hexdigest(),
            transaction_id=txn.id if txn and txn.recorded_by_id == current_user.id else None,
        )
        db.add(row)
        db.flush()
        items.append(row)
    db.commit()
    for row in items:
        db.refresh(row)
    return ImportResult(items=items, skipped=skipped)


@router.get("", response_model=list[MessageImportOut])
def list_imports(status_filter: str | None = None, limit: int = 100, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    if current_user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only members of an institution use imports")
    q = _scoped(db, current_user)
    if status_filter:
        q = q.filter(MessageImport.status == status_filter)
    return q.order_by(MessageImport.id.desc()).limit(min(max(limit, 1), 300)).all()


@router.post("/{import_id}/approve", response_model=MessageImportOut)
@limiter.limit("30/minute")
def approve_import(import_id: int, payload: ApproveRequest, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """Turns a reviewed message into a normal entry; the amount and code come from the parsed message, never from the client."""
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff record transactions")
    row = _get_open(db, current_user, import_id)
    if row.amount is None or row.direction not in ("in", "out") or row.kind == "reversal":
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="This message cannot be posted automatically; enter it manually")
    if _code_exists(db, current_user.institution_id, row.code) and not payload.force:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="This M-Pesa code is already recorded. Save anyway?")
    txn_type = TransactionType.INCOME if row.direction == "in" else TransactionType.EXPENSE
    category, stock_item_id, quantity = _check_line(db, current_user, txn_type, payload.category_type, payload.category, row.amount, payload.stock_item_id, payload.quantity)
    txn = Transaction(
        cost_total=_sale_cost(db, txn_type, stock_item_id, quantity),
        institution_id=current_user.institution_id,
        type=txn_type,
        method=TransactionMethod.MPESA,
        category_type=payload.category_type,
        category=category,
        description=(payload.description.strip() if payload.description else None) or None,
        amount=row.amount,
        stock_item_id=stock_item_id,
        quantity=quantity,
        mpesa_code=row.code,
        mpesa_payer_name=row.counterparty,
        transaction_date=datetime.now(timezone.utc),
        recorded_by_id=current_user.id,
    )
    db.add(txn)
    db.flush()
    post_transaction(db, txn)
    row.status = "imported"
    row.transaction_id = txn.id
    row.reviewed_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(row)
    return row


@router.post("/{import_id}/ignore", response_model=MessageImportOut)
def ignore_import(import_id: int, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff record transactions")
    row = _get_open(db, current_user, import_id)
    row.status = "ignored"
    row.reviewed_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(row)
    return row
