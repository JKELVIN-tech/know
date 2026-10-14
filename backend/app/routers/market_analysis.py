from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from statistics import median

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import require_super_admin
from app.models.institution import Institution
from app.models.product_category import ProductCategory
from app.models.stock_item import StockItem
from app.models.stock_price_history import StockPriceHistory
from app.models.transaction import Transaction, TransactionCategoryType, TransactionType
from app.models.user import User
from app.schemas.market_analysis import CategoryInsightOut, CategoryDetailOut, RegionBreakdownEntry, TrendPointOut

router = APIRouter(prefix="/api/market-analysis", tags=["market-analysis"])

# Core anti-re-identification safeguard: no aggregate stat is ever returned
# for a slice (a category, a category+region, a category+month) backed by
# fewer than this many distinct institutions. Applies everywhere below.
MIN_INSTITUTIONS = 5


def _per_institution(pairs) -> list:
    """Collapses the rows of an institution's branches to one mean price, so a firm with many branches counts once."""
    groups = defaultdict(list)
    for key, price in pairs:
        groups[key].append(price)
    return [sum(v) / len(v) for v in groups.values()]


def _price_stats(prices: list[Decimal]) -> dict:
    floats = [float(p) for p in prices]
    return {
        "average_price": round(sum(floats) / len(floats), 2),
        "median_price": round(float(median(floats)), 2),
        "min_price": round(min(floats), 2),
        "max_price": round(max(floats), 2),
    }


def _quantity_sold(db: Session, category_id: int) -> Decimal:
    rows = (
        db.query(Transaction.quantity)
        .join(StockItem, Transaction.stock_item_id == StockItem.id)
        .filter(
            StockItem.category_id == category_id,
            Transaction.category_type == TransactionCategoryType.STOCK,
            Transaction.type == TransactionType.INCOME,
        )
        .all()
    )
    return sum((r[0] or Decimal("0")) for r in rows)


@router.get("/categories", response_model=list[CategoryInsightOut])
def list_category_insights(db: Session = Depends(get_db), _: User = Depends(require_super_admin)):
    """Category-level aggregate list. Categories with fewer than
    MIN_INSTITUTIONS distinct institutions carrying a priced item are left
    out entirely — not returned with nulled-out fields."""
    categories = db.query(ProductCategory).order_by(ProductCategory.name).all()
    results = []
    for cat in categories:
        rows = (
            db.query(StockItem.institution_id, StockItem.unit_price)
            .filter(StockItem.category_id == cat.id, StockItem.unit_price.isnot(None))
            .all()
        )
        institution_ids = {r[0] for r in rows}
        if len(institution_ids) < MIN_INSTITUTIONS:
            continue
        stats = _price_stats(_per_institution((r[0], r[1]) for r in rows))
        results.append(CategoryInsightOut(
            category_id=cat.id,
            category_name=cat.name,
            institution_count=len(institution_ids),
            total_quantity_sold=_quantity_sold(db, cat.id),
            **stats,
        ))
    return results


@router.get("/categories/{category_id}", response_model=CategoryDetailOut)
def get_category_detail(category_id: int, db: Session = Depends(get_db), _: User = Depends(require_super_admin)):
    cat = db.query(ProductCategory).filter(ProductCategory.id == category_id).first()
    if not cat:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Category not found")

    # Current snapshot
    rows = (
        db.query(StockItem.institution_id, StockItem.unit_price)
        .filter(StockItem.category_id == category_id, StockItem.unit_price.isnot(None))
        .all()
    )
    institution_ids = {r[0] for r in rows}
    if len(institution_ids) < MIN_INSTITUTIONS:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Not enough contributing institutions yet for this category to be shown",
        )
    stats = _price_stats(_per_institution((r[0], r[1]) for r in rows))

    # Regional breakdown — same threshold applied per region
    region_rows = (
        db.query(StockItem.institution_id, StockItem.unit_price, Institution.region)
        .join(Institution, StockItem.institution_id == Institution.id)
        .filter(StockItem.category_id == category_id, StockItem.unit_price.isnot(None), Institution.region.isnot(None))
        .all()
    )
    by_region_inst: dict[str, dict] = defaultdict(lambda: defaultdict(list))
    region_institutions: dict[str, set] = defaultdict(set)
    for institution_id, price, region in region_rows:
        by_region_inst[region][institution_id].append(price)
        region_institutions[region].add(institution_id)
    by_region = {region: [sum(v) / len(v) for v in d.values()] for region, d in by_region_inst.items()}
    regional_breakdown = [
        RegionBreakdownEntry(region=region, institution_count=len(region_institutions[region]), **_price_stats(prices))
        for region, prices in by_region.items()
        if len(region_institutions[region]) >= MIN_INSTITUTIONS
    ]
    regional_breakdown.sort(key=lambda r: r.region)

    # Price trend, bucketed by month — same threshold applied per month
    history_rows = (
        db.query(StockPriceHistory.recorded_at, StockPriceHistory.price, StockItem.institution_id)
        .join(StockItem, StockPriceHistory.stock_item_id == StockItem.id)
        .filter(StockItem.category_id == category_id)
        .all()
    )
    by_month_inst: dict[str, dict] = defaultdict(lambda: defaultdict(list))
    month_institutions: dict[str, set] = defaultdict(set)
    for recorded_at, price, institution_id in history_rows:
        month_key = recorded_at.strftime("%Y-%m") if recorded_at else "unknown"
        by_month_inst[month_key][institution_id].append(price)
        month_institutions[month_key].add(institution_id)
    by_month = {month: [sum(v) / len(v) for v in d.values()] for month, d in by_month_inst.items()}
    trend = [
        TrendPointOut(month=month, average_price=round(sum(float(p) for p in prices) / len(prices), 2), institution_count=len(month_institutions[month]))
        for month, prices in by_month.items()
        if len(month_institutions[month]) >= MIN_INSTITUTIONS
    ]
    trend.sort(key=lambda t: t.month)

    return CategoryDetailOut(
        category_id=cat.id,
        category_name=cat.name,
        institution_count=len(institution_ids),
        total_quantity_sold=_quantity_sold(db, category_id),
        regional_breakdown=regional_breakdown,
        trend=trend,
        **stats,
    )
