from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, func

from app.core.database import Base


class PasswordReset(Base):
    """A single-use reset link; only the SHA-256 of the token is stored, never the token itself."""
    __tablename__ = "password_resets"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = Column(String(64), nullable=False, unique=True, index=True)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    used_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    requested_ip = Column(String(64), nullable=True)
