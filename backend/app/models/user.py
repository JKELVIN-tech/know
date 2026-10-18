import enum

from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Enum, Boolean, Index, func
from sqlalchemy.orm import relationship

from app.core.database import Base


class UserRole(str, enum.Enum):
    SUPER_ADMIN = "super_admin"           # creates institutions, everything, everywhere
    INSTITUTION_ADMIN = "institution_admin"  # onboards staff, manages stock/logo — for their OWN institution only
    STAFF = "staff"                       # enters audits/transactions; STAFF whose staff_type is TEACHER never touch stock


class StaffType(str, enum.Enum):
    """Only meaningful when role=STAFF. TEACHER accounts never see or use
    the stock/inventory feature — enforced both in the UI and server-side,
    since a school's finances (fees, rent) are a different concern from a
    shop's inventory even when they share the same platform."""
    TEACHER = "teacher"
    GENERAL = "general"


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(80), nullable=False, unique=True, index=True)
    # Used only to send password reset links; unique per account
    email = Column(String(254), nullable=True)
    full_name = Column(String(150), nullable=False)
    hashed_password = Column(String(255), nullable=False)
    role = Column(Enum(UserRole), nullable=False, default=UserRole.STAFF)
    staff_type = Column(Enum(StaffType), nullable=True)
    is_active = Column(Boolean, nullable=False, default=True)

    # Brute-force protection: incremented on each wrong password, reset on
    # success. locked_until blocks login attempts entirely while in the future.
    failed_login_attempts = Column(Integer, nullable=False, default=0)
    locked_until = Column(DateTime(timezone=True), nullable=True)

    # Bumped on password reset / deactivation so previously-issued JWTs for
    # this user stop being accepted immediately, without needing a token
    # blocklist. Embedded in the JWT as "tv" and checked on every request.
    token_version = Column(Integer, nullable=False, default=0)

    # Nullable because a super_admin is not scoped to a single institution.
    institution_id = Column(Integer, ForeignKey("institutions.id"), nullable=True)

    # Staff self-service toggle: when False (default), only this staff
    # member and super_admins can see their audits. When True, other staff
    # at the same institution can also see them. Never affects edit/delete
    # rights — only the submitter (or a super_admin) can modify an audit.
    share_audits = Column(Boolean, nullable=False, default=False)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    # Track who issued the credentials, for accountability.
    created_by_id = Column(Integer, ForeignKey("users.id"), nullable=True)

    # Shown beside their posts on the news feed
    bio = Column(String(300), nullable=True)
    avatar_path = Column(String(500), nullable=True)

    institution = relationship("Institution", back_populates="users")
    transactions_recorded = relationship("Transaction", back_populates="recorded_by", foreign_keys="Transaction.recorded_by_id")
    posts = relationship("Post", back_populates="author")

    @property
    def institution_name(self) -> str | None:
        return self.institution.name if self.institution else None


Index("uq_users_email", func.lower(User.email), unique=True, postgresql_where=User.email.isnot(None))
