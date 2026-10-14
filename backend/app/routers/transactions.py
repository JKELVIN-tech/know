from datetime import datetime, timezone
from decimal import Decimal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile, status
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user, require_institution_scope
from app.core.limiter import limiter
from app.core.staff_scope import resolve_staff_filter
from app.models.audit import Audit, AuditStatus
from app.models.stock_item import StockItem
from app.models.transaction import Transaction, TransactionType, TransactionMethod, TransactionCategoryType
from app.models.user import User, UserRole, StaffType
from pydantic import ValidationError

from app.schemas.transaction import BatchCreate, TransactionOut
from app.utils.ledger import post_transaction
from app.utils.uploads import save_transaction_image, delete_storage_object

router = APIRouter(prefix="/api/transactions", tags=["transactions"])


def _scoped_institution_id(current_user: User, requested_institution_id: int | None) -> int:
    """
    Staff and institution_admin are locked to their own institution —
    an institution_admin passing a different institution_id must NOT see
    another institution's transactions. Only a super admin may specify
    institution_id explicitly, since they aren't tied to one institution.
    """
    if current_user.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        return current_user.institution_id
    if requested_institution_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required")
    return requested_institution_id


def _apply_stock_delta(item: StockItem, txn_type: TransactionType, quantity: Decimal) -> None:
    """A STOCK expense is a restock (adds to inventory); a STOCK income is
    a sale (removes from inventory, and can never go negative)."""
    if txn_type == TransactionType.EXPENSE:
        item.quantity += quantity
    else:
        if item.quantity < quantity:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Not enough stock: only {item.quantity} of '{item.name}' available",
            )
        item.quantity -= quantity


def _reverse_stock_delta(item: StockItem, txn_type: TransactionType, quantity: Decimal) -> None:
    """Undoes _apply_stock_delta — used when editing/deleting a STOCK
    transaction, so the running count stays correct."""
    if txn_type == TransactionType.EXPENSE:
        item.quantity -= quantity
    else:
        item.quantity += quantity


def _check_line(db, current_user, type, category_type, category, amount, stock_item_id, quantity):
    """Validates one entry and applies its stock change; returns the cleaned category, item and quantity."""
    if amount <= 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="amount must be greater than 0")

    if category_type == TransactionCategoryType.STOCK:
        if current_user.staff_type == StaffType.TEACHER:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Personal accounts don't use stock")
        if stock_item_id is None or quantity is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="stock_item_id and quantity are required for a stock entry")
        if quantity <= 0:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="quantity must be greater than 0")
        stock_item = db.query(StockItem).filter(StockItem.id == stock_item_id, StockItem.institution_id == current_user.institution_id, or_(StockItem.branch_id.is_(None), StockItem.branch_id == current_user.id)).first()
        if not stock_item:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stock item not found")
        category = (category or stock_item.name).strip()
        _apply_stock_delta(stock_item, type, quantity)
        return category, stock_item_id, quantity

    category = (category or "").strip()
    if not category:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="category is required")
    return category, None, None


def _release_image(db, path):
    # Lines of one batch can share a photo, so it is only removed once nothing else points at it
    if path and db.query(Transaction).filter(Transaction.image_path == path).count() == 0:
        delete_storage_object(path)


@router.post("", response_model=TransactionOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("30/minute")
def create_transaction(
    request: Request,
    type: TransactionType = Form(...),
    method: TransactionMethod = Form(...),
    category_type: TransactionCategoryType = Form(TransactionCategoryType.OTHER),
    category: str | None = Form(None),
    description: str | None = Form(None),
    amount: Decimal = Form(...),
    stock_item_id: int | None = Form(None),
    quantity: Decimal | None = Form(None),
    mpesa_code: str | None = Form(None),
    mpesa_payer_name: str | None = Form(None),
    image: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    """
    Records a Receiving (income) or Paying (expense) entry. The evidence
    photo is optional; the date is never taken from the client — it's
    always "now" on the server, so it can't be backdated or mismatched.

    A STOCK entry (category_type=STOCK) links to a stock_item_id and a
    quantity: an EXPENSE restocks it (quantity goes up), an INCOME sells
    it (quantity goes down, capped at what's on hand). An OTHER entry
    (rent, utilities, ...) behaves exactly like before — free-text
    category, no item/quantity.
    """
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff record transactions")

    category, stock_item_id, quantity = _check_line(db, current_user, type, category_type, category, amount, stock_item_id, quantity)

    image_path = None
    if image is not None and image.filename:
        contents = image.file.read()
        image_path = save_transaction_image(image, contents)

    txn = Transaction(
        institution_id=current_user.institution_id,
        type=type,
        method=method,
        category_type=category_type,
        category=category,
        description=(description.strip() if description else None) or None,
        amount=amount,
        stock_item_id=stock_item_id,
        quantity=quantity,
        mpesa_code=(mpesa_code.strip() if mpesa_code else None) or None,
        mpesa_payer_name=(mpesa_payer_name.strip() if mpesa_payer_name else None) or None,
        transaction_date=datetime.now(timezone.utc),
        image_path=image_path,
        recorded_by_id=current_user.id,
    )
    db.add(txn)
    db.flush()
    post_transaction(db, txn)
    db.commit()
    db.refresh(txn)
    return txn


@router.get("", response_model=list[TransactionOut])
def list_transactions(
    institution_id: int | None = None,
    method: TransactionMethod | None = None,
    category_type: TransactionCategoryType | None = None,
    start_date: datetime | None = None,
    end_date: datetime | None = None,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    scoped_institution_id = _scoped_institution_id(current_user, institution_id)
    query = db.query(Transaction).filter(Transaction.institution_id == scoped_institution_id)
    # Staff only ever see what they recorded themselves; admins see everyone's, or one person's via staff_id
    only_user = resolve_staff_filter(db, current_user, scoped_institution_id, staff_id)
    if only_user is not None:
        query = query.filter(Transaction.recorded_by_id == only_user)
    if method is not None:
        query = query.filter(Transaction.method == method)
    if category_type is not None:
        query = query.filter(Transaction.category_type == category_type)
    if start_date is not None:
        query = query.filter(Transaction.transaction_date >= start_date)
    if end_date is not None:
        query = query.filter(Transaction.transaction_date <= end_date)
    return query.order_by(Transaction.transaction_date.desc()).all()


@router.post("/batch", response_model=list[TransactionOut], status_code=status.HTTP_201_CREATED)
@limiter.limit("20/minute")
def create_batch(
    request: Request,
    payload: str = Form(...),
    image: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    """
    Records several items sold or bought together as one payment: every line becomes its own
    entry (with its own stock change and ledger posting), all sharing one method, proof and photo.
    All lines are saved or none are. Sending the same batch_id again returns the saved lines
    instead of recording them twice, so a retry after a dropped connection is safe.
    """
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff record transactions")
    try:
        batch = BatchCreate.model_validate_json(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=exc.errors(include_url=False, include_context=False, include_input=False))
    if not batch.lines:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Add at least one item")
    if len(batch.lines) > 50:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="A batch can have at most 50 items")
    if not (8 <= len(batch.batch_id) <= 36):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid batch id")

    existing = db.query(Transaction).filter(Transaction.batch_id == batch.batch_id, Transaction.recorded_by_id == current_user.id).order_by(Transaction.id).all()
    if existing:
        return existing

    created = []
    now = datetime.now(timezone.utc)
    for line in batch.lines:
        category, stock_item_id, quantity = _check_line(db, current_user, batch.type, line.category_type, line.category, line.amount, line.stock_item_id, line.quantity)
        txn = Transaction(
            institution_id=current_user.institution_id,
            type=batch.type,
            method=batch.method,
            category_type=line.category_type,
            category=category,
            description=((line.description or batch.description or "").strip()) or None,
            amount=line.amount,
            stock_item_id=stock_item_id,
            quantity=quantity,
            mpesa_code=(batch.mpesa_code.strip() if batch.mpesa_code else None) or None,
            mpesa_payer_name=(batch.mpesa_payer_name.strip() if batch.mpesa_payer_name else None) or None,
            transaction_date=now,
            recorded_by_id=current_user.id,
            batch_id=batch.batch_id,
        )
        db.add(txn)
        db.flush()
        post_transaction(db, txn)
        created.append(txn)

    if image is not None and image.filename:
        image_path = save_transaction_image(image, image.file.read())
        for txn in created:
            txn.image_path = image_path

    db.commit()
    for txn in created:
        db.refresh(txn)
    total = sum((t.amount for t in created), Decimal("0"))
    log_activity(db, action="transaction_batch_created", actor=current_user, target_type="transaction", target_id=created[0].id,
                 detail=f"{batch.type.value} of {total} across {len(created)} items", request=request)
    return created


@router.get("/reference-check")
def reference_check(code: str, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    # Tells the app whether an M-Pesa code or bank reference was already recorded in this institution
    code = code.strip().upper()
    if len(code) < 6 or current_user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        return {"exists": False, "mine": False}
    match = (
        db.query(Transaction)
        .filter(Transaction.institution_id == current_user.institution_id, func.upper(Transaction.mpesa_code) == code)
        .order_by(Transaction.id.desc())
        .first()
    )
    if not match:
        return {"exists": False, "mine": False}
    # Staff only get details of their own entry; a colleague's entry is reported without amounts
    mine = current_user.role == UserRole.INSTITUTION_ADMIN or match.recorded_by_id == current_user.id
    return {
        "exists": True,
        "mine": mine,
        "date": match.transaction_date.isoformat() if mine else None,
        "amount": str(match.amount) if mine else None,
    }


@router.get("/{transaction_id}", response_model=TransactionOut)
def get_transaction(transaction_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    txn = db.query(Transaction).filter(Transaction.id == transaction_id).first()
    if not txn:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transaction not found")
    if current_user.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN) and txn.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this transaction")
    if current_user.role == UserRole.STAFF and txn.recorded_by_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this transaction")
    return txn


def _get_transaction_scoped(transaction_id: int, current_user: User, db: Session) -> Transaction:
    txn = db.query(Transaction).filter(Transaction.id == transaction_id).first()
    if not txn:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transaction not found")
    if txn.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to modify this transaction")
    if current_user.role == UserRole.STAFF and txn.recorded_by_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to modify this transaction")
    return txn


def _transaction_is_locked(txn: Transaction, db: Session) -> bool:
    """
    Transactions aren't linked to an audit by foreign key — a finalized
    audit's "statements" are whatever fell inside its period_start/
    period_end for its institution (see routers/audits.py). So a
    transaction is locked once ANY finalized audit for its institution
    covers its date, since editing it afterward would silently change a
    report that's already been signed off.
    """
    return (
        db.query(Audit)
        .filter(
            Audit.institution_id == txn.institution_id,
            Audit.status == AuditStatus.FINALIZED,
            Audit.period_start <= txn.transaction_date,
            Audit.period_end >= txn.transaction_date,
        )
        .first()
        is not None
    )


@router.patch("/{transaction_id}", response_model=TransactionOut)
@limiter.limit("30/minute")
def update_transaction(
    transaction_id: int,
    request: Request,
    type: TransactionType | None = Form(None),
    method: TransactionMethod | None = Form(None),
    category: str | None = Form(None),
    description: str | None = Form(None),
    amount: Decimal | None = Form(None),
    quantity: Decimal | None = Form(None),
    mpesa_code: str | None = Form(None),
    mpesa_payer_name: str | None = Form(None),
    image: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    """
    transaction_date is never editable here — it's set once, server-side,
    at creation (see create_transaction) and stays that way. category_type
    and stock_item_id are also immutable after creation, to keep stock
    reconciliation tractable — delete and re-create instead if a stock
    entry was linked to the wrong item entirely.
    """
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff edit transactions")

    txn = _get_transaction_scoped(transaction_id, current_user, db)
    if _transaction_is_locked(txn, db):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This transaction falls inside a finalized audit and can no longer be changed",
        )

    new_type = type if type is not None else txn.type
    new_quantity = quantity if quantity is not None else txn.quantity

    if txn.category_type == TransactionCategoryType.STOCK and (type is not None or quantity is not None):
        if current_user.staff_type == StaffType.TEACHER:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Personal accounts don't use stock")
        if new_quantity is None or new_quantity <= 0:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="quantity must be greater than 0")
        item = db.query(StockItem).filter(StockItem.id == txn.stock_item_id).first()
        if not item:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stock item for this transaction no longer exists")
        _reverse_stock_delta(item, txn.type, txn.quantity)
        _apply_stock_delta(item, new_type, new_quantity)
        txn.quantity = new_quantity

    if type is not None:
        txn.type = type
    if method is not None:
        txn.method = method
    if category is not None:
        category = category.strip()
        if not category:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="category cannot be blank")
        txn.category = category
    if description is not None:
        txn.description = description.strip() or None
    if amount is not None:
        if amount <= 0:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="amount must be greater than 0")
        txn.amount = amount
    if mpesa_code is not None:
        txn.mpesa_code = mpesa_code.strip() or None
    if mpesa_payer_name is not None:
        txn.mpesa_payer_name = mpesa_payer_name.strip() or None
    old_image_path = txn.image_path
    if image is not None and image.filename:
        contents = image.file.read()
        txn.image_path = save_transaction_image(image, contents)

    db.flush()
    post_transaction(db, txn)
    db.commit()
    db.refresh(txn)
    if image is not None and image.filename:
        _release_image(db, old_image_path)
    log_activity(db, action="transaction_updated", actor=current_user, target_type="transaction", target_id=txn.id,
                 detail=f"Updated {txn.type.value} of {txn.amount} ({txn.category})", request=request)
    return txn


@router.delete("/{transaction_id}", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("20/minute")
def delete_transaction(
    transaction_id: int,
    request: Request,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff delete transactions")

    txn = _get_transaction_scoped(transaction_id, current_user, db)
    if _transaction_is_locked(txn, db):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This transaction falls inside a finalized audit and can no longer be deleted",
        )

    if txn.category_type == TransactionCategoryType.STOCK and txn.stock_item_id is not None:
        item = db.query(StockItem).filter(StockItem.id == txn.stock_item_id).first()
        if item:
            _reverse_stock_delta(item, txn.type, txn.quantity)

    detail = f"Deleted {txn.type.value} of {txn.amount} ({txn.category})"
    image_path = txn.image_path
    db.delete(txn)
    db.commit()
    _release_image(db, image_path)
    log_activity(db, action="transaction_deleted", actor=current_user, target_type="transaction", target_id=transaction_id,
                 detail=detail, request=request)
