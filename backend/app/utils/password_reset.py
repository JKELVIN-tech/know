import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from html import escape

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.password_reset import PasswordReset
from app.models.user import User
from app.utils.email import send_email


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def recent_requests(db: Session, user_id: int) -> int:
    since = datetime.now(timezone.utc) - timedelta(hours=1)
    return db.query(func.count(PasswordReset.id)).filter(PasswordReset.user_id == user_id, PasswordReset.created_at >= since).scalar() or 0


def issue_token(db: Session, user: User, ip: str | None) -> str:
    """Creates a fresh single-use token and cancels any earlier unused ones for the account."""
    now = datetime.now(timezone.utc)
    db.query(PasswordReset).filter(PasswordReset.user_id == user.id, PasswordReset.used_at.is_(None)).update({"used_at": now})
    token = secrets.token_urlsafe(32)
    db.add(PasswordReset(user_id=user.id, token_hash=hash_token(token), expires_at=now + timedelta(minutes=settings.PASSWORD_RESET_MINUTES), requested_ip=(ip or None) and ip[:64]))
    db.commit()
    return token


def reset_link(token: str) -> str:
    # The token sits after the # so it is never sent to a server, written to a log or passed on in a Referer header
    return f"{settings.frontend_base}/reset-password.html#token={token}"


def send_reset_email(to: str, full_name: str, username: str, token: str) -> bool:
    link = reset_link(token)
    minutes = settings.PASSWORD_RESET_MINUTES
    text = (f"Hello {full_name},\n\nA password reset was requested for your K.N.O.W. account ({username}).\n"
            f"Open this link within {minutes} minutes to choose a new password:\n\n{link}\n\n"
            "If you did not ask for this, ignore this email. Your password stays the same.\n")
    html = (f"<p>Hello {escape(full_name)},</p><p>A password reset was requested for your K.N.O.W. account (<b>{escape(username)}</b>).</p>"
            f"<p><a href=\"{escape(link)}\">Choose a new password</a><br>The link works once and expires in {minutes} minutes.</p>"
            "<p>If you did not ask for this, ignore this email. Your password stays the same.</p>")
    return send_email(to, "Reset your K.N.O.W. password", text, html)


def send_changed_email(to: str, full_name: str, username: str) -> bool:
    text = (f"Hello {full_name},\n\nThe password for your K.N.O.W. account ({username}) was just changed and you were signed out everywhere.\n"
            "If this was not you, contact your administrator straight away.\n")
    return send_email(to, "Your K.N.O.W. password was changed", text)
