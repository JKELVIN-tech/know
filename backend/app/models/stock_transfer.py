from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Numeric, func

from app.core.database import Base


class StockTransfer(Base):
    """Stock moved between two branch accounts of one institution; it never touches the books, only quantities."""
    __tablename__ = "stock_transfers"

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=False, index=True)
    from_branch_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    to_branch_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    source_item_id = Column(Integer, ForeignKey("stock_items.id"), nullable=False)
    dest_item_id = Column(Integer, ForeignKey("stock_items.id"), nullable=True)
    item_name = Column(String(150), nullable=False)
    category_id = Column(Integer, ForeignKey("product_categories.id"), nullable=True)
    unit_price = Column(Numeric(12, 2), nullable=True)
    unit_cost = Column(Numeric(12, 4), nullable=True)
    quantity = Column(Numeric(12, 2), nullable=False)
    status = Column(String(12), nullable=False, default="pending", index=True)
    note = Column(String(200), nullable=True)
    created_by_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    resolved_at = Column(DateTime(timezone=True), nullable=True)
    resolved_by_id = Column(Integer, ForeignKey("users.id"), nullable=True)
