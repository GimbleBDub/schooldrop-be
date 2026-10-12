"""
End-to-end walk through every SchoolDrop flow against a running server (sandbox payment gateway).

    python3 scripts/e2e.py <PAYMENT_WEBHOOK_SECRET>

Env: SCHOOLDROP_API (default http://localhost:8097/api/v1), ADMIN_EMAIL, ADMIN_PASSWORD.
Creates its own school/parents/driver with a random suffix, so it can run repeatedly.
Run it against a scratch schema, not real data.
"""
import hashlib
import os
import hmac
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone

BASE = os.environ.get("SCHOOLDROP_API", "http://localhost:8097/api/v1")
WEBHOOK_SECRET = sys.argv[1]
RUN = uuid.uuid4().hex[:6]
passed = failed = 0


def call(method, path, body=None, token=None, raw=None, headers=None, expect=None, label=None):
    global passed, failed
    data = None
    h = dict(headers or {})
    if raw is not None:
        data = raw
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req) as r:
            status, text = r.status, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode(errors="replace")
    payload = json.loads(text) if text and text.strip().startswith(("{", "[")) else text
    if expect is not None:
        ok = status == expect
        if ok:
            passed += 1
        else:
            failed += 1
        print(("PASS " if ok else "FAIL ") + (label or f"{method} {path}") + f" -> {status}"
              + ("" if ok else f" (expected {expect}) {str(payload)[:300]}"))
    return status, payload


def check(label, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print("PASS " + label)
    else:
        failed += 1
        print("FAIL " + label + " " + str(detail)[:300])


def multipart(fields, file_bytes, filename):
    boundary = "----bs" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        if v is None:
            continue
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                 f'Content-Type: application/octet-stream\r\n\r\n'.encode() + file_bytes + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), {"Content-Type": f"multipart/form-data; boundary={boundary}"}


import random


def signup(account_type, email, onboarding, label=None, expect_onboarding=200):
    """Two steps: account (type, email, phone, password), then onboarding details. Returns the token."""
    phone = "+2677" + "".join(random.choice("0123456789") for _ in range(7))
    _, auth = call("POST", "/auth/register", {"accountType": account_type, "email": email, "phone": phone,
                                               "password": "Passw0rd-123"},
                   expect=201 if label else None, label=label and f"sign up {label}")
    token = auth["accessToken"]
    _, me = call("PUT", f"/onboarding/{account_type.lower()}", onboarding, token,
                 expect=expect_onboarding if label else None, label=label and f"onboard {label}")
    return token


PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
PDF = b"%PDF-1.4\n%fake\n"
today = date.today()

# ─── Admin & terms ─────────────────────────────────────────────────────────
_, a = call("POST", "/auth/login", {"email": os.environ.get("ADMIN_EMAIL", "admin@schooldrop.local"),
                                    "password": os.environ.get("ADMIN_PASSWORD", "ChangeMe-Local-123"), "deviceId": "web"},
            expect=200, label="admin login")
ADMIN = a["accessToken"]
check("admin has SUPER_ADMIN", "SUPER_ADMIN" in a["user"]["staffPermissions"], a["user"])
_, term = call("POST", "/admin/terms", {"name": f"Term {RUN}", "startDate": str(today - timedelta(days=30)),
                                        "endDate": str(today + timedelta(days=60))}, ADMIN, expect=201, label="create term")

# ─── Phase 1: school (added by SchoolDrop, pinned on the map) ───────────────
_, sch = call("POST", "/admin/schools", {"name": f"Gaborone Primary {RUN}", "address": "Plot 1, Main Mall", "city": "Gaborone",
                                         "latitude": -24.6545, "longitude": 25.9086, "phone": "+267 390 0000"},
              ADMIN, expect=201, label="admin adds a school with its map location")
check("admin-added school is verified and pinned", sch["status"] == "VERIFIED" and sch["latitude"] == -24.6545, sch)
call("POST", "/admin/schools", {"name": f"Gaborone Primary {RUN}", "address": "x", "city": "gaborone",
                                "latitude": -24.6, "longitude": 25.9}, ADMIN, expect=409, label="same school can't be added twice")
call("POST", "/admin/schools", {"name": "No pin", "address": "x", "city": "Gaborone"}, ADMIN, expect=400,
     label="a school needs its map location")
_, ainv = call("POST", f"/admin/schools/{sch['id']}/invites", {"role": "ADMIN", "name": "Mma Head"}, ADMIN, expect=201,
               label="admin creates the school admin's invite code")
call("POST", "/auth/register", {"accountType": "PARENT", "email": f"weak{RUN}@x.bw", "phone": "+26771999999",
                                "password": "password123"}, expect=400, label="weak password rejected (needs A-z, 0-9, symbol)")
_, s0 = call("POST", "/auth/register", {"accountType": "SCHOOL", "email": f"head{RUN}@school.bw",
                                        "phone": "+2673" + RUN[:6].encode().hex()[:7], "password": "Passw0rd-123"},
             expect=201, label="sign up school staff (account only)")
check("new account needs onboarding", s0["user"]["onboardingComplete"] is False, s0["user"])
call("GET", "/school/me", token=s0["accessToken"], expect=403, label="school features locked before onboarding")
call("POST", "/auth/register", {"accountType": "ADMIN", "email": f"x{RUN}@x.bw", "phone": "+26770000001",
                                "password": "Passw0rd-123"}, expect=400, label="can't self-register as admin")
call("PUT", "/onboarding/school", {"firstName": "Mma", "lastName": "Head", "schoolName": "Self made", "address": "Plot 1",
                                   "city": "Gaborone"}, s0["accessToken"], expect=400, label="schools can't register themselves")
_, onboarded = call("PUT", "/onboarding/join", {"firstName": "Mma", "lastName": "Head", "code": ainv["code"]},
                    s0["accessToken"], expect=200, label="school admin joins with SchoolDrop's code")
check("onboarding complete as school admin", onboarded["onboardingComplete"] is True and onboarded["schoolRole"] == "ADMIN", onboarded)
s = s0
SCHOOL = s["accessToken"]
_, me = call("GET", "/school/me", token=SCHOOL, expect=200, label="school sees own school")
check("school is ready to use", me["status"] == "VERIFIED", me)
_, me2 = call("PUT", "/school/me", {"phone": "+267 391 1111", "email": f"office{RUN}@school.bw"}, SCHOOL, expect=200,
              label="school admin updates contact details")
check("name and location stay as SchoolDrop set them", me2["name"] == sch["name"] and me2["latitude"] == -24.6545, me2)
SCHOOL_ID = me["id"]
_, sinv = call("POST", "/school/staff/invites", {"role": "STAFF", "name": "Ms Dube"}, SCHOOL, expect=201,
               label="school admin invites a teacher")
_, t0 = call("POST", "/auth/register", {"accountType": "SCHOOL", "email": f"teacher{RUN}@school.bw",
                                        "phone": "+2673" + str(random.randint(1000000, 9999999)), "password": "Passw0rd-123"},
             expect=201, label="teacher signs up")
TEACHER = t0["accessToken"]
_, tme = call("PUT", "/onboarding/join", {"firstName": "Kefilwe", "lastName": "Dube", "code": sinv["code"],
                                          "title": "Grade 3 teacher"}, TEACHER, expect=200, label="teacher joins school with code")
check("teacher is STAFF", tme["schoolRole"] == "STAFF", tme)
call("PUT", "/onboarding/join", {"firstName": "x", "lastName": "y", "code": sinv["code"]}, SCHOOL, expect=409,
     label="invite code works once")
_, staff_list = call("GET", "/school/staff", token=SCHOOL, expect=200, label="school staff list")
check("school has admin + teacher", len(staff_list["staff"]) == 2, staff_list)

# ─── Phase 1: parent & children ────────────────────────────────────────────
PARENT = signup("PARENT", f"p{RUN}@x.bw", {"firstName": "Ama", "lastName": "Kgosi"}, label="parent")
call("PUT", "/onboarding/driver", {"firstName": "x", "lastName": "y"}, PARENT, expect=403, label="wrong onboarding type rejected")
_, schools = call("GET", f"/schools?query={RUN}", token=PARENT, expect=200, label="parent searches schools")
check("verified school listed", any(x["id"] == SCHOOL_ID for x in schools["content"]), schools)
kids = []
for name in ["Neo", "Lesedi", "Tumi"]:
    _, c = call("POST", "/parent/children", {"firstName": name, "lastName": "Kgosi", "grade": "Std 3",
                                             "schoolId": SCHOOL_ID, "specialNeeds": "Peanut allergy" if name == "Neo" else None},
                PARENT, expect=201, label=f"add child {name}")
    kids.append(c)
check("child enrollment PENDING", kids[0]["enrollmentStatus"] == "PENDING", kids[0])
call("GET", f"/parent/children/{kids[0]['id']}/offers", token=PARENT, expect=400, label="offers blocked until school confirms")
_, pending = call("GET", "/school/children?status=PENDING", token=SCHOOL, expect=200, label="school lists pending children")
check("school sees 3 pending", pending["totalElements"] == 3, pending)
for c in kids:
    call("POST", f"/school/children/{c['id']}/confirm", token=SCHOOL, expect=200, label=f"school confirms {c['firstName']}")

call("POST", f"/school/children/{kids[0]['id']}/reject", {"reason": "x"}, TEACHER, expect=403,
     label="teacher can't decide enrollments")

# ─── Family: invite a helper (nanny) ───────────────────────────────────────
_, hinv = call("POST", "/parent/household/invites", {"role": "HELPER", "name": "Mpho"}, PARENT, expect=201,
               label="parent invites a helper")
_, h0 = call("POST", "/auth/register", {"accountType": "PARENT", "email": f"helper{RUN}@x.bw",
                                        "phone": "+2677" + str(random.randint(1000000, 9999999)), "password": "Passw0rd-123"},
             expect=201, label="helper signs up")
HELPER = h0["accessToken"]
_, hme = call("PUT", "/onboarding/join", {"firstName": "Mpho", "lastName": "Sello", "code": hinv["code"]}, HELPER,
              expect=200, label="helper joins family with code")
check("helper role", hme["householdRole"] == "HELPER", hme)
_, fam = call("GET", "/parent/household", token=PARENT, expect=200, label="family members")
check("family has owner + helper", sorted(m["role"] for m in fam["members"]) == ["HELPER", "OWNER"], fam)
_, hkids = call("GET", "/parent/children", token=HELPER, expect=200, label="helper sees the family's children")
check("helper sees 3 children", len(hkids) == 3, hkids)
call("POST", "/parent/children", {"firstName": "x", "lastName": "y"}, HELPER, expect=403, label="helper can't add children")
call("POST", "/parent/household/invites", {"role": "GUARDIAN"}, HELPER, expect=403, label="helper can't invite")

# ─── Phase 2: driver onboarding ────────────────────────────────────────────
DRIVER = signup("DRIVER", f"d{RUN}@x.bw", {"firstName": "Kabo", "lastName": "Driver", "omangNumber": "123456789"},
                label="driver")
_, prof = call("GET", "/driver/profile", token=DRIVER, expect=200, label="driver profile")
check("omang masked for driver", prof["omangNumber"] == "******789", prof["omangNumber"])
check("missing requirements listed", len(prof["missingRequirements"]) > 3, prof["missingRequirements"])
call("PUT", "/driver/profile", {"licenceNumber": "BW123456", "licenceCode": "C1", "dateOfBirth": "1985-04-02",
                                "payoutMethod": "ORANGE_MONEY", "payoutAccount": "+26771111222", "acceptsAdHoc": True},
     DRIVER, expect=200, label="driver fills profile")
call("PUT", "/driver/schools", {"schoolIds": [SCHOOL_ID]}, DRIVER, expect=200, label="driver picks school")
_, veh = call("POST", "/driver/vehicles", {"make": "Toyota", "model": "Quantum", "year": 2019, "colour": "White",
                                          "plateNumber": f"B {RUN[:3]} ABC", "type": "MINIBUS", "seatCapacity": 14},
              DRIVER, expect=201, label="add vehicle")
_, colours = call("GET", "/vehicle-colours", token=DRIVER, expect=200, label="vehicle colour list")
check("common colours listed", "White" in colours and "Silver" in colours, colours)
call("POST", "/driver/vehicles", {"make": "Toyota", "model": "Avanza", "year": 2020, "colour": "Sparkly unicorn",
                                  "plateNumber": f"B {RUN[:3]} XYZ", "type": "MINIBUS", "seatCapacity": 7},
     DRIVER, expect=400, label="colour must come from the list")
call("POST", "/driver/vehicles", {"make": "Toyota", "model": "Avanza", "year": today.year + 3, "colour": "White",
                                  "plateNumber": f"B {RUN[:3]} XYZ", "type": "MINIBUS", "seatCapacity": 7},
     DRIVER, expect=400, label="year can't be in the future")
_, nc = call("POST", "/admin/vehicle-colours", {"name": f"champagne {RUN}"}, ADMIN, expect=201, label="admin adds a colour")
_, colours = call("GET", "/vehicle-colours", token=DRIVER, expect=200, label="colour list after admin adds one")
check("new colour available to drivers", nc["name"] in colours, colours)
call("POST", f"/admin/vehicle-colours/{nc['id']}/remove", token=ADMIN, expect=200, label="admin removes it again")
call("POST", "/driver/submit", token=DRIVER, expect=400, label="submit blocked without documents")

raw, h = multipart({"type": "OMANG_FRONT"}, b"GIF89a not allowed", "x.gif")
call("POST", "/driver/documents", token=DRIVER, raw=raw, headers=h, expect=400, label="non PDF/JPEG/PNG rejected")
raw, h = multipart({"type": "POLICE_CLEARANCE", "issuedOn": str(today - timedelta(days=200)), "referenceNumber": "PC1"}, PDF, "pc.pdf")
call("POST", "/driver/documents", token=DRIVER, raw=raw, headers=h, expect=400, label="old police clearance rejected")

docs = [("OMANG_FRONT", {}), ("OMANG_BACK", {}), ("SELFIE", {}),
        ("DRIVERS_LICENCE", {"expiresOn": str(today + timedelta(days=700))}),
        ("POLICE_CLEARANCE", {"issuedOn": str(today - timedelta(days=10)), "referenceNumber": "BPS-2026-001"}),
        ("VEHICLE_REGISTRATION", {"vehicleId": veh["id"]}), ("VEHICLE_PHOTO", {"vehicleId": veh["id"]}),
        ("ROADWORTHY_CERTIFICATE", {"vehicleId": veh["id"], "expiresOn": str(today + timedelta(days=300))}),
        ("INSURANCE", {"vehicleId": veh["id"], "expiresOn": str(today + timedelta(days=300))})]
for t, extra in docs:
    raw, h = multipart({"type": t, **extra}, PNG if t in ("SELFIE", "VEHICLE_PHOTO") else PDF, t.lower())
    call("POST", "/driver/documents", token=DRIVER, raw=raw, headers=h, expect=201, label=f"upload {t}")
_, sub = call("POST", "/driver/submit", token=DRIVER, expect=200, label="driver submits for review")
check("driver UNDER_REVIEW", sub["status"] == "UNDER_REVIEW", sub["status"])
call("POST", "/driver/offers", {"vehicleId": veh["id"], "schoolId": SCHOOL_ID, "coverage": "BOTH", "capacity": 2,
                                "serviceArea": "Phakalane", "pricePerMonth": 600}, DRIVER, expect=403,
     label="unapproved driver can't create offers")

# ─── Phase 3: admin verification ───────────────────────────────────────────
_, q = call("GET", "/admin/drivers?status=UNDER_REVIEW", token=ADMIN, expect=200, label="verification queue")
DRIVER_ID = prof["id"]
check("driver in queue", any(x["id"] == DRIVER_ID for x in q["content"]), q)
_, full = call("GET", f"/admin/drivers/{DRIVER_ID}", token=ADMIN, expect=200, label="admin opens driver")
check("verifier sees full omang", full["omangNumber"] == "123456789", full["omangNumber"])
call("POST", f"/admin/drivers/{DRIVER_ID}/approve", token=ADMIN, expect=400, label="approve blocked until docs approved")
doc0 = full["documents"][0]
s_, f = call("GET", f"/admin/documents/{doc0['id']}/file", token=ADMIN, expect=200, label="admin downloads document")
call("GET", f"/driver/documents/{doc0['id']}/file", token=PARENT, expect=403, label="parent can't fetch driver docs")
for doc in full["documents"]:
    call("POST", f"/admin/documents/{doc['id']}/review", {"approve": True}, ADMIN, expect=200, label=f"approve {doc['type']}")
_, ap = call("POST", f"/admin/drivers/{DRIVER_ID}/approve", token=ADMIN, expect=200, label="admin approves driver")
check("driver APPROVED", ap["status"] == "APPROVED", ap["status"])
call("PUT", "/driver/profile", {"omangNumber": "999999999"}, DRIVER, expect=409, label="identity locked after approval")

# ─── Phase 4: offers & bookings ────────────────────────────────────────────
call("POST", "/driver/offers", {"vehicleId": veh["id"], "schoolId": SCHOOL_ID, "coverage": "BOTH", "capacity": 20,
                                "serviceArea": "Phakalane", "pricePerMonth": 600}, DRIVER, expect=400,
     label="capacity above seats rejected")
_, offer = call("POST", "/driver/offers", {"vehicleId": veh["id"], "schoolId": SCHOOL_ID, "coverage": "BOTH", "capacity": 2,
                                           "serviceArea": "Phakalane, Block 10", "morningPickupTime": "06:30",
                                           "pricePerTrip": 25, "pricePerMonth": 600, "pricePerTerm": 1500, "adHocFare": 80},
                DRIVER, expect=201, label="driver creates offer")
call("POST", "/driver/offers", {"vehicleId": veh["id"], "schoolId": SCHOOL_ID, "coverage": "MORNING", "capacity": 13,
                                "serviceArea": "x", "pricePerMonth": 1}, DRIVER, expect=400,
     label="vehicle seats can't be oversold across offers")
_, hidden = call("GET", f"/parent/children/{kids[0]['id']}/offers", token=PARENT, expect=200, label="offers before fee paid")
check("unpaid driver hidden from parents", hidden == [], hidden)
_, sub = call("GET", "/driver/subscription", token=DRIVER, expect=200, label="driver fee status")
check("P50 monthly fee, not yet active", float(sub["monthlyFee"]) == 50.0 and sub["active"] is False, sub)
_, spay = call("POST", "/driver/subscription/pay", {"method": "ORANGE_MONEY", "payerPhone": "+26771111222"}, DRIVER,
               expect=200, label="driver pays monthly fee")
call("POST", f"/payments/sandbox/{spay['providerReference']}/complete", token=DRIVER, expect=204, label="fee payment completes")
_, sub = call("GET", "/driver/subscription", token=DRIVER, expect=200, label="driver fee status after paying")
check("driver live for a month", sub["active"] is True and sub["daysLeft"] >= 28, sub)

# Driver advert: pay, staff approve, parents see it and the offer is featured
_, ad = call("POST", "/driver/adverts", {"title": "Safe rides from Phakalane", "body": "Two seats left this term.",
                                         "targetSchoolId": SCHOOL_ID}, DRIVER, expect=201, label="driver creates advert")
_, adpay = call("POST", f"/driver/adverts/{ad['id']}/pay", {"method": "CARD"}, DRIVER, expect=200, label="driver pays for advert")
call("POST", f"/payments/sandbox/{adpay['providerReference']}/complete", token=DRIVER, expect=204, label="advert payment completes")
call("GET", "/adverts/next", token=PARENT, expect=204, label="no advert before review")
_, rev = call("POST", f"/admin/adverts/{ad['id']}/review", {"approve": True}, ADMIN, expect=200, label="staff approve advert")
check("advert live 30 days", rev["status"] == "ACTIVE", rev)
_, seen = call("GET", "/adverts/next", token=PARENT, expect=200, label="parent sees advert")
check("advert shown to parent at that school", seen["title"] == "Safe rides from Phakalane", seen)
call("GET", "/adverts/next", token=SCHOOL, expect=200, label="school staff see advert")
call("GET", "/adverts/next", token=DRIVER, expect=403, label="drivers aren't shown adverts")
call("POST", f"/adverts/{seen['id']}/click", token=PARENT, expect=204, label="advert click counted")

_, offers = call("GET", f"/parent/children/{kids[0]['id']}/offers", token=PARENT, expect=200, label="parent sees offers")
check("one offer with 2 seats", len(offers) == 1 and offers[0]["seatsAvailable"] == 2, offers)
check("advertising driver is featured", offers[0]["featured"] is True, offers[0])

month = today.strftime("%Y-%m")
_, b1 = call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": kids[0]["id"], "plan": "MONTHLY",
                                         "month": month, "pickupAddress": "Plot 5, Phakalane"},
             PARENT, expect=201, label="book Neo monthly")
check("monthly amount 600", float(b1["amount"]) == 600.0, b1["amount"])
end = today + timedelta(days=6)
_, b2 = call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": kids[1]["id"], "plan": "PER_TRIP",
                                         "startDate": str(today), "endDate": str(end), "pickupAddress": "Plot 5"},
             PARENT, expect=201, label="book Lesedi per trip")
weekdays = sum(1 for i in range(7) if (today + timedelta(days=i)).weekday() < 5)
check("per-trip price = 25 x 2 legs x weekdays", float(b2["amount"]) == 25 * 2 * weekdays, (b2["amount"], weekdays))
call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": kids[2]["id"], "plan": "TERMLY",
                                  "termId": term["id"], "pickupAddress": "Plot 5"}, PARENT, expect=409,
     label="third child rejected: offer full")
call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": kids[0]["id"], "plan": "MONTHLY",
                                  "month": month, "pickupAddress": "x"}, PARENT, expect=409, label="no double booking")

# ─── Phase 5: payments ─────────────────────────────────────────────────────
call("POST", f"/parent/bookings/{b1['id']}/pay", {"method": "ORANGE_MONEY"}, PARENT, expect=400,
     label="mobile money needs a number")
_, pay = call("POST", f"/parent/bookings/{b1['id']}/pay", {"method": "ORANGE_MONEY", "payerPhone": "+26771234567"},
              PARENT, expect=200, label="start payment")
check("payment PENDING, fee hidden from parent", pay["status"] == "PENDING" and pay["platformFee"] is None, pay)
call("POST", "/payments/webhook", raw=json.dumps({"reference": pay["providerReference"], "status": "SUCCEEDED"}).encode(),
     headers={"Content-Type": "application/json", "X-Signature": "bad"}, expect=401, label="webhook bad signature rejected")
body = json.dumps({"reference": pay["providerReference"], "status": "SUCCEEDED"}).encode()
sig = hmac.new(WEBHOOK_SECRET.encode(), body, hashlib.sha256).hexdigest()
call("POST", "/payments/webhook", raw=body, headers={"Content-Type": "application/json", "X-Signature": sig},
     expect=204, label="signed webhook accepted")
call("POST", "/payments/webhook", raw=body, headers={"Content-Type": "application/json", "X-Signature": sig},
     expect=204, label="webhook replay is idempotent")
_, b1s = call("GET", f"/parent/bookings/{b1['id']}", token=PARENT, expect=200, label="get booking")
check("booking ACTIVE after payment", b1s["status"] == "ACTIVE", b1s["status"])
_, admin_pays = call("GET", "/admin/payments?status=SUCCEEDED", token=ADMIN, expect=200, label="admin payments")
mine = [x for x in admin_pays["content"] if x["id"] == pay["id"]]
check("10% fee split", mine and float(mine[0]["platformFee"]) == 60.0 and float(mine[0]["driverAmount"]) == 540.0, mine)

# ─── Phase 6: trips & handover ─────────────────────────────────────────────
is_weekday = today.weekday() < 5
_, trips = call("GET", "/driver/trips", token=DRIVER, expect=200, label="driver opens today's trips")
if is_weekday:
    check("two legs generated", len(trips) == 2, [t["leg"] for t in trips])
    morning = next(t for t in trips if t["leg"] == "TO_SCHOOL")
    afternoon = next(t for t in trips if t["leg"] == "FROM_SCHOOL")
    check("only paid booking riding", [x["childName"] for x in morning["passengers"]] == ["Neo Kgosi"], morning["passengers"])
    check("driver sees special needs", morning["passengers"][0]["specialNeeds"] == "Peanut allergy", morning["passengers"][0])
    pid = morning["passengers"][0]["id"]
    call("POST", f"/driver/trips/{morning['id']}/passengers/{pid}/pickup", {}, DRIVER, expect=409, label="pickup before start blocked")
    call("POST", f"/driver/trips/{morning['id']}/start", token=DRIVER, expect=200, label="start morning trip")
    call("POST", f"/driver/trips/{morning['id']}/location", {"latitude": -24.6282, "longitude": 25.9231}, DRIVER,
         expect=204, label="share location")
    _, t = call("POST", f"/driver/trips/{morning['id']}/passengers/{pid}/pickup", token=DRIVER, expect=200, label="driver: picked up")
    check("waiting for the family's confirmation", t["passengers"][0]["pickupConfirmedAt"] is None, t["passengers"][0])
    call("POST", f"/school/passengers/{pid}/release", token=SCHOOL, expect=400, label="school can't confirm a home handover")
    _, ct = call("POST", f"/parent/passengers/{pid}/confirm?stage=PICKUP", token=HELPER, expect=200,
                 label="helper confirms handing Neo over")
    check("pickup confirmed by helper", ct["passengers"][0]["pickupConfirmedBy"] == "Mpho Sello", ct["passengers"][0])
    _, ct = call("POST", f"/parent/passengers/{pid}/confirm?stage=PICKUP", token=PARENT, expect=200,
                 label="second confirmation is harmless")
    check("first confirmation wins", ct["passengers"][0]["pickupConfirmedBy"] == "Mpho Sello", ct["passengers"][0])
    _, ptrips = call("GET", "/parent/trips/today", token=PARENT, expect=200, label="parent tracks trips")
    live = next(x for x in ptrips if x["id"] == morning["id"])
    check("parent sees live location", live["lastLatitude"] == -24.6282, live)
    call("POST", f"/driver/trips/{morning['id']}/complete", token=DRIVER, expect=409, label="can't complete with child on board")
    call("POST", f"/driver/trips/{morning['id']}/passengers/{pid}/dropoff", token=DRIVER, expect=200, label="drop off at school")
    call("POST", f"/parent/passengers/{pid}/confirm?stage=DROPOFF", token=PARENT, expect=400,
         label="family can't confirm a school arrival")
    _, at = call("POST", f"/school/passengers/{pid}/arrived", token=TEACHER, expect=200, label="teacher confirms arrival")
    check("arrival confirmed by teacher", at and next(p for p in at["passengers"] if p["id"] == pid)["dropoffConfirmedBy"] == "Kefilwe Dube", at)
    call("POST", f"/driver/trips/{morning['id']}/complete", token=DRIVER, expect=200, label="complete morning trip")

    # Kid pairing & confirmation
    _, code = call("POST", f"/parent/children/{kids[0]['id']}/pairing-code", token=PARENT, expect=200, label="pairing code")
    _, kid = call("POST", "/auth/kid/pair", {"code": code["code"].lower(), "deviceId": "kidphone"}, expect=200, label="kid pairs phone")
    KID = kid["accessToken"]
    call("POST", "/auth/kid/pair", {"code": code["code"]}, expect=401, label="pairing code is single-use")
    call("GET", "/parent/children", token=KID, expect=403, label="kid can't use parent API")
    _, ktrips = call("GET", "/kid/trips/today", token=KID, expect=200, label="kid sees trips")
    kp = next(t for t in ktrips if t["leg"] == "FROM_SCHOOL")["passengers"][0]["id"]

    # School gate
    _, strips = call("GET", "/school/trips", token=SCHOOL, expect=200, label="school gate list")
    check("school sees afternoon run", any(t["leg"] == "FROM_SCHOOL" for t in strips), strips)
    call("POST", f"/school/passengers/{kp}/release", token=TEACHER, expect=200, label="teacher releases child")
    call("POST", f"/driver/trips/{afternoon['id']}/start", token=DRIVER, expect=200, label="start afternoon trip")
    _, t = call("POST", f"/driver/trips/{afternoon['id']}/passengers/{kp}/pickup", token=DRIVER, expect=200, label="driver: collected")
    check("release recorded as the school's confirmation", t["passengers"][0]["pickupConfirmedBy"] == "Kefilwe Dube",
          t["passengers"][0])
    call("POST", f"/driver/trips/{afternoon['id']}/passengers/{kp}/dropoff", {}, DRIVER, expect=400,
         label="home drop-off needs who received the child")
    _, t = call("POST", f"/driver/trips/{afternoon['id']}/passengers/{kp}/dropoff", {"handedTo": "Helper (Mpho)"}, DRIVER,
                expect=200, label="driver: dropped home, handed to helper")
    _, ct = call("POST", f"/parent/passengers/{kp}/confirm?stage=DROPOFF", token=HELPER, expect=200, label="helper confirms Neo is home")
    check("home confirmed by helper", ct["passengers"][0]["dropoffConfirmedBy"] == "Mpho Sello", ct["passengers"][0])
    _, dt = call("POST", f"/parent/passengers/{pid}/dispute", {"reason": "Neo says he was dropped at the wrong gate"}, PARENT,
                 expect=200, label="parent reports a handover problem")
    _, open_incidents = call("GET", "/admin/incidents?status=OPEN", token=ADMIN, expect=200, label="incident created for dispute")
    check("dispute reaches support", any("Handover disputed" in i["description"] for i in open_incidents["content"]), open_incidents)
    call("POST", f"/kid/passengers/{kp}/confirm-pickup", token=KID, expect=200, label="kid confirms pickup")
else:
    print("(weekend: skipping trip run checks)")

_, notes = call("GET", "/notifications", token=PARENT, expect=200, label="parent inbox")
check("parent notified", notes["totalElements"] >= 3, [n["title"] for n in notes["content"]])

# ─── Once-off pickup ──────────────────────────────────────────────────────
pickup_at = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat().replace("+00:00", "Z")
_, adhoc = call("POST", "/parent/adhoc", {"childId": kids[2]["id"], "pickupAt": pickup_at, "dropoffAddress": "Plot 5"},
                PARENT, expect=201, label="parent requests once-off pickup")
_, feed = call("GET", "/driver/adhoc/open", token=DRIVER, expect=200, label="driver sees open pickups")
mine = [x for x in feed if x["id"] == adhoc["id"]]
check("driver sees own fare", mine and float(mine[0]["fare"]) == 80.0, feed)
call("POST", f"/parent/adhoc/{adhoc['id']}/pay", {"method": "CARD"}, PARENT, expect=409, label="can't pay before a driver accepts")
call("POST", f"/driver/adhoc/{adhoc['id']}/accept", token=DRIVER, expect=200, label="driver accepts")
call("POST", f"/driver/adhoc/{adhoc['id']}/accept", token=DRIVER, expect=409, label="second accept rejected")
_, apay = call("POST", f"/parent/adhoc/{adhoc['id']}/pay", {"method": "CARD"}, PARENT, expect=200, label="pay once-off pickup")
call("POST", f"/payments/sandbox/{apay['providerReference']}/complete", token=PARENT, expect=204, label="sandbox completes")
_, alist = call("GET", "/parent/adhoc", token=PARENT, expect=200, label="list pickups")
check("pickup CONFIRMED", next(x for x in alist if x["id"] == adhoc["id"])["status"] == "CONFIRMED", alist)
_, snotes = call("GET", "/notifications", token=SCHOOL, expect=200, label="school inbox")
check("school told about authorised pickup", any(n["type"] == "AUTHORISED_PICKUP" for n in snotes["content"]), snotes)

# ─── Cancellation, refunds, payouts ────────────────────────────────────────
_, c2 = call("POST", f"/parent/bookings/{b2['id']}/cancel", {"reason": "Changed plans"}, PARENT, expect=200, label="cancel unpaid booking")
check("booking CANCELLED", c2["status"] == "CANCELLED", c2)
_, earn = call("GET", "/driver/earnings", token=DRIVER, expect=200, label="driver earnings")
check("earnings: 540 available, 72 once-off upcoming",
      float(earn["availableBalance"]) == 540.0 and float(earn["upcomingBalance"]) == 72.0, earn)
_, run = call("POST", "/admin/payouts/run", token=ADMIN, expect=200, label="run payouts")
_, pos = call("GET", "/admin/payouts?status=PENDING", token=ADMIN, expect=200, label="pending payouts")
po = next(x for x in pos["content"] if x["driverId"] == DRIVER_ID)
check("payout total 540 with full account for finance", float(po["amount"]) == 540.0 and po["account"] == "+26771111222", po)
call("POST", f"/admin/payouts/{po['id']}/paid", {"reference": "OM-TX-1"}, ADMIN, expect=200, label="mark payout paid")
call("POST", f"/admin/payments/{pay['id']}/refund", {"reason": "test"}, ADMIN, expect=409, label="refund after payout blocked")

# ─── Concurrency: 6 parents race for the last seat ─────────────────────────
import threading
racers = []
for i in range(6):
    rtoken = signup("PARENT", f"race{i}{RUN}@x.bw", {"firstName": "R", "lastName": str(i)})
    _, rc = call("POST", "/parent/children", {"firstName": "Racer", "lastName": str(i), "schoolId": SCHOOL_ID}, rtoken)
    call("POST", f"/school/children/{rc['id']}/confirm", token=SCHOOL)
    racers.append((rtoken, rc["id"]))
results = []
def race(tok, cid):
    st, _ = call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": cid, "plan": "MONTHLY", "month": month,
                                              "pickupAddress": "x"}, tok)
    results.append(st)
threads = [threading.Thread(target=race, args=r) for r in racers]
[t.start() for t in threads]
[t.join() for t in threads]
check("exactly one racer gets the last seat", sorted(results) == [201, 409, 409, 409, 409, 409], sorted(results))

# ─── Incidents & RBAC ──────────────────────────────────────────────────────
if is_weekday:
    _, inc = call("POST", "/incidents", {"type": "SOS", "description": "Help", "tripId": afternoon["id"]}, KID,
                  expect=201, label="kid raises SOS")
    _, pn = call("GET", "/notifications", token=PARENT, expect=200, label="parent inbox after SOS")
    check("parent told about SOS", any(n["type"] == "SOS" for n in pn["content"]), pn)
_, incs = call("GET", "/admin/incidents?status=OPEN", token=ADMIN, expect=200, label="admin incident queue")

_, staff = call("POST", "/admin/staff", {"firstName": "Vee", "lastName": "Rifier", "email": f"v{RUN}@schooldrop.bw",
                                        "temporaryPassword": "TempPassword123!", "permissions": ["VERIFIER"]},
                ADMIN, expect=201, label="super admin creates verifier")
_, v = call("POST", "/auth/login", {"email": f"v{RUN}@schooldrop.bw", "password": "TempPassword123!"}, expect=200, label="verifier logs in")
V = v["accessToken"]
check("new staff must choose a password", v["user"]["passwordChangeRequired"] is True, v["user"])
call("GET", "/admin/drivers", token=V, expect=403, label="back office locked until the temporary password is changed")
_, v = call("PUT", "/account/password", {"currentPassword": "TempPassword123!", "newPassword": "VerifierOwn-Pass-1",
                                         "deviceId": "vweb", "deviceName": "Chrome on macOS"}, V, expect=200,
            label="verifier chooses own password")
V = v["accessToken"]
check("password change flag cleared", v["user"]["passwordChangeRequired"] is False, v["user"])
call("GET", "/admin/drivers", token=V, expect=200, label="verifier sees drivers")
call("GET", "/admin/payments", token=V, expect=403, label="verifier can't see payments")
call("POST", "/admin/staff", {"firstName": "x", "lastName": "y", "email": "z@z.bw", "temporaryPassword": "TempPassword123!",
                              "permissions": ["FINANCE"]}, V, expect=403, label="verifier can't create staff")

# ─── Paying the driver directly (no platform cut) ──────────────────────────
nm = (today.replace(day=1) + timedelta(days=32)).replace(day=1)
_, ob = call("POST", "/parent/bookings", {"offerId": offer["id"], "childId": kids[2]["id"], "plan": "PER_TRIP",
                                          "startDate": str(nm), "endDate": str(nm + timedelta(days=4)),
                                          "pickupAddress": "Plot 5", "paymentMode": "OFFLINE"},
             PARENT, expect=201, label="book, paying the driver directly")
check("offline booking holds the seat for 48h", ob["paymentMode"] == "OFFLINE" and ob["status"] == "PENDING_PAYMENT", ob)
call("POST", f"/parent/bookings/{ob['id']}/paid-driver", token=PARENT, expect=200, label="parent: I've paid the driver")
_, ob2 = call("POST", f"/driver/bookings/{ob['id']}/payment-received", token=DRIVER, expect=200, label="driver confirms cash received")
check("offline booking active", ob2["status"] == "ACTIVE", ob2)
_, offp = call("GET", "/admin/payments?status=SUCCEEDED", token=ADMIN, expect=200, label="payments incl. offline")
off = [p for p in offp["content"] if p.get("bookingId") == ob["id"]]
check("offline payment recorded with no platform cut", off and off[0]["offline"] and float(off[0]["platformFee"]) == 0, off)
call("POST", f"/admin/payments/{off[0]['id']}/refund", {"reason": "x"}, ADMIN, expect=409, label="offline payments aren't refunded in-app")

call("POST", f"/admin/drivers/{DRIVER_ID}/suspend", {"reason": "Complaint under investigation"}, ADMIN, expect=200, label="suspend driver")
_, offers = call("GET", f"/parent/children/{kids[2]['id']}/offers", token=PARENT, expect=200, label="offers after suspension")
check("suspended driver hidden from parents", offers == [], offers)
_, dash = call("GET", "/admin/dashboard", token=ADMIN, expect=200, label="dashboard")
check("dashboard has revenue", float(dash["grossLast30Days"]) >= 680, dash)
_, audit = call("GET", f"/admin/audit?targetId={DRIVER_ID}", token=ADMIN, expect=200, label="audit trail")
check("audit has approval and suspension",
      {"DRIVER_APPROVED", "DRIVER_SUSPENDED"} <= {x["action"] for x in audit["content"]}, [x["action"] for x in audit["content"]])

# ─── Profile & settings ────────────────────────────────────────────────────
_, acc = call("GET", "/account", token=PARENT, expect=200, label="parent account")
check("parent account has billing and all pushes on",
      acc["hasBilling"] and all(acc["notifications"].values()) and not acc["nameLocked"], acc)
_, acc = call("PUT", "/account/profile", {"firstName": "Amantle", "lastName": "Kgosi"}, PARENT, expect=200, label="parent renames")
check("name updated", acc["profile"]["firstName"] == "Amantle", acc)
_, dacc = call("GET", "/account", token=DRIVER, expect=200, label="driver account")
check("verified driver's name is locked", dacc["nameLocked"], dacc)
call("PUT", "/account/profile", {"firstName": "Someone", "lastName": "Else"}, DRIVER, expect=409, label="verified driver can't rename")
_, hacc = call("GET", "/account", token=HELPER, expect=200, label="helper account")
check("helper has no billing", hacc["hasBilling"] is False, hacc)
if globals().get("KID"):
    call("PUT", "/account/profile", {"firstName": "x", "lastName": "y"}, KID, expect=403, label="kids can't edit their account")

call("PUT", "/account/phone", {"newPhone": "71 000 111", "password": "wrong-password"}, PARENT, expect=400, label="phone change needs the password")
call("PUT", "/account/phone", {"newPhone": dacc["profile"]["phone"], "password": "Passw0rd-123"}, PARENT, expect=409,
     label="phone already used by another account")
new_phone = "7" + str(random.randint(1000000, 9999999))
_, acc = call("PUT", "/account/phone", {"newPhone": new_phone[:2] + " " + new_phone[2:], "password": "Passw0rd-123"}, PARENT,
              expect=200, label="parent changes phone")
check("phone normalised to +267", acc["profile"]["phone"] == "+267" + new_phone, acc["profile"])
call("POST", "/auth/register", {"accountType": "PARENT", "email": f"dup{RUN}@x.bw", "phone": "+267" + new_phone,
                                "password": "Passw0rd-123"}, expect=409, label="same number in another format can't sign up")

call("PUT", "/account/email", {"newEmail": f"d{RUN}@x.bw", "password": "Passw0rd-123"}, PARENT, expect=409,
     label="email already used by another account")
OLD_PARENT = PARENT
_, em = call("PUT", "/account/email", {"newEmail": f"ama{RUN}@x.bw", "password": "Passw0rd-123", "deviceId": "pphone",
                                       "deviceName": "iPhone"}, PARENT, expect=200, label="parent changes email")
PARENT = em["accessToken"]
check("new email returned, needs verifying", em["user"]["email"] == f"ama{RUN}@x.bw" and not em["user"]["emailVerified"], em["user"])
call("GET", "/account", token=OLD_PARENT, expect=401, label="old session ends after email change")
call("GET", "/account", token=PARENT, expect=200, label="new session works")
call("POST", "/auth/login", {"email": f"p{RUN}@x.bw", "password": "Passw0rd-123"}, expect=401, label="old email no longer signs in")

call("PUT", "/account/password", {"currentPassword": "nope-nope", "newPassword": "NewPassword-123"}, PARENT, expect=400,
     label="wrong current password")
_, pw = call("PUT", "/account/password", {"currentPassword": "Passw0rd-123", "newPassword": "NewPassword-123",
                                          "deviceId": "pphone", "deviceName": "iPhone"}, PARENT, expect=200,
             label="parent changes password")
OLD_PARENT, PARENT = PARENT, pw["accessToken"]
call("GET", "/account", token=OLD_PARENT, expect=401, label="old session ends after password change")
_, web = call("POST", "/auth/login", {"email": f"ama{RUN}@x.bw", "password": "NewPassword-123", "deviceId": "pweb",
                                      "deviceName": "Chrome on Windows"}, expect=200, label="sign in on a second device")
_, sess = call("GET", "/account/sessions?deviceId=pphone", token=PARENT, expect=200, label="signed-in devices")
check("two devices, this one marked", len(sess) == 2 and sum(1 for x in sess if x["current"]) == 1
      and {x["deviceName"] for x in sess} == {"iPhone", "Chrome on Windows"}, sess)
_, so = call("POST", "/account/sessions/sign-out-others", {"deviceId": "pphone"}, PARENT, expect=200, label="sign out other devices")
check("one other device signed out", so["signedOut"] == 1, so)
call("POST", "/auth/refresh", {"refreshToken": web["refreshToken"]}, expect=401, label="signed-out device can't refresh")

_, acc = call("PUT", "/account/notifications", {"trips": True, "bookings": True, "payments": False, "news": False}, PARENT,
              expect=200, label="mute payment and news pushes")
check("notification settings saved", acc["notifications"] == {"trips": True, "bookings": True, "payments": False, "news": False}, acc)

# ─── Billing, invoices, receipts ───────────────────────────────────────────
_, bs = call("GET", "/billing/summary", token=PARENT, expect=200, label="family billing summary")
fam_b = bs["family"]
check("family summary counts app and direct payments", bs["kind"] == "FAMILY" and float(fam_b["paidAllTime"]) > 0
      and float(fam_b["paidDirectAllTime"]) > 0 and len(fam_b["months"]) == 12, fam_b)
call("GET", "/billing/summary", token=HELPER, expect=403, label="helpers have no billing")
call("GET", "/billing/summary", token=SCHOOL, expect=403, label="schools have no billing")
_, ptx = call("GET", "/billing/transactions", token=PARENT, expect=200, label="family transactions")
check("payments carry receipt numbers", ptx and all(t["invoiceNumber"] for t in ptx if t["kind"] == "PAYMENT"), ptx[:3])
_, pinv = call("GET", "/billing/invoices", token=PARENT, expect=200, label="family documents")
receipts = [i for i in pinv if i["type"] == "RECEIPT"]
check("receipts issued, one paid to the driver directly",
      len(receipts) >= 3 and any(i["paidDirectly"] for i in receipts) and all(i["number"].startswith("RCT-") for i in receipts), pinv)
_, rc = call("GET", f"/billing/invoices/{receipts[0]['id']}", token=PARENT, expect=200, label="open a receipt")
check("receipt has lines and SchoolDrop's details", rc["lines"] and rc["issuer"]["name"], rc)
call("GET", f"/billing/invoices/{receipts[0]['id']}", token=HELPER, expect=404, label="helper can't open family receipts")
call("GET", f"/billing/invoices/{receipts[0]['id']}", token=racers[0][0], expect=404, label="other families can't open it")
_, link = call("POST", f"/billing/invoices/{receipts[0]['id']}/link", token=PARENT, expect=200, label="PDF download link")
st, pdf = call("GET", link["path"], expect=200, label="signed link opens the PDF without signing in")
check("it's a PDF", isinstance(pdf, str) and pdf.startswith("%PDF"), str(pdf)[:20])
call("GET", link["path"][:-4] + "0000", expect=403, label="tampered link rejected")
call("GET", f"/billing/invoices/{receipts[0]['id']}/pdf", token=PARENT, expect=200, label="PDF with the app's token")
call("GET", f"/billing/invoices/{receipts[0]['id']}/pdf", expect=403, label="PDF without token or signature rejected")

_, refunded = call("POST", f"/admin/payments/{apay['id']}/refund", {"reason": "Driver no-show"}, ADMIN, expect=200,
                   label="refund the once-off pickup")
_, pinv = call("GET", "/billing/invoices", token=PARENT, expect=200, label="documents after refund")
check("credit note issued for the refund", any(i["type"] == "CREDIT_NOTE" and i["number"].startswith("CRN-") for i in pinv), pinv)
_, bs = call("GET", "/billing/summary", token=PARENT, expect=200, label="summary after refund")
check("refund counted", float(bs["family"]["refundedAllTime"]) == 80.0, bs["family"])

_, ds = call("GET", "/billing/summary", token=DRIVER, expect=200, label="driver billing summary")
drv_b = ds["driver"]
check("driver summary: commission, payouts, fees paid to SchoolDrop",
      ds["kind"] == "DRIVER" and float(drv_b["commissionAllTime"]) > 0 and float(drv_b["paidOutAllTime"]) == 540.0
      and float(drv_b["listingFeesAllTime"]) == 50.0 and float(drv_b["advertsAllTime"]) == 150.0, drv_b)
_, dinv = call("GET", "/billing/invoices", token=DRIVER, expect=200, label="driver documents")
check("driver has listing/advert invoices and a payout statement",
      sum(1 for i in dinv if i["type"] == "INVOICE") == 2 and any(i["type"] == "STATEMENT" for i in dinv), dinv)
stm = next(i for i in dinv if i["type"] == "STATEMENT")
_, stmd = call("GET", f"/billing/invoices/{stm['id']}", token=DRIVER, expect=200, label="open payout statement")
check("statement deducts commission down to the payout",
      float(stmd["total"]) == 540.0 and any(float(l["amount"]) < 0 for l in stmd["lines"]), stmd)
_, dtx = call("GET", "/billing/transactions", token=DRIVER, expect=200, label="driver transactions")
check("driver history has earnings, fees and the payout",
      {"EARNING", "FEE", "PAYOUT"} <= {t["kind"] for t in dtx}, {t["kind"] for t in dtx})
call("GET", f"/billing/invoices/{stm['id']}", token=PARENT, expect=404, label="parents can't open driver statements")

_, ai = call("GET", "/admin/invoices?query=STM", token=ADMIN, expect=200, label="finance searches documents")
check("statement found", any(i["id"] == stm["id"] for i in ai["content"]), ai)
_, fin = call("GET", "/admin/finance?months=12", token=ADMIN, expect=200, label="finance report")
check("SchoolDrop revenue = commission + fees + adverts - refunded commission",
      abs(float(fin["totals"]["revenue"]) - (float(fin["totals"]["commission"]) + float(fin["totals"]["listingFees"])
          + float(fin["totals"]["adverts"]) - float(fin["totals"]["refundedCommission"]))) < 0.01
      and float(fin["totals"]["revenue"]) > 0, fin["totals"])
call("GET", "/admin/finance", token=V, expect=403, label="verifier can't see finance")

# ─── Staff management ──────────────────────────────────────────────────────
_, viewer = call("POST", "/admin/staff", {"firstName": "Read", "lastName": "Only", "email": f"ro{RUN}@schooldrop.bw",
                                          "permissions": ["VIEWER"]}, ADMIN, expect=201, label="add read-only staff (generated password)")
check("temporary password generated once", viewer["temporaryPassword"] and len(viewer["temporaryPassword"]) >= 12, viewer)
_, vo = call("POST", "/auth/login", {"email": f"ro{RUN}@schooldrop.bw", "password": viewer["temporaryPassword"]}, expect=200,
             label="viewer signs in")
_, vo = call("PUT", "/account/password", {"currentPassword": viewer["temporaryPassword"], "newPassword": "Viewer-Own-Pass-1"},
             vo["accessToken"], expect=200, label="viewer sets password")
VO = vo["accessToken"]
call("GET", "/admin/payments", token=VO, expect=200, label="viewer can see payments")
call("GET", "/admin/adverts", token=VO, expect=200, label="viewer can see adverts")
call("POST", f"/admin/payments/{pay['id']}/refund", {"reason": "x"}, VO, expect=403, label="viewer can't refund")
call("POST", f"/admin/drivers/{DRIVER_ID}/approve", token=VO, expect=403, label="viewer can't approve drivers")
call("GET", "/admin/staff", token=VO, expect=403, label="viewer can't see staff")
_, sl = call("GET", "/admin/staff", token=ADMIN, expect=200, label="staff list")
check("staff list has the viewer", any(x["email"] == f"ro{RUN}@schooldrop.bw" for x in sl), sl)
_, rs = call("POST", f"/admin/staff/{viewer['id']}/reset-password", token=ADMIN, expect=200, label="reset staff password")
call("GET", "/admin/payments", token=VO, expect=401, label="reset signs the staff member out")
check("reset issues a new temporary password", rs["temporaryPassword"] and rs["passwordChangeRequired"], rs)
call("POST", f"/admin/users/{viewer['id']}/disable", {"reason": "left"}, V, expect=403, label="non-super staff can't disable staff")
call("POST", f"/admin/users/{viewer['id']}/disable", {"reason": "Left the company"}, ADMIN, expect=200, label="super admin disables staff")

# ─── Driver teams / transport companies ────────────────────────────────────
def upload(token, t, extra=None, label=None):
    raw, h = multipart({"type": t, **(extra or {})}, PNG if t in ("SELFIE", "VEHICLE_PHOTO") else PDF, t.lower())
    return call("POST", "/driver/documents", token=token, raw=raw, headers=h, expect=201, label=label or f"upload {t}")[1]

def personal_docs(token, drives=True):
    for t, extra in [("OMANG_FRONT", {}), ("OMANG_BACK", {}), ("SELFIE", {}),
                     ("POLICE_CLEARANCE", {"issuedOn": str(today - timedelta(days=5)), "referenceNumber": "BPS-T-" + RUN})]:
        upload(token, t, extra)
    if drives:
        upload(token, "DRIVERS_LICENCE", {"expiresOn": str(today + timedelta(days=700))})

def approve_all(driver_id, label):
    _, full = call("GET", f"/admin/drivers/{driver_id}", token=ADMIN, expect=200, label=f"admin opens {label}")
    for doc in full["documents"]:
        if doc["status"] == "PENDING":
            call("POST", f"/admin/documents/{doc['id']}/review", {"approve": True}, ADMIN, expect=200)
    _, ap = call("POST", f"/admin/drivers/{driver_id}/approve", token=ADMIN, expect=200, label=f"approve {label}")
    return ap

team_name = f"Safe Rides {RUN}"
OWNER = signup("DRIVER", f"owner{RUN}@x.bw", {"firstName": "Thato", "lastName": "Owner", "omangNumber": "222333444",
                                              "team": {"name": team_name, "kind": "COMPANY", "ownerDrives": False}},
               label="company owner", expect_onboarding=400)
_, own = call("PUT", "/onboarding/driver", {"firstName": "Thato", "lastName": "Owner", "omangNumber": "222333444",
                                            "team": {"name": team_name, "kind": "COMPANY", "registrationNumber": "BW00012345",
                                                     "ownerDrives": False}}, OWNER, expect=200,
              label="company needs its CIPA number, then onboarding works")
check("owner of the company", own["teamRole"] == "OWNER" and own["teamName"] == team_name, own)
_, oprof = call("GET", "/driver/profile", token=OWNER, expect=200, label="owner checklist")
miss = " ".join(oprof["missingRequirements"])
check("non-driving owner: CIPA certificate needed, no licence", "company registration" in miss and "licence" not in miss
      and oprof["team"]["role"] == "OWNER" and oprof["drives"] is False and oprof["ownsBusiness"], oprof["missingRequirements"])
call("PUT", "/driver/profile", {"dateOfBirth": "1980-02-02", "payoutMethod": "BANK_TRANSFER", "payoutAccount": "FNB 62000000001"},
     OWNER, expect=200, label="owner sets company payout account")
call("PUT", "/driver/schools", {"schoolIds": [SCHOOL_ID]}, OWNER, expect=200, label="company picks schools")
_, fleet = call("POST", "/driver/vehicles", {"make": "Toyota", "model": "Quantum", "year": 2021, "colour": "Silver",
                                            "plateNumber": f"B {RUN[:3]} SRF", "type": "MINIBUS", "seatCapacity": 14},
                OWNER, expect=201, label="add fleet vehicle")
for t, extra in [("VEHICLE_REGISTRATION", {}), ("VEHICLE_PHOTO", {}),
                 ("ROADWORTHY_CERTIFICATE", {"expiresOn": str(today + timedelta(days=300))}),
                 ("INSURANCE", {"expiresOn": str(today + timedelta(days=300))})]:
    upload(OWNER, t, {"vehicleId": fleet["id"], **extra})
personal_docs(OWNER, drives=False)
upload(OWNER, "COMPANY_REGISTRATION", {"referenceNumber": "BW00012345"})
_, osub = call("POST", "/driver/submit", token=OWNER, expect=200, label="company owner submits")
OWNER_ID = osub["id"]

_, tinv = call("POST", "/driver/team/invites", {"role": "DRIVER", "name": "Kgosi"}, OWNER, expect=201, label="owner invites a driver")
_, td = call("POST", "/auth/register", {"accountType": "DRIVER", "email": f"teamdrv{RUN}@x.bw",
                                        "phone": "+2677" + str(random.randint(1000000, 9999999)), "password": "Passw0rd-123"},
             expect=201, label="team driver signs up (email, phone, password only)")
TD = td["accessToken"]
_, tdme = call("PUT", "/onboarding/join", {"firstName": "Kgosi", "lastName": "Driver", "code": tinv["code"]}, TD,
               expect=200, label="team driver joins with the code")
check("team driver role", tdme["teamRole"] == "DRIVER" and tdme["teamName"] == team_name, tdme)
_, tdp = call("GET", "/driver/profile", token=TD, expect=200, label="team driver checklist")
miss = " ".join(tdp["missingRequirements"])
check("team driver needs no vehicle, payout or schools", "vehicle" not in miss and "payout" not in miss and "school" not in miss
      and tdp["vehicles"] and tdp["vehicles"][0]["id"] == fleet["id"], tdp["missingRequirements"])
call("PUT", "/driver/profile", {"omangNumber": "555666777", "dateOfBirth": "1990-03-03", "licenceNumber": "L-T-1",
                                "licenceCode": "C1"}, TD, expect=200, label="team driver fills identity")
call("PUT", "/driver/profile", {"payoutMethod": "ORANGE_MONEY", "payoutAccount": "+26770000000"}, TD, expect=400,
     label="team drivers can't redirect payouts")
call("POST", "/driver/vehicles", {"make": "x", "model": "y", "plateNumber": f"B {RUN[:3]} ZZZ", "type": "SEDAN",
                                  "seatCapacity": 3}, TD, expect=403, label="team drivers don't manage vehicles")
personal_docs(TD)
_, tds = call("POST", "/driver/submit", token=TD, expect=200, label="team driver submits")
TD_ID = tds["id"]
_, tq = call("GET", "/admin/drivers?status=UNDER_REVIEW", token=ADMIN, expect=200, label="queue shows the team")
check("admin sees team membership", any(x["id"] == TD_ID and team_name in (x["team"] or "") for x in tq["content"]), tq)

approve_all(OWNER_ID, "company owner")
approve_all(TD_ID, "team driver")

_, tsub = call("GET", "/driver/subscription", token=OWNER, expect=200, label="company fee")
check("fee is per driver (owner doesn't drive)", tsub["drivers"] == 1 and float(tsub["monthlyFee"]) == 50.0 and tsub["canPay"], tsub)
_, tdsub = call("GET", "/driver/subscription", token=TD, expect=200, label="team driver sees the team's fee")
check("team driver can't pay, sees team", tdsub["canPay"] is False and tdsub["teamName"] == team_name, tdsub)
call("POST", "/driver/subscription/pay", {"method": "CARD"}, TD, expect=403, label="team driver can't pay the fee")
_, tp = call("POST", "/driver/subscription/pay", {"method": "CARD"}, OWNER, expect=200, label="owner pays the team's fee")
call("POST", f"/payments/sandbox/{tp['providerReference']}/complete", token=OWNER, expect=204, label="team fee paid")

route = {"vehicleId": fleet["id"], "schoolId": SCHOOL_ID, "coverage": "BOTH", "capacity": 10, "serviceArea": "Mogoditshane",
         "pricePerMonth": 550}
call("POST", "/driver/offers", route, OWNER, expect=400, label="non-driving owner must pick a driver")
call("POST", "/driver/offers", route, TD, expect=403, label="team drivers don't create routes")
_, toffer = call("POST", "/driver/offers", {**route, "driverId": TD_ID}, OWNER, expect=201, label="owner assigns route to team driver")
check("route driven by team driver", toffer["driver"]["id"] == TD_ID and toffer["driver"]["companyName"] == team_name, toffer["driver"])
_, tdo = call("GET", "/driver/offers", token=TD, expect=200, label="team driver sees their route")
check("team driver's route listed", any(o["id"] == toffer["id"] for o in tdo), tdo)
call("POST", "/driver/offers", {**route, "capacity": 5, "driverId": TD_ID, "coverage": "MORNING"}, OWNER, expect=400,
     label="fleet seats can't be oversold")

TP = signup("PARENT", f"tparent{RUN}@x.bw", {"firstName": "Mpho", "lastName": "Parent"})
_, tc = call("POST", "/parent/children", {"firstName": "Lone", "lastName": "Parent", "schoolId": SCHOOL_ID}, TP, expect=201,
             label="parent adds child")
call("POST", f"/school/children/{tc['id']}/confirm", token=SCHOOL, expect=200, label="school confirms child")
_, tof = call("GET", f"/parent/children/{tc['id']}/offers", token=TP, expect=200, label="parent sees company routes")
check("company's route offered with its name", any(o["id"] == toffer["id"] and o["driver"]["companyName"] == team_name for o in tof), tof)
_, tb = call("POST", "/parent/bookings", {"offerId": toffer["id"], "childId": tc["id"], "plan": "MONTHLY", "month": month,
                                          "pickupAddress": "Plot 9"}, TP, expect=201, label="book the company's route")
_, tpay = call("POST", f"/parent/bookings/{tb['id']}/pay", {"method": "CARD"}, TP, expect=200, label="pay the company route")
call("POST", f"/payments/sandbox/{tpay['providerReference']}/complete", token=TP, expect=204, label="payment completes")

_, obs = call("GET", "/billing/summary", token=OWNER, expect=200, label="company billing")
check("company earns the team's money", float(obs["driver"]["grossAllTime"]) == 550.0 and float(obs["driver"]["listingFeesAllTime"]) == 50.0, obs["driver"])
call("GET", "/billing/summary", token=TD, expect=403, label="team driver has no billing")
_, tacc = call("GET", "/account", token=TD, expect=200, label="team driver account")
check("team driver account has no billing", tacc["hasBilling"] is False, tacc)
_, otx = call("GET", "/billing/transactions", token=OWNER, expect=200, label="company transactions")
check("company history says which driver earned it", any("Kgosi" in t["subtitle"] for t in otx if t["kind"] == "EARNING"), otx[:2])
_, ob_ = call("GET", "/driver/bookings", token=OWNER, expect=200, label="owner sees the team's passengers")
check("owner sees booking on team driver's route", any(b["id"] == tb["id"] for b in ob_), ob_)
_, ttrips = call("GET", "/driver/team/trips", token=OWNER, expect=200, label="owner sees today's team runs")
call("GET", "/driver/team/trips", token=TD, expect=403, label="team drivers don't see the whole team's runs")

# A manager who also drives, to hand the route over
_, minv = call("POST", "/driver/team/invites", {"role": "MANAGER", "name": "Neo"}, OWNER, expect=201, label="owner invites a manager")
call("POST", "/driver/team/invites", {"role": "MANAGER"}, TD, expect=403, label="drivers can't invite")
MG = signup("DRIVER", f"mgr{RUN}@x.bw", {"firstName": "x", "lastName": "y"})
call("POST", "/driver/team/join", {"code": minv["code"]}, MG, expect=204, label="existing driver account joins as manager")
call("PUT", "/driver/profile", {"omangNumber": "777888999", "dateOfBirth": "1985-04-04", "licenceNumber": "L-M-1",
                                "licenceCode": "B"}, MG, expect=200)
personal_docs(MG)
_, mgs = call("POST", "/driver/submit", token=MG, expect=200, label="manager submits")
approve_all(mgs["id"], "manager")
_, tsub = call("GET", "/driver/subscription", token=OWNER, expect=200, label="fee after a second driver")
check("fee now covers 2 drivers", tsub["drivers"] == 2 and float(tsub["monthlyFee"]) == 100.0, tsub)

call("DELETE", f"/driver/team/members/{TD_ID}", token=OWNER, expect=409, label="can't remove a driver who has routes")
_, moved = call("PUT", f"/driver/offers/{toffer['id']}", {**route, "driverId": mgs["id"]}, MG, expect=200,
                label="manager hands the route to themselves")
check("route moved", moved["driver"]["id"] == mgs["id"], moved["driver"])
_, tpn = call("GET", "/notifications", token=TP, expect=200, label="family told about the new driver")
check("family notified of new driver", any(n["title"] == "New driver" for n in tpn["content"]), [n["title"] for n in tpn["content"]][:5])
call("DELETE", f"/driver/team/members/{TD_ID}", token=OWNER, expect=204, label="owner removes the team driver")
call("GET", "/driver/profile", token=TD, expect=401, label="removed driver is signed out")
_, td2 = call("POST", "/auth/login", {"email": f"teamdrv{RUN}@x.bw", "password": "Passw0rd-123"}, expect=200, label="removed driver signs in again")
_, tdp = call("GET", "/driver/profile", token=td2["accessToken"], expect=200, label="removed driver's profile")
check("now driving on their own, earnings stayed with the company", tdp["team"] is None and tdp["ownsBusiness"], tdp["team"])
_, obs = call("GET", "/billing/summary", token=OWNER, expect=200, label="company billing after removal")
check("company keeps the earnings", float(obs["driver"]["grossAllTime"]) == 550.0, obs["driver"])
call("POST", "/driver/team/leave", token=OWNER, expect=400, label="owner can't leave their own company")

print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
