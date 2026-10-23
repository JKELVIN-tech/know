import re
from datetime import datetime

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

from app.models.user import UserRole, StaffType


_COMMON = {"password", "password1", "12345678", "123456789", "1234567890", "qwerty123", "qwertyuiop", "11111111", "iloveyou", "abc12345", "welcome1", "admin123", "letmein123"}


_LEET = str.maketrans({"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s", "!": "i"})
_WORDS = ("password", "passw0rd", "qwerty", "letmein", "welcome", "iloveyou", "changeme", "administrator", "abcdefg")


def check_password(v: str, username: str | None = None) -> str:
    v = v.strip()
    missing = []
    if len(v) < 10:
        missing.append("at least 10 characters")
    if not re.search(r"[A-Z]", v):
        missing.append("an uppercase letter")
    if not re.search(r"[a-z]", v):
        missing.append("a lowercase letter")
    if not re.search(r"[0-9]", v):
        missing.append("a number")
    if not re.search(r"[^A-Za-z0-9\s]", v):
        missing.append("a symbol")
    if missing:
        raise ValueError("Password needs " + ", ".join(missing))
    if len(v.encode("utf-8")) > 72:
        raise ValueError("Password is too long (72 bytes at most)")
    simplified = v.lower().translate(_LEET)
    if v.lower() in _COMMON or len(set(v)) < 5 or any(word in simplified for word in _WORDS):
        raise ValueError("Password is too easy to guess")
    if username and len(username) >= 3 and username.lower() in v.lower():
        raise ValueError("Password must not contain the username")
    return v


_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]{2,}$")


def clean_email(v: str | None) -> str | None:
    if v is None:
        return None
    v = v.strip().lower()
    if v == "":
        return ""
    if len(v) > 254 or not _EMAIL.match(v):
        raise ValueError("Enter a valid email address")
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
    email: str | None = None

    @field_validator("email")
    @classmethod
    def email_format(cls, v: str | None) -> str | None:
        return clean_email(v) or None

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
        check_password(self.password, self.username)
        if self.role == UserRole.STAFF and self.staff_type is None:
            raise ValueError("staff_type is required for a staff account (teacher or general)")
        return self


class SignupRequest(BaseModel):
    username: str
    full_name: str
    password: str
    account_type: str
    email: str | None = None

    @field_validator("email")
    @classmethod
    def email_format(cls, v: str | None) -> str | None:
        return clean_email(v) or None

    @field_validator("account_type")
    @classmethod
    def account_type_valid(cls, v: str) -> str:
        v = v.strip().lower()
        if v not in ("staff", "personal"):
            raise ValueError("account_type must be staff or personal")
        return v

    @field_validator("full_name")
    @classmethod
    def name_clean(cls, v: str) -> str:
        v = " ".join(v.split())
        if len(v) < 2 or len(v) > 100:
            raise ValueError("Enter your name (2 to 100 characters)")
        return v

    @field_validator("username")
    @classmethod
    def username_format(cls, v: str) -> str:
        v = v.strip()
        if len(v) < 3 or len(v) > 40 or not re.fullmatch(r"[A-Za-z0-9_.-]+", v):
            raise ValueError("Username must be 3 to 40 letters, numbers, dots, dashes or underscores")
        return v

    @model_validator(mode="after")
    def password_ok(self):
        check_password(self.password, self.username)
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
    email: str | None = None
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
    email: str | None = None

    @field_validator("email")
    @classmethod
    def email_format(cls, v: str | None) -> str | None:
        return clean_email(v)

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
    """A user changing their own password."""
    current_password: str
    new_password: str

    @field_validator("new_password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        return check_password(v)


class ForgotPassword(BaseModel):
    identifier: str

    @field_validator("identifier")
    @classmethod
    def not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v or len(v) > 254:
            raise ValueError("Enter your username or email")
        return v


class ResetWithToken(BaseModel):
    token: str
    new_password: str

    @field_validator("new_password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        return check_password(v)


class EmailChange(BaseModel):
    email: str
    current_password: str

    @field_validator("email")
    @classmethod
    def email_format(cls, v: str) -> str:
        return clean_email(v) or ""
