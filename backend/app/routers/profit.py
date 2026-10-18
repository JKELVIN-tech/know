from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import case, func
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user
from app.core.limiter import limiter
from app.models.stock_item import StockItem
from app.models.transaction import Transaction, TransactionCategoryType, TransactionType
from app.models.user import StaffType, User, UserRole

router = APIRouter(prefix="/api/profit", tags=["profit"])

LOCAL = ZoneInfo("Africa/Nairobi")
CENT = Decimal("0.01")


def _owner(current_user: User = Depends(get_current_user)) -> User:
    """Profit is for the institution's owner only; staff and the platform's super admin never see it."""
    if current_user.role != UserRole.INSTITUTION_ADMIN or current_user.institution_id is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only an owner can see profit")
    return current_user


def _money(value) -> str:
    return str(Decimal(value or 0).quantize(CENT))


def _bucket(rows: list) -> dict:
    revenue = sum((r["revenue"] for r in rows), Decimal("0"))
    cost = sum((r["cost"] for r in rows), Decimal("0"))
    costed_revenue = sum((r["costed_revenue"] for r in rows), Decimal("0"))
    unknown = sum((r["unknown_units"] for r in rows), Decimal("0"))
    profit = costed_revenue - cost
    return {
        "units": str(sum((r["units"] for r in rows), Decimal("0")).normalize()),
        "revenue": _money(revenue), "cost": _money(cost), "profit": _money(profit),
        "margin": str((profit / costed_revenue * 100).quantize(Decimal("0.1"))) if costed_revenue > 0 else None,
        "unknown_units": str(unknown.normalize()),
    }


@router.get("/daily")
@limiter.limit("60/minute")
def daily_profit(request: Request, day: date | None = None, branch_id: int | None = None, db: Session = Depends(get_db), owner: User = Depends(_owner)):
    """Sales, cost and profit for one day, per branch and per stock item; the day runs midnight to midnight in Kenya."""
    chosen = day or datetime.now(LOCAL).date()
    start = datetime.combine(chosen, time.min, LOCAL).astimezone(timezone.utc)
    end = start + timedelta(days=1)
    q = (
        db.query(
            Transaction.recorded_by_id, Transaction.stock_item_id,
            func.sum(Transaction.quantity), func.sum(Transaction.amount), func.sum(func.coalesce(Transaction.cost_total, 0)),
            func.sum(case((Transaction.cost_total.isnot(None), Transaction.amount), else_=0)),
            func.sum(case((Transaction.cost_total.is_(None), Transaction.quantity), else_=0)),
        )
        .filter(
            Transaction.institution_id == owner.institution_id, Transaction.type == TransactionType.INCOME,
            Transaction.category_type == TransactionCategoryType.STOCK,
            Transaction.transaction_date >= start, Transaction.transaction_date < end,
        )
        .group_by(Transaction.recorded_by_id, Transaction.stock_item_id)
    )
    if branch_id is not None:
        q = q.filter(Transaction.recorded_by_id == branch_id)
    raw = q.all()
    items = {i.id: i for i in db.query(StockItem).filter(StockItem.id.in_({r[1] for r in raw})).all()} if raw else {}
    people = {u.id: u.full_name for u in db.query(User).filter(User.institution_id == owner.institution_id, User.role == UserRole.STAFF)}
    branches: dict = {}
    for who, item_id, units, revenue, cost, costed_revenue, unknown_units in raw:
        item = items.get(item_id)
        row = {"item_id": item_id, "name": item.name if item else "Removed item", "units": Decimal(str(units or 0)), "revenue": Decimal(str(revenue or 0)), "cost": Decimal(str(cost or 0)),
               "costed_revenue": Decimal(str(costed_revenue or 0)), "unknown_units": Decimal(str(unknown_units or 0))}
        branches.setdefault(who, []).append(row)
    out = []
    for who, rows in branches.items():
        rows.sort(key=lambda r: r["costed_revenue"] - r["cost"], reverse=True)
        out.append({"branch_id": who, "branch_name": people.get(who, "Removed account"), "totals": _bucket(rows),
                    "items": [{"item_id": r["item_id"], "name": r["name"], **_bucket([r])} for r in rows]})
    out.sort(key=lambda b: Decimal(b["totals"]["profit"]), reverse=True)
    every = [r for rows in branches.values() for r in rows]
    return {"day": chosen.isoformat(), "timezone": "Africa/Nairobi", "totals": _bucket(every), "branches": out}


@router.get("/costs")
def list_costs(db: Session = Depends(get_db), owner: User = Depends(_owner)):
    """Every stock item with its current average cost, so the owner can fill in the ones that are missing."""
    people = {u.id: u.full_name for u in db.query(User).filter(User.institution_id == owner.institution_id, User.role == UserRole.STAFF)}
    items = db.query(StockItem).filter(StockItem.institution_id == owner.institution_id).order_by(StockItem.name).all()
    return [{"item_id": i.id, "name": i.name, "branch_id": i.branch_id, "branch_name": people.get(i.branch_id), "unit_price": str(i.unit_price) if i.unit_price is not None else None,
             "avg_cost": str(i.avg_cost.quantize(CENT)) if i.avg_cost is not None else None, "quantity": str(i.quantity)} for i in items]


class CostUpdate(BaseModel):
    avg_cost: Decimal = Field(ge=0, le=Decimal("100000000"))


@router.patch("/costs/{item_id}")
@limiter.limit("60/minute")
def set_cost(item_id: int, payload: CostUpdate, request: Request, db: Session = Depends(get_db), owner: User = Depends(_owner)):
    """Sets what one unit costs. Sales already made keep the cost they were recorded with."""
    item = db.query(StockItem).filter(StockItem.id == item_id, StockItem.institution_id == owner.institution_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stock item not found")
    before = item.avg_cost
    item.avg_cost = payload.avg_cost.quantize(Decimal("0.0001"))
    db.commit()
    log_activity(db, action="stock_cost_set", actor=owner, target_type="stock_item", target_id=item.id,
                 detail=f"Cost of '{item.name}' set to {payload.avg_cost} (was {before if before is not None else 'unknown'})", request=request)
    return {"item_id": item.id, "avg_cost": str(item.avg_cost.quantize(CENT))}
