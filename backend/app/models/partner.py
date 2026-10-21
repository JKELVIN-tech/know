from sqlalchemy import Boolean, Column, Date, DateTime, ForeignKey, Integer, Numeric, String, func

from app.core.database import Base


class Partner(Base):
    """An agent who brings businesses to the platform and earns a share of the monthly fees they pay."""
    __tablename__ = "partners"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True)
    # Stored without the hyphen (AG7K2M9Q); shown as AG-7K2M9Q
    code = Column(String(12), nullable=False, unique=True, index=True)
    commission_rate = Column(Numeric(5, 2), nullable=False, default=20)
    phone = Column(String(40), nullable=True)
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class FeePayment(Base):
    """A monthly fee a business paid; the agent's share is fixed here so later rate changes never rewrite earnings."""
    __tablename__ = "fee_payments"

    id = Column(Integer, primary_key=True, index=True)
    institution_id = Column(Integer, ForeignKey("institutions.id", ondelete="CASCADE"), nullable=False, index=True)
    period = Column(String(7), nullable=False, index=True)
    amount = Column(Numeric(12, 2), nullable=False)
    paid_on = Column(Date, nullable=False)
    partner_id = Column(Integer, ForeignKey("partners.id", ondelete="SET NULL"), nullable=True, index=True)
    commission_rate = Column(Numeric(5, 2), nullable=True)
    commission_amount = Column(Numeric(12, 2), nullable=False, default=0)
    note = Column(String(200), nullable=True)
    recorded_by_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    voided_at = Column(DateTime(timezone=True), nullable=True)


class PartnerPayout(Base):
    """Money paid out to an agent against what they have earned."""
    __tablename__ = "partner_payouts"

    id = Column(Integer, primary_key=True, index=True)
    partner_id = Column(Integer, ForeignKey("partners.id", ondelete="CASCADE"), nullable=False, index=True)
    amount = Column(Numeric(12, 2), nullable=False)
    paid_on = Column(Date, nullable=False)
    reference = Column(String(80), nullable=True)
    note = Column(String(200), nullable=True)
    recorded_by_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    voided_at = Column(DateTime(timezone=True), nullable=True)
