from datetime import datetime, timedelta, timezone

from fastapi import Header, APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.config import settings
from app.core.database import get_db
from app.core.deps import ACCESS_TOKEN_COOKIE, get_current_user
from app.core.limiter import limiter
from app.core.security import hash_password, verify_password, create_access_token, generate_csrf_token
from app.models.user import User
from app.schemas.auth import TokenResponse

router = APIRouter(prefix="/api/auth", tags=["auth"])

CSRF_COOKIE = "csrf_token"

def _utc(moment: datetime) -> datetime:
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


# Checked when the username does not exist, so a wrong username takes as long as a wrong password
_DUMMY_HASH = hash_password("not-a-real-password")


def _set_session_cookies(response: Response, token: str, csrf_token: str) -> None:
    max_age = settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60
    response.set_cookie(
        key=ACCESS_TOKEN_COOKIE,
        value=token,
        max_age=max_age,
        httponly=True,
        secure=settings.COOKIE_SECURE,
        samesite=settings.COOKIE_SAMESITE,
        path="/",
    )
    # Deliberately NOT httponly — the frontend reads this to echo it back as
    # an X-CSRF-Token header on state-changing requests (double-submit
    # pattern). It authenticates nothing by itself; it only proves the
    # request came from a script that could read this origin's cookies.
    response.set_cookie(
        key=CSRF_COOKIE,
        value=csrf_token,
        max_age=max_age,
        httponly=False,
        secure=settings.COOKIE_SECURE,
        samesite=settings.COOKIE_SAMESITE,
        path="/",
    )


@router.post("/login", response_model=TokenResponse)
@limiter.limit("10/minute")
@limiter.limit("120/minute", key_func=lambda request: "login-all")
def login(
    request: Request,
    response: Response,
    form_data: OAuth2PasswordRequestForm = Depends(),
    db: Session = Depends(get_db),
    x_client: str | None = Header(None),
):
    """
    Credential-based login. Accounts are provisioned by a super admin —
    there is no self-service signup.

    Two layers of brute-force protection stack here: the slowapi limit
    above throttles by IP, and the per-account lockout below stops a
    distributed attack against one specific username regardless of
    how many IPs it comes from.
    """
    # Usernames ignore capital letters and stray spaces, so "Sam" and "sam " both reach the same account
    submitted_username = form_data.username.strip()
    user = db.query(User).filter(func.lower(User.username) == submitted_username.lower()).first()

    if user and user.locked_until and _utc(user.locked_until) > datetime.now(timezone.utc):
        minutes_left = max(1, int((_utc(user.locked_until) - datetime.now(timezone.utc)).total_seconds() // 60) + 1)
        log_activity(db, action="login_blocked_locked", actor_username=form_data.username, request=request)
        raise HTTPException(
            status_code=status.HTTP_423_LOCKED,
            detail=f"Too many failed attempts. Try again in about {minutes_left} minute(s).",
        )

    # A phone keyboard often adds a trailing space after a word, so the password is also tried without outer spaces
    if not user:
        verify_password(form_data.password, _DUMMY_HASH)
    password_ok = bool(user) and (
        verify_password(form_data.password, user.hashed_password)
        or (form_data.password != form_data.password.strip() and verify_password(form_data.password.strip(), user.hashed_password))
    )
    if not user or not password_ok:
        if user:
            user.failed_login_attempts += 1
            if user.failed_login_attempts >= settings.LOGIN_MAX_ATTEMPTS:
                user.locked_until = datetime.now(timezone.utc) + timedelta(minutes=settings.LOGIN_LOCKOUT_MINUTES)
                user.failed_login_attempts = 0
                db.commit()
                log_activity(db, action="account_locked", actor=user, request=request,
                             detail=f"Locked for {settings.LOGIN_LOCKOUT_MINUTES} minutes after repeated failures.")
            else:
                db.commit()
        log_activity(db, action="login_failed", actor_username=form_data.username, request=request)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not user.is_active:
        log_activity(db, action="login_blocked_inactive", actor=user, request=request)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is disabled")

    if user.failed_login_attempts or user.locked_until:
        user.failed_login_attempts = 0
        user.locked_until = None
        db.commit()

    log_activity(db, action="login_success", actor=user, request=request)

    # The Android app asks for a long-lived session so people stay signed in; the website keeps its short one
    lifetime = 60 * 24 * settings.ANDROID_TOKEN_EXPIRE_DAYS if x_client == "android" else None
    token = create_access_token(data={"sub": str(user.id), "role": user.role.value, "tv": user.token_version}, expires_minutes=lifetime)
    csrf_token = generate_csrf_token()
    _set_session_cookies(response, token, csrf_token)

    return TokenResponse(
        access_token=token,
        user_id=user.id,
        role=user.role.value,
        staff_type=user.staff_type.value if user.staff_type else None,
        full_name=user.full_name,
        institution_id=user.institution_id,
    )


@router.post("/logout-all", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("5/minute")
def logout_all(request: Request, response: Response, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Signs this account out of every device at once (a lost phone, a shared computer)."""
    current_user.token_version += 1
    db.commit()
    log_activity(db, action="logout_all", actor=current_user, request=request, detail="Signed out of all devices")
    response.delete_cookie(ACCESS_TOKEN_COOKIE, path="/")
    response.delete_cookie(CSRF_COOKIE, path="/")


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(response: Response):
    """
    Clears the session cookies. Doesn't require a valid session — logging
    out should always succeed, even against an already-expired or tampered
    cookie.
    """
    response.delete_cookie(ACCESS_TOKEN_COOKIE, path="/")
    response.delete_cookie(CSRF_COOKIE, path="/")
