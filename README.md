# SchoolDrop API

School transport for Botswana. Parents book vetted drivers to take their children to and from school, drivers get paid, schools see who is collecting which child, and the SchoolDrop team runs everything from an admin panel.

Spring Boot 4 · Java 25 · PostgreSQL · Flyway. The Flutter app (mobile + web) is [schooldrop-app](https://github.com/GimbleBDub/schooldrop-app). The project started under the working name BusStop, which is why the Java package is `com.gimble.bus_stop`.

How it's built and why: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Open work: [issues](https://github.com/GimbleBDub/schooldrop-be/issues).

## Who does what

| Side | Where | Can do |
|---|---|---|
| **Parent / family** | app + web | A parent account is a family: the owner, guardians (e.g. a partner) and helpers (e.g. a nanny) invited by code. Owners and guardians add children, book drivers (per trip / monthly / termly), pay in-app or directly to the driver, request once-off pickups. Everyone confirms handovers and follows trips live |
| **Driver** | app | Onboard (profile, Omang, licence, police clearance, vehicles, schools), pay the P50 monthly listing fee, create offers, advertise, confirm cash/EFT payments, accept once-off pickups, run trips (pickup, drop-off with who received the child, live location), see earnings |
| **School** | web (mostly) | Admins confirm enrollments and invite staff; any staff member (teacher, gate, office) releases children to their authorised driver and confirms arrivals |
| **Kid** | app (optional) | Paired by the parent with a one-time code; sees own trips, confirms pickup/arrival, SOS |
| **Admin staff** | web only | Dashboard, verify schools and drivers (document review), review adverts, users, bookings, payments, refunds, payouts, incidents, school terms, audit log |

Staff have permissions: `SUPER_ADMIN` (everything), `VERIFIER`, `SUPPORT`, `FINANCE`.

## Run locally

Requirements: Docker, and JDK 25 if you run the API outside Docker.

```bash
docker compose up -d        # Postgres 17 (port 5435) + MinIO (9004, console 9005)
./mvnw spring-boot:run      # profile "local"; API on http://localhost:8097/api
```

Or run everything in containers, no JDK needed:

```bash
docker compose --profile app up -d --build
```

* Swagger UI: http://localhost:8097/api/swagger-ui.html · health: http://localhost:8097/api/actuator/health
* MinIO console (uploaded driver documents): http://localhost:9005 (minioadmin / minioadmin)
* `src/main/resources/application-local.yaml` holds throwaway defaults for this stack; override any of them with env vars.
* Start over with an empty database: `docker compose down -v`.

On an empty database the local profile creates demo accounts so every side can be tried immediately:

| Who | Email | Password |
|---|---|---|
| Admin (super admin) | admin@schooldrop.local | ChangeMe-Local-123 |
| Parent, family owner (Neo already rides this month, Lesedi can be booked) | parent@schooldrop.local | Demo-Pass-123 |
| Helper in the same family (trips + handovers only) | helper@schooldrop.local | Demo-Pass-123 |
| Driver (approved, has today's trips) | driver@schooldrop.local | Demo-Pass-123 |
| School admin (Demo Primary School) | school@schooldrop.local | Demo-Pass-123 |
| Teacher at the same school (staff) | teacher@schooldrop.local | Demo-Pass-123 |

Payments use the sandbox gateway: the app shows "Simulate success" while paying.

Point the Flutter app at the API: web `flutter run -d chrome` works on any localhost port; Android emulator `--dart-define=API_BASE_URL=http://10.0.2.2:8097/api/v1`; a phone on your Wi-Fi `http://<your-lan-ip>:8097/api/v1`.

## Tests

```bash
./mvnw verify
```

Unit tests plus integration tests (`*IT`) against Testcontainers Postgres 17 with the real migrations — only Docker is needed. CI (`.github/workflows/ci.yml`) runs the same and builds the image.

`python3 scripts/e2e.py local-only-webhook-secret` walks every flow against a running local server (150 checks: onboarding, verification, booking, a race for the last seat, payments, trips, kid pairing, school gate, once-off pickups, payouts, RBAC, audit). It creates its own users, so run it on a database you can throw away.

## Deploy

Build the image (`docker build -t schooldrop-api .`) and run it with the variables in [.env.example](.env.example) (`SPRING_PROFILES_ACTIVE=prod`). The prod profile has no defaults: a missing secret stops startup. Back up `CRYPTO_KEY` — without it, Omang/licence/payout-account columns can't be read.

## How the main flows work

**Driver vetting.** Driver registers → fills profile, picks schools, adds vehicles, uploads documents (Omang front/back, licence, police clearance ≤ 90 days old, selfie; per vehicle: registration, roadworthy, insurance, photo) → submits → a verifier reviews each document and approves the driver. Only approved drivers with a fully-approved vehicle can publish offers. A nightly job lapses expired documents, pauses that driver's offers and warns 14 days ahead. Police clearance is re-required yearly.

**Schools are added by SchoolDrop.** Schools don't register themselves: staff with Verification or Support add each school (name, address, contact) and pin it on the map (`POST /v1/admin/schools`), then create an invite code for the school's admin (`POST /v1/admin/schools/{id}/invites`). School staff sign up and join with that code; school admins invite their own teachers. A school's admin can change its phone and email only — name, address and pin stay as SchoolDrop set them, because families and drivers rely on them.

**Passwords.** 8–72 characters with an uppercase letter, a lowercase letter, a number and a symbol (`@StrongPassword`), at sign-up and every password change. The app shows the same checklist and asks for the password twice.

**Vehicle colours** come from a list SchoolDrop staff manage (Back office → Vehicle colours; seeded with common colours). Removing a colour hides it from the dropdown without touching vehicles that use it. The vehicle year can't be in the future.

**Driver teams and transport companies.** At onboarding a driver chooses: *I drive on my own*, *I run a company or team* (company name, CIPA number for registered companies, and whether they also drive), or *I drive for a company or team* (invite code). A solo driver can start or join a team later; their vehicles and routes come along.

| Role | Does | Must provide |
|---|---|---|
| Owner | the business: payouts, monthly fee, adverts, billing; plus everything a manager does | Omang, selfie, police clearance, payout account, schools, ≥1 fleet vehicle; licence only if they drive; CIPA certificate for companies |
| Manager | invites drivers, adds fleet vehicles, creates routes and picks the driver, hands today's run to someone else | Omang, selfie, police clearance (+ licence if they drive) |
| Driver | drives the routes and runs they're given | Omang, selfie, police clearance, licence |

Everyone who can see children's details is vetted individually. Fleet vehicles are shared: any team driver can be assigned, and seats on a vehicle can't be oversold across routes. The listing fee is per driver (P50 × drivers), paid by the owner. Each payment records who it belongs to (`payments.payee_id`) when it's made: team earnings go to the owner's payout account and stay with the team if a driver leaves. Handing a route to another driver moves its upcoming runs and tells the families; *Hand to…* on a single run covers a sick day. A driver can't be removed (or leave) while they still drive a route; removal signs them out at once. Suspending an owner pauses all the team's routes; parents only see team routes while the owner's business is verified.

**Booking.** A child must be confirmed by their school. The parent picks an offer at that school and a plan the driver offers; the seat is held for 30 minutes (`PENDING_PAYMENT`) while they pay. The offer row is locked while seats are counted, so the last seat can't be sold twice. A vehicle's seats can't be oversold across a driver's offers on the same leg.

**Payments.** `PaymentGateway` abstracts the provider; `sandbox` is the dev gateway. Results arrive by HMAC-signed webhook (`POST /v1/payments/webhook`, header `X-Signature`), applied idempotently. SchoolDrop's cut (`PLATFORM_FEE_PERCENT`, default 10%) is split at payment time. Late successes on cancelled attempts are refunded automatically. Driver earnings become payable when service starts (once-off: the day after the pickup); a weekly job batches them into payouts that finance sends and marks paid.

**Trips and handovers.** Each school day the driver's trips (to school / from school) are generated from active bookings. Every handover has two sides, recorded with who and when:

| Leg | Step | Driver | Other side confirms |
|---|---|---|---|
| Morning | at home | "Picked up" | anyone in the family ("I handed Neo over") |
| Morning | at school | "Dropped at school" | any school staff ("Arrived") |
| Afternoon | at school | "Collected" | any school staff (release to the authorised driver) |
| Afternoon | at home | "Dropped home, handed to …" (required) | anyone in the family ("Neo is home") |

The first confirmation wins; anyone in the family or school can tap "Not right", which opens an incident and alerts support. Families who haven't confirmed a home drop-off get one reminder after 20 minutes. Collecting a child the school hasn't released is allowed (not every gate uses the app) but recorded and flagged to the family. Kids can still confirm from their own phone.

**Drivers' monthly fee.** P50/month (`DRIVER_MONTHLY_FEE`) to be listed — it funds the platform and leaves a payment trail that deters people who shouldn't be near children. Unpaid drivers are hidden and can't take new bookings or once-off pickups; children already booked keep riding.

**Paying the driver directly.** Parents can choose cash / EFT at booking: the seat is held 48 hours, the parent taps "I've paid", the driver confirms receipt and the booking activates. No platform fee is taken, the payment is never paid out or refunded through SchoolDrop.

**Adverts.** Drivers buy an advert (P150 for 30 days, `ADVERT_PRICE`) shown to parents and school staff at the schools they serve; their offers also show as "Featured". Staff review every advert (rejections are refunded) and can add outside advertisers who paid directly. Placement: one "Sponsored" card on calm screens only — parent home (below trips), children list, school enrollments / profile — never on trip tracking, SOS or the gate list, and never to kids or drivers.

**Profile & settings (every account).** `/v1/account`: edit name; change email, phone or password (the current password is required; email and phone stay unique, and phones are stored in one spelling, `+267…`, so "71 234 567" and "+26771234567" can't become two accounts). An email or password change signs out every other device at once (access tokens issued earlier are rejected, not just refresh tokens) and returns fresh tokens for this one. Signed-in devices are listed by name and can be signed out one by one or all at once. Push settings per category (trips, bookings, payments, news); safety and account-security messages always push, and the in-app inbox always keeps everything. Verified drivers can't rename themselves (their name was checked against their Omang); kids' accounts are read-only.

**Billing & documents.** `/v1/billing` for family owners/guardians and drivers (helpers, school staff and kids have none). Every movement of money issues a numbered document automatically, from a gap-free series per type:

| Document | When | To | Number |
|---|---|---|---|
| Receipt | a family pays for transport (in the app, or directly to the driver and confirmed by them) | the payer; the whole family can see it | `RCT-2026-000001` |
| Invoice | a driver pays SchoolDrop (listing fee, advert) | the driver | `INV-…` |
| Credit note | any refund | whoever got the original | `CRN-…` |
| Payout statement | finance marks a payout sent | the driver: what families paid, SchoolDrop's commission, what was sent | `STM-…` |

Names, amounts and contact details are snapshots, so later profile changes don't rewrite history. PDFs open through a 10-minute signed link (`POST …/invoices/{id}/link`), so a browser or phone share sheet can download them without the app's token. On startup, any money that moved without a document gets one (idempotent). Company details and VAT come from `BILLING_*`: leave `BILLING_VAT_NUMBER` empty until SchoolDrop is VAT registered; then invoices become tax invoices and show the VAT included in SchoolDrop's own fees (listing, adverts, commission). Drivers' transport isn't VAT'd by SchoolDrop.

**Back-office staff.** Super admins add staff (a temporary password is generated and shown once; the person must choose their own before the back office opens), reset passwords (signs them out everywhere), disable accounts and combine permissions: Verification (drivers, documents, schools), Support (users, bookings, incidents, live trips), Finance (payments, refunds, payouts, documents, revenue report), Marketing (adverts), Read-only (see everything except staff and audit, change nothing). The last super admin can't be disabled or demoted. Finance → `GET /v1/admin/finance` shows SchoolDrop's revenue by month: commission + listing fees + adverts − commission on refunds, alongside money collected, paid out and paid to drivers directly.

**Once-off pickups.** Parent posts a request → approved drivers at that school who take ad-hoc work see it at their own fare → first to accept gets it (row-locked) → parent pays → school staff are told who is authorised to collect the child.
