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
from app.models import *
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng)
app.dependency_overrides[get_db] = lambda: (yield S())
c = TestClient(app)
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
body = lambda u, t="staff", **k: {"username": u, "full_name": "Amina " + u, "password": "Strong#Pass99", "account_type": t, **k}
r = c.post("/api/auth/signup", json=body("amina1"))
ok("staff signup 201", r.status_code == 201)
ok("staff gets general type and own workspace", r.json()["role"] == "staff" and r.json()["staff_type"] == "general" and r.json()["institution_id"])
h = {"authorization": "Bearer " + r.json()["access_token"]}
ok("new account can use the API", c.get("/api/users/me", headers=h).status_code == 200)
ok("new account cannot create users", c.post("/api/users", json={"username": "x1x", "full_name": "X", "password": "Strong#Pass99", "role": "staff", "staff_type": "general"}, headers=h).status_code == 403)
r2 = c.post("/api/auth/signup", json=body("juma2", "personal"))
ok("personal signup is teacher type", r2.status_code == 201 and r2.json()["staff_type"] == "teacher")
ok("two signups get different workspaces", r.json()["institution_id"] != r2.json()["institution_id"])
ok("duplicate username (any case) refused", c.post("/api/auth/signup", json=body("AMINA1")).status_code == 409)
ok("weak password refused", c.post("/api/auth/signup", json={**body("weak1"), "password": "short"}).status_code == 422)
ok("bad account type refused", c.post("/api/auth/signup", json=body("bad1", "owner")).status_code == 422)
ok("duplicate email refused", c.post("/api/auth/signup", json=body("mail1", email="a@b.test")).status_code == 201 and c.post("/api/auth/signup", json=body("mail2", email="A@B.test")).status_code == 409)
ok("can log in afterwards", c.post("/api/auth/login", data={"username": "amina1", "password": "Strong#Pass99"}).status_code == 200)
ok("signup redirect works", c.get("/api/auth/signup-redirect", follow_redirects=False).status_code == 302)
from app.core.security import hash_password
_d = S(); _u = User(username="agent1", full_name="Agent", hashed_password=hash_password("Strong#Pass99"), role=UserRole.AGENT); _d.add(_u); _d.flush()
_d.add(Partner(user_id=_u.id, code="ABC234", commission_rate=20)); _d.commit()
rp = c.post("/api/auth/signup", json=body("viapartner", partner_code="abc234"))
ok("valid partner code accepted", rp.status_code == 201)
ok("workspace is linked to the partner", _d.query(Institution).get(rp.json()["institution_id"]).partner_id is not None)
ok("unknown partner code refused", c.post("/api/auth/signup", json=body("badpartner", partner_code="ZZZ999")).status_code == 422)
sb = c.get("/api/billing/status", headers=h).json()
ok("new account is on a 7 day trial", sb["applies"] and sb["state"] == "trial" and sb["days_left"] == 7 and sb["plan"] == "individual" and sb["total"] == 200)
ok("trial ends after the 7 days pass", c.get("/api/billing/status", headers={"authorization": "Bearer " + rp.json()["access_token"]}).json()["days_left"] == 7)
from datetime import datetime, timedelta, timezone
_i = _d.query(Institution).get(rp.json()["institution_id"]); _i.created_at = datetime.now(timezone.utc) - timedelta(days=9); _d.commit()
ok("old account shows trial ended", c.get("/api/billing/status", headers={"authorization": "Bearer " + rp.json()["access_token"]}).json()["state"] == "trial_ended")

ro = c.post("/api/auth/signup", json={**body("owner1", "owner"), "business_name": "Mama Njeri Shop", "business_type": "shop"})
ok("owner signup 201 as institution admin", ro.status_code == 201 and ro.json()["role"] == "institution_admin")
ho = {"authorization": "Bearer " + ro.json()["access_token"]}
so = c.get("/api/billing/status", headers=ho).json()
ok("owner plan is 500 with 5 included", so["plan"] == "owner" and so["total"] == 500 and so["included_accounts"] == 5 and so["accounts_used"] == 0)
ok("owner without business name refused", c.post("/api/auth/signup", json=body("owner2", "owner")).status_code == 422)
ok("duplicate business name refused", c.post("/api/auth/signup", json={**body("owner3", "owner"), "business_name": "mama njeri shop"}).status_code == 409)
for i in range(7):
    r_ = c.post("/api/users", json={"username": f"staffer{i}x", "full_name": f"Staff {i}", "password": "Strong#Pass99", "role": "staff", "staff_type": "general"}, headers=ho)
    assert r_.status_code in (200, 201), r_.text
so = c.get("/api/billing/status", headers=ho).json()
ok("7 individual accounts bill 2 extra at 200", so["accounts_used"] == 7 and so["extra_accounts"] == 2 and so["extra_amount"] == 400 and so["total"] == 900)
rs = c.post("/api/auth/login", data={"username": "staffer0x", "password": "Strong#Pass99"})
ok("owner's staff are not billed themselves", c.get("/api/billing/status", headers={"authorization": "Bearer " + rs.json()["access_token"]}).json()["applies"] is False)
