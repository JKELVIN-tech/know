from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import get_current_user
from app.core.limiter import limiter
from app.models.institution import Institution
from app.models.user import User, UserRole
from app.utils.vault import VaultError, export_institution, institution_is_empty, restore_into

router = APIRouter(prefix="/api/vault", tags=["vault"])

MAX_BODY_BYTES = 25 * 1024 * 1024


def _owner(current_user: User = Depends(get_current_user)) -> User:
    if current_user.role != UserRole.INSTITUTION_ADMIN or current_user.institution_id is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only an owner can use the Vault")
    return current_user


@router.get("/export")
@limiter.limit("6/hour")
def export_vault(request: Request, db: Session = Depends(get_db), owner: User = Depends(_owner)):
    """Returns the institution's data for the owner's device to encrypt and keep; nothing is stored on the server."""
    institution = db.get(Institution, owner.institution_id)
    payload = export_institution(db, institution)
    log_activity(db, action="vault_exported", actor=owner, target_type="institution", target_id=institution.id, detail="Backup created", request=request)
    return payload


@router.post("/restore")
@limiter.limit("10/hour")
async def restore_vault(request: Request, db: Session = Depends(get_db), owner: User = Depends(_owner)):
    """Loads a decrypted backup into this institution, which must be new and empty; it is all-or-nothing."""
    if int(request.headers.get("content-length") or 0) > MAX_BODY_BYTES:
        raise HTTPException(status_code=413, detail="Backup is too large")
    raw = await request.body()
    if len(raw) > MAX_BODY_BYTES:
        raise HTTPException(status_code=413, detail="Backup is too large")
    try:
        import json
        payload = json.loads(raw)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This is not a KNOW backup file")
    if not institution_is_empty(db, owner.institution_id, owner.id):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Restore only works into a new, empty institution")
    try:
        summary = restore_into(db, owner.institution_id, owner, payload)
        db.commit()
    except VaultError as err:
        db.rollback()
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(err))
    except Exception:
        db.rollback()
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Restore failed and nothing was changed")
    log_activity(db, action="vault_restored", actor=owner, target_type="institution", target_id=owner.institution_id, detail="Backup restored", request=request)
    return summary
