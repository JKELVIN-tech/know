from datetime import datetime
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user
from app.core.staff_scope import resolve_staff_filter
from app.models.accounting import Account, AccountType, JournalEntry, JournalLine
from app.models.audit import Audit, AuditStatus
from app.models.transaction import Transaction
from app.models.user import User, UserRole
from app.schemas.accounting import (
    AccountBalance, JournalEntryOut, OpeningBalances, OpeningBalancesOut, JournalLineOut, Ledger, LedgerLine, MoneyPosition, Summary, TrialBalance,
)
from datetime import timedelta, timezone

from app.utils.ledger import backfill_institution, system_account

router = APIRouter(prefix="/api/accounting", tags=["accounting"])

_DEBIT_NORMAL = (AccountType.ASSET, AccountType.EXPENSE)


def _scoped_institution_id(current_user: User, requested_institution_id: int | None) -> int:
    # Staff and institution_admin are locked to their own institution; only a super admin names one
    if current_user.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        return current_user.institution_id
    if requested_institution_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required")
    return requested_institution_id


def _require_books_access(current_user: User) -> None:
    # Debit/credit views are for the institution's admins and the super admin, never plain staff
    if current_user.role == UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view the books")


def _balances(db: Session, institution_id: int, start: datetime | None, end: datetime | None, only_user_id: int | None = None) -> list[AccountBalance]:
    query = (
        db.query(Account, func.coalesce(func.sum(JournalLine.debit), 0), func.coalesce(func.sum(JournalLine.credit), 0))
        .outerjoin(JournalLine, JournalLine.account_id == Account.id)
        .outerjoin(JournalEntry, JournalEntry.id == JournalLine.entry_id)
        .filter(Account.institution_id == institution_id)
    )
    if only_user_id is not None:
        # Own transactions plus this person's own starting balances
        query = query.outerjoin(Transaction, Transaction.id == JournalEntry.transaction_id).filter(
            (JournalEntry.id.is_(None)) | (Transaction.recorded_by_id == only_user_id) | (JournalEntry.owner_id == only_user_id)
        )
    if start is not None:
        query = query.filter((JournalEntry.id.is_(None)) | (JournalEntry.entry_date >= start))
    if end is not None:
        query = query.filter((JournalEntry.id.is_(None)) | (JournalEntry.entry_date <= end))
    rows = query.group_by(Account.id).order_by(Account.code).all()
    result = []
    for account, debit, credit in rows:
        debit, credit = Decimal(debit), Decimal(credit)
        balance = debit - credit if account.type in _DEBIT_NORMAL else credit - debit
        result.append(AccountBalance(id=account.id, code=account.code, key=account.key, name=account.name, type=account.type, debit=debit, credit=credit, balance=balance))
    return result


@router.get("/summary", response_model=Summary)
def summary(
    institution_id: int | None = None,
    start_date: datetime | None = None,
    end_date: datetime | None = None,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    scoped = _scoped_institution_id(current_user, institution_id)
    backfill_institution(db, scoped)
    # Money-account balances are all-time; income/expense honour the date filter
    only_user = resolve_staff_filter(db, current_user, scoped, staff_id)
    all_time = {b.key: b for b in _balances(db, scoped, None, None, only_user)}
    period = _balances(db, scoped, start_date, end_date, only_user)
    money = [MoneyPosition(key=k, name=all_time[k].name, balance=all_time[k].balance) for k in ("cash", "mpesa", "bank") if k in all_time]
    income = sum((b.balance for b in period if b.type == AccountType.INCOME), Decimal("0"))
    expenses = sum((b.balance for b in period if b.type == AccountType.EXPENSE), Decimal("0"))
    return Summary(money=money, total_money=sum((m.balance for m in money), Decimal("0")), income=income, expenses=expenses, net=income - expenses)


@router.get("/trial-balance", response_model=TrialBalance)
def trial_balance(
    institution_id: int | None = None,
    start_date: datetime | None = None,
    end_date: datetime | None = None,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    _require_books_access(current_user)
    scoped = _scoped_institution_id(current_user, institution_id)
    backfill_institution(db, scoped)
    only_user = resolve_staff_filter(db, current_user, scoped, staff_id)
    accounts = [a for a in _balances(db, scoped, start_date, end_date, only_user) if a.debit or a.credit]
    total_debit = sum((a.debit for a in accounts), Decimal("0"))
    total_credit = sum((a.credit for a in accounts), Decimal("0"))
    return TrialBalance(accounts=accounts, total_debit=total_debit, total_credit=total_credit, balanced=total_debit == total_credit)


@router.get("/ledger/{account_id}", response_model=Ledger)
def ledger(
    account_id: int,
    start_date: datetime | None = None,
    end_date: datetime | None = None,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    _require_books_access(current_user)
    account = db.query(Account).filter(Account.id == account_id).first()
    if not account:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Account not found")
    if current_user.role == UserRole.INSTITUTION_ADMIN and account.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this account")
    backfill_institution(db, account.institution_id)
    only_user = resolve_staff_filter(db, current_user, account.institution_id, staff_id)

    query = (
        db.query(JournalEntry, JournalLine)
        .join(JournalLine, JournalLine.entry_id == JournalEntry.id)
        .filter(JournalLine.account_id == account.id)
    )
    if only_user is not None:
        query = query.outerjoin(Transaction, Transaction.id == JournalEntry.transaction_id).filter(
            (Transaction.recorded_by_id == only_user) | (JournalEntry.owner_id == only_user)
        )
    if start_date is not None:
        query = query.filter(JournalEntry.entry_date >= start_date)
    if end_date is not None:
        query = query.filter(JournalEntry.entry_date <= end_date)
    rows = query.order_by(JournalEntry.entry_date, JournalEntry.id).all()

    running = Decimal("0")
    lines = []
    for entry, line in rows:
        running += (line.debit - line.credit) if account.type in _DEBIT_NORMAL else (line.credit - line.debit)
        lines.append(LedgerLine(entry_id=entry.id, entry_date=entry.entry_date, memo=entry.memo, recorded_by_name=entry.recorded_by_name, reference=entry.reference, debit=line.debit, credit=line.credit, running_balance=running))
    total_debit = sum((l.debit for l in lines), Decimal("0"))
    total_credit = sum((l.credit for l in lines), Decimal("0"))
    summary_row = AccountBalance(id=account.id, code=account.code, key=account.key, name=account.name, type=account.type, debit=total_debit, credit=total_credit, balance=running)
    return Ledger(account=summary_row, lines=lines)


@router.get("/journal", response_model=list[JournalEntryOut])
def journal(
    institution_id: int | None = None,
    start_date: datetime | None = None,
    end_date: datetime | None = None,
    limit: int = 200,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    _require_books_access(current_user)
    scoped = _scoped_institution_id(current_user, institution_id)
    backfill_institution(db, scoped)
    only_user = resolve_staff_filter(db, current_user, scoped, staff_id)
    query = db.query(JournalEntry).filter(JournalEntry.institution_id == scoped)
    if only_user is not None:
        query = query.outerjoin(Transaction, Transaction.id == JournalEntry.transaction_id).filter(
            (Transaction.recorded_by_id == only_user) | (JournalEntry.owner_id == only_user)
        )
    if start_date is not None:
        query = query.filter(JournalEntry.entry_date >= start_date)
    if end_date is not None:
        query = query.filter(JournalEntry.entry_date <= end_date)
    entries = query.order_by(JournalEntry.entry_date.desc(), JournalEntry.id.desc()).limit(min(max(limit, 1), 500)).all()
    return [
        JournalEntryOut(
            id=e.id, entry_date=e.entry_date, memo=e.memo, transaction_id=e.transaction_id,
            recorded_by_name=e.recorded_by_name, reference=e.reference,
            lines=[JournalLineOut(account_id=l.account_id, account_name=l.account.name, debit=l.debit, credit=l.credit) for l in e.lines],
        )
        for e in entries
    ]


OPENING_MEMO = "Opening balances"
_OPENING_KEYS = ("cash", "mpesa", "bank")


def _opening_owner(current_user: User) -> int | None:
    # Each staff member keeps their own starting balances; admins' are institution-level
    return current_user.id if current_user.role == UserRole.STAFF else None


def _opening_entry(db: Session, institution_id: int, owner_id: int | None) -> JournalEntry | None:
    query = db.query(JournalEntry).filter(
        JournalEntry.institution_id == institution_id, JournalEntry.transaction_id.is_(None), JournalEntry.memo == OPENING_MEMO
    )
    query = query.filter(JournalEntry.owner_id == owner_id) if owner_id is not None else query.filter(JournalEntry.owner_id.is_(None))
    return query.first()


def _opening_amounts(db: Session, institution_id: int, owner_id: int | None) -> OpeningBalancesOut:
    entry = _opening_entry(db, institution_id, owner_id)
    amounts = {k: Decimal("0") for k in _OPENING_KEYS}
    if entry:
        for line in entry.lines:
            if line.account.key in amounts:
                amounts[line.account.key] = line.debit
    return OpeningBalancesOut(is_set=entry is not None, **amounts)


@router.get("/opening-balances", response_model=OpeningBalancesOut)
def get_opening_balances(
    institution_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    scoped = _scoped_institution_id(current_user, institution_id)
    backfill_institution(db, scoped)
    return _opening_amounts(db, scoped, _opening_owner(current_user))


@router.put("/opening-balances", response_model=OpeningBalancesOut)
def set_opening_balances(
    payload: OpeningBalances,
    institution_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    # What the institution already holds on the day it starts using KNOW; replaces any earlier figures
    scoped = _scoped_institution_id(current_user, institution_id)
    amounts = {"cash": payload.cash, "mpesa": payload.mpesa, "bank": payload.bank}
    if any(v < 0 for v in amounts.values()):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Starting balances cannot be negative")
    if any(v >= Decimal("1000000000") for v in amounts.values()):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Starting balance is too large")

    finalized = db.query(Audit).filter(Audit.institution_id == scoped, Audit.status == AuditStatus.FINALIZED).first()
    if finalized:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Starting balances can't be changed once an audit has been finalized")

    backfill_institution(db, scoped)
    owner_id = _opening_owner(current_user)
    existing = _opening_entry(db, scoped, owner_id)
    if existing:
        db.delete(existing)
        db.flush()

    total = sum(amounts.values(), Decimal("0"))
    if total > 0:
        # Dated just before the first transaction so it always counts as the starting position
        first_query = db.query(func.min(Transaction.transaction_date)).filter(Transaction.institution_id == scoped)
        if owner_id is not None:
            first_query = first_query.filter(Transaction.recorded_by_id == owner_id)
        first = first_query.scalar()
        if first is not None and first.tzinfo is None:
            first = first.replace(tzinfo=timezone.utc)
        when = (first - timedelta(seconds=1)) if first is not None else datetime.now(timezone.utc)
        entry = JournalEntry(institution_id=scoped, transaction_id=None, entry_date=when, memo=OPENING_MEMO, owner_id=owner_id)
        for key, value in amounts.items():
            if value > 0:
                entry.lines.append(JournalLine(account_id=system_account(db, scoped, key).id, debit=value, credit=Decimal("0")))
        entry.lines.append(JournalLine(account_id=system_account(db, scoped, "equity").id, debit=Decimal("0"), credit=total))
        db.add(entry)
    db.commit()
    return _opening_amounts(db, scoped, owner_id)
