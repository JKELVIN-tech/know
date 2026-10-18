import enum

from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Enum, Numeric, Text, func
from sqlalchemy.orm import relationship

from app.core.database import Base


class TransactionType(str, enum.Enum):
    INCOME = "income"
    EXPENSE = "expense"


class TransactionMethod(str, enum.Enum):
    CASH = "cash"
    MPESA = "mpesa"
    BANK = "bank"


class TransactionCategoryType(str, enum.Enum):
    """STOCK entries move inventory (a sale or a restock) and link to a
    StockItem + quantity. OTHER entries are the original free-form ledger
    line (rent, utilities, etc.) with no item/quantity attached."""
    STOCK = "STOCK"
    OTHER = "OTHER"


class Transaction(Base):
    __tablename__ = "transactions"

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=False, index=True)

    # `type` is the original column — it already stores "INCOME"/"EXPENSE"
    # (the enum member NAME, SQLAlchemy's default) from before this
    # feature existed, so it stays on that default behavior.
    type = Column(Enum(TransactionType), nullable=False)

    # `method` was written as lowercase text ("cash"/"mpesa"/"bank") by an
    # earlier migration, so — unlike `type` — it needs values_callable to
    # map by .value instead of .name.
    method = Column(Enum(TransactionMethod, values_callable=lambda obj: [e.value for e in obj]), nullable=False, default=TransactionMethod.CASH)

    # Written as the enum member NAME ("STOCK"/"OTHER"), matching `type`'s
    # existing convention rather than `method`'s.
    category_type = Column(Enum(TransactionCategoryType), nullable=False, default=TransactionCategoryType.OTHER)

    category = Column(String(100), nullable=False)   # e.g. "tuition fees", "utilities" — or the stock item's name when category_type=STOCK
    description = Column(Text, nullable=True)
    amount = Column(Numeric(12, 2), nullable=False)

    # Only set when category_type=STOCK: which item, and how many units.
    # A STOCK expense adds this quantity to the item's stock (a restock);
    # a STOCK income subtracts it (a sale).
    stock_item_id = Column(Integer, ForeignKey("stock_items.id", ondelete="SET NULL"), nullable=True)
    quantity = Column(Numeric(12, 2), nullable=True)
    # For a stock sale: what the units cost when they were sold, fixed at that moment so later cost changes never rewrite history
    cost_total = Column(Numeric(12, 2), nullable=True)

    # Populated when a staff member pastes an M-Pesa confirmation message —
    # parsed client-request, not verified against Safaricom directly, but
    # gives a reconcilable code/payer name attached to the ledger entry.
    mpesa_code = Column(String(30), nullable=True)
    mpesa_payer_name = Column(String(150), nullable=True)

    # Always set server-side at creation time — never accepted from the client.
    transaction_date = Column(DateTime(timezone=True), nullable=False, server_default=func.now())

    # Optional evidence photo (receipt, till slip, etc.)
    image_path = Column(String(500), nullable=True)
    # Lines of one sale or purchase entered together share this id
    batch_id = Column(String(36), nullable=True, index=True)

    recorded_by_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    institution = relationship("Institution", back_populates="transactions")
    recorded_by = relationship("User", back_populates="transactions_recorded", foreign_keys=[recorded_by_id])
    stock_item = relationship("StockItem", back_populates="transactions")
    journal_entry = relationship("JournalEntry", back_populates="transaction", uselist=False, cascade="all, delete-orphan")
