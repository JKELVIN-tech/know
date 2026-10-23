import os, json
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
def inst(n): i = Institution(name=n, type="shop"); db.add(i); db.flush(); return i
A, B = inst("A"), inst("B")
def user(n, i, role, st=None): u = User(username=n, full_name=n.upper(), hashed_password=hash_password("Zebra!Quartz#2024"), role=role, staff_type=st, institution_id=i.id if i else None); db.add(u); db.flush(); return u
oa = user("oa", A, UserRole.INSTITUTION_ADMIN); a1 = user("a1", A, UserRole.STAFF, StaffType.GENERAL); a2 = user("a2", A, UserRole.STAFF, StaffType.GENERAL); ob = user("ob", B, UserRole.INSTITUTION_ADMIN); sx = user("sx", None, UserRole.SUPER_ADMIN)
cat = ProductCategory(name="Food"); db.add(cat); db.commit()
c = TestClient(app)
tok = {n: c.post("/api/auth/login", data={"username": n, "password": "Zebra!Quartz#2024"}).json()["access_token"] for n in ("oa", "a1", "a2", "ob", "sx")}
H = lambda n: {"authorization": "Bearer " + tok[n]}
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
sugar = c.post("/api/stock", json={"name": "Sugar", "category_id": cat.id, "unit_price": "100", "quantity": "10"}, headers=H("a1")).json()["id"]
def txn(who, t, item, qty, amount): return c.post("/api/transactions", data={"type": t, "method": "cash", "category_type": "STOCK", "amount": amount, "stock_item_id": item, "quantity": qty}, headers=H(who))
def day(**kw): return c.get("/api/profit/daily", params=kw, headers=H("oa")).json()
ok("sale 1 (no cost known)", txn("a1", "income", sugar, "2", "200").status_code == 201)
d = day(); it = d["branches"][0]["items"][0]
ok("unknown cost is flagged, not counted as profit", it["unknown_units"] == "2" and it["profit"] == "0.00" and it["margin"] is None)
r = c.patch(f"/api/profit/costs/{sugar}", json={"avg_cost": "60"}, headers=H("oa")); ok("owner sets cost", r.status_code == 200)
txn("a1", "income", sugar, "3", "300")
it = day()["branches"][0]["items"][0]
ok("sale 2 profit 120 on costed sales", it["profit"] == "120.00" and it["cost"] == "180.00" and it["revenue"] == "500.00" and it["unknown_units"] == "2")
ok("restock 10 for 800 (5 on hand at 60)", txn("a1", "expense", sugar, "10", "800").status_code == 201)
costs = {x["item_id"]: x for x in c.get("/api/profit/costs", headers=H("oa")).json()}
ok("weighted average cost is 73.33", costs[sugar]["avg_cost"] == "73.33")
txn("a1", "income", sugar, "3", "300")
b1 = [b for b in day()["branches"] if b["branch_name"] == "A1"][0]
ok("sale 3 cost 220.00, branch profit 200.00", b1["totals"]["cost"] == "400.00" and b1["totals"]["profit"] == "200.00")
t = c.post("/api/exchange/transfers", json={"item_id": sugar, "quantity": "4", "to_branch_id": a2.id}, headers=H("a1")).json()
ok("receive transfer", c.post(f"/api/exchange/transfers/{t['id']}/receive", headers=H("a2")).status_code == 200)
a2item = [i for i in c.get("/api/stock", headers=H("a2")).json() if i["name"] == "Sugar"][0]
ok("cost travelled with the stock", {x["item_id"]: x for x in c.get("/api/profit/costs", headers=H("oa")).json()}[a2item["id"]]["avg_cost"] == "73.33")
txn("a2", "income", a2item["id"], "2", "200")
d = day(); b2 = [b for b in d["branches"] if b["branch_name"] == "A2"][0]
ok("branch A2 profit 53.33", b2["totals"]["profit"] == "53.33" and b2["totals"]["cost"] == "146.67")
ok("overall profit 253.33 at 31.7% margin", d["totals"]["profit"] == "253.33" and d["totals"]["margin"] == "31.7")
ok("branches ordered by profit", [b["branch_name"] for b in d["branches"]] == ["A1", "A2"])
ok("branch filter", [b["branch_name"] for b in day(branch_id=a2.id)["branches"]] == ["A2"])
ok("other day is empty", day(day="2020-01-01")["branches"] == [])
ok("restock is not counted as a sale", d["totals"]["units"] == "10")
# history never rewritten
c.patch(f"/api/profit/costs/{sugar}", json={"avg_cost": "1"}, headers=H("oa"))
ok("changing cost later does not change past profit", [b for b in day()["branches"] if b["branch_name"] == "A1"][0]["totals"]["profit"] == "200.00")
# safety
ok("staff cannot see profit", c.get("/api/profit/daily", headers=H("a1")).status_code == 403 and c.get("/api/profit/costs", headers=H("a1")).status_code == 403)
ok("super admin cannot see profit", c.get("/api/profit/daily", headers=H("sx")).status_code == 403)
ok("other institution's owner sees nothing", c.get("/api/profit/daily", headers=H("ob")).json()["branches"] == [])
ok("other owner cannot set cost", c.patch(f"/api/profit/costs/{sugar}", json={"avg_cost": "5"}, headers=H("ob")).status_code == 404)
ok("negative cost rejected", c.patch(f"/api/profit/costs/{sugar}", json={"avg_cost": "-1"}, headers=H("oa")).status_code == 422)
ok("staff stock list does not expose cost", "avg_cost" not in json.dumps(c.get("/api/stock", headers=H("a1")).json()))
ok("bad date rejected", c.get("/api/profit/daily", params={"day": "nope"}, headers=H("oa")).status_code == 422)
