from datetime import date, datetime, timezone
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import require_super_admin
from app.core.limiter import limiter
from app.core.security import hash_password
from app.models.institution import Institution
from app.models.partner import FeePayment, Partner, PartnerPayout
from app.models.user import User, UserRole
from app.schemas.user import check_password, clean_email
from app.utils.partner import commission_for, current_period, display_code, fee_status, money, new_code, normalize_code, parse_day, valid_period

router = APIRouter(prefix="/api/admin", tags=["partners"])

CENT = Decimal("0.01")


class PartnerCreate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)
    username: str = Field(min_length=3, max_length=40, pattern=r"^[A-Za-z0-9_.-]+$")
    email: str
    phone: str | None = Field(default=None, max_length=40)
    password: str
    commission_rate: Decimal = Field(default=Decimal("20"), ge=0, le=100)

    @field_validator("email")
    @classmethod
    def email_format(cls, v: str) -> str:
        cleaned = clean_email(v)
        if not cleaned:
            raise ValueError("An email address is required so the agent can reset their own password")
        return cleaned

    @field_validator("password")
    @classmethod
    def password_rule(cls, v: str) -> str:
        return check_password(v)


class PartnerUpdate(BaseModel):
    commission_rate: Decimal | None = Field(default=None, ge=0, le=100)
    phone: str | None = Field(default=None, max_length=40)
    is_active: bool | None = None


class PayoutCreate(BaseModel):
    amount: Decimal = Field(gt=0, le=Decimal("100000000"))
    paid_on: date | None = None
    reference: str | None = Field(default=None, max_length=80)
    note: str | None = Field(default=None, max_length=200)


class BillingUpdate(BaseModel):
    partner_code: str | None = None
    monthly_fee: Decimal | None = Field(default=None, ge=0, le=Decimal("100000000"))
    clear_fee: bool = False


class PaymentCreate(BaseModel):
    period: str
    amount: Decimal = Field(gt=0, le=Decimal("100000000"))
    paid_on: date | None = None
    note: str | None = Field(default=None, max_length=200)

    @field_validator("period")
    @classmethod
    def period_format(cls, v: str) -> str:
        if not valid_period(v):
            raise ValueError("Choose the month the fee is for")
        return v


def _partner(db: Session, partner_id: int) -> Partner:
    partner = db.get(Partner, partner_id)
    if not partner:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Agent not found")
    return partner


def _summary(db: Session, partner: Partner, user: User) -> dict:
    live = FeePayment.voided_at.is_(None)
    earned = Decimal(str(db.query(func.coalesce(func.sum(FeePayment.commission_amount), 0)).filter(FeePayment.partner_id == partner.id, live).scalar()))
    paid = Decimal(str(db.query(func.coalesce(func.sum(PartnerPayout.amount), 0)).filter(PartnerPayout.partner_id == partner.id, PartnerPayout.voided_at.is_(None)).scalar()))
    businesses = db.query(func.count(Institution.id)).filter(Institution.partner_id == partner.id).scalar()
    return {"id": partner.id, "name": user.full_name, "username": user.username, "email": user.email, "phone": partner.phone, "code": display_code(partner.code),
            "commission_rate": money(partner.commission_rate), "is_active": partner.is_active, "businesses": businesses,
            "earned_total": money(earned), "paid_out": money(paid), "balance_due": money(earned - paid), "created_at": partner.created_at.isoformat() if partner.created_at else None}


@router.post("/partners", status_code=status.HTTP_201_CREATED)
@limiter.limit("20/hour")
def create_partner(payload: PartnerCreate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    """Creates the agent's login and their partner code in one step."""
    if db.query(User).filter(func.lower(User.username) == payload.username.lower()).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That username is already taken")
    if db.query(User).filter(func.lower(User.email) == payload.email).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That email address is already used by another account")
    user = User(username=payload.username, full_name=payload.full_name.strip(), hashed_password=hash_password(payload.password), role=UserRole.AGENT, institution_id=None, email=payload.email, created_by_id=admin.id)
    db.add(user)
    db.flush()
    partner = Partner(user_id=user.id, code=new_code(db), commission_rate=payload.commission_rate, phone=(payload.phone or "").strip() or None)
    db.add(partner)
    db.commit()
    db.refresh(partner)
    log_activity(db, action="partner_created", actor=admin, target_type="partner", target_id=partner.id, detail=f"Created agent '{user.username}'", request=request)
    return _summary(db, partner, user)


@router.get("/partners")
def list_partners(db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    rows = db.query(Partner, User).join(User, User.id == Partner.user_id).order_by(Partner.created_at.desc()).all()
    return [_summary(db, p, u) for p, u in rows]


@router.patch("/partners/{partner_id}")
def update_partner(partner_id: int, payload: PartnerUpdate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    """A new rate applies to fees paid from now on; earlier commissions keep the rate they were earned at."""
    partner = _partner(db, partner_id)
    user = db.get(User, partner.user_id)
    if payload.commission_rate is not None:
        partner.commission_rate = payload.commission_rate
    if payload.phone is not None:
        partner.phone = payload.phone.strip() or None
    if payload.is_active is not None and payload.is_active != partner.is_active:
        partner.is_active = payload.is_active
        user.is_active = payload.is_active
        if not payload.is_active:
            user.token_version += 1
    db.commit()
    log_activity(db, action="partner_updated", actor=admin, target_type="partner", target_id=partner.id, detail="Agent details changed", request=request)
    return _summary(db, partner, user)


@router.post("/partners/{partner_id}/new-code")
@limiter.limit("10/hour")
def new_partner_code(partner_id: int, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    """Replaces the code (for example if it leaked); businesses already registered stay with the agent."""
    partner = _partner(db, partner_id)
    partner.code = new_code(db)
    db.commit()
    log_activity(db, action="partner_code_changed", actor=admin, target_type="partner", target_id=partner.id, detail="Partner code replaced", request=request)
    return _summary(db, partner, db.get(User, partner.user_id))


@router.post("/partners/{partner_id}/payouts", status_code=status.HTTP_201_CREATED)
@limiter.limit("60/hour")
def record_payout(partner_id: int, payload: PayoutCreate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    partner = _partner(db, partner_id)
    summary = _summary(db, partner, db.get(User, partner.user_id))
    if payload.amount.quantize(CENT) > Decimal(summary["balance_due"]):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"That is more than the balance due ({summary['balance_due']})")
    payout = PartnerPayout(partner_id=partner.id, amount=payload.amount.quantize(CENT), paid_on=parse_day(payload.paid_on), reference=(payload.reference or "").strip() or None, note=(payload.note or "").strip() or None, recorded_by_id=admin.id)
    db.add(payout)
    db.commit()
    log_activity(db, action="partner_payout_recorded", actor=admin, target_type="partner", target_id=partner.id, detail="Payout recorded", request=request)
    return _summary(db, partner, db.get(User, partner.user_id))


@router.get("/partners/{partner_id}/payouts")
def list_payouts(partner_id: int, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    _partner(db, partner_id)
    return [{"id": p.id, "amount": money(p.amount), "paid_on": p.paid_on.isoformat(), "reference": p.reference, "note": p.note, "voided": p.voided_at is not None}
            for p in db.query(PartnerPayout).filter(PartnerPayout.partner_id == partner_id).order_by(PartnerPayout.paid_on.desc(), PartnerPayout.id.desc()).limit(100)]


@router.post("/payouts/{payout_id}/void")
def void_payout(payout_id: int, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    payout = db.get(PartnerPayout, payout_id)
    if not payout or payout.voided_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payout not found")
    payout.voided_at = datetime.now(timezone.utc)
    db.commit()
    log_activity(db, action="partner_payout_voided", actor=admin, target_type="partner", target_id=payout.partner_id, detail="Payout voided", request=request)
    return {"ok": True}


# ---- businesses: which agent, what fee, what has been paid ----

@router.get("/institutions/billing")
def institutions_billing(db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    now_period = current_period()
    partners = {p.id: (p, u) for p, u in db.query(Partner, User).join(User, User.id == Partner.user_id).all()}
    last = {r[0]: r[1] for r in db.query(FeePayment.institution_id, func.max(FeePayment.period)).filter(FeePayment.voided_at.is_(None)).group_by(FeePayment.institution_id)}
    out = []
    for i in db.query(Institution).order_by(Institution.name).all():
        p = partners.get(i.partner_id)
        out.append({"institution_id": i.id, "name": i.name, "monthly_fee": money(i.monthly_fee) if i.monthly_fee is not None else None,
                    "partner_id": i.partner_id, "partner_name": p[1].full_name if p else None, "partner_code": display_code(p[0].code) if p else None,
                    "last_paid_period": last.get(i.id), "status": fee_status(i.created_at, last.get(i.id), now_period)})
    return out


@router.patch("/institutions/{institution_id}/billing")
@limiter.limit("60/hour")
def update_billing(institution_id: int, payload: BillingUpdate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    institution = db.get(Institution, institution_id)
    if not institution:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")
    if payload.partner_code is not None:
        code = normalize_code(payload.partner_code)
        if not code:
            institution.partner_id = None
        else:
            partner = db.query(Partner).filter(Partner.code == code, Partner.is_active.is_(True)).first()
            if not partner:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Unknown partner code")
            institution.partner_id = partner.id
    if payload.clear_fee:
        institution.monthly_fee = None
    elif payload.monthly_fee is not None:
        institution.monthly_fee = payload.monthly_fee.quantize(CENT)
    db.commit()
    log_activity(db, action="institution_billing_changed", actor=admin, target_type="institution", target_id=institution.id, detail="Billing details changed", request=request)
    return {"ok": True}


@router.post("/institutions/{institution_id}/payments", status_code=status.HTTP_201_CREATED)
@limiter.limit("120/hour")
def record_payment(institution_id: int, payload: PaymentCreate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    """Records a monthly fee a business paid; the agent's share is worked out and fixed right now."""
    institution = db.get(Institution, institution_id)
    if not institution:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")
    partner = db.get(Partner, institution.partner_id) if institution.partner_id else None
    amount = payload.amount.quantize(CENT)
    payment = FeePayment(institution_id=institution.id, period=payload.period, amount=amount, paid_on=parse_day(payload.paid_on), note=(payload.note or "").strip() or None,
                         partner_id=partner.id if partner else None, commission_rate=partner.commission_rate if partner else None,
                         commission_amount=commission_for(amount, partner.commission_rate) if partner else Decimal("0.00"), recorded_by_id=admin.id)
    db.add(payment)
    db.commit()
    log_activity(db, action="fee_payment_recorded", actor=admin, target_type="institution", target_id=institution.id, detail="Fee payment recorded", request=request)
    return {"id": payment.id, "commission": money(payment.commission_amount)}


@router.get("/institutions/{institution_id}/payments")
def list_payments(institution_id: int, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    return [{"id": p.id, "period": p.period, "amount": money(p.amount), "paid_on": p.paid_on.isoformat(), "commission": money(p.commission_amount), "rate": money(p.commission_rate) if p.commission_rate is not None else None,
             "note": p.note, "voided": p.voided_at is not None}
            for p in db.query(FeePayment).filter(FeePayment.institution_id == institution_id).order_by(FeePayment.period.desc(), FeePayment.id.desc()).limit(60)]


@router.post("/payments/{payment_id}/void")
def void_payment(payment_id: int, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    """Cancels a mistaken payment; its commission stops counting."""
    payment = db.get(FeePayment, payment_id)
    if not payment or payment.voided_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payment not found")
    payment.voided_at = datetime.now(timezone.utc)
    db.commit()
    log_activity(db, action="fee_payment_voided", actor=admin, target_type="institution", target_id=payment.institution_id, detail="Fee payment voided", request=request)
    return {"ok": True}
