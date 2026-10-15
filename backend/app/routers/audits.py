from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user, require_institution_scope
from app.core.limiter import limiter
from app.models.audit import Audit, AuditStatus
from app.models.transaction import Transaction
from app.models.user import User, UserRole
from app.schemas.audit import AuditCreate, AuditUpdate, AuditOut
from app.schemas.transaction import TransactionOut

router = APIRouter(prefix="/api/audits", tags=["audits"])


@router.post("", response_model=AuditOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("10/minute")
def create_audit(request: Request, payload: AuditCreate, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    if current_user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff and owners submit audits")

    audit = Audit(
        institution_id=current_user.institution_id,
        title=payload.title,
        period_start=payload.period_start,
        period_end=payload.period_end,
        summary=payload.summary,
        submitted_by_id=current_user.id,
    )
    db.add(audit)
    db.commit()
    db.refresh(audit)
    return audit


@router.get("", response_model=list[AuditOut])
def list_audits(
    institution_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    if current_user.role == UserRole.STAFF:
        # Private by default: a staff member always sees their own audits,
        # plus any other same-institution staff member's audits only if
        # that person has opted in via share_audits.
        return (
            db.query(Audit)
            .join(User, Audit.submitted_by_id == User.id)
            .filter(
                Audit.institution_id == current_user.institution_id,
                (Audit.submitted_by_id == current_user.id) | (User.share_audits.is_(True)),
            )
            .order_by(Audit.created_at.desc())
            .all()
        )
    if current_user.role == UserRole.INSTITUTION_ADMIN:
        # Full oversight of their own institution's audits — never another
        # institution's, regardless of what institution_id the client sends.
        return db.query(Audit).filter(Audit.institution_id == current_user.institution_id).order_by(Audit.created_at.desc()).all()
    if institution_id is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required")
    return db.query(Audit).filter(Audit.institution_id == institution_id).order_by(Audit.created_at.desc()).all()


def _get_audit_visible(audit_id: int, current_user: User, db: Session) -> Audit:
    """Read access: owner, a super_admin, an institution_admin (own
    institution only), or (for staff) anyone at the same institution if
    the submitter has opted into sharing."""
    audit = db.query(Audit).filter(Audit.id == audit_id).first()
    if not audit:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Audit not found")
    if current_user.role == UserRole.STAFF:
        if audit.institution_id != current_user.institution_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access this audit")
        if audit.submitted_by_id != current_user.id:
            submitter = db.query(User).filter(User.id == audit.submitted_by_id).first()
            if not submitter or not submitter.share_audits:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access this audit")
    elif current_user.role == UserRole.INSTITUTION_ADMIN and audit.institution_id != current_user.institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to access this audit")
    return audit


def _get_audit_owned(audit_id: int, current_user: User, db: Session) -> Audit:
    """Write access (edit/delete/finalize): only the submitter (and only
    while still at the same institution — covers the edge case of someone
    being reassigned to a different institution later) or a super_admin.
    Sharing only ever affects visibility, never edit rights, and
    institution_admin gets no edit access at all, matching this
    docstring's stated intent exactly."""
    audit = db.query(Audit).filter(Audit.id == audit_id).first()
    if not audit:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Audit not found")
    is_owning_submitter = audit.submitted_by_id == current_user.id and audit.institution_id == current_user.institution_id
    if current_user.role != UserRole.SUPER_ADMIN and not is_owning_submitter:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the staff member who submitted this audit (or a super admin) can modify it")
    return audit


@router.get("/{audit_id}", response_model=AuditOut)
def get_audit(audit_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    return _get_audit_visible(audit_id, current_user, db)


@router.get("/{audit_id}/transactions", response_model=list[TransactionOut])
def get_audit_transactions(audit_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """
    An audit isn't a manual bucket staff file transactions into — it's a
    signed-off snapshot of whatever the institution recorded during its
    period_start/period_end. So "the audit's transactions" are computed
    from that date range, not stored via a foreign key.
    """
    audit = _get_audit_visible(audit_id, current_user, db)
    query = db.query(Transaction).filter(
        Transaction.institution_id == audit.institution_id,
        Transaction.transaction_date >= audit.period_start,
        Transaction.transaction_date <= audit.period_end,
    )
    if current_user.role == UserRole.STAFF:
        query = query.filter(Transaction.recorded_by_id == current_user.id)
    return query.order_by(Transaction.transaction_date.desc()).all()


@router.patch("/{audit_id}", response_model=AuditOut)
@limiter.limit("20/minute")
def update_audit(audit_id: int, payload: AuditUpdate, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    audit = _get_audit_owned(audit_id, current_user, db)
    if audit.status == AuditStatus.FINALIZED:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cannot edit a finalized audit")

    if payload.title is not None:
        audit.title = payload.title
    if payload.summary is not None:
        audit.summary = payload.summary
    if payload.period_start is not None:
        audit.period_start = payload.period_start
    if payload.period_end is not None:
        audit.period_end = payload.period_end

    # Cross-field check against the merged result, since a PATCH schema
    # validator only sees fields present in this specific request body —
    # e.g. moving period_end earlier without resending period_start.
    if audit.period_end < audit.period_start:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="period_end must be on or after period_start")

    db.commit()
    db.refresh(audit)
    log_activity(db, action="audit_updated", actor=current_user, target_type="audit", target_id=audit.id,
                 detail=f"Updated '{audit.title}'", request=request)
    return audit


@router.delete("/{audit_id}", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("10/minute")
def delete_audit(audit_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """
    Deletes a draft audit. Since transactions aren't linked to an audit by
    foreign key (they're matched by date range at read time — see
    get_audit_transactions above), deleting the audit row is all that's
    needed; no transactions are touched either way.
    """
    audit = _get_audit_owned(audit_id, current_user, db)
    if audit.status == AuditStatus.FINALIZED:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cannot delete a finalized audit")

    title = audit.title
    db.delete(audit)
    db.commit()
    log_activity(db, action="audit_deleted", actor=current_user, target_type="audit", target_id=audit_id,
                 detail=f"Deleted '{title}'", request=request)


@router.post("/{audit_id}/finalize", response_model=AuditOut)
@limiter.limit("10/minute")
def finalize_audit(audit_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    """
    Locks the audit so it (and its report) reflects a fixed, signed-off
    state. Since an audit's "transactions" are computed from its date range
    rather than stored, finalizing also freezes every transaction that
    currently falls in that range — see transactions.py's locking check.
    """
    audit = _get_audit_owned(audit_id, current_user, db)
    if audit.status == AuditStatus.FINALIZED:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Audit is already finalized")
    audit.status = AuditStatus.FINALIZED
    audit.finalized_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(audit)
    log_activity(db, action="audit_finalized", actor=current_user, target_type="audit", target_id=audit.id,
                 detail=f"Finalized '{audit.title}'", request=request)
    return audit
