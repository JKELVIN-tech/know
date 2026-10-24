import logging
from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from slowapi.errors import RateLimitExceeded
from slowapi import _rate_limit_exceeded_handler

from app.core.bootstrap import ensure_super_admin
from app.core.config import settings
from app.core.database import Base, engine, SessionLocal
from app.core.limiter import limiter
from app.routers import auth, institutions, users, transactions, audits, posts, reports, moderation, activity_log, stock, mpesa, product_categories, market_analysis, accounting, source_documents, admin_overview, message_imports, exchange, vault, storage_usage, profit, partner, partners_admin, billing

app = FastAPI(
    title="KNOW",
    description="Institution financial audit, inventory & communication portal",
    docs_url="/docs" if settings.ENABLE_DOCS else None,
    redoc_url="/redoc" if settings.ENABLE_DOCS else None,
    openapi_url="/openapi.json" if settings.ENABLE_DOCS else None,
)

if len(settings.SECRET_KEY) < 32 or settings.SECRET_KEY.lower().startswith(("change", "secret", "test", "example")):
    logging.getLogger("know.security").warning("SECRET_KEY is short or looks like a placeholder; set a random value of 32+ characters")

app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-CSRF-Token", "X-Client"],
)

_DOCS_PATHS = ("/docs", "/redoc", "/openapi.json")


@app.middleware("http")
async def limit_request_size(request: Request, call_next):
    """Refuses oversized bodies up front, before anything is read into memory."""
    limit = 25 * 1024 * 1024 if request.url.path.startswith("/api/vault") else settings.MAX_REQUEST_BYTES
    try:
        declared = int(request.headers.get("content-length") or 0)
    except ValueError:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content={"detail": "Bad request"})
    if declared > limit:
        return JSONResponse(status_code=413, content={"detail": "Request is too large"})
    return await call_next(request)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/"):
        # Financial data must never sit in a browser or proxy cache
        response.headers["Cache-Control"] = "no-store"

    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
    # Sent unconditionally: browsers only honor HSTS on responses actually
    # delivered over HTTPS, so this is inert over local plain HTTP and
    # active as soon as this sits behind TLS.
    response.headers["Strict-Transport-Security"] = "max-age=63072000; includeSubDomains"

    if not any(request.url.path.startswith(p) for p in _DOCS_PATHS):
        response.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"

    return response


_UNSAFE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
_CSRF_EXEMPT_PATHS = ("/api/auth/login", "/api/auth/signup", "/api/auth/forgot-password", "/api/auth/reset-password")


@app.middleware("http")
async def csrf_protection(request: Request, call_next):
    """
    Double-submit CSRF check for cookie-authenticated requests. If the
    request carries its own Authorization: Bearer header, it's exempt —
    browsers never attach that header automatically on a cross-site
    request, so it isn't vulnerable to CSRF the way an automatically-sent
    cookie is. /api/auth/login is exempt because there's no session yet
    to compare against.
    """
    if request.method in _UNSAFE_METHODS and not any(request.url.path.startswith(p) for p in _CSRF_EXEMPT_PATHS):
        if "authorization" not in request.headers:
            cookie_token = request.cookies.get("csrf_token")
            header_token = request.headers.get("x-csrf-token")
            if not cookie_token or not header_token or cookie_token != header_token:
                return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content={"detail": "CSRF validation failed"})
    return await call_next(request)


app.include_router(auth.router)
app.include_router(billing.router)
app.include_router(institutions.router)
app.include_router(users.router)
app.include_router(transactions.router)
app.include_router(audits.router)
app.include_router(posts.router)
app.include_router(reports.router)
app.include_router(moderation.router)
app.include_router(activity_log.router)
app.include_router(stock.router)
app.include_router(mpesa.router)
app.include_router(product_categories.router)
app.include_router(market_analysis.router)
app.include_router(accounting.router)
app.include_router(source_documents.router)
app.include_router(admin_overview.router)
app.include_router(message_imports.router)
app.include_router(exchange.router)
app.include_router(vault.router)
app.include_router(storage_usage.router)
app.include_router(profit.router)
app.include_router(partner.router)
app.include_router(partners_admin.router)

# Institution logos are not sensitive, so they're served as plain static
# files — this lets the frontend use them directly in <img src> without
# attaching credentials the way fetch can.
app.mount("/uploads", StaticFiles(directory="uploads"), name="uploads")


@app.on_event("startup")
def on_startup():
    # In production, prefer Alembic migrations over create_all. This is kept
    # for fast local setup; see README for the migration-based path.
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        ensure_super_admin(db)
    finally:
        db.close()


@app.get("/api/health")
def health_check():
    return {"status": "ok"}
