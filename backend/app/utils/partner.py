import re
import secrets
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app.models.partner import Partner

LOCAL = ZoneInfo("Africa/Nairobi")
CENT = Decimal("0.01")
# No 0/O or 1/I/L, so a code read out over the phone is not mistaken
ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
_PERIOD = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def normalize_code(raw: str | None) -> str:
    return re.sub(r"[^A-Z0-9]", "", (raw or "").upper())


def display_code(code: str) -> str:
    return f"{code[:2]}-{code[2:]}" if len(code) > 2 else code


def new_code(db: Session) -> str:
    while True:
        code = "AG" + "".join(secrets.choice(ALPHABET) for _ in range(6))
        if not db.query(Partner.id).filter(Partner.code == code).first():
            return code


def commission_for(amount: Decimal, rate: Decimal) -> Decimal:
    return (Decimal(amount) * Decimal(rate) / 100).quantize(CENT, rounding=ROUND_HALF_UP)


def valid_period(period: str) -> bool:
    return bool(_PERIOD.match(period or ""))


def current_period() -> str:
    return datetime.now(LOCAL).strftime("%Y-%m")


def shift_period(period: str, months: int) -> str:
    year, month = int(period[:4]), int(period[5:])
    index = year * 12 + (month - 1) + months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def months_between(older: str, newer: str) -> int:
    return (int(newer[:4]) * 12 + int(newer[5:])) - (int(older[:4]) * 12 + int(older[5:]))


def fee_status(registered_at: datetime | None, last_period: str | None, now_period: str) -> str:
    """trial: new and not yet billed; active: paid this or last month; overdue: paid before but not recently; unpaid: never paid."""
    if last_period:
        return "active" if months_between(last_period, now_period) <= 1 else "overdue"
    if registered_at and (datetime.now(LOCAL).date() - registered_at.astimezone(LOCAL).date()).days <= 30:
        return "trial"
    return "unpaid"


def money(value) -> str:
    return str(Decimal(value or 0).quantize(CENT))


def parse_day(value: date | None) -> date:
    return value or datetime.now(LOCAL).date()
