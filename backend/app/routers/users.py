from fastapi import BackgroundTasks, APIRouter, Depends, File, Form, HTTPException, Request, UploadFile, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user, require_institution_scope, require_admin_scope
from app.core.limiter import get_client_ip, limiter
from app.core.security import hash_password, verify_password
from app.models.activity_log import ActivityLog
from app.models.institution import Institution
from app.models.transaction import Transaction
from app.models.user import User, UserRole, StaffType
from app.utils.uploads import delete_storage_object, save_avatar, read_upload
from app.schemas.user import UserCreate, UserOut, PasswordReset, UserUpdate, AuditSharingUpdate, SelfPasswordChange, EmailChange
from app.utils.email import email_configured
from app.utils.password_reset import issue_token, recent_requests, send_reset_email

router = APIRouter(prefix="/api/users", tags=["users"])


def _assert_can_manage(actor: User, target: User) -> None:
    """
    super_admin manages anyone, anywhere. An institution_admin only
    manages STAFF and other INSTITUTION_ADMIN accounts at their OWN
    institution — never a super_admin, and never a different institution.
    """
    if actor.role == UserRole.SUPER_ADMIN:
        return
    if target.role == UserRole.SUPER_ADMIN or target.institution_id != actor.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to manage this account")


@router.get("/me", response_model=UserOut)
def get_my_profile(current_user: User = Depends(require_institution_scope)):
    return current_user


@router.post("/me/password", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("5/minute")
def change_my_password(payload: SelfPasswordChange, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """Any user changing their own password. Requires the current password,
    unlike the admin reset-password endpoint which is for issuing someone
    else's password without it."""
    if not verify_password(payload.current_password, current_user.hashed_password):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Current password is incorrect")
    current_user.hashed_password = hash_password(payload.new_password)
    # Bumping this invalidates the token used for this very request too, so
    # the frontend must treat a successful change as an implicit logout —
    # the old cookie/bearer token stops working on the next call regardless.
    current_user.token_version += 1
    db.commit()
    log_activity(db, action="self_password_change", actor=current_user, target_type="user", target_id=current_user.id,
                 detail="Changed own password", request=request)


@router.post("/me/email", response_model=UserOut)
@limiter.limit("5/minute")
def change_my_email(payload: EmailChange, request: Request, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Sets the address that password reset links are sent to; needs the current password so a left-open session can't redirect resets."""
    user = db.query(User).filter(User.id == current_user.id).first()
    if not verify_password(payload.current_password, user.hashed_password):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Current password is incorrect")
    new_email = payload.email or None
    if new_email and db.query(User).filter(func.lower(User.email) == new_email, User.id != user.id).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That email address is already used by another account")
    user.email = new_email
    db.commit()
    db.refresh(user)
    log_activity(db, action="email_changed", actor=user, target_type="user", target_id=user.id, detail="Changed own reset email", request=request)
    return user


@router.patch("/me/profile", response_model=UserOut)
@limiter.limit("10/minute")
def update_my_profile(
    request: Request,
    bio: str | None = Form(None),
    remove_avatar: bool = Form(False),
    avatar: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """A member's own profile: a short bio and a photo, both shown beside their news posts."""
    user = db.query(User).filter(User.id == current_user.id).first()
    if bio is not None:
        bio = bio.strip()
        if len(bio) > 300:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Bio can be at most 300 characters")
        user.bio = bio or None
    old_avatar = user.avatar_path
    if avatar is not None and avatar.filename:
        user.avatar_path = save_avatar(avatar, read_upload(avatar))
        delete_storage_object(old_avatar)
    elif remove_avatar:
        user.avatar_path = None
        delete_storage_object(old_avatar)
    db.commit()
    db.refresh(user)
    return user


@router.patch("/me/audit-sharing", response_model=UserOut)
@limiter.limit("20/minute")
def update_audit_sharing(payload: AuditSharingUpdate, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """Staff self-service: opt their own audits in/out of same-institution
    visibility for other staff. Never affects edit/delete rights."""
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Only staff accounts have audits to share")
    current_user.share_audits = payload.share_audits
    db.commit()
    db.refresh(current_user)
    return current_user


@router.post("", response_model=UserOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("10/minute")
def create_user(payload: UserCreate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    """
    A super_admin issues credentials for anyone, anywhere. An
    institution_admin can only issue STAFF or INSTITUTION_ADMIN accounts —
    never SUPER_ADMIN — and only for their own institution; institution_id
    is silently pinned to their own rather than trusting the payload.
    There is no self-registration path in this system by design.
    """
    if admin.role == UserRole.INSTITUTION_ADMIN:
        if payload.role == UserRole.SUPER_ADMIN:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Institution admins can't create super admin accounts")
        payload.institution_id = admin.institution_id

    if db.query(User).filter(func.lower(User.username) == payload.username.lower()).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Username already taken")

    needs_institution = payload.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN)
    if needs_institution:
        if payload.institution_id is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required for this role")
        if not db.query(Institution).filter(Institution.id == payload.institution_id).first():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")

    user = User(
        username=payload.username,
        full_name=payload.full_name,
        hashed_password=hash_password(payload.password),
        role=payload.role,
        staff_type=payload.staff_type if payload.role == UserRole.STAFF else None,
        institution_id=payload.institution_id if needs_institution else None,
        created_by_id=admin.id,
        email=payload.email,
    )
    if payload.email and db.query(User).filter(func.lower(User.email) == payload.email).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That email address is already used by another account")
    db.add(user)
    db.commit()
    db.refresh(user)
    log_activity(db, action="user_created", actor=admin, target_type="user", target_id=user.id,
                 detail=f"Created {user.role.value} account '{user.username}'", request=request)
    return user


@router.get("", response_model=list[UserOut])
def list_users(db: Session = Depends(get_db), current_user: User = Depends(require_admin_scope)):
    """super_admin sees everyone; institution_admin sees only their own institution's accounts."""
    query = db.query(User)
    if current_user.role == UserRole.INSTITUTION_ADMIN:
        query = query.filter(User.institution_id == current_user.institution_id)
    users = query.order_by(User.created_at.desc()).all()
    # Last activity = the later of their latest recorded entry and their latest sign-in
    entries = dict(db.query(Transaction.recorded_by_id, func.max(Transaction.created_at)).group_by(Transaction.recorded_by_id).all())
    logins = dict(
        db.query(ActivityLog.actor_id, func.max(ActivityLog.created_at))
        .filter(ActivityLog.action == "login_success", ActivityLog.actor_id.isnot(None))
        .group_by(ActivityLog.actor_id)
        .all()
    )
    for u in users:
        times = [t for t in (entries.get(u.id), logins.get(u.id)) if t is not None]
        u.last_active_at = max(times) if times else None
    return users


@router.patch("/{user_id}", response_model=UserOut)
@limiter.limit("20/minute")
def update_user(user_id: int, payload: UserUpdate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    """Edits an existing account's username, display name, staff type, or
    institution assignment. See _assert_can_manage for who can touch whom."""
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    _assert_can_manage(admin, user)

    changes = []

    if payload.username is not None and payload.username != user.username:
        existing = db.query(User).filter(func.lower(User.username) == payload.username.lower(), User.id != user_id).first()
        if existing:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Username already taken")
        changes.append(f"username '{user.username}' -> '{payload.username}'")
        user.username = payload.username

    if payload.email is not None and (payload.email or None) != user.email:
        new_email = payload.email or None
        if new_email and db.query(User).filter(func.lower(User.email) == new_email, User.id != user_id).first():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That email address is already used by another account")
        changes.append("email updated")
        user.email = new_email

    if payload.full_name is not None and payload.full_name != user.full_name:
        changes.append(f"full name '{user.full_name}' -> '{payload.full_name}'")
        user.full_name = payload.full_name

    if payload.role is not None and payload.role != user.role:
        if payload.role == UserRole.SUPER_ADMIN or user.role == UserRole.SUPER_ADMIN:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Super admin status can't be granted or removed here")
        if payload.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid role")
        changes.append(f"role '{user.role.value}' -> '{payload.role.value}'")
        user.role = payload.role
        if payload.role == UserRole.INSTITUTION_ADMIN:
            user.staff_type = None  # only meaningful for STAFF
        elif payload.role == UserRole.STAFF and user.staff_type is None:
            user.staff_type = payload.staff_type or StaffType.GENERAL

    if payload.staff_type is not None and payload.staff_type != user.staff_type:
        if user.role != UserRole.STAFF:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Only staff accounts have a staff type")
        changes.append(f"staff type '{user.staff_type}' -> '{payload.staff_type.value}'")
        user.staff_type = payload.staff_type

    if payload.institution_id is not None and payload.institution_id != user.institution_id:
        if admin.role == UserRole.INSTITUTION_ADMIN:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Institution admins can't reassign an account to a different institution")
        if user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Only staff/institution admin accounts have an institution assignment")
        institution = db.query(Institution).filter(Institution.id == payload.institution_id).first()
        if not institution:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")
        changes.append(f"institution {user.institution_id} -> {payload.institution_id}")
        user.institution_id = payload.institution_id

    if changes:
        db.commit()
        db.refresh(user)
        log_activity(db, action="user_updated", actor=admin, target_type="user", target_id=user.id,
                     detail="; ".join(changes), request=request)
    return user


@router.patch("/{user_id}/deactivate", response_model=UserOut)
@limiter.limit("20/minute")
def deactivate_user(user_id: int, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    _assert_can_manage(admin, user)
    if user.id == admin.id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cannot deactivate your own account")
    user.is_active = False
    # Belt-and-suspenders: is_active is already re-checked on every request,
    # but bumping the token version too means this account's sessions die
    # immediately even in a code path that only checked the token, not the
    # live DB row.
    user.token_version += 1
    db.commit()
    db.refresh(user)
    log_activity(db, action="user_deactivated", actor=admin, target_type="user", target_id=user.id,
                 detail=f"Deactivated '{user.username}'", request=request)
    return user


@router.patch("/{user_id}/reactivate", response_model=UserOut)
@limiter.limit("20/minute")
def reactivate_user(user_id: int, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    _assert_can_manage(admin, user)
    user.is_active = True
    db.commit()
    db.refresh(user)
    log_activity(db, action="user_reactivated", actor=admin, target_type="user", target_id=user.id,
                 detail=f"Reactivated '{user.username}'", request=request)
    return user


@router.post("/{user_id}/reset-password", response_model=UserOut)
@limiter.limit("10/minute")
def reset_password(user_id: int, payload: PasswordReset, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    """Admin issues a new password for a user they manage (e.g. after a reset request)."""
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    _assert_can_manage(admin, user)
    user.hashed_password = hash_password(payload.new_password)
    # Any session started with the old password stops working immediately,
    # not just at its natural expiry — important if the reset was prompted
    # by a compromised or carelessly-shared password.
    user.token_version += 1
    user.failed_login_attempts = 0
    user.locked_until = None
    db.commit()
    db.refresh(user)
    log_activity(db, action="password_reset", actor=admin, target_type="user", target_id=user.id,
                 detail=f"Password reset for '{user.username}'", request=request)
    return user


@router.post("/{user_id}/send-reset-link", status_code=status.HTTP_202_ACCEPTED)
@limiter.limit("10/minute")
def send_reset_link(user_id: int, request: Request, background: BackgroundTasks, db: Session = Depends(get_db), admin: User = Depends(require_admin_scope)):
    """An admin emails a reset link to an account they manage, so the new password is never typed in by someone else."""
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    _assert_can_manage(admin, user)
    if not user.email:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This account has no email address. Add one first, or set a password directly.")
    if not user.is_active:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This account is deactivated")
    if not email_configured():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Email is not set up on the server yet")
    if recent_requests(db, user.id) >= 5:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many links were sent for this account. Try again later.")
    token = issue_token(db, user, get_client_ip(request))
    background.add_task(send_reset_email, user.email, user.full_name, user.username, token)
    log_activity(db, action="reset_link_sent", actor=admin, target_type="user", target_id=user.id, detail=f"Reset link sent to '{user.username}'", request=request)
    return {"message": "Reset link sent"}
