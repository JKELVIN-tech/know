from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Numeric, UniqueConstraint, func

from app.core.database import Base


class MessageImport(Base):
    """A detected M-Pesa message waiting for review; only parsed fields and a hash are kept, never the message text."""
    __tablename__ = "message_imports"
    __table_args__ = (UniqueConstraint("institution_id", "recorded_by_id", "code", name="uq_message_import_code"),)

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=False, index=True)
    recorded_by_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    code = Column(String(30), nullable=False, index=True)
    kind = Column(String(20), nullable=False)
    direction = Column(String(3), nullable=True)
    amount = Column(Numeric(12, 2), nullable=True)
    fee = Column(Numeric(12, 2), nullable=True)
    balance = Column(Numeric(12, 2), nullable=True)
    counterparty = Column(String(150), nullable=True)
    account_ref = Column(String(60), nullable=True)
    bank_hint = Column(String(40), nullable=True)
    message_time = Column(DateTime(timezone=False), nullable=True)
    status = Column(String(20), nullable=False, default="needs_review", index=True)
    reason = Column(String(40), nullable=True)
    text_hash = Column(String(64), nullable=False)
    transaction_id = Column(Integer, ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    reviewed_at = Column(DateTime(timezone=True), nullable=True)
