from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile, status
from sqlalchemy import or_
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user
from app.core.document_types import DOCUMENT_GROUPS, DOCUMENT_KEYS, DOCUMENT_TYPES
from app.core.limiter import limiter
from app.models.source_document import SourceDocument
from app.models.stock_item import StockItem
from app.models.transaction import Transaction
from app.models.user import User, UserRole
from app.schemas.source_document import SourceDocumentOut
from app.utils.uploads import delete_storage_object, save_transaction_image, read_upload

router = APIRouter(prefix="/api/documents", tags=["documents"])


def _require_member(current_user: User) -> None:
    if current_user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only members of an institution keep source documents")


def _can_see(current_user: User, doc: SourceDocument) -> bool:
    if doc.institution_id != current_user.institution_id:
        return False
    # Staff see their own paperwork; the sub admin sees the whole institution's
    return current_user.role == UserRole.INSTITUTION_ADMIN or doc.recorded_by_id == current_user.id


@router.get("/types")
def document_types(current_user: User = Depends(get_current_user)):
    return {
        "groups": [{"key": k, "name": v} for k, v in DOCUMENT_GROUPS.items()],
        "types": [
            {"key": k, "name": n, "group": g, "issued_by": i, "purpose": p, "party_label": label}
            for (k, n, g, i, p, label) in DOCUMENT_TYPES
        ],
    }


@router.get("", response_model=list[SourceDocumentOut])
def list_documents(
    doc_type: str | None = None,
    group: str | None = None,
    q: str | None = None,
    staff_id: int | None = None,
    stock_item_id: int | None = None,
    limit: int = 200,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    _require_member(current_user)
    query = db.query(SourceDocument).filter(SourceDocument.institution_id == current_user.institution_id)
    if current_user.role == UserRole.STAFF:
        query = query.filter(SourceDocument.recorded_by_id == current_user.id)
    elif staff_id is not None:
        query = query.filter(SourceDocument.recorded_by_id == staff_id)
    if doc_type:
        query = query.filter(SourceDocument.doc_type == doc_type)
    if stock_item_id is not None:
        query = query.filter(SourceDocument.stock_item_id == stock_item_id)
    if group:
        keys = [t[0] for t in DOCUMENT_TYPES if t[2] == group]
        query = query.filter(SourceDocument.doc_type.in_(keys))
    if q and q.strip():
        like = f"%{q.strip()}%"
        query = query.filter(or_(SourceDocument.reference_no.ilike(like), SourceDocument.party_name.ilike(like), SourceDocument.notes.ilike(like)))
    return query.order_by(SourceDocument.document_date.desc(), SourceDocument.id.desc()).limit(min(max(limit, 1), 500)).all()


@router.post("", response_model=SourceDocumentOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("60/minute")
def create_document(
    request: Request,
    doc_type: str = Form(...),
    document_date: date = Form(...),
    reference_no: str | None = Form(None),
    party_name: str | None = Form(None),
    stock_item_id: int | None = Form(None),
    quantity: Decimal | None = Form(None),
    amount: Decimal | None = Form(None),
    notes: str | None = Form(None),
    transaction_id: int | None = Form(None),
    image: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    _require_member(current_user)
    if doc_type not in DOCUMENT_KEYS:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown document type")
    if quantity is not None and quantity < 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Quantity cannot be negative")
    if amount is not None and amount < 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Amount cannot be negative")
    if stock_item_id is not None:
        item = db.query(StockItem).filter(StockItem.id == stock_item_id, StockItem.institution_id == current_user.institution_id).first()
        if not item or (current_user.role == UserRole.STAFF and item.branch_id not in (None, current_user.id)):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="That stock item isn't in your institution")
    if transaction_id is not None:
        txn = db.query(Transaction).filter(Transaction.id == transaction_id, Transaction.institution_id == current_user.institution_id).first()
        if not txn or (current_user.role == UserRole.STAFF and txn.recorded_by_id != current_user.id):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="That entry can't be linked")

    image_path = None
    if image is not None and image.filename:
        image_path = save_transaction_image(image, read_upload(image))

    doc = SourceDocument(
        institution_id=current_user.institution_id,
        recorded_by_id=current_user.id,
        doc_type=doc_type,
        reference_no=(reference_no or "").strip()[:80] or None,
        document_date=document_date,
        party_name=(party_name or "").strip()[:150] or None,
        stock_item_id=stock_item_id,
        quantity=quantity,
        amount=amount,
        notes=(notes or "").strip() or None,
        image_path=image_path,
        transaction_id=transaction_id,
    )
    db.add(doc)
    db.commit()
    db.refresh(doc)
    log_activity(db, action="document_created", actor=current_user, target_type="source_document", target_id=doc.id,
                 detail="Recorded a source document", request=request)
    return doc


@router.get("/{document_id}", response_model=SourceDocumentOut)
def get_document(document_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    _require_member(current_user)
    doc = db.query(SourceDocument).filter(SourceDocument.id == document_id).first()
    if not doc or not _can_see(current_user, doc):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Document not found")
    return doc


@router.delete("/{document_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_document(document_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    _require_member(current_user)
    doc = db.query(SourceDocument).filter(SourceDocument.id == document_id).first()
    if not doc or not _can_see(current_user, doc):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Document not found")
    path = doc.image_path
    db.delete(doc)
    db.commit()
    delete_storage_object(path)
    log_activity(db, action="document_deleted", actor=current_user, target_type="source_document", target_id=document_id, request=request)
