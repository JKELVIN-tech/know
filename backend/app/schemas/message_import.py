from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field

from app.models.transaction import TransactionCategoryType


class ImportRequest(BaseModel):
    messages: list[str] = Field(min_length=1, max_length=50)


class ApproveRequest(BaseModel):
    category_type: TransactionCategoryType = TransactionCategoryType.OTHER
    category: str | None = None
    stock_item_id: int | None = None
    quantity: Decimal | None = None
    description: str | None = None
    force: bool = False


class MessageImportOut(BaseModel):
    id: int
    code: str
    kind: str
    direction: str | None
    amount: Decimal | None
    fee: Decimal | None
    balance: Decimal | None
    counterparty: str | None
    account_ref: str | None
    bank_hint: str | None
    message_time: datetime | None
    status: str
    reason: str | None
    transaction_id: int | None
    created_at: datetime | None

    class Config:
        from_attributes = True


class ImportResult(BaseModel):
    items: list[MessageImportOut]
    skipped: int
