"""
Centralized application settings, loaded from environment variables / .env.
Never hardcode secrets here — everything sensitive comes from the environment.
"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    DATABASE_URL: str
    SECRET_KEY: str
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    ANDROID_TOKEN_EXPIRE_DAYS: int = 90
    ENABLE_DOCS: bool = False
    # Password reset email: an email service with a web API (free hosts block SMTP); provider is "resend" or "brevo"
    EMAIL_PROVIDER: str = ""
    EMAIL_API_KEY: str = ""
    EMAIL_FROM: str = ""
    FRONTEND_URL: str = "http://localhost:5500"
    PASSWORD_RESET_MINUTES: int = 30
    MAX_REQUEST_BYTES: int = 12 * 1024 * 1024

    BOOTSTRAP_SUPER_ADMIN_USERNAME: str | None = None
    BOOTSTRAP_SUPER_ADMIN_PASSWORD: str | None = None

    UPLOAD_DIR: str = "uploads/logos"
    MAX_LOGO_SIZE_BYTES: int = 2 * 1024 * 1024  # 2 MB

    POST_UPLOAD_DIR: str = "uploads/posts"
    MAX_POST_IMAGE_SIZE_BYTES: int = 5 * 1024 * 1024  # 5 MB

    TRANSACTION_UPLOAD_DIR: str = "uploads/transactions"
    MAX_TRANSACTION_IMAGE_SIZE_BYTES: int = 5 * 1024 * 1024  # 5 MB

    # Uploaded images (logos, post/transaction photos) are stored in
    # Supabase Storage rather than local disk — Render's free tier wipes
    # local files on every restart. SUPABASE_SERVICE_ROLE_KEY is a secret;
    # it's only used server-side to write to the storage bucket, never
    # sent to the frontend.
    SUPABASE_URL: str | None = None
    SUPABASE_SERVICE_ROLE_KEY: str | None = None
    SUPABASE_STORAGE_BUCKET: str = "uploads"

    CORS_ORIGINS: str = "http://localhost:5500"

    # Brute-force protection: after this many consecutive failed logins for
    # one account, it's locked for LOGIN_LOCKOUT_MINUTES regardless of which
    # IP the attempts came from (the slowapi rate limit on /login is per-IP
    # and doesn't by itself stop a distributed attack against one account).
    LOGIN_MAX_ATTEMPTS: int = 5
    LOGIN_LOCKOUT_MINUTES: int = 15

    # Session cookie behavior. COOKIE_SECURE should be True in any real
    # deployment (cookie only sent over HTTPS) — set False only for local
    # http:// development. "site" for cookie purposes ignores port, so a
    # frontend on :5500 and backend on :8000 on the same host are same-site
    # under SameSite=lax; a genuinely cross-site deployment (different
    # registrable domains) needs SameSite=none plus Secure=true.
    COOKIE_SECURE: bool = True
    COOKIE_SAMESITE: str = "lax"

    @property
    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.CORS_ORIGINS.split(",") if o.strip()]


settings = Settings()