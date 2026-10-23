import os, io
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
from app.core.database import Base, get_db
from app.core.security import hash_password
from app.models import *
from app.models.transaction import *
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng); db = S()
app.dependency_overrides[get_db] = lambda: (yield S())
def inst(n): i = Institution(name=n, type="shop"); db.add(i); db.flush(); return i
A, B = inst("A"), inst("B")
def user(n, i, role, st=None): u = User(username=n, full_name=n, hashed_password=hash_password("Zebra!Quartz#2024"), role=role, staff_type=st, institution_id=i.id); db.add(u); db.flush(); return u
oa = user("oa", A, UserRole.INSTITUTION_ADMIN); sa = user("sa", A, UserRole.STAFF, StaffType.GENERAL); ob = user("ob", B, UserRole.INSTITUTION_ADMIN); sb = user("sb", B, UserRole.STAFF, StaffType.GENERAL)
cat = ProductCategory(name="Food"); db.add(cat); db.flush()
item = StockItem(institution_id=A.id, branch_id=sa.id, category_id=cat.id, name="Sugar", unit_price=1, quantity=5); db.add(item); db.flush()
txn = Transaction(institution_id=A.id, type=TransactionType.EXPENSE, method=TransactionMethod.CASH, category_type=TransactionCategoryType.OTHER, category="rent", amount=10, recorded_by_id=sa.id); db.add(txn); db.commit()
c = TestClient(app)
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
def login(u, pw="Zebra!Quartz#2024"): return c.post("/api/auth/login", data={"username": u, "password": pw})
limiter.enabled = False
r1 = login("nobody", "x"); r2 = login("oa", "wrong")
ok("same message for unknown user and wrong password", r1.status_code == r2.status_code == 401 and r1.json() == r2.json())
tok = {u: login(u).json()["access_token"] for u in ("oa", "sa", "ob", "sb")}
H = lambda u: {"authorization": "Bearer " + tok[u]}
ok("docs hidden", c.get("/docs").status_code == 404 and c.get("/openapi.json").status_code == 404)
ok("api responses are no-store", c.get("/api/users/me", headers=H("sa")).headers.get("cache-control") == "no-store")
ok("no token -> 401", c.get("/api/stock").status_code == 401 and c.get("/api/transactions").status_code == 401)
ok("garbage token -> 401", c.get("/api/stock", headers={"authorization": "Bearer abc.def.ghi"}).status_code == 401)
ok("B owner cannot read A stock item", c.get(f"/api/stock/{item.id}", headers=H("ob")).status_code in (403, 404))
ok("B staff cannot read A stock item", c.get(f"/api/stock/{item.id}", headers=H("sb")).status_code in (403, 404))
ok("B staff cannot read A transaction", c.get(f"/api/transactions/{txn.id}", headers=H("sb")).status_code in (403, 404))
ok("B owner cannot read A transaction", c.get(f"/api/transactions/{txn.id}", headers=H("ob")).status_code in (403, 404))
ok("B owner cannot manage A user", c.post(f"/api/users/{sa.id}/reset-password", json={"new_password": "Zebra!Quartz#2025"}, headers=H("ob")).status_code == 403)
ok("B owner sees only B users", all(u["institution_id"] == B.id for u in c.get("/api/users", headers=H("ob")).json()))
ok("staff cannot create accounts", c.post("/api/users", json={"username": "z", "full_name": "z", "password": "Zebra!Quartz#2024"}, headers=H("sa")).status_code == 403)
ok("owner cannot create super admin", c.post("/api/users", json={"username": "zeta2", "full_name": "z", "password": "Zebra!Quartz#2024", "role": "super_admin"}, headers=H("oa")).status_code == 403)
r = c.post("/api/users", json={"username": "zeta3", "full_name": "z", "password": "Zebra!Quartz#2024", "role": "staff", "staff_type": "general", "institution_id": B.id}, headers=H("oa"))
ok("owner's institution_id forced to own", r.status_code == 201 and r.json()["institution_id"] == A.id)
au = Audit(institution_id=A.id, title="private", period_start=txn.transaction_date, period_end=txn.transaction_date, submitted_by_id=oa.id); db.add(au); db.commit()
ok("staff cannot download an audit PDF they are not allowed to see", c.get(f"/api/reports/audits/{au.id}.pdf", headers=H("sa")).status_code == 403)
ok("other institution cannot download it either", c.get(f"/api/reports/audits/{au.id}.pdf", headers=H("ob")).status_code == 403)
ok("owner can download it", c.get(f"/api/reports/audits/{au.id}.pdf", headers=H("oa")).status_code == 200)
ok("activity log shows no business details", all(("Sugar" not in (r.detail or "")) for r in db.query(ActivityLog).all()))
ok("staff cannot export vault", c.get("/api/vault/export", headers=H("sa")).status_code == 403)
ok("staff cannot see storage meter", c.get("/api/admin/storage-usage", headers=H("sa")).status_code == 403)
for pw, label in (("short", "too short"), ("password", "common"), ("aaaaaaaaaa", "repeated"), ("x1" * 40, "over 72 bytes")):
    ok(f"weak password rejected: {label}", c.post("/api/users", json={"username": "w" + label[:3], "full_name": "w", "password": pw, "role": "staff", "staff_type": "general"}, headers=H("oa")).status_code == 422)
big = io.BytesIO(b"\x89PNG" + b"0" * (7 * 1024 * 1024))
ok("oversized logo -> 413", c.post(f"/api/institutions/{A.id}/logo", files={"file": ("a.png", big, "image/png")}, headers=H("oa")).status_code == 413)
ok("non-image upload rejected", c.post(f"/api/institutions/{A.id}/logo", files={"file": ("a.png", io.BytesIO(b"<script>alert(1)</script>"), "image/png")}, headers=H("oa")).status_code == 400)
ok("huge declared body -> 413", c.post("/api/stock", content=b"x", headers={**H("sa"), "content-length": str(50 * 1024 * 1024)}).status_code in (413, 400))
ok("logout-all ok", c.post("/api/auth/logout-all", headers=H("sa")).status_code == 204)
ok("old token dead after logout-all", c.get("/api/users/me", headers=H("sa")).status_code == 401)
ok("can log in again", login("sa").status_code == 200)
for _ in range(5): login("sb", "bad")
ok("account locks after repeated failures", login("sb").status_code == 423)
limiter.enabled = True; limiter.reset()
ok("per-IP limit stops rapid guessing", 429 in [login("ob", "bad").status_code for _ in range(12)])
