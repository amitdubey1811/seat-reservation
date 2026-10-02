# Implementation Plan

This document explains what we are building and why each piece exists.
Read it top to bottom once and the code should hold no surprises.

---

## 1. What we are building

A web service that sells numbered seats for a show (a concert or a movie hall).

It must never do these three things:

1. Sell the same seat to two people.
2. Let one person hold more seats than allowed.
3. Create two bookings when the same request arrives twice.

It talks JSON over HTTP. There is no UI.

---

## 2. The hard part

A show goes on sale. Thousands of people press "book" in the same second.
Many of them want the same seat, say `A12`.

The obvious code is wrong:

```
step 1: read seat A12   ->  it says "available"
step 2: write seat A12   ->  mark it mine
```

Two people can both finish step 1 before either reaches step 2.
Both believe they won. The seat is sold twice.

**So we never read first and write second.**
The whole decision happens inside one SQL statement. Section 6 shows how.

---

## 3. The API

| Method | Path | Who | What it does |
| --- | --- | --- | --- |
| `POST` | `/auth/token` | anyone | Gives you a token to call the rest. Our stand-in for a login service. |
| `POST` | `/shows` | admin | Creates a show and all of its seats. |
| `GET` | `/shows/{id}` | anyone | Seat-by-seat status, plus counts. |
| `POST` | `/shows/{id}/reserve` | user | Tries to take one or more seats. The important one. |
| `POST` | `/reservations/{id}/cancel` | owner | Gives the seats back. |
| `GET` | `/reservations/{id}` | owner | Shows one booking. |
| `GET` | `/actuator/health/liveness` | anyone | Is the process alive? |
| `GET` | `/actuator/health/readiness` | anyone | Is the database reachable? |
| `GET` | `/actuator/prometheus` | anyone | Numbers to watch during a burst. |

**Who you are comes from the token, never from the request body.**
If someone sends `{"user_id": "somebody-else"}`, we ignore that field completely.
We do not compare it to the token; we do not read it at all.

---

## 4. The database tables

| Table | In plain words |
| --- | --- |
| `app_users` | Who exists. |
| `shows` | A show: its name, its seat price in paise, how many seats. |
| `seats` | **One row per seat, forever.** Its status is `AVAILABLE`, `HELD`, or `CONFIRMED`. |
| `reservations` | One booking: who, which show, how much, what state. |
| `reservation_seats` | Which seats belong to which booking. |
| `user_show_locks` | A lock, nothing more. Explained in section 7. |
| `idempotency_keys` | Remembers requests we have already answered. |
| `service_config` | Settings we can change without redeploying. |

Two of these are worth pausing on.

**`seats` rows are created once and never again.** When a show is created, we insert
every seat. After that, no code anywhere inserts a seat — we only ever update the row
that already exists. So "the same seat existing twice" is not something we prevent;
it is something that cannot be written down.

**Money is always a whole number of paise**, stored as `BIGINT`.
₹250 is `25000`. No decimals anywhere, ever.

---

## 5. How one reservation works, step by step

A user sends:

```json
POST /shows/{id}/reserve
{ "seats": ["A12"], "idempotency_key": "abc-123" }
```

Inside a single database transaction, in this exact order:

| # | Step | Why this order |
| --- | --- | --- |
| 1 | Record the idempotency key | If we have seen this key before, stop here and replay the old answer. |
| 2 | Take this user's lock row | Stops the same user's parallel requests from racing each other. |
| 3 | Count the seats this user already has | Safe to count now, because step 2 means no other request for this user is running. |
| 4 | Create the reservation row | Needed before seats, because seats point at it. |
| 5 | **Take the seats, one statement each, sorted by name** | The actual decision. Section 6. |
| 6 | Mark the idempotency key finished, store the response | Same transaction, so the answer and the booking are saved together or not at all. |

If any step says no, the whole transaction is rolled back and the user gets a `409`.
Nothing is half-done. There is no cleanup code, because there is nothing to clean up.

---

## 6. Why two people cannot get the same seat

This is the one statement that decides everything:

```sql
UPDATE seats
   SET status = 'HELD',
       held_by = :user,
       hold_expires_at = now() + :ttl,
       reservation_id = :reservation
 WHERE show_id = :show
   AND label   = :label
   AND ( status = 'AVAILABLE'
         OR (status = 'HELD' AND hold_expires_at <= now()) )
```

Then we look at **how many rows it changed**:

- `1` row changed → **you got the seat.**
- `0` rows changed → **someone else has it.** Return `409`.

Postgres locks the row as part of doing the update. Two transactions cannot both
change the same row at the same time — the second one waits, then re-checks the
`WHERE` clause and finds the seat no longer matches. So it changes nothing.

Why this is better than the obvious approach:

- There is no gap between checking and taking. They are the same operation.
- A loser gets `0 rows`, which is **not an error**. There is no exception to catch,
  nothing to retry, and no way for contention to become a `500`.
- 500 people fight over `A12`: exactly one sees `1`, and 499 see `0`.

We verified this against a real Postgres before writing any Java (see section 14).

### Asking for several seats at once

If a user asks for `["A12", "A13"]` we do **all or nothing**: both, or neither.

We run the statement above once per seat, **sorted alphabetically**, in one transaction.
Sorting is what prevents a deadlock:

- Without sorting, request A could hold `A12` and want `A13`, while request B holds
  `A13` and wants `A12`. Both wait forever.
- With sorting, everyone always takes `A12` before `A13`. A circle cannot form.

The full ordering rule for the whole transaction is:

```
idempotency key  ->  user lock  ->  seats (sorted by name)
```

The first two are per-user, so two different users can only ever meet at the seats,
where they both climb in the same direction. A deadlock is not merely unlikely here;
it is impossible to construct.

### A second safety net

`reservation_seats` has a unique index allowing only **one active row per seat**.
If the logic above were ever wrong, the database itself would reject the second
booking. We do not rely on this — it is there so a bug becomes a clean `409`
instead of a double-sold seat.

---

## 7. Why one user cannot exceed the limit

The default limit is 4 seats per user per show.

The naive version has the same bug as section 2:

```
count this user's seats -> 3, fine
insert 1 more            -> now 4
```

Ten parallel requests all count 3 and all insert. The user ends up with 13.

So before counting, we take a lock on one row in `user_show_locks`:

```sql
SELECT 1 FROM user_show_locks
 WHERE user_id = :user AND show_id = :show
   FOR UPDATE
```

That row holds **no data**. It exists only so that one user's requests for one show
form a queue. The first request locks it; the other nine wait; each then counts the
real, current number and decides correctly.

Two deliberate choices here:

- **The lock is per user, per show.** Different users never wait for each other, so
  this does not slow the service down overall.
- **We do not store a counter.** We count the actual `seats` rows while holding the
  lock. A stored counter would drift out of step with reality the moment a hold
  expired, and a drifted counter would break the invariant in section 9.

---

## 8. Why a retry does not book twice

Every reserve request carries an `idempotency_key` chosen by the client.

We store it in `idempotency_keys`, unique per `(user, key)` — so two different users
using the same string never collide.

Alongside the key we store a **hash of the request** (the show plus the sorted seat
names). That lets us tell three situations apart:

| Situation | What we do |
| --- | --- |
| New key | Process it normally. |
| Same key, same request, already finished | Return the **original** answer. Nothing new is created. |
| Same key, **different** seats | Return `409`. The client has a bug and we will not guess. |
| Same key, still being processed | Return `409`. One of the two will finish and own the answer. |

The key row is written **in the same transaction** as the booking.
That is what makes this exactly-once rather than nearly-always-once: either both the
booking and the key are saved, or neither is.

If a request is **declined**, the transaction rolls back and the key disappears with
it. So retrying after a decline is a fresh attempt, which is what a caller expects.

---

## 9. Holds, expiry, and the counting rule

A seat can be in exactly one of three states, and they must always add up:

```
available + held + confirmed == total_seats
```

This has to hold *during* the burst, not just afterwards. So `GET /shows/{id}`
computes all three counts in **one** SQL query. Three separate queries could each
see a slightly different moment and make a correct service look broken.

**By default, reserving confirms immediately** — the response says
`"status": "confirmed"`, matching the spec's example.

**Optionally**, a request can ask for a temporary hold instead. The seat becomes
`HELD` with an expiry time, and returns to `AVAILABLE` on its own.

Expiry needs no background job to be correct. Look again at the `WHERE` clause in
section 6:

```sql
status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= now())
```

An expired hold is treated as available **by the same statement that takes seats**.
So a seat becomes bookable the instant it expires, even if nothing has swept it.
There is a sweeper, but it only tidies up the status column so the dashboards look
right. If it never ran, no user would be able to tell.

A cancel can only ever release a seat that still points at the canceller's own
reservation — so cancelling can never take a seat away from someone else.

---

## 10. Every answer we can give

The rule: **a decline is a `4xx`. A `5xx` means we have a bug.**

| Code | Meaning | When |
| --- | --- | --- |
| `201` | created | You got the seats. |
| `200` | replay | Same idempotency key as before; here is the original answer. |
| `400` | bad request | Malformed body, too many seats, duplicate seat names. |
| `401` | unauthenticated | Missing or bad token. |
| `403` | forbidden | Your token may not do that. |
| `404` | not found | No such show, seat, or reservation — also used when you ask for someone else's reservation, so we do not leak that it exists. |
| `409` | declined | Seat taken, over the limit, or idempotency key misused. **This is a normal answer, not a failure.** |
| `429` | busy | We could not decide right now: the connection pool was full, or a row lock timed out. Includes `Retry-After`. |
| `500` | bug | Should never happen. We count these separately so we notice. |

That `429` row deserves a note, because it is the most common way a submission like
this fails. Under heavy load the database connection pool runs out. Spring's default
answer is `503`, which is a `5xx` and would fail the requirement outright. We catch it
and answer `429` instead, which is also simply more honest: we did not decline the
booking on its merits, we declined to decide at that moment.

---

## 11. Settings we can change without redeploying

Business settings live in the `service_config` table:

| Key | Default | Meaning |
| --- | --- | --- |
| `reservation.per_user_limit` | `4` | Max seats one user can hold for one show. |
| `reservation.hold_ttl_seconds` | `120` | How long a temporary hold lasts. |
| `reservation.max_seats_per_request` | `10` | Most seats allowed in one call. |
| `expiry.sweeper_interval_seconds` | `15` | How often the tidy-up job runs. |
| `idempotency.retention_hours` | `24` | How long we can replay an old answer. |

Changing one is an `UPDATE` plus `POST /admin/config/reload`. No deploy, no restart.

**These are never read from the database during a request.** That would add a query
to the hottest path in the service. Instead we load them into memory and refresh
every 10 seconds.

Being up to 10 seconds stale is safe here, and it is worth being clear why: the limit
is a *policy* number. The thing that must be exact is the *count*, and that is read
inside the lock from section 7. A slightly old limit can never cause a double-sell.

**If someone types a bad value, nothing breaks.** We validate on load and keep the
last good values, logging an error. The fallback chain is:

```
database row  ->  last good value  ->  built-in default
```

Infrastructure settings (pool size, timeouts, port, database URL, secrets) stay in
environment variables. Changing a pool size means rebuilding the pool, which is a
restart in all but name, so pretending it is live-tunable would be dishonest.

---

## 12. Where the code lives

One folder per feature. Files you want to understand first are marked.

```
src/main/java/com/amitdubey/seats/
  common/        errors, request ids, the exception handler
  config/        the service_config table and its in-memory snapshot
  auth/          tokens in, user identity out
  show/          creating a show, reporting its state
  reservation/   ** the reserve/cancel logic and the SQL that decides **
  metrics/       counters and gauges

src/main/resources/
  application.properties
  db/migration/V1__init.sql     ** the schema, with comments **

src/test/java/...                concurrency tests
burst/                           the load script
```

If you read two files, read `V1__init.sql` and the seat repository in
`reservation/`. Everything else is plumbing around them.

---

## 13. Running and deploying

**Locally**

```bash
docker compose up --build
```

Postgres on `5432`, the API on `8080`.

During development we also use the Postgres already installed on this machine
(Postgres.app, port `5430`) because it is faster than rebuilding a container.

**Deployed**

- Render builds the `Dockerfile` and runs it.
- Neon hosts Postgres. We use its **pooled** connection string, because a free
  database has a hard cap on connections and the burst would exhaust direct ones.
- Health checks: liveness does **not** touch the database, readiness does. A broken
  database should stop traffic being routed to us, not cause the process to be
  killed and restarted in a loop.

**Proving it works**

`./burst.sh <URL>` fires the on-sale stampede, including hundreds of users fighting
over one seat, then prints:

- how many were confirmed
- how many were declined, grouped by reason
- how many `5xx` there were (must be zero)
- whether `available + held + confirmed` still equals the seat count

---

## 14. Build order

One branch per chunk, each reviewed as a PR, merged with a **merge commit** so the
individual commits stay visible on `main`.

| PR | Branch | Contents |
| --- | --- | --- |
| 1 | `docs/implementation-plan` | this document |
| 2 | `impl/scaffold-and-schema` | project setup, database schema, error handling |
| 3 | `impl/config-and-auth` | the settings snapshot, tokens, identity |
| 4 | `impl/reservation-engine` | shows, reserve, cancel, expiry, concurrency tests |
| 5 | `impl/deploy-and-observe` | metrics, logs, burst script, deploy config, write-up |

### Already done and verified

The schema is applied to a real Postgres and the central mechanism was tested in SQL
directly, before any Java was written:

| Check | Result |
| --- | --- |
| First user takes a seat | `1 row` ✅ |
| Second user takes the same seat | `0 rows`, no error ✅ |
| Forcing a second active booking for one seat | rejected by the unique index ✅ |
| A nonsense seat row (held by nobody) | rejected by the check constraint ✅ |
| An expired hold | taken by the normal statement ✅ |
| `available + held + confirmed` | equals the total ✅ |

---

## 15. Open questions

Things still to decide or confirm:

1. Docker is not installed locally yet. Needed to confirm the image builds before
   Render does, and to run the concurrency tests the way the graders will.
2. Go is not installed yet. Needed for the burst script.
3. Neon and Render accounts need creating, and the pooled connection string handing
   over.
4. Render's free tier gives a tenth of a CPU. That is enough to be *correct* under
   the burst but slow enough that latency may cause `429`s. Paying $7 for the
   evaluation window is cheap insurance.
