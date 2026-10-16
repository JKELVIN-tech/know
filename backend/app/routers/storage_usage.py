from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import require_super_admin
from app.models.institution import Institution
from app.models.user import User

router = APIRouter(prefix="/api/admin", tags=["admin"])

# Free-plan limits, shown as the 100% mark
DATABASE_LIMIT_BYTES = 500 * 1024 * 1024
STORAGE_LIMIT_BYTES = 1024 * 1024 * 1024

PHOTO_SQL = text("""
    SELECT t.institution_id AS institution_id, COUNT(*) AS files, COALESCE(SUM((o.metadata->>'size')::bigint), 0) AS bytes
    FROM (
        SELECT institution_id, image_path FROM transactions WHERE image_path IS NOT NULL
        UNION ALL
        SELECT institution_id, image_path FROM source_documents WHERE image_path IS NOT NULL
    ) t
    JOIN storage.objects o ON o.name = regexp_replace(t.image_path, '^.*/object/public/[^/]+/', '')
    GROUP BY t.institution_id
""")


@router.get("/storage-usage")
def storage_usage(db: Session = Depends(get_db), _: User = Depends(require_super_admin)):
    """How full the free database and photo storage are, and which institutions use the photo space."""
    result = {"database_bytes": None, "database_limit_bytes": DATABASE_LIMIT_BYTES, "storage_bytes": None, "storage_limit_bytes": STORAGE_LIMIT_BYTES, "institutions": []}
    try:
        result["database_bytes"] = db.execute(text("SELECT pg_database_size(current_database())")).scalar()
        result["storage_bytes"] = int(db.execute(text("SELECT COALESCE(SUM((metadata->>'size')::bigint), 0) FROM storage.objects")).scalar())
        per = {r.institution_id: (r.files, int(r.bytes)) for r in db.execute(PHOTO_SQL)}
    except Exception:
        db.rollback()
        return result
    names = {i.id: i.name for i in db.query(Institution).all()}
    result["institutions"] = sorted(
        [{"id": i, "name": names.get(i, f"#{i}"), "photo_files": f, "photo_bytes": b} for i, (f, b) in per.items()],
        key=lambda r: r["photo_bytes"], reverse=True,
    )
    return result
