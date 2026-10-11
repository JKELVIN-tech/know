import enum

from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Enum, Numeric, Text, UniqueConstraint, func
from sqlalchemy.orm import relationship

from app.core.database import Base


class AccountType(str, enum.Enum):
    ASSET = "asset"
    LIABILITY = "liability"
    EQUITY = "equity"
    INCOME = "income"
    EXPENSE = "expense"


class Account(Base):
    __tablename__ = "accounts"
    __table_args__ = (
        UniqueConstraint("institution_id", "key", name="uq_accounts_institution_key"),
        UniqueConstraint("institution_id", "code", name="uq_accounts_institution_code"),
    )

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=False, index=True)
    # Stable machine key ("cash", "mpesa", "inc:rent", ...) so posting never depends on a display name
    key = Column(String(120), nullable=False)
    code = Column(Integer, nullable=False)
    name = Column(String(120), nullable=False)
    type = Column(Enum(AccountType, values_callable=lambda obj: [e.value for e in obj], name="accounttype"), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    lines = relationship("JournalLine", back_populates="account")


class JournalEntry(Base):
    __tablename__ = "journal_entries"

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=False, index=True)
    # One auto-posted entry per Receiving/Paying record; manual adjustments (later) leave this null
    transaction_id = Column(Integer, ForeignKey("transactions.id", ondelete="CASCADE"), nullable=True, unique=True)
    entry_date = Column(DateTime(timezone=True), nullable=False, index=True)
    memo = Column(Text, nullable=True)
    # Set on a staff member's own starting balances; null for institution-level entries
    owner_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    transaction = relationship("Transaction", back_populates="journal_entry")
    owner = relationship("User", foreign_keys=[owner_id])

    @property
    def recorded_by_name(self) -> str | None:
        if self.transaction is not None and self.transaction.recorded_by is not None:
            return self.transaction.recorded_by.full_name
        return self.owner.full_name if self.owner is not None else None

    @property
    def reference(self) -> str | None:
        return self.transaction.mpesa_code if self.transaction is not None else None
    lines = relationship("JournalLine", back_populates="entry", cascade="all, delete-orphan")


class JournalLine(Base):
    __tablename__ = "journal_lines"

    id = Column(Integer, primary_key=True, index=True)
    entry_id = Column(Integer, ForeignKey("journal_entries.id", ondelete="CASCADE"), nullable=False, index=True)
    account_id = Column(Integer, ForeignKey("accounts.id"), nullable=False, index=True)
    debit = Column(Numeric(12, 2), nullable=False, default=0)
    credit = Column(Numeric(12, 2), nullable=False, default=0)

    entry = relationship("JournalEntry", back_populates="lines")
    account = relationship("Account", back_populates="lines")
