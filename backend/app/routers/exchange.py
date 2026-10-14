from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import require_stock_access
from app.core.limiter import limiter
from app.models.stock_item import StockItem
from app.models.stock_price_history import StockPriceHistory
from app.models.stock_transfer import StockTransfer
from app.models.user import StaffType, User, UserRole
from app.schemas.exchange import BranchOut, TransferCreate, TransferOut

router = APIRouter(prefix="/api/exchange", tags=["exchange"])


def _branch_query(db: Session, institution_id: int):
    return db.query(User).filter(User.institution_id == institution_id, User.role == UserRole.STAFF, User.staff_type == StaffType.GENERAL, User.is_active.is_(True))


def _is_owner(user: User) -> bool:
    return user.role == UserRole.INSTITUTION_ADMIN


def _out(t: StockTransfer, names: dict, viewer: User) -> TransferOut:
    direction = "incoming" if t.to_branch_id == viewer.id else "outgoing" if t.from_branch_id == viewer.id else "other"
    return TransferOut(id=t.id, item_name=t.item_name, quantity=t.quantity, from_branch_id=t.from_branch_id, from_branch_name=names.get(t.from_branch_id, "—"),
                       to_branch_id=t.to_branch_id, to_branch_name=names.get(t.to_branch_id, "—"), status=t.status, note=t.note, direction=direction,
                       created_at=t.created_at, resolved_at=t.resolved_at)


def _names(db: Session, transfers: list[StockTransfer]) -> dict:
    ids = {t.from_branch_id for t in transfers} | {t.to_branch_id for t in transfers}
    return {u.id: u.full_name for u in db.query(User).filter(User.id.in_(ids)).all()} if ids else {}


def _locked_transfer(db: Session, user: User, transfer_id: int) -> StockTransfer:
    t = db.query(StockTransfer).filter(StockTransfer.id == transfer_id, StockTransfer.institution_id == user.institution_id).with_for_update().first()
    if not t:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transfer not found")
    if t.status != "pending":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="This transfer was already handled")
    return t


def _finish(t: StockTransfer, user: User, new_status: str) -> None:
    t.status = new_status
    t.resolved_at = datetime.now(timezone.utc)
    t.resolved_by_id = user.id


def _give_back(db: Session, t: StockTransfer) -> None:
    source = db.query(StockItem).filter(StockItem.id == t.source_item_id).with_for_update().first()
    source.quantity = source.quantity + t.quantity


@router.get("/branches", response_model=list[BranchOut])
def list_branches(db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    """Branch names only, so a branch can pick a destination; nothing else about other branches is visible."""
    q = _branch_query(db, current_user.institution_id)
    if not _is_owner(current_user):
        q = q.filter(User.id != current_user.id)
    return [BranchOut(id=u.id, name=u.full_name) for u in q.order_by(User.full_name).all()]


@router.post("/transfers", response_model=TransferOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("20/minute")
def create_transfer(payload: TransferCreate, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    """Sends stock to another branch: the quantity leaves the sender now and reaches the recipient once they confirm."""
    if _is_owner(current_user):
        if payload.from_branch_id is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Choose the branch the stock leaves from")
        from_id = payload.from_branch_id
    else:
        from_id = current_user.id
    if from_id == payload.to_branch_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Choose a different branch to send to")
    branches = {u.id: u for u in _branch_query(db, current_user.institution_id).filter(User.id.in_([from_id, payload.to_branch_id])).all()}
    if from_id not in branches or payload.to_branch_id not in branches:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown branch account")
    source = db.query(StockItem).filter(StockItem.id == payload.item_id, StockItem.institution_id == current_user.institution_id).with_for_update().first()
    if not source or source.branch_id != from_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="That item is not in the sending branch's stock")
    if source.quantity < payload.quantity:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"Not enough stock: only {source.quantity} of '{source.name}' available")
    source.quantity = source.quantity - payload.quantity
    t = StockTransfer(institution_id=current_user.institution_id, from_branch_id=from_id, to_branch_id=payload.to_branch_id, source_item_id=source.id, item_name=source.name,
                      category_id=source.category_id, unit_price=source.unit_price, quantity=payload.quantity, status="pending",
                      note=(payload.note or "").strip() or None, created_by_id=current_user.id)
    db.add(t)
    db.commit()
    db.refresh(t)
    log_activity(db, action="stock_transfer_sent", actor=current_user, target_type="stock_transfer", target_id=t.id,
                 detail=f"Sent {payload.quantity} of '{source.name}' to {branches[payload.to_branch_id].full_name}", request=request)
    return _out(t, _names(db, [t]), current_user)


@router.get("/transfers", response_model=list[TransferOut])
def list_transfers(status_filter: str | None = None, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    q = db.query(StockTransfer).filter(StockTransfer.institution_id == current_user.institution_id)
    if not _is_owner(current_user):
        q = q.filter((StockTransfer.from_branch_id == current_user.id) | (StockTransfer.to_branch_id == current_user.id))
    if status_filter:
        q = q.filter(StockTransfer.status == status_filter)
    rows = q.order_by(StockTransfer.id.desc()).limit(200).all()
    names = _names(db, rows)
    return [_out(t, names, current_user) for t in rows]


@router.post("/transfers/{transfer_id}/receive", response_model=TransferOut)
@limiter.limit("30/minute")
def receive_transfer(transfer_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    t = _locked_transfer(db, current_user, transfer_id)
    if t.to_branch_id != current_user.id and not _is_owner(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the receiving branch can confirm this")
    dest = db.query(StockItem).filter(StockItem.institution_id == t.institution_id, StockItem.branch_id == t.to_branch_id, func.lower(StockItem.name) == t.item_name.lower()).with_for_update().first()
    if not dest:
        dest = StockItem(institution_id=t.institution_id, branch_id=t.to_branch_id, category_id=t.category_id, name=t.item_name, unit_price=t.unit_price, quantity=0)
        db.add(dest)
        db.flush()
        if dest.unit_price is not None:
            db.add(StockPriceHistory(stock_item_id=dest.id, price=dest.unit_price))
    dest.quantity = dest.quantity + t.quantity
    t.dest_item_id = dest.id
    _finish(t, current_user, "received")
    db.commit()
    db.refresh(t)
    log_activity(db, action="stock_transfer_received", actor=current_user, target_type="stock_transfer", target_id=t.id,
                 detail=f"Received {t.quantity} of '{t.item_name}'", request=request)
    return _out(t, _names(db, [t]), current_user)


@router.post("/transfers/{transfer_id}/reject", response_model=TransferOut)
@limiter.limit("30/minute")
def reject_transfer(transfer_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    t = _locked_transfer(db, current_user, transfer_id)
    if t.to_branch_id != current_user.id and not _is_owner(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the receiving branch can reject this")
    _give_back(db, t)
    _finish(t, current_user, "rejected")
    db.commit()
    db.refresh(t)
    log_activity(db, action="stock_transfer_rejected", actor=current_user, target_type="stock_transfer", target_id=t.id,
                 detail=f"Rejected {t.quantity} of '{t.item_name}'; returned to sender", request=request)
    return _out(t, _names(db, [t]), current_user)


@router.post("/transfers/{transfer_id}/cancel", response_model=TransferOut)
@limiter.limit("30/minute")
def cancel_transfer(transfer_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    t = _locked_transfer(db, current_user, transfer_id)
    if t.from_branch_id != current_user.id and not _is_owner(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the sending branch can cancel this")
    _give_back(db, t)
    _finish(t, current_user, "cancelled")
    db.commit()
    db.refresh(t)
    log_activity(db, action="stock_transfer_cancelled", actor=current_user, target_type="stock_transfer", target_id=t.id,
                 detail=f"Cancelled {t.quantity} of '{t.item_name}'; returned to sender", request=request)
    return _out(t, _names(db, [t]), current_user)
