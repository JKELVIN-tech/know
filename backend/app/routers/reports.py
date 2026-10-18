from datetime import datetime

from app.core.limiter import limiter
from app.routers.audits import _get_audit_visible
from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user
from app.models.audit import Audit
from app.core.staff_scope import resolve_staff_filter
from app.models.institution import Institution
from app.models.transaction import Transaction, TransactionMethod
from app.models.user import User, UserRole
from app.utils.report_pdf import build_audit_report_pdf, build_statement_pdf

router = APIRouter(prefix="/api/reports", tags=["reports"])


@router.get("/audits/{audit_id}.pdf")
@limiter.limit("20/minute")
def generate_audit_report(request: Request, audit_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    audit = _get_audit_visible(audit_id, current_user, db)

    institution = db.query(Institution).filter(Institution.id == audit.institution_id).first()
    # Computed from the audit's period rather than a stored link — see
    # routers/audits.py's /transactions endpoint for the same query.
    audit_query = db.query(Transaction).filter(
        Transaction.institution_id == audit.institution_id,
        Transaction.transaction_date >= audit.period_start,
        Transaction.transaction_date <= audit.period_end,
    )
    if current_user.role == UserRole.STAFF:
        audit_query = audit_query.filter(Transaction.recorded_by_id == current_user.id)
    transactions = audit_query.all()

    pdf_bytes = build_audit_report_pdf(institution, audit, transactions)

    filename = f"audit-report-{audit.id}.pdf"
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="{filename}"'},
    )


_METHOD_LABELS = {
    TransactionMethod.CASH: "Cash",
    TransactionMethod.MPESA: "M-Pesa",
    TransactionMethod.BANK: "Bank",
}


@router.get("/statements.pdf")
@limiter.limit("20/minute")
def generate_statement_report(
    request: Request,
    start: datetime,
    end: datetime,
    method: TransactionMethod | None = None,
    institution_id: int | None = None,
    staff_id: int | None = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    A printable movement statement for one method (or every method
    combined) over a date range — the Audit page's "Statements" feature.
    """
    if current_user.role in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        scoped_institution_id = current_user.institution_id
    else:
        if institution_id is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="institution_id is required")
        scoped_institution_id = institution_id

    institution = db.query(Institution).filter(Institution.id == scoped_institution_id).first()
    if not institution:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Institution not found")

    query = db.query(Transaction).filter(
        Transaction.institution_id == scoped_institution_id,
        Transaction.transaction_date >= start,
        Transaction.transaction_date <= end,
    )
    only_user = resolve_staff_filter(db, current_user, scoped_institution_id, staff_id)
    if only_user is not None:
        query = query.filter(Transaction.recorded_by_id == only_user)
    if method is not None:
        query = query.filter(Transaction.method == method)
    transactions = query.all()

    method_label = _METHOD_LABELS.get(method, "Combined")
    pdf_bytes = build_statement_pdf(institution, method_label, start, end, transactions)

    filename = f"{method_label.lower().replace(' ', '-').replace('-pesa','pesa')}-statement.pdf"
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="{filename}"'},
    )