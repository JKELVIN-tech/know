from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict

from app.models.transaction import TransactionType, TransactionMethod, TransactionCategoryType


class TransactionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    institution_id: int
    type: TransactionType
    method: TransactionMethod
    category_type: TransactionCategoryType
    category: str
    description: str | None
    amount: Decimal
    stock_item_id: int | None
    quantity: Decimal | None
    mpesa_code: str | None
    mpesa_payer_name: str | None
    transaction_date: datetime
    image_path: str | None
    recorded_by_id: int
    batch_id: str | None = None
    created_at: datetime


class BatchLine(BaseModel):
    category_type: TransactionCategoryType = TransactionCategoryType.OTHER
    category: str | None = None
    stock_item_id: int | None = None
    quantity: Decimal | None = None
    amount: Decimal
    description: str | None = None


class BatchCreate(BaseModel):
    """Several items sold or bought together: one payment, one proof, a record for every item."""
    batch_id: str
    type: TransactionType
    method: TransactionMethod
    description: str | None = None
    mpesa_code: str | None = None
    mpesa_payer_name: str | None = None
    lines: list[BatchLine]
