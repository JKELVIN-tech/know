from datetime import datetime

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

from app.models.user import UserRole, StaffType


_COMMON = {"password", "password1", "12345678", "123456789", "1234567890", "qwerty123", "qwertyuiop", "11111111", "iloveyou", "abc12345", "welcome1", "admin123", "letmein123"}


def check_password(v: str) -> str:
    v = v.strip()
    if len(v) < 8:
        raise ValueError("password must be at least 8 characters")
    if len(v.encode("utf-8")) > 72:
        raise ValueError("password is too long (72 bytes at most)")
    if v.lower() in _COMMON or len(set(v)) < 4:
        raise ValueError("password is too easy to guess")
    return v



class UserCreate(BaseModel):
    """Used by a super_admin (any institution) or an institution_admin
    (their own institution only — enforced in the router, not here) to
    issue credentials for a new account."""
    username: str
    full_name: str
    password: str
    role: UserRole = UserRole.STAFF
    staff_type: StaffType | None = None
    institution_id: int | None = None

    @field_validator("password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        return check_password(v)

    @field_validator("username")
    @classmethod
    def username_format(cls, v: str) -> str:
        v = v.strip()
        if len(v) < 3:
            raise ValueError("username must be at least 3 characters")
        return v

    @model_validator(mode="after")
    def staff_type_required_for_staff(self):
        if self.role == UserRole.STAFF and self.staff_type is None:
            raise ValueError("staff_type is required for a staff account (teacher or general)")
        return self


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    full_name: str
    role: UserRole
    staff_type: StaffType | None
    is_active: bool
    bio: str | None = None
    avatar_path: str | None = None
    institution_id: int | None
    institution_name: str | None = None
    last_active_at: datetime | None = None
    share_audits: bool
    created_at: datetime


class AuditSharingUpdate(BaseModel):
    """Self-service toggle: a staff member opting their own audits in/out of
    same-institution visibility. Never touches edit/delete rights."""
    share_audits: bool


class UserUpdate(BaseModel):
    """Used by a super_admin or institution_admin to edit an existing
    user's identity/assignment, or promote/demote them between STAFF and
    INSTITUTION_ADMIN (router enforces who can set which role). All
    fields optional so a caller can patch just one at a time."""
    username: str | None = None
    full_name: str | None = None
    institution_id: int | None = None
    staff_type: StaffType | None = None
    role: UserRole | None = None

    @field_validator("username")
    @classmethod
    def username_format(cls, v: str | None) -> str | None:
        if v is None:
            return v
        v = v.strip()
        if len(v) < 3:
            raise ValueError("username must be at least 3 characters")
        return v

    @field_validator("full_name")
    @classmethod
    def full_name_not_blank(cls, v: str | None) -> str | None:
        if v is not None and not v.strip():
            raise ValueError("full_name cannot be blank")
        return v


class PasswordReset(BaseModel):
    new_password: str

    @field_validator("new_password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        return check_password(v)


class SelfPasswordChange(BaseModel):
    """A user changing their own password (as opposed to PasswordReset,
    which is a super_admin resetting someone else's)."""
    current_password: str
    new_password: str

    @field_validator("new_password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("password must be at least 8 characters")
        return v
