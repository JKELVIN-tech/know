from sqlalchemy import Column, Integer, String, Numeric, DateTime, ForeignKey, Text, func
from sqlalchemy.orm import relationship

from app.core.database import Base


class StockItem(Base):
    """
    An inventory line for an institution's shop-style stock (e.g. items
    sold in a school canteen, or a supermarket/shop's actual product
    catalog). Quantity is a running count: transactions of category
    STOCK adjust it up (a restock, an expense) or down (a sale, income).
    """
    __tablename__ = "stock_items"

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id", ondelete="CASCADE"), nullable=False, index=True)
    # Nullable at first for existing rows created before the market-analysis
    # taxonomy existed; required going forward at the schema/router level.
    category_id = Column(Integer, ForeignKey("product_categories.id"), nullable=True, index=True)
    # The branch account that owns this stock; null means legacy shared stock the owner has not assigned yet.
    branch_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    name = Column(String(150), nullable=False)
    description = Column(Text, nullable=True)
    unit_price = Column(Numeric(12, 2), nullable=True)
    quantity = Column(Numeric(12, 2), nullable=False, default=0)
    # Alert level: at or below this many units the item counts as low; null uses the app default
    low_stock_threshold = Column(Numeric(12, 2), nullable=True)
    # Running average cost of one unit, built from restocks and transfers; null until a cost is known
    avg_cost = Column(Numeric(12, 4), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    institution = relationship("Institution", back_populates="stock_items")
    transactions = relationship("Transaction", back_populates="stock_item")
    category = relationship("ProductCategory", back_populates="stock_items")
    price_history = relationship("StockPriceHistory", back_populates="stock_item", cascade="all, delete-orphan", order_by="StockPriceHistory.recorded_at")

    branch = relationship("User", foreign_keys=[branch_id])

    @property
    def branch_name(self) -> str | None:
        return self.branch.full_name if self.branch else None

    @property
    def category_name(self) -> str | None:
        return self.category.name if self.category else None
