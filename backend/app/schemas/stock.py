from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, field_validator


class StockItemCreate(BaseModel):
    name: str
    category_id: int
    description: str | None = None
    unit_price: Decimal | None = None
    quantity: Decimal = Decimal("0")
    branch_id: int | None = None

    @field_validator("name")
    @classmethod
    def name_not_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("name is required")
        return v

    @field_validator("quantity")
    @classmethod
    def quantity_not_negative(cls, v: Decimal) -> Decimal:
        if v < 0:
            raise ValueError("quantity cannot be negative")
        return v


class StockItemUpdate(BaseModel):
    """Edits the item's catalog info only — quantity changes go through
    transactions (a restock or a sale), not a direct edit here, so the
    stock count always has a transaction trail behind it."""
    name: str | None = None
    category_id: int | None = None
    description: str | None = None
    unit_price: Decimal | None = None
    branch_id: int | None = None

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, v: str | None) -> str | None:
        if v is not None and not v.strip():
            raise ValueError("name cannot be blank")
        return v


class StockItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    institution_id: int
    category_id: int | None
    category_name: str | None = None
    branch_id: int | None = None
    branch_name: str | None = None
    name: str
    description: str | None
    unit_price: Decimal | None
    quantity: Decimal
    created_at: datetime
