from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, File, status
from sqlalchemy.orm import Session

from app.core.activity_log import log_activity
from app.core.database import get_db
from app.core.deps import require_super_admin, require_admin_scope, get_current_user
from app.core.limiter import limiter
from app.models.institution import Institution
from app.models.user import User, UserRole
from app.schemas.institution import InstitutionCreate, InstitutionOut
from app.utils.uploads import save_school_logo, delete_storage_object, read_upload

router = APIRouter(prefix="/api/institutions", tags=["institutions"])


@router.post("", response_model=InstitutionOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("10/minute")
def create_institution(payload: InstitutionCreate, request: Request, db: Session = Depends(get_db), admin: User = Depends(require_super_admin)):
    if db.query(Institution).filter(Institution.name == payload.name).first():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="An institution with this name already exists")
    institution = Institution(name=payload.name, type=payload.type, address=payload.address, region=payload.region)
    db.add(institution)
    db.commit()
    db.refresh(institution)
    log_activity(db, action="institution_created", actor=admin, target_type="institution", target_id=institution.id,
                 detail=f"Created {institution.type} '{institution.name}'", request=request)
    return institution


@router.get("", response_model=list[InstitutionOut])
def list_institutions(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    query = db.query(Institution)
    # Only the super admin sees every institution; everyone else sees their own
    if current_user.role != UserRole.SUPER_ADMIN:
        query = query.filter(Institution.id == current_user.institution_id)
    return query.order_by(Institution.name).all()


@router.get("/{institution_id}", response_model=InstitutionOut)
def get_institution(institution_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    if current_user.role != UserRole.SUPER_ADMIN and current_user.institution_id != institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to view this institution")
    institution = db.query(Institution).filter(Institution.id == institution_id).first()
    if not institution:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")
    return institution


@router.post("/{institution_id}/logo", response_model=InstitutionOut)
@limiter.limit("10/minute")
def upload_institution_logo(
    institution_id: int,
    request: Request,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    admin: User = Depends(require_admin_scope),
):
    """
    Sets the icon shown across the portal (and on generated reports) for
    this institution. A super_admin can change any institution's icon; an
    institution_admin can only change their own.
    """
    if admin.role == UserRole.INSTITUTION_ADMIN and admin.institution_id != institution_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to change this institution's icon")

    institution = db.query(Institution).filter(Institution.id == institution_id).first()
    if not institution:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")

    contents = read_upload(file)
    old_logo_path = institution.logo_path
    path = save_school_logo(file, contents)
    institution.logo_path = path
    db.commit()
    db.refresh(institution)
    delete_storage_object(old_logo_path)
    log_activity(db, action="institution_logo_updated", actor=admin, target_type="institution", target_id=institution.id,
                 detail=f"Updated icon for '{institution.name}'", request=request)
    return institution
