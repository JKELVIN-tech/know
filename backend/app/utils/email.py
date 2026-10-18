import logging

import httpx

from app.core.config import settings

logger = logging.getLogger("know.email")


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
            return False
        if response.status_code >= 300:
            logger.warning("Email provider refused a message (status %s)", response.status_code)
            return False
        return True
    except Exception as err:
        logger.warning("Email could not be sent (%s)", type(err).__name__)
        return False
