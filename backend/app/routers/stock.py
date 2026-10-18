from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import or_
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user, require_stock_access
from app.core.limiter import limiter
from app.models.product_category import ProductCategory
from app.models.stock_item import StockItem
from app.models.stock_price_history import StockPriceHistory
from app.models.stock_transfer import StockTransfer
from app.models.transaction import Transaction
from app.models.user import User, UserRole, StaffType
from app.schemas.stock import StockItemCreate, StockItemUpdate, StockItemOut

router = APIRouter(prefix="/api/stock", tags=["stock"])


def _scoped_institution_id(current_user: User, requested_institution_id: int | None) -> int:
    if current_user.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        return current_user.institution_id
    if requested_institution_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required")
    return requested_institution_id


def _branch_or_400(db: Session, institution_id: int, branch_id: int) -> User:
    branch = db.query(User).filter(User.id == branch_id, User.institution_id == institution_id, User.role == UserRole.STAFF, User.staff_type == StaffType.GENERAL, User.is_active.is_(True)).first()
    if not branch:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown branch account")
    return branch


def _can_use(item: StockItem, user: User) -> bool:
    return user.role != UserRole.STAFF or item.branch_id is None or item.branch_id == user.id


@router.post("", response_model=StockItemOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("20/minute")
def create_stock_item(payload: StockItemCreate, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    if current_user.role == UserRole.STAFF:
        branch_id = current_user.id
    else:
        branch_id = payload.branch_id
        if branch_id is not None:
            _branch_or_400(db, current_user.institution_id, branch_id)
    same_name = db.query(StockItem).filter(StockItem.institution_id == current_user.institution_id, StockItem.name == payload.name)
    same_name = same_name.filter(or_(StockItem.branch_id.is_(None), StockItem.branch_id == branch_id)) if branch_id is not None else same_name.filter(StockItem.branch_id.is_(None))
    if same_name.first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="A stock item with this name already exists")
    if not db.query(ProductCategory).filter(ProductCategory.id == payload.category_id).first():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown product category")

    item = StockItem(
        institution_id=current_user.institution_id,
        branch_id=branch_id,
        category_id=payload.category_id,
        name=payload.name,
        description=payload.description,
        unit_price=payload.unit_price,
        quantity=payload.quantity,
    )
    db.add(item)
    db.commit()
    db.refresh(item)
    if item.unit_price is not None:
        db.add(StockPriceHistory(stock_item_id=item.id, price=item.unit_price))
        db.commit()
    log_activity(db, action="stock_item_created", actor=current_user, target_type="stock_item", target_id=item.id,
                 detail="Added a stock item", request=request)
    return item


@router.get("", response_model=list[StockItemOut])
def list_stock_items(institution_id: int | None = None, branch_id: int | None = None, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Read access: any staff (non-teacher)/institution_admin sees their own
    institution; super_admin can view any institution's stock (oversight,
    read-only) by passing institution_id explicitly."""
    if current_user.role == UserRole.STAFF and current_user.staff_type == StaffType.TEACHER:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Personal accounts don't use stock")
    scoped_institution_id = _scoped_institution_id(current_user, institution_id)
    q = db.query(StockItem).filter(StockItem.institution_id == scoped_institution_id)
    if current_user.role == UserRole.STAFF:
        q = q.filter(or_(StockItem.branch_id.is_(None), StockItem.branch_id == current_user.id))
    elif branch_id is not None:
        q = q.filter(StockItem.branch_id == branch_id)
    return q.order_by(StockItem.name).all()


def _get_stock_item_scoped(item_id: int, current_user: User, db: Session) -> StockItem:
    item = db.query(StockItem).filter(StockItem.id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stock item not found")
    if item.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access this stock item")
    if not _can_use(item, current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access this stock item")
    return item


@router.get("/{item_id}", response_model=StockItemOut)
def get_stock_item(item_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    item = db.query(StockItem).filter(StockItem.id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stock item not found")
    if current_user.role == UserRole.STAFF:
        if current_user.staff_type == StaffType.TEACHER:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Personal accounts don't use stock")
        if item.institution_id != current_user.institution_id or not _can_use(item, current_user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this stock item")
    elif current_user.role == UserRole.INSTITUTION_ADMIN and item.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this stock item")
    return item


@router.patch("/{item_id}", response_model=StockItemOut)
@limiter.limit("20/minute")
def update_stock_item(item_id: int, payload: StockItemUpdate, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    """Edits catalog info only (name/description/price) — quantity is never
    set directly here; it only moves through recorded transactions, so
    every change to it has a transaction behind it."""
    item = _get_stock_item_scoped(item_id, current_user, db)
    if payload.name is not None and payload.name != item.name:
        if db.query(StockItem).filter(StockItem.institution_id == item.institution_id, StockItem.name == payload.name, StockItem.id != item_id).first():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="A stock item with this name already exists")
        item.name = payload.name
    if payload.category_id is not None and payload.category_id != item.category_id:
        if not db.query(ProductCategory).filter(ProductCategory.id == payload.category_id).first():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown product category")
        item.category_id = payload.category_id
    if payload.branch_id is not None and payload.branch_id != item.branch_id:
        if current_user.role != UserRole.INSTITUTION_ADMIN or item.branch_id is not None:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the owner can assign shared stock to a branch")
        _branch_or_400(db, item.institution_id, payload.branch_id)
        item.branch_id = payload.branch_id
    if payload.description is not None:
        item.description = payload.description
    price_changed = payload.unit_price is not None and payload.unit_price != item.unit_price
    if payload.unit_price is not None:
        item.unit_price = payload.unit_price

    db.commit()
    db.refresh(item)
    if price_changed:
        db.add(StockPriceHistory(stock_item_id=item.id, price=item.unit_price))
        db.commit()
    log_activity(db, action="stock_item_updated", actor=current_user, target_type="stock_item", target_id=item.id,
                 detail="Edited a stock item", request=request)
    return item


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("10/minute")
def delete_stock_item(item_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_stock_access)):
    item = _get_stock_item_scoped(item_id, current_user, db)
    if db.query(Transaction).filter(Transaction.stock_item_id == item_id).first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This item has recorded transactions and can't be deleted — its sales/restock history must stay intact",
        )
    if db.query(StockTransfer).filter((StockTransfer.source_item_id == item_id) | (StockTransfer.dest_item_id == item_id)).first():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This item has transfer history and can't be deleted")
    db.delete(item)
    db.commit()
    log_activity(db, action="stock_item_deleted", actor=current_user, target_type="stock_item", target_id=item_id,
                 detail="Deleted a stock item", request=request)
