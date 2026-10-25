from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import require_super_admin
from app.utils import email as email_util
from app.models.activity_log import ActivityLog
from app.models.institution import Institution
from app.models.post_report import PostReport, ReportStatus
from app.models.transaction import Transaction
from app.models.user import User, UserRole

router = APIRouter(prefix="/api/admin", tags=["admin"])


def _institution_rows(db: Session) -> list[dict]:
    now = datetime.now(timezone.utc)
    since = now - timedelta(days=30)
    last = dict(db.query(Transaction.institution_id, func.max(Transaction.created_at)).group_by(Transaction.institution_id).all())
    recent = dict(db.query(Transaction.institution_id, func.count(Transaction.id)).filter(Transaction.created_at >= since).group_by(Transaction.institution_id).all())
    people = {}
    for inst_id, role, active, count in db.query(User.institution_id, User.role, User.is_active, func.count(User.id)).filter(User.institution_id.isnot(None)).group_by(User.institution_id, User.role, User.is_active).all():
        row = people.setdefault(inst_id, {"staff": 0, "admins": 0, "active": 0, "inactive": 0})
        row["staff" if role == UserRole.STAFF else "admins"] += count
        row["active" if active else "inactive"] += count
    rows = []
    for inst in db.query(Institution).order_by(Institution.name).all():
        p = people.get(inst.id, {"staff": 0, "admins": 0, "active": 0, "inactive": 0})
        rows.append({
            "id": inst.id,
            "name": inst.name,
            "type": inst.type,
            "region": inst.region,
            "address": inst.address,
            "logo_path": inst.logo_path,
            "created_at": inst.created_at,
            "staff_count": p["staff"],
            "admin_count": p["admins"],
            "active_accounts": p["active"],
            "inactive_accounts": p["inactive"],
            "entries_30d": recent.get(inst.id, 0),
            "last_activity_at": last.get(inst.id),
        })
    return rows


@router.get("/institutions")
def institutions_overview(db: Session = Depends(get_db), _: User = Depends(require_super_admin)):
    """Every institution with the numbers an admin needs: people, activity and when it was last used."""
    return _institution_rows(db)


@router.get("/overview")
def overview(db: Session = Depends(get_db), _: User = Depends(require_super_admin)):
    now = datetime.now(timezone.utc)
    rows = _institution_rows(db)
    quiet_cutoff = now - timedelta(days=14)

    def quiet(r: dict) -> bool:
        last = r["last_activity_at"]
        if last is None:
            return True
        if last.tzinfo is None:
            last = last.replace(tzinfo=timezone.utc)
        return last < quiet_cutoff

    by_role = dict(db.query(User.role, func.count(User.id)).group_by(User.role).all())
    active = db.query(func.count(User.id)).filter(User.is_active.is_(True)).scalar() or 0
    total = db.query(func.count(User.id)).scalar() or 0
    new_30 = db.query(func.count(User.id)).filter(User.created_at >= now - timedelta(days=30)).scalar() or 0
    open_reports = db.query(func.count(PostReport.id)).filter(PostReport.status == ReportStatus.OPEN).scalar() or 0
    entries_7d = db.query(func.count(Transaction.id)).filter(Transaction.created_at >= now - timedelta(days=7)).scalar() or 0
    logs = db.query(ActivityLog).order_by(ActivityLog.created_at.desc()).limit(10).all()
    return {
        "institutions": len(rows),
        "accounts_total": total,
        "accounts_active": active,
        "accounts_inactive": total - active,
        "staff": by_role.get(UserRole.STAFF, 0),
        "institution_admins": by_role.get(UserRole.INSTITUTION_ADMIN, 0),
        "super_admins": by_role.get(UserRole.SUPER_ADMIN, 0),
        "new_accounts_30d": new_30,
        "open_reports": open_reports,
        "entries_7d": entries_7d,
        "quiet_institutions": [{"id": r["id"], "name": r["name"], "last_activity_at": r["last_activity_at"]} for r in rows if quiet(r)][:8],
        "recent_activity": [
            {"id": l.id, "action": l.action, "actor_username": l.actor_username, "detail": l.detail, "target_type": l.target_type, "created_at": l.created_at}
            for l in logs
        ],
    }


@router.get("/email-status")
def email_status(admin: User = Depends(require_super_admin)):
    """Shows whether password reset mail can be sent and the reason the last message failed."""
    return {
        "configured": email_util.email_configured(),
        "provider": email_util.settings.EMAIL_PROVIDER or None,
        "from": email_util.settings.EMAIL_FROM or None,
        "last": email_util.last_result,
    }


@router.post("/email-test")
def email_test(admin: User = Depends(require_super_admin)):
    """Sends one test message to the signed-in super admin's own address."""
    if not admin.email:
        return {"sent": False, "reason": "Add an email address to your own account first (Settings > Password reset email)"}
    sent = email_util.send_email(admin.email, "KNOW email test", "This is a test message from KNOW. Password reset emails can be sent.")
    return {"sent": sent, "reason": None if sent else email_util.last_result.get("error")}
