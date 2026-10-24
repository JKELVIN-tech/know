from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user
from app.models.activity_log import ActivityLog
from app.models.institution import Institution
from app.models.user import User, UserRole

router = APIRouter(prefix="/api/billing", tags=["billing"])

TRIAL_DAYS = 7
INDIVIDUAL_PRICE = 200
OWNER_PRICE = 500
OWNER_INCLUDED_ACCOUNTS = 5
EXTRA_ACCOUNT_PRICE = 200


class BillingStatus(BaseModel):
    applies: bool
    plan: str | None = None
    plan_label: str = ""
    state: str = "none"
    trial_days: int = TRIAL_DAYS
    trial_ends_at: datetime | None = None
    days_left: int = 0
    base_amount: int = 0
    included_accounts: int = 0
    accounts_used: int = 0
    extra_accounts: int = 0
    extra_rate: int = EXTRA_ACCOUNT_PRICE
    extra_amount: int = 0
    total: int = 0
    note: str = ""


def _self_signup_workspace(db: Session, institution: Institution) -> bool:
    if institution.type == "individual":
        return True
    return db.query(ActivityLog.id).filter(
        ActivityLog.action == "self_signup", ActivityLog.target_type == "institution", ActivityLog.target_id == institution.id,
    ).first() is not None


@router.get("/status", response_model=BillingStatus)
def billing_status(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Preview only: self-signed-up accounts get a 7-day trial and see their plan price; nothing is charged or locked yet."""
    institution = db.get(Institution, current_user.institution_id) if current_user.institution_id else None
    if not institution or not institution.created_at or not _self_signup_workspace(db, institution):
        return BillingStatus(applies=False)
    is_individual = institution.type == "individual"
    # Only the account that holds the workspace pays; staff added by an owner are covered by the owner's plan
    if not is_individual and current_user.role != UserRole.INSTITUTION_ADMIN:
        return BillingStatus(applies=False)

    started = institution.created_at if institution.created_at.tzinfo else institution.created_at.replace(tzinfo=timezone.utc)
    ends = started + timedelta(days=TRIAL_DAYS)
    remaining = ends - datetime.now(timezone.utc)
    days_left = max(0, -(-int(remaining.total_seconds()) // 86400))
    state = "trial" if remaining.total_seconds() > 0 else "trial_ended"
    note = "Payments are not switched on yet. Nothing is charged and your account stays open."

    if is_individual:
        return BillingStatus(applies=True, plan="individual", plan_label="Individual", state=state, trial_ends_at=ends, days_left=days_left,
                             base_amount=INDIVIDUAL_PRICE, total=INDIVIDUAL_PRICE, note=note)

    used = db.query(User).filter(User.institution_id == institution.id, User.role == UserRole.STAFF, User.is_active.is_(True)).count()
    extra = max(0, used - OWNER_INCLUDED_ACCOUNTS)
    return BillingStatus(
        applies=True, plan="owner", plan_label="Owner", state=state, trial_ends_at=ends, days_left=days_left,
        base_amount=OWNER_PRICE, included_accounts=OWNER_INCLUDED_ACCOUNTS, accounts_used=used,
        extra_accounts=extra, extra_amount=extra * EXTRA_ACCOUNT_PRICE, total=OWNER_PRICE + extra * EXTRA_ACCOUNT_PRICE, note=note,
    )
