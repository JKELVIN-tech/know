from decimal import Decimal
from datetime import datetime

from pydantic import BaseModel, Field, ConfigDict, field_validator

from app.core.constants import KENYA_COUNTIES


class InstitutionCreate(BaseModel):
    name: str
    type: str
    address: str | None = None
    region: str | None = None
    partner_code: str | None = None
    monthly_fee: Decimal | None = Field(default=None, ge=0, le=Decimal("100000000"))

    @field_validator("type")
    @classmethod
    def type_not_empty(cls, v: str) -> str:
        v = v.strip().lower()
        if not v:
            raise ValueError("type is required (e.g. school, shop, supermarket)")
        return v

    @field_validator("region")
    @classmethod
    def region_valid(cls, v: str | None) -> str | None:
        if v is None:
            return v
        if v not in KENYA_COUNTIES:
            raise ValueError("region must be one of Kenya's 47 counties")
        return v


class InstitutionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    type: str
    address: str | None
    region: str | None
    logo_path: str | None
    created_at: datetime
