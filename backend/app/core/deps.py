from fastapi import Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import decode_access_token
from app.models.user import User, UserRole, StaffType

ACCESS_TOKEN_COOKIE = "access_token"


def _extract_token(request: Request) -> str | None:
    """
    Accepts either an httpOnly session cookie (used by the frontend, subject
    to CSRF protection for state-changing requests — see main.py) or a
    Bearer Authorization header (used by scripts/API clients; browsers never
    attach this automatically cross-site, so it doesn't need CSRF checks).
    Header takes precedence when both are present.
    """
    auth_header = request.headers.get("authorization")
    if auth_header and auth_header.lower().startswith("bearer "):
        return auth_header[7:].strip()
    return request.cookies.get(ACCESS_TOKEN_COOKIE)


# An agent account may only reach these paths; everything else is refused before any route runs
AGENT_ALLOWED = ("/api/partner", "/api/auth", "/api/users/me")


def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    token = _extract_token(request)
    if not token:
        raise credentials_exception

    payload = decode_access_token(token)
    if payload is None:
        raise credentials_exception
    user_id = payload.get("sub")
    if user_id is None:
        raise credentials_exception

    user = db.query(User).filter(User.id == int(user_id)).first()
    if user is None or not user.is_active:
        raise credentials_exception

    token_version = payload.get("tv")
    if token_version is None or int(token_version) != user.token_version:
        # Token was issued before a password reset / deactivation bumped
        # the version — treat it the same as an invalid token.
        raise credentials_exception

    if user.role == UserRole.AGENT and not request.url.path.startswith(AGENT_ALLOWED):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="This account can only use the agent dashboard")

    return user


def require_super_admin(current_user: User = Depends(get_current_user)) -> User:
    if current_user.role != UserRole.SUPER_ADMIN:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only a super admin can perform this action",
        )
    return current_user


def require_institution_scope(current_user: User = Depends(get_current_user)) -> User:
    """
    Any authenticated, active user whose account is tied to an institution
    (i.e. staff). Super admins manage accounts/institutions but don't
    submit audits/transactions/posts themselves.
    """
    if current_user.role == UserRole.STAFF and current_user.institution_id is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is not assigned to an institution",
        )
    return current_user


def require_admin_scope(current_user: User = Depends(get_current_user)) -> User:
    """
    A super_admin (unrestricted) or an institution_admin (restricted to
    their own institution_id — callers must still check that explicitly;
    this only confirms the account TYPE is allowed to administer at all).
    """
    if current_user.role not in (UserRole.SUPER_ADMIN, UserRole.INSTITUTION_ADMIN):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only a super admin or institution admin can perform this action",
        )
    return current_user


def require_stock_access(current_user: User = Depends(get_current_user)) -> User:
    """
    Stock is for institution_admins and GENERAL staff only. TEACHER-type
    staff never touch it — their dashboard stays exactly the simple
    income/expense ledger it always was, enforced here rather than only
    hidden in the UI.
    """
    if current_user.role == UserRole.STAFF:
        if current_user.institution_id is None:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is not assigned to an institution")
        if current_user.staff_type == StaffType.TEACHER:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Personal accounts don't use stock")
        return current_user
    if current_user.role == UserRole.INSTITUTION_ADMIN:
        return current_user
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access stock")
