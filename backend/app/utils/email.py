import logging

import httpx

from app.core.config import settings

logger = logging.getLogger("know.email")

# The latest outcome, kept in memory so a super admin can see why mail is not arriving (never holds the message or the key)
last_result: dict = {"ok": None, "error": None, "at": None}


def _record(ok: bool, error: str | None = None) -> None:
    from datetime import datetime, timezone
    last_result.update({"ok": ok, "error": error, "at": datetime.now(timezone.utc).isoformat()})
    if not ok and error:
        _keep_failure(error)


def _keep_failure(error: str) -> None:
    """Writes the reason a message failed (never the message, address or key) to the activity log so it can be read later."""
    try:
        from app.core.database import SessionLocal
        from app.models.activity_log import ActivityLog
        db = SessionLocal()
        try:
            db.add(ActivityLog(action="email_failed", target_type="email", detail=error[:480]))
            db.commit()
        finally:
            db.close()
    except Exception:
        logger.warning("Could not record an email failure")


def email_configured() -> bool:
    return bool(settings.EMAIL_PROVIDER and settings.EMAIL_API_KEY and settings.EMAIL_FROM)


def _sender() -> tuple[str, str]:
    raw = settings.EMAIL_FROM.strip()
    if "<" in raw and raw.endswith(">"):
        name, address = raw[:-1].split("<", 1)
        return name.strip().strip('"') or "K.N.O.W.", address.strip()
    return "K.N.O.W.", raw


def send_email(to: str, subject: str, text: str, html: str | None = None) -> bool:
    """Sends one email through the configured web API (free hosts block SMTP); never raises, never logs the message or the key."""
    if not email_configured():
        logger.warning("Email is not configured; a message was not sent")
        _record(False, "Email is not set up on the server: EMAIL_PROVIDER, EMAIL_API_KEY and EMAIL_FROM are all needed")
        return False
    name, address = _sender()
    try:
        if settings.EMAIL_PROVIDER == "resend":
            response = httpx.post("https://api.resend.com/emails", timeout=10,
                                  headers={"Authorization": f"Bearer {settings.EMAIL_API_KEY}"},
                                  json={"from": f"{name} <{address}>", "to": [to], "subject": subject, "text": text, **({"html": html} if html else {})})
        elif settings.EMAIL_PROVIDER == "brevo":
            response = httpx.post("https://api.brevo.com/v3/smtp/email", timeout=10,
                                  headers={"api-key": settings.EMAIL_API_KEY, "accept": "application/json"},
                                  json={"sender": {"name": name, "email": address}, "to": [{"email": to}], "subject": subject, "textContent": text, **({"htmlContent": html} if html else {})})
        else:
            logger.warning("Unknown EMAIL_PROVIDER; a message was not sent")
            _record(False, "EMAIL_PROVIDER must be brevo or resend")
            return False
        if response.status_code >= 300:
            logger.warning("Email provider refused a message (status %s)", response.status_code)
            _record(False, f"The email service refused the message (status {response.status_code}): {response.text[:240]}")
            return False
        _record(True)
        return True
    except Exception as err:
        logger.warning("Email could not be sent (%s)", type(err).__name__)
        _record(False, f"Could not reach the email service ({type(err).__name__})")
        return False
