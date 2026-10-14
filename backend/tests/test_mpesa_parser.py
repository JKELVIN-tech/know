from datetime import datetime
from decimal import Decimal
from app.utils.mpesa_parser import parse_message

SENT = "QGH12ABCDE Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/5/26 at 2:30 PM. New M-PESA balance is Ksh1,200.00. Transaction cost, Ksh7.00."
PAYBILL = "QGH12ABCDF Confirmed. Ksh4,000.00 sent to KCB for account 1234567 on 9/9/25 at 9:05 AM. New M-PESA balance is Ksh800.50. Transaction cost, Ksh0.00."
TILL = "QGH12ABCDG Confirmed. Ksh150.00 paid to NAIVAS SUPERMARKET. on 1/10/26 at 12:10 PM. New M-PESA balance is Ksh2,000.00. Transaction cost, Ksh0.00."
RECEIVED = "QGH12ABCDH Confirmed. You have received Ksh50.00 from MARY WANJIKU 0722000000 on 3/10/26 at 8:00 AM. New M-PESA balance is Ksh2,050.00."
WITHDRAW = "QGH12ABCDJ Confirmed. Ksh1,000.00 withdrawn from 123456 - AGENT ONE on 4/10/26 at 11:59 PM. New M-PESA balance is Ksh1,050.00. Transaction cost, Ksh28.00."


def test_sent():
    r = parse_message(SENT)
    assert (r["kind"], r["direction"], r["amount"], r["fee"], r["balance"]) == ("sent", "out", Decimal("500.00"), Decimal("7.00"), Decimal("1200.00"))
    assert r["counterparty"] == "JOHN DOE" and r["phone"] == "0712345678"
    assert r["when"] == datetime(2026, 5, 12, 14, 30) and r["confidence"] == "high"


def test_paybill_to_bank_is_tagged():
    r = parse_message(PAYBILL)
    assert r["kind"] == "paybill" and r["account_ref"] == "1234567" and r["bank_hint"] == "KCB"
    assert r["amount"] == Decimal("4000.00") and r["balance"] == Decimal("800.50")


def test_till():
    r = parse_message(TILL)
    assert r["kind"] == "till" and r["counterparty"] == "NAIVAS SUPERMARKET" and r["amount"] == Decimal("150.00")


def test_received():
    r = parse_message(RECEIVED)
    assert r["kind"] == "received" and r["direction"] == "in" and r["amount"] == Decimal("50.00") and r["fee"] is None


def test_withdrawal_midnight_hour():
    r = parse_message(WITHDRAW)
    assert r["kind"] == "withdrawal" and r["when"] == datetime(2026, 10, 4, 23, 59)


def test_reversal_goes_to_review():
    r = parse_message("QGH12ABCDK Confirmed. Reversal of transaction QGH12ABCDE has been successfully reversed on 12/5/26 at 3:00 PM.")
    assert r["kind"] == "reversal" and r["confidence"] == "review"


def test_junk_and_unsafe_input():
    for bad in ("", "   ", None, 42, "hello there", "x" * 2000):
        assert parse_message(bad)["matched"] is False
    r = parse_message("QGH12ABCDL Confirmed. something new happened")
    assert r["matched"] and r["kind"] == "unknown" and r["confidence"] == "review"


def test_repeat_is_stable():
    assert parse_message(SENT) == parse_message(SENT)
