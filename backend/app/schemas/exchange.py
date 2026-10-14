from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field


class BranchOut(BaseModel):
    id: int
    name: str


class TransferCreate(BaseModel):
    item_id: int
    quantity: Decimal = Field(gt=0)
    to_branch_id: int
    from_branch_id: int | None = None
    note: str | None = Field(default=None, max_length=200)


class TransferOut(BaseModel):
    id: int
    item_name: str
    quantity: Decimal
    from_branch_id: int
    from_branch_name: str
    to_branch_id: int
    to_branch_name: str
    status: str
    note: str | None
    direction: str
    created_at: datetime | None
    resolved_at: datetime | None
