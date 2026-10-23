import os
os.environ.update(DATABASE_URL="sqlite:///:memory:", SECRET_KEY="x"*40)
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker, configure_mappers
from fastapi.testclient import TestClient
from app.core import database
eng = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
database.engine = eng
database.SessionLocal = sessionmaker(bind=eng)
from app.main import app
from app.core.database import Base, get_db
from app.core.deps import get_current_user, require_institution_scope
from app.models import *
configure_mappers()
Base.metadata.create_all(eng)
S = sessionmaker(bind=eng)
db = S()
inst = Institution(name="T", type="school"); db.add(inst); db.flush()
u = User(username="a", full_name="A", hashed_password="x", role=UserRole.STAFF, institution_id=inst.id, staff_type=StaffType.GENERAL)
u2 = User(username="b", full_name="B", hashed_password="x", role=UserRole.STAFF, institution_id=inst.id, staff_type=StaffType.GENERAL)
db.add_all([u, u2]); db.commit()
cur = {"id": u.id}
def me():
    return S().get(User, cur["id"])
app.dependency_overrides[get_db] = lambda: (yield S())
app.dependency_overrides[get_current_user] = me
app.dependency_overrides[require_institution_scope] = me
c = TestClient(app, headers={"authorization": "Bearer t"})
M1 = "QGH12ABCDE Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/5/26 at 2:30 PM. New M-PESA balance is Ksh1,200.00. Transaction cost, Ksh7.00."
M2 = "QGH12ABCDF Confirmed. Ksh4,000.00 sent to KCB for account 1234567 on 9/9/25 at 9:05 AM. New M-PESA balance is Ksh800.50."
r = c.post("/api/imports", json={"messages": [M1, M2, "hello"]}); print(r.status_code, r.json()["skipped"], [(i["status"], i["reason"]) for i in r.json()["items"]])
r = c.post("/api/imports", json={"messages": [M1]}); print("repeat", len(r.json()["items"]), len(c.get("/api/imports").json()))
i1 = c.get("/api/imports").json()[-1]["id"]
r = c.post(f"/api/imports/{i1}/approve", json={"category_type": "OTHER", "category": "transport"}); print("approve", r.status_code, r.json().get("status"))
r = c.post(f"/api/imports/{i1}/approve", json={"category": "x"}); print("again", r.status_code)
r = c.post("/api/imports", json={"messages": [M1]}); print("after post", r.json()["items"][0]["status"])
cur["id"] = u2.id
print("other staff sees", len(c.get("/api/imports").json()))
r = c.post("/api/imports", json={"messages": [M1]}); print("colleague paste", r.json()["items"][0]["status"], r.json()["items"][0]["transaction_id"])
from app.models.transaction import Transaction
print("txns", S().query(Transaction).count())
