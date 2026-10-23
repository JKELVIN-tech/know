import os, re, json
os.environ.update(DATABASE_URL="sqlite:///:memory:", SECRET_KEY="x"*40)
from datetime import datetime, timedelta, timezone
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker, configure_mappers
from fastapi.testclient import TestClient
from app.core import database
eng = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
database.engine = eng; database.SessionLocal = sessionmaker(bind=eng)
from app.main import app
from app.core.config import settings
from app.core.limiter import limiter
limiter.enabled = False
from app.core.database import Base, get_db
from app.core.security import hash_password
from app.models import *
from app.utils import password_reset as pr
configure_mappers(); Base.metadata.create_all(eng)
S = sessionmaker(bind=eng); db = S()
app.dependency_overrides[get_db] = lambda: (yield S())
sent = []
pr.send_email = lambda to, subject, text, html=None: (sent.append({"to": to, "subject": subject, "text": text}) or True)
settings.EMAIL_PROVIDER, settings.EMAIL_API_KEY, settings.EMAIL_FROM = "resend", "k", "KNOW <no-reply@example.com>"
settings.FRONTEND_URL = "https://know.example.com"
rr = None
def inst(n): i = Institution(name=n, type="shop"); db.add(i); db.flush(); return i
A, B = inst("A"), inst("B")
def user(n, i, role, st=None, email=None): u = User(username=n, full_name=n.upper(), hashed_password=hash_password("OldPass#12345"), role=role, staff_type=st, institution_id=i.id if i else None, email=email); db.add(u); db.flush(); return u
oa = user("owner_a", A, UserRole.INSTITUTION_ADMIN, email="owner@a.test"); sa = user("staff_a", A, UserRole.STAFF, StaffType.GENERAL, email="staff@a.test"); noemail = user("noemail_a", A, UserRole.STAFF, StaffType.GENERAL)
ob = user("owner_b", B, UserRole.INSTITUTION_ADMIN, email="owner@b.test"); off = user("off_a", A, UserRole.STAFF, StaffType.GENERAL, email="off@a.test"); off.is_active = False; db.commit()
c = TestClient(app)
ok = lambda l, v: print(("PASS " if v else "FAIL ") + l)
login = lambda u, p="OldPass#12345": c.post("/api/auth/login", data={"username": u, "password": p})
tok = lambda u: {"authorization": "Bearer " + login(u).json()["access_token"]}
token_of = lambda msg: re.search(r"#token=([\w-]+)", msg["text"]).group(1)
forgot = lambda ident: c.post("/api/auth/forgot-password", json={"identifier": ident})

# policy
Ho = tok("owner_a")
def create(pw): return c.post("/api/users", json={"username": "newbie_x", "full_name": "N", "password": pw, "role": "staff", "staff_type": "general"}, headers=Ho)
for pw, why in (("alllowercase1!", "no capital"), ("ALLUPPERCASE1!", "no lowercase"), ("NoNumbersHere!", "no number"), ("NoSymbols12345", "no symbol"), ("Sh0rt!aA", "under 10"), ("Newbie_x1234!", "contains username"), ("Password1!", "common word")):
    ok(f"password rejected: {why}", create(pw).status_code == 422)
r = create("Tr1cky#Horse-Staple"); print(r.status_code, r.text[:150]); ok("strong password accepted", r.status_code == 201)
ok("error message names what is missing", "uppercase" in json.dumps(create("alllowercase1!").json()))

# forgot: same answer, no leak
a = forgot("nobody_at_all"); b = forgot("staff_a"); sent_count = len(sent)
ok("same reply for unknown and known", a.status_code == b.status_code == 202 and a.json() == b.json())
ok("one email sent to the account's address", sent_count == 1 and sent[0]["to"] == "staff@a.test")
ok("link has the token after #", "https://know.example.com/reset-password.html#token=" in sent[0]["text"])
ok("no email for an account without an address", (forgot("noemail_a"), len(sent))[1] == 1)
ok("no email for a deactivated account", (forgot("off_a"), len(sent))[1] == 1)
ok("lookup by email works, case-insensitive", (forgot("STAFF@A.TEST"), len(sent))[1] == 2)
first = token_of(sent[0]); second = token_of(sent[1])
ok("tokens are not stored in the clear", all(first not in (r.token_hash or "") for r in db.query(PasswordReset).all()) and db.query(PasswordReset).count() == 2)
ok("a new request cancels the earlier link", c.post("/api/auth/reset-password", json={"token": first, "new_password": "Br4nd!NewPass"}).status_code == 400)
forgot("staff_a"); forgot("staff_a")
ok("per-account limit: 3 links an hour", len(sent) == 3)
# reset
last = token_of(sent[2])
ok("weak new password rejected and link NOT used up", c.post("/api/auth/reset-password", json={"token": last, "new_password": "weakpass"}).status_code == 422)
old_session = tok("staff_a")
for _ in range(5): login("staff_a", "wrong")
ok("account locked before reset", login("staff_a").status_code == 423)
r = c.post("/api/auth/reset-password", json={"token": last, "new_password": "Br4nd!NewPass"}); ok("valid link sets password", r.status_code == 200)
ok("lock cleared and new password works", login("staff_a", "Br4nd!NewPass").status_code == 200)
ok("old password no longer works", login("staff_a").status_code == 401)
ok("old session signed out", c.get("/api/users/me", headers=old_session).status_code == 401)
ok("link is single-use", c.post("/api/auth/reset-password", json={"token": last, "new_password": "An0ther!Pass99"}).status_code == 400)
ok("confirmation email sent", sent[-1]["subject"] == "Your K.N.O.W. password was changed")
ok("garbage token rejected", c.post("/api/auth/reset-password", json={"token": "x" * 43, "new_password": "An0ther!Pass99"}).status_code == 400)
forgot("owner_a"); t = token_of(sent[-1])
row = db.query(PasswordReset).filter(PasswordReset.user_id == oa.id).first(); row.expires_at = datetime.now(timezone.utc) - timedelta(minutes=1); db.commit()
ok("expired link rejected", c.post("/api/auth/reset-password", json={"token": t, "new_password": "An0ther!Pass99"}).status_code == 400)
# admin
Ho = tok("owner_a")
n = len(sent)
ok("owner emails a reset link to own staff", c.post(f"/api/users/{sa.id}/send-reset-link", headers=Ho).status_code == 202 and len(sent) == n + 1)
ok("no address -> clear error", c.post(f"/api/users/{noemail.id}/send-reset-link", headers=Ho).status_code == 400)
ok("other institution's owner refused", c.post(f"/api/users/{sa.id}/send-reset-link", headers=tok("owner_b")).status_code == 403)
ok("staff refused", c.post(f"/api/users/{oa.id}/send-reset-link", headers=tok("staff_a") if False else {"authorization": "Bearer " + login("staff_a", "Br4nd!NewPass").json()["access_token"]}).status_code == 403)
settings.EMAIL_PROVIDER = ""
ok("email not set up -> 503", c.post(f"/api/users/{sa.id}/send-reset-link", headers=Ho).status_code == 503)
settings.EMAIL_PROVIDER = "resend"
ok("owner can still set a password directly (new rule applies)", c.post(f"/api/users/{noemail.id}/reset-password", json={"new_password": "weak"}, headers=Ho).status_code == 422 and c.post(f"/api/users/{noemail.id}/reset-password", json={"new_password": "Zebra!Quartz#2024"}, headers=Ho).status_code == 200)
# email management
ok("duplicate email refused", c.patch(f"/api/users/{noemail.id}", json={"email": "staff@a.test"}, headers=Ho).status_code == 409)
ok("owner adds an email", c.patch(f"/api/users/{noemail.id}", json={"email": " New@A.test "}, headers=Ho).json()["email"] == "new@a.test")
ok("bad email rejected", c.patch(f"/api/users/{noemail.id}", json={"email": "nope"}, headers=Ho).status_code == 422)
ok("clearing an email works", c.patch(f"/api/users/{noemail.id}", json={"email": ""}, headers=Ho).json()["email"] is None)
ok("own email change needs the current password", c.post("/api/users/me/email", json={"email": "me@a.test", "current_password": "wrong"}, headers=Ho).status_code == 400)
ok("own email change works", c.post("/api/users/me/email", json={"email": "me@a.test", "current_password": "OldPass#12345"}, headers=Ho).json()["email"] == "me@a.test")
# privacy
logs = " ".join((r.detail or "") for r in db.query(ActivityLog).all())
ok("activity log holds no email addresses", "@" not in logs)
ok("vault export leaves emails out", "@a.test" not in json.dumps(__import__("app.utils.vault", fromlist=["x"]).export_institution(db, A)))
ok("android redirect points at the website", c.get("/api/auth/forgot-redirect", follow_redirects=False).headers.get("location") == "https://know.example.com/forgot-password.html")
settings.FRONTEND_URL = ""; settings.CORS_ORIGINS = "http://localhost:5500,https://app.example.org"
ok("website address derived from allowed origins", settings.frontend_base == "https://app.example.org")
settings.FRONTEND_URL = "https://know.example.com"
ok("forgot works with no session and no CSRF cookie", forgot("staff_a").status_code == 202)
limiter.enabled = True; limiter.reset()
ok("forgot is rate limited per address", 429 in [forgot("staff_a").status_code for _ in range(8)])
