import hashlib
import json
import secrets
from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import Date, DateTime, Enum as SAEnum, Numeric, func
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.models.accounting import Account, JournalEntry, JournalLine
from app.models.audit import Audit
from app.models.source_document import SourceDocument
from app.models.stock_item import StockItem
from app.models.stock_price_history import StockPriceHistory
from app.models.stock_transfer import StockTransfer
from app.models.transaction import Transaction
from app.models.user import User, UserRole

FORMAT = "know-vault"
VERSION = 1

# Parents before children; each table is exported and restored in this order.
ORDER = [
    ("users", User),
    ("accounts", Account),
    ("stock_items", StockItem),
    ("stock_price_history", StockPriceHistory),
    ("transactions", Transaction),
    ("journal_entries", JournalEntry),
    ("journal_lines", JournalLine),
    ("audits", Audit),
    ("source_documents", SourceDocument),
    ("stock_transfers", StockTransfer),
]
# Tables whose ids get remapped on restore, by the table name a foreign key points at.
REMAPPED = {"users", "accounts", "stock_items", "transactions", "journal_entries"}
# Never exported: secrets, login state and image links (photos are not part of a backup).
DROP = {"hashed_password", "email", "avatar_path", "image_path", "failed_login_attempts", "locked_until", "token_version", "reset_token"}


class VaultError(Exception):
    pass


def _encode(column, value):
    if value is None:
        return None
    if isinstance(column.type, SAEnum):
        return column.type._db_value_for_elem(value)
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    return value


def _decode(column, value):
    if value is None:
        return None
    if isinstance(column.type, Numeric):
        return Decimal(str(value))
    if isinstance(column.type, DateTime):
        return datetime.fromisoformat(value)
    if isinstance(column.type, Date):
        return date.fromisoformat(value)
    return value


def _rows(db: Session, name: str, model, institution_id: int, ids: dict) -> list:
    q = db.query(model)
    if name == "users":
        q = q.filter(model.institution_id == institution_id, model.role != UserRole.INSTITUTION_ADMIN)
    elif name == "stock_price_history":
        q = q.filter(model.stock_item_id.in_(ids["stock_items"]))
    elif name == "journal_lines":
        q = q.filter(model.entry_id.in_(ids["journal_entries"]))
    else:
        q = q.filter(model.institution_id == institution_id)
    return q.order_by(model.id).all()


def build_checks(tables: dict) -> dict:
    lines = tables["journal_lines"]
    fingerprint = hashlib.sha256(json.dumps(tables, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {
        "sha256": fingerprint,
        "counts": {name: len(rows) for name, rows in tables.items()},
        "debit_total": str(sum((Decimal(r["debit"]) for r in lines), Decimal("0"))),
        "credit_total": str(sum((Decimal(r["credit"]) for r in lines), Decimal("0"))),
        "stock_quantity_total": str(sum((Decimal(r["quantity"]) for r in tables["stock_items"]), Decimal("0"))),
    }


def export_institution(db: Session, institution) -> dict:
    tables: dict = {}
    ids: dict = {}
    for name, model in ORDER:
        out = []
        for obj in _rows(db, name, model, institution.id, ids):
            row = {}
            for col in model.__table__.columns:
                if col.name in DROP or col.name == "institution_id":
                    continue
                row[col.name] = _encode(col, getattr(obj, col.name))
            out.append(row)
        tables[name] = out
        ids[name] = [r["id"] for r in out]
    return {"format": FORMAT, "version": VERSION, "created_at": datetime.utcnow().isoformat(), "institution_name": institution.name, "checks": build_checks(tables), "tables": tables}


def institution_is_empty(db: Session, institution_id: int, owner_id: int) -> bool:
    for model in (Transaction, StockItem, Audit, SourceDocument, StockTransfer, JournalEntry):
        if db.query(model).filter(model.institution_id == institution_id).first():
            return False
    return db.query(User).filter(User.institution_id == institution_id, User.id != owner_id, User.role != UserRole.INSTITUTION_ADMIN).first() is None


def restore_into(db: Session, institution_id: int, owner: User, payload: dict) -> dict:
    """Loads a backup into an empty institution with new ids, keeping every balance and status; raises VaultError before anything is saved if it does not add up."""
    if not isinstance(payload, dict) or payload.get("format") != FORMAT or payload.get("version") != VERSION:
        raise VaultError("This is not a KNOW backup file")
    tables = payload.get("tables") or {}
    if any(name not in tables or not isinstance(tables[name], list) for name, _ in ORDER):
        raise VaultError("The backup is incomplete")
    if build_checks(tables) != payload.get("checks"):
        raise VaultError("The backup failed its integrity check, so nothing was restored")
    per_entry: dict = {}
    for line in tables["journal_lines"]:
        per_entry[line["entry_id"]] = per_entry.get(line["entry_id"], Decimal("0")) + Decimal(line["debit"]) - Decimal(line["credit"])
    if any(diff != 0 for diff in per_entry.values()):
        raise VaultError("The backup's books do not balance, so nothing was restored")

    db.query(Account).filter(Account.institution_id == institution_id).delete()
    maps: dict = {name: {} for name in REMAPPED}
    admin_ids = {u.id for u in db.query(User).filter(User.institution_id == institution_id, User.role == UserRole.INSTITUTION_ADMIN)}
    taken = {u for (u,) in db.query(User.username).all()}

    def fk_target(col):
        fks = list(col.foreign_keys)
        return fks[0].column.table.name if fks else None

    for name, model in ORDER:
        source = tables[name]
        values = []
        for row in source:
            new = {}
            for col in model.__table__.columns:
                if col.name == "id" or col.name in DROP:
                    continue
                if col.name == "institution_id":
                    new[col.name] = institution_id
                    continue
                value = _decode(col, row.get(col.name))
                target = fk_target(col)
                if target in REMAPPED and value is not None:
                    # Owner accounts are not exported, so anything pointing at one points at the restoring owner
                    value = maps[target].get(value, owner.id if target == "users" else None)
                new[col.name] = value
            if name == "users":
                username = row["username"]
                while username in taken:
                    username = f"{row['username']}-r{secrets.randbelow(10000)}"
                taken.add(username)
                new["username"] = username
                new["hashed_password"] = hash_password(secrets.token_urlsafe(32))
                new["created_by_id"] = None
            values.append(new)
        if not values:
            continue
        result = db.execute(model.__table__.insert().returning(model.__table__.c.id), values)
        new_ids = [r[0] for r in result]
        if len(new_ids) != len(source):
            raise VaultError(f"Could not restore {name}")
        if name in REMAPPED:
            maps[name] = {row["id"]: nid for row, nid in zip(source, new_ids)}
    for row in tables["users"]:
        if row.get("created_by_id") is not None:
            db.query(User).filter(User.id == maps["users"][row["id"]]).update({"created_by_id": maps["users"].get(row["created_by_id"], owner.id)})
    db.flush()
    checks = payload["checks"]
    debit = db.query(func.coalesce(func.sum(JournalLine.debit), 0)).join(JournalEntry, JournalEntry.id == JournalLine.entry_id).filter(JournalEntry.institution_id == institution_id).scalar()
    credit = db.query(func.coalesce(func.sum(JournalLine.credit), 0)).join(JournalEntry, JournalEntry.id == JournalLine.entry_id).filter(JournalEntry.institution_id == institution_id).scalar()
    stock = db.query(func.coalesce(func.sum(StockItem.quantity), 0)).filter(StockItem.institution_id == institution_id).scalar()
    txns = db.query(func.count(Transaction.id)).filter(Transaction.institution_id == institution_id).scalar()
    if Decimal(str(debit)) != Decimal(checks["debit_total"]) or Decimal(str(credit)) != Decimal(checks["credit_total"]) or Decimal(str(stock)) != Decimal(checks["stock_quantity_total"]) or txns != checks["counts"]["transactions"]:
        raise VaultError("The restored totals did not match the backup, so nothing was restored")
    return {"restored": checks["counts"], "staff_accounts": len(maps["users"])}
