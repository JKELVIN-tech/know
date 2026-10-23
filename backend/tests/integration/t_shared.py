import os
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
from app.core.security import hash_password
from app.models import *
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng); db = S()
app.dependency_overrides[get_db] = lambda: (yield S())
i = Institution(name="A", type="shop"); j = Institution(name="B", type="shop"); db.add_all([i, j]); db.flush()
def user(n, inst, role, st=None): u = User(username=n, full_name=n.upper(), hashed_password=hash_password("Zebra!Quartz#2024"), role=role, staff_type=st, institution_id=inst.id); db.add(u); db.flush(); return u
oa = user("oa", i, UserRole.INSTITUTION_ADMIN); a1 = user("a1", i, UserRole.STAFF, StaffType.GENERAL); a2 = user("a2", i, UserRole.STAFF, StaffType.GENERAL); ob = user("ob", j, UserRole.INSTITUTION_ADMIN); b1 = user("b1", j, UserRole.STAFF, StaffType.GENERAL)
cat = ProductCategory(name="Food"); db.add(cat); db.flush()
pool = StockItem(institution_id=i.id, branch_id=None, category_id=cat.id, name="Sugar", unit_price=100, quantity=20); other = StockItem(institution_id=j.id, branch_id=b1.id, category_id=cat.id, name="Rice", unit_price=1, quantity=9); db.add_all([pool, other]); db.commit()
c = TestClient(app)
tok = {n: c.post("/api/auth/login", data={"username": n, "password": "Zebra!Quartz#2024"}).json()["access_token"] for n in ("oa", "a1", "a2", "ob")}
H = lambda n: {"authorization": "Bearer " + tok[n]}
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
qty = lambda item_id, who="oa": float(c.get(f"/api/stock/{item_id}", headers=H(who)).json()["quantity"])
r = c.post("/api/exchange/transfers", json={"item_id": pool.id, "quantity": "5", "to_branch_id": a2.id}, headers=H("a1")); ok("staff sends from shared stock", r.status_code == 201 and r.json()["from_branch_name"] == "A1" and r.json()["to_branch_name"] == "A2")
ok("shared stock went down", qty(pool.id) == 15.0)
t = r.json()["id"]
ok("recipient confirms", c.post(f"/api/exchange/transfers/{t}/receive", headers=H("a2")).status_code == 200)
got = [x for x in c.get("/api/stock", headers=H("a2")).json() if x["branch_id"] == a2.id]
ok("recipient now owns 5 Sugar", len(got) == 1 and float(got[0]["quantity"]) == 5.0)
r = c.post("/api/exchange/transfers", json={"item_id": pool.id, "quantity": "4", "to_branch_id": a2.id}, headers=H("a1")); t2 = r.json()["id"]
ok("reject returns it to the shared stock", c.post(f"/api/exchange/transfers/{t2}/reject", headers=H("a2")).status_code == 200 and qty(pool.id) == 15.0)
ok("overdraw of shared stock refused", c.post("/api/exchange/transfers", json={"item_id": pool.id, "quantity": "999", "to_branch_id": a2.id}, headers=H("a1")).status_code == 409)
ok("cannot send another institution's stock", c.post("/api/exchange/transfers", json={"item_id": other.id, "quantity": "1", "to_branch_id": a2.id}, headers=H("a1")).status_code == 404)
r = c.post("/api/exchange/transfers", json={"item_id": pool.id, "quantity": "3", "to_branch_id": a1.id}, headers=H("oa")); ok("owner sends shared stock to a branch", r.status_code == 201 and qty(pool.id) == 12.0)
ok("owner must name a branch for branch stock", c.post("/api/exchange/transfers", json={"item_id": got[0]["id"], "quantity": "1", "to_branch_id": a1.id}, headers=H("oa")).status_code == 400)
ok("owner sends from a named branch", c.post("/api/exchange/transfers", json={"item_id": got[0]["id"], "quantity": "1", "to_branch_id": a1.id, "from_branch_id": a2.id}, headers=H("oa")).status_code == 201)
ok("a1 cannot send a2's own stock", c.post("/api/exchange/transfers", json={"item_id": got[0]["id"], "quantity": "1", "to_branch_id": a2.id}, headers=H("a1")).status_code in (400, 404))
