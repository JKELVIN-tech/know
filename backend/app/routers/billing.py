from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user
from app.models.institution import Institution
from app.models.user import User

router = APIRouter(prefix="/api/billing", tags=["billing"])

TRIAL_DAYS = 7


class BillingStatus(BaseModel):
    applies: bool
    state: str = "none"
    trial_days: int = TRIAL_DAYS
    trial_ends_at: datetime | None = None
    days_left: int = 0
    amount: str | None = None
    note: str = ""


@router.get("/status", response_model=BillingStatus)
def billing_status(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Preview only: self-signed-up accounts get a 7-day trial; nothing is charged or locked yet and the price is not set."""
    institution = db.get(Institution, current_user.institution_id) if current_user.institution_id else None
    if not institution or institution.type != "individual" or not institution.created_at:
        return BillingStatus(applies=False)
    started = institution.created_at if institution.created_at.tzinfo else institution.created_at.replace(tzinfo=timezone.utc)
    ends = started + timedelta(days=TRIAL_DAYS)
    remaining = ends - datetime.now(timezone.utc)
    days_left = max(0, -(-int(remaining.total_seconds()) // 86400))
    return BillingStatus(
        applies=True,
        state="trial" if remaining.total_seconds() > 0 else "trial_ended",
        trial_ends_at=ends,
        days_left=days_left,
        amount=None,
        note="Payments are not switched on yet. Nothing is charged and your account stays open.",
    )
