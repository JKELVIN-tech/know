import re
from datetime import datetime
from decimal import Decimal, InvalidOperation

MAX_LEN = 1000
_NUM = r"([\d,]+(?:\.\d{1,2})?)"
_DATE_AHEAD = r"(?=\s+on\s+\d{1,2}/\d{1,2}/\d{2,4})"

_CODE = re.compile(r"^\s*([A-Z0-9]{8,12})\s+Confirmed\.?", re.I)
_DATE = re.compile(r"\bon\s+(\d{1,2})/(\d{1,2})/(\d{2,4})\s+at\s+(\d{1,2}):(\d{2})\s*(AM|PM)", re.I)
_BAL = re.compile(r"New\s+M-?PESA\s+balance\s+is\s+Ksh\s*" + _NUM, re.I)
_FEE = re.compile(r"Transaction\s+cost,?\s*Ksh\s*" + _NUM, re.I)
_RECEIVED = re.compile(r"You\s+have\s+received\s+Ksh\s*" + _NUM + r"\s+from\s+(.+?)" + _DATE_AHEAD, re.I)
_SENT = re.compile(r"Ksh\s*" + _NUM + r"\s+sent\s+to\s+(.+?)" + _DATE_AHEAD, re.I)
_PAID = re.compile(r"Ksh\s*" + _NUM + r"\s+paid\s+to\s+(.+?)\.?" + _DATE_AHEAD, re.I)
_WITHDRAWN = re.compile(r"Ksh\s*" + _NUM + r"\s+withdrawn\s+from\s+(.+?)" + _DATE_AHEAD, re.I)
_FOR_ACCOUNT = re.compile(r"^(.+?)\s+for\s+account\s+(\S+)$", re.I)
_PHONE_TAIL = re.compile(r"^(.+?)\s+(\+?\d[\d*]{8,12})$")

# Bank names that can appear as the paybill or sender of an M-Pesa message.
BANK_HINTS = ("KCB", "EQUITY", "CO-OPERATIVE", "CO-OP", "COOP", "ABSA", "STANBIC", "NCBA", "FAMILY BANK",
              "I&M", "DTB", "DIAMOND TRUST", "STANDARD CHARTERED", "SIDIAN", "HF GROUP", "GULF AFRICAN")


def _money(text):
    try:
        return Decimal(text.replace(",", ""))
    except (InvalidOperation, AttributeError):
        return None


def _when(m):
    if not m:
        return None
    d, mo, y, hh, mm, ap = m.groups()
    year = int(y) + 2000 if len(y) == 2 else int(y)
    hour = int(hh) % 12 + (12 if ap.upper() == "PM" else 0)
    try:
        return datetime(year, int(mo), int(d), hour, int(mm))
    except ValueError:
        return None


def _bank_hint(name):
    upper = (name or "").upper()
    return next((b for b in BANK_HINTS if b in upper), None)


def parse_message(text):
    """Parse one M-Pesa SMS into a normalized dict; never raises, unknown text returns kind 'unknown'."""
    out = {"matched": False, "kind": "unknown", "direction": None, "code": None, "amount": None, "fee": None,
           "balance": None, "counterparty": None, "account_ref": None, "phone": None, "when": None,
           "bank_hint": None, "confidence": "review", "reason": None}
    if not isinstance(text, str) or not text.strip():
        out["reason"] = "empty"
        return out
    if len(text) > MAX_LEN:
        out["reason"] = "too_long"
        return out
    text = re.sub(r"\s+", " ", re.sub(r"[\x00-\x1f\x7f]", " ", text)).strip()
    code = _CODE.match(text)
    if not code:
        out["reason"] = "no_code"
        return out
    out["code"] = code.group(1).upper()
    out["matched"] = True
    out["fee"] = _money(m.group(1)) if (m := _FEE.search(text)) else None
    out["balance"] = _money(m.group(1)) if (m := _BAL.search(text)) else None
    out["when"] = _when(_DATE.search(text))
    if re.search(r"\breversal\b", text, re.I):
        out.update(kind="reversal", reason="reversal_needs_review")
        return out
    for kind, direction, rx in (("received", "in", _RECEIVED), ("sent", "out", _SENT),
                                ("till", "out", _PAID), ("withdrawal", "out", _WITHDRAWN)):
        m = rx.search(text)
        if not m:
            continue
        name = m.group(2).strip()
        out.update(kind=kind, direction=direction, amount=_money(m.group(1)))
        acct = _FOR_ACCOUNT.match(name)
        if acct and kind == "sent":
            out.update(kind="paybill", counterparty=acct.group(1).strip(), account_ref=acct.group(2))
        else:
            ph = _PHONE_TAIL.match(name)
            out["counterparty"], out["phone"] = (ph.group(1).strip(), ph.group(2)) if ph else (name, None)
        out["bank_hint"] = _bank_hint(out["counterparty"])
        break
    if out["kind"] == "unknown":
        out["reason"] = "unrecognized_template"
    elif out["amount"] is None or out["when"] is None:
        out["reason"] = "missing_fields"
    else:
        out["confidence"] = "high"
    return out
