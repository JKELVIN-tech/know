import os, json, copy
os.environ.update(DATABASE_URL="sqlite:///:memory:", SECRET_KEY="x"*40)
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker, configure_mappers
from fastapi.testclient import TestClient
from app.core import database
eng = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
database.engine = eng; database.SessionLocal = sessionmaker(bind=eng)
from app.main import app
from app.core.limiter import limiter
limiter.enabled = False
from app.core.database import Base, get_db
from app.core.deps import get_current_user
from app.models import *
from app.models.transaction import *
from app.utils.ledger import post_transaction
from app.utils.vault import build_checks
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng); db = S()
def inst(n): i = Institution(name=n, type="shop"); db.add(i); db.flush(); return i
A, B = inst("A"), inst("B")
def user(n, i, role, st=None): u = User(username=n, full_name=n, hashed_password="x", role=role, staff_type=st, institution_id=i.id); db.add(u); db.flush(); return u
oa = user("oa", A, UserRole.INSTITUTION_ADMIN); b1 = user("b1", A, UserRole.STAFF, StaffType.GENERAL); b2 = user("b2", A, UserRole.STAFF, StaffType.GENERAL); ob = user("ob", B, UserRole.INSTITUTION_ADMIN)
cat = ProductCategory(name="Food"); db.add(cat); db.flush()
item = StockItem(institution_id=A.id, branch_id=b1.id, category_id=cat.id, name="Sugar", unit_price=150, quantity=70); db.add(item); db.flush()
t1 = Transaction(institution_id=A.id, type=TransactionType.INCOME, method=TransactionMethod.MPESA, category_type=TransactionCategoryType.STOCK, category="Sugar", amount=300, stock_item_id=item.id, quantity=2, mpesa_code="QAAAAAAAAA", recorded_by_id=b1.id); db.add(t1); db.flush(); post_transaction(db, t1)
t2 = Transaction(institution_id=A.id, type=TransactionType.EXPENSE, method=TransactionMethod.CASH, category_type=TransactionCategoryType.OTHER, category="rent", amount=1000, recorded_by_id=b2.id, image_path="http://x/y.png"); db.add(t2); db.flush(); post_transaction(db, t2)
db.add(StockTransfer(institution_id=A.id, from_branch_id=b1.id, to_branch_id=b2.id, source_item_id=item.id, item_name="Sugar", quantity=5, status="pending", created_by_id=b1.id))
db.add(Audit(institution_id=A.id, title="Q1", period_start=t1.transaction_date, period_end=t1.transaction_date, submitted_by_id=oa.id)); db.commit()
cur = {"id": oa.id}
app.dependency_overrides[get_db] = lambda: (yield S())
app.dependency_overrides[get_current_user] = lambda: S().get(User, cur["id"])
c = TestClient(app, headers={"authorization": "Bearer t"})
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
vault = c.get("/api/vault/export").json(); raw = json.dumps(vault)
cur["id"] = ob.id
bad = copy.deepcopy(vault); bad["tables"]["transactions"][0]["amount"] = "999.00"
ok("tampered amount rejected", c.post("/api/vault/restore", content=json.dumps(bad)).status_code == 422)
ok("nothing saved after failure", S().query(Transaction).filter(Transaction.institution_id == B.id).count() == 0 and S().query(User).filter(User.institution_id == B.id).count() == 1)
r = c.post("/api/vault/restore", content=raw); print(r.status_code, r.json()); ok("restored", r.status_code == 200)
again = c.get("/api/vault/export").json()
ok("same counts, debits and stock", again["checks"]["counts"] == vault["checks"]["counts"] and again["checks"]["debit_total"] == vault["checks"]["debit_total"] and again["checks"]["stock_quantity_total"] == vault["checks"]["stock_quantity_total"])
d2 = S()
ok("stock branch remapped", d2.query(StockItem).filter(StockItem.institution_id == B.id).first().branch.institution_id == B.id)
ok("transaction stock link remapped", d2.query(Transaction).filter(Transaction.institution_id == B.id, Transaction.stock_item_id.isnot(None)).first().stock_item.institution_id == B.id)
ok("both journal entries linked", d2.query(JournalEntry).filter(JournalEntry.institution_id == B.id, JournalEntry.transaction_id.isnot(None)).count() == 2)
ok("transfer remapped", d2.query(StockTransfer).filter(StockTransfer.institution_id == B.id).first().from_branch_id in [u.id for u in d2.query(User).filter(User.institution_id == B.id)])
ok("restored staff can't log in until reset (random password)", all(u.hashed_password != "x" for u in d2.query(User).filter(User.institution_id == B.id, User.role == UserRole.STAFF)))
ok("second restore blocked", c.post("/api/vault/restore", content=raw).status_code == 409)
ok("original untouched", d2.query(Transaction).filter(Transaction.institution_id == A.id).count() == 2)
