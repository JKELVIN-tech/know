from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict

from app.models.accounting import AccountType


class AccountBalance(BaseModel):
    id: int
    code: int
    key: str
    name: str
    type: AccountType
    debit: Decimal
    credit: Decimal
    balance: Decimal


class MoneyPosition(BaseModel):
    key: str
    name: str
    balance: Decimal


class Summary(BaseModel):
    money: list[MoneyPosition]
    total_money: Decimal
    income: Decimal
    expenses: Decimal
    net: Decimal


class TrialBalance(BaseModel):
    accounts: list[AccountBalance]
    total_debit: Decimal
    total_credit: Decimal
    balanced: bool


class LedgerLine(BaseModel):
    entry_id: int
    entry_date: datetime
    memo: str | None
    recorded_by_name: str | None = None
    reference: str | None = None
    debit: Decimal
    credit: Decimal
    running_balance: Decimal


class Ledger(BaseModel):
    account: AccountBalance
    lines: list[LedgerLine]


class JournalLineOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    account_id: int
    account_name: str
    debit: Decimal
    credit: Decimal


class JournalEntryOut(BaseModel):
    id: int
    entry_date: datetime
    memo: str | None
    transaction_id: int | None
    recorded_by_name: str | None = None
    reference: str | None = None
    lines: list[JournalLineOut]


class OpeningBalances(BaseModel):
    cash: Decimal
    mpesa: Decimal
    bank: Decimal


class OpeningBalancesOut(OpeningBalances):
    is_set: bool
