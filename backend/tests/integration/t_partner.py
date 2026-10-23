import os, re
os.environ.update(DATABASE_URL="sqlite:///:memory:", SECRET_KEY="x"*40)
from datetime import date
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
from app.utils.partner import current_period, shift_period
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng); db = S()
app.dependency_overrides[get_db] = lambda: (yield S())
PW = "Zebra!Quartz#2024"
sa = User(username="boss", full_name="Boss", hashed_password=hash_password(PW), role=UserRole.SUPER_ADMIN); db.add(sa); db.commit()
c = TestClient(app)
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
tok = lambda u, p=PW: {"authorization": "Bearer " + c.post("/api/auth/login", data={"username": u, "password": p}).json()["access_token"]}
HS = tok("boss")
def agent(un, rate="20", email=None, pw=PW):
    return c.post("/api/admin/partners", json={"full_name": un.title(), "username": un, "email": email or f"{un}@mail.test", "password": pw, "commission_rate": rate}, headers=HS)

ok("weak password refused", agent("weakling", pw="password1").status_code == 422)
ok("email is required", c.post("/api/admin/partners", json={"full_name": "No Mail", "username": "nomail", "email": "", "password": PW}, headers=HS).status_code == 422)
r = agent("amina"); ok("agent created with a code like AG-XXXXXX", r.status_code == 201 and re.fullmatch(r"AG-[A-Z2-9]{6}", r.json()["code"]) is not None)
amina = r.json(); code = amina["code"]
ok("code never contains confusing characters", not re.search(r"[01OIL]", code[3:]))
ok("duplicate username refused", agent("amina", email="x@mail.test").status_code == 409)
ok("duplicate email refused", agent("other_amina", email="amina@mail.test").status_code == 409)
bob = agent("bob", rate="25").json()

# registering businesses
mk = lambda name, **extra: c.post("/api/institutions", json={"name": name, "type": "shop", **extra}, headers=HS)
ok("unknown partner code refused", mk("Ghost Shop", partner_code="AG-ZZZZZZ").status_code == 422)
ok("code works in any case or format", mk("Alpha Mart", partner_code=code.lower().replace("-", " ")).status_code == 201)
ok("business without a code is fine", mk("Solo Shop").status_code == 201)
mk("Beta Duka", partner_code=code, monthly_fee="2000"); mk("Gamma Stores", partner_code=bob["code"], monthly_fee="3000")
inst = {i["name"]: i["id"] for i in c.get("/api/institutions", headers=HS).json()}
ok("agent code can be set later", c.patch(f"/api/admin/institutions/{inst['Solo Shop']}/billing", json={"partner_code": bob["code"]}, headers=HS).status_code == 200)
ok("and removed", c.patch(f"/api/admin/institutions/{inst['Solo Shop']}/billing", json={"partner_code": ""}, headers=HS).status_code == 200)
ok("owner cannot create an agent", c.post("/api/users", json={"username": "sneaky", "full_name": "S", "password": PW, "role": "agent"}, headers=HS).status_code == 400)

# fees and commission
cp = current_period()
pay = lambda name, period, amount, **kw: c.post(f"/api/admin/institutions/{inst[name]}/payments", json={"period": period, "amount": amount, **kw}, headers=HS)
r = pay("Alpha Mart", cp, "5000"); ok("20% of 5000 is 1000", r.status_code == 201 and r.json()["commission"] == "1000.00")
c.patch(f"/api/admin/partners/{amina['id']}", json={"commission_rate": "30"}, headers=HS)
r = pay("Alpha Mart", shift_period(cp, -1), "5000"); ok("new rate applies to new payments (1500)", r.json()["commission"] == "1500.00")
ls = c.get(f"/api/admin/institutions/{inst['Alpha Mart']}/payments", headers=HS).json()
ok("earlier commission keeps its old rate", sorted(p["commission"] for p in ls) == ["1000.00", "1500.00"])
bad = pay("Beta Duka", cp, "2000").json()["id"]
ok("payment with no agent earns nothing", pay("Solo Shop", cp, "1000").json()["commission"] == "0.00")
ok("staff-style bad period refused", pay("Alpha Mart", "2026-13", "100").status_code == 422)
ok("void a mistaken payment", c.post(f"/api/admin/payments/{bad}/void", headers=HS).status_code == 200 and c.post(f"/api/admin/payments/{bad}/void", headers=HS).status_code == 404)
pay("Beta Duka", cp, "2000")
pay("Gamma Stores", cp, "3000")

# the agent's dashboard
A = tok("amina")
d = c.get("/api/partner/dashboard", headers=A).json()
ok("dashboard totals", d["totals"]["businesses"] == 2 and d["totals"]["paying_this_month"] == 2 and d["totals"]["fees_collected_month"] == "7000.00" and d["totals"]["earned_month"] == "1600.00")
print("   totals:", d["totals"])
ok("earned total = 1000 + 1500 + 600 (rate changed to 30% before the third payment)", d["totals"]["earned_total"] == "3100.00")
names = sorted(b["name"] for b in d["businesses"]); ok("sees only their own businesses", names == ["Alpha Mart", "Beta Duka"])
ok("no ids or private business data in the list", all(set(b) <= {"name", "type", "region", "owner_name", "registered_at", "monthly_fee", "status", "last_paid_period", "earned_month", "earned_total"} for b in d["businesses"]))
ok("status active for paying business", {b["name"]: b["status"] for b in d["businesses"]}["Alpha Mart"] == "active")
ok("new business with no payment is on trial", {b["name"]: b["status"] for b in c.get("/api/partner/dashboard", headers=tok("bob")).json()["businesses"]}["Gamma Stores"] == "active")
ok("another agent sees theirs only", [b["name"] for b in c.get("/api/partner/dashboard", headers=tok("bob")).json()["businesses"]] == ["Gamma Stores"])
ok("six-month series", len(d["series"]) == 6 and d["series"][-1]["earned"] == "1600.00" and d["series"][-2]["earned"] == "1500.00")
ok("a past month can be viewed", c.get("/api/partner/dashboard", params={"month": shift_period(cp, -1)}, headers=A).json()["totals"]["earned_month"] == "1500.00")
# payouts
pid = amina["id"]
ok("payout above the balance refused", c.post(f"/api/admin/partners/{pid}/payouts", json={"amount": "99999"}, headers=HS).status_code == 409)
ok("payout recorded", c.post(f"/api/admin/partners/{pid}/payouts", json={"amount": "1200", "reference": "MPESA123"}, headers=HS).status_code == 201)
t = c.get("/api/partner/dashboard", headers=A).json()["totals"]; ok("balance falls by the payout", t["paid_out"] == "1200.00" and t["balance_due"] == "1900.00")
po = c.get(f"/api/admin/partners/{pid}/payouts", headers=HS).json()[0]["id"]
ok("void the payout restores the balance", c.post(f"/api/admin/payouts/{po}/void", headers=HS).status_code == 200 and c.get("/api/partner/dashboard", headers=A).json()["totals"]["balance_due"] == "3100.00")
# the agent can reach nothing else
allowed = ("/api/partner", "/api/auth", "/api/users/me")
leaks = []
for route in app.routes:
    path = getattr(route, "path", ""); methods = getattr(route, "methods", None)
    if not path.startswith("/api/") or not methods or path.startswith(allowed) or path in ("/api/health",): continue
    for m in methods & {"GET", "POST", "PATCH", "PUT", "DELETE"}:
        res = c.request(m, re.sub(r"\{[^}]+\}", "1", path), headers=A, json={} if m != "GET" else None)
        if res.status_code < 400: leaks.append((m, path, res.status_code))
print("   routes probed as agent; leaks:", leaks[:5])
ok("agent is refused on every other route", leaks == [])
ok("agent cannot list institutions or stock", c.get("/api/institutions", headers=A).status_code == 403 and c.get("/api/stock", headers=A).status_code == 403)
ok("agent cannot see partner admin", c.get("/api/admin/partners", headers=A).status_code == 403)
ok("agent can read own profile", c.get("/api/users/me", headers=A).status_code == 200)
ok("owners/staff cannot open the agent dashboard", c.get("/api/partner/dashboard", headers=HS).status_code == 403)
ok("deactivating an agent signs them out", c.patch(f"/api/admin/partners/{pid}", json={"is_active": False}, headers=HS).status_code == 200 and c.get("/api/partner/dashboard", headers=A).status_code == 401)
ok("deactivated agent's code stops working", mk("Late Shop", partner_code=code).status_code == 422)
r = c.post(f"/api/admin/partners/{bob['id']}/new-code", headers=HS); ok("code can be replaced", r.status_code == 200 and r.json()["code"] != bob["code"])
ok("activity log holds no money amounts", all("1000" not in (x.detail or "") and "5000" not in (x.detail or "") for x in db.query(ActivityLog).all()))
