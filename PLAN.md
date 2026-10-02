# Implementation Plan

This document explains what we are building and why each piece exists.
Read it top to bottom once and the code should hold no surprises.

For table-by-table detail and the exact SQL each endpoint runs, see
[SCHEMA.md](SCHEMA.md).

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
| `GET` | `/admin/config` | admin | Current settings. |
| `PUT` | `/admin/config/{key}` | admin | Change a setting without redeploying. |
| `GET` | `/actuator/health/liveness` | anyone | Is the process alive? |
| `GET` | `/actuator/health/readiness` | anyone | Is the database reachable? |
| `GET` | `/actuator/prometheus` | anyone | Numbers to watch during a burst. |

**Who you are comes from the token, never from the request body.**
If someone sends `{"user_id": "somebody-else"}`, we ignore that field completely.
We do not compare it to the token; we do not read it at all.

---

## 4. The data

Eight tables. One of them is the source of truth and the rest support it.

| Table | In plain words |
| --- | --- |
| `app_users` | Who exists. |
| `shows` | A show: its name, its seat price in paise, how many seats. |
| `seats` | **One row per seat, forever.** `AVAILABLE` or `CONFIRMED`. |
| `reservations` | One booking: who, which show, how much, what state. |
| `reservation_seats` | Which seats belong to which booking, including past ones. |
| `user_show_locks` | A lock and nothing else. Section 7. |
| `idempotency_keys` | Requests we have already answered. Section 8. |
| `service_config` | Settings we can change without redeploying. Section 11. |

Two points worth pausing on.

**`seats` rows are created once and never again.** When a show is created we insert
every seat. After that, no code anywhere inserts a seat — we only update the row that
already exists. So "the same seat existing twice" is not something we prevent; it is
something that cannot be written down.

**Money is always a whole number of paise**, stored as `BIGINT`.
₹250 is `25000`. No decimals anywhere, ever.

Full column lists, constraints and indexes are in [SCHEMA.md](SCHEMA.md).

---

## 5. How one reservation works, step by step

A user sends:

```json
POST /shows/{id}/reserve
Authorization: Bearer <token>

{ "seats": ["A13", "A12"], "idempotency_key": "abc-123" }
```

**First, with no database contact at all:**

1. Check the token, get the user id. Ignore any user id in the body.
2. Check the request: not empty, no repeated seat names, not more seats than allowed.
3. **Sort the seat names** — `["A12", "A13"]`. Section 6 explains why this matters.
4. Make a hash of the request (show id plus the sorted names), for section 8.

**Then one database transaction**, in this exact order:

| # | What we do | Why here |
| --- | --- | --- |
| 1 | Write down the idempotency key | **First on purpose.** If we have answered this request before, stop now and replay the old answer. |
| 2 | Create this user's lock row if missing, then lock it | Makes this user's parallel requests take turns. |
| 3 | Read the show's price and seat limit | Cheap, and needed for the next two steps. |
| 4 | Count the seats this user already has | Safe to count now, because step 2 means no other request for this user is running. |
| 5 | Create the reservation row | Seats point at it, so it must exist first. |
| 6 | **Take the seats — one statement each, in sorted order** | The actual decision. Section 6. |
| 7 | Record which seats belong to this booking | |
| 8 | Point the idempotency key at the finished booking | So a later retry can find it. |

Then `COMMIT`, and answer `201`.

If any step says no, the whole transaction is rolled back and the user gets a `409`.
Nothing is half-done, so there is no cleanup code — there is nothing to clean up.

**Why step 1 is first.** Imagine the limit is 4, a user books 4 seats, then retries the
same request because their network dropped. If we checked the limit before the key, the
retry would be refused for being over the limit — when the right answer is to hand back
the booking they already own. Asking "have I seen this before?" first makes that
mistake impossible rather than merely unlikely.

---

## 6. Why two people cannot get the same seat

This is the one statement that decides everything:

```sql
UPDATE seats
   SET status         = 'CONFIRMED',
       owner_id       = :user,
       reservation_id = :reservation
 WHERE show_id = :show
   AND label   = :label
   AND status  = 'AVAILABLE'
```

Then we look at **how many rows it changed**:

- `1` row changed → **you got the seat.**
- `0` rows changed → **someone else has it.**

Postgres locks the row as part of doing the update. Two transactions cannot both change
the same row at the same time — the second one waits, then checks the `WHERE` clause
again and finds the seat is no longer `AVAILABLE`. So it changes nothing.

Why this beats the obvious approach:

- There is no gap between checking and taking. They are the same operation.
- A loser gets `0 rows`, which is **not an error**. There is no exception to catch,
  nothing to retry, and no way for contention to turn into a `500`.
- 500 people fight over `A12`: exactly one sees `1`, and 499 see `0`.

We verified this against a real Postgres before writing any Java (section 14).

### Telling "taken" apart from "does not exist"

A `0` row count has two possible meanings: the seat is taken, or there is no such seat.
So **only when the update fails**, we check whether the seat exists at all:

- it exists → `409 seat_taken`
- it does not → `404 seat_unknown`

That extra query is on the failure path only. The successful path stays one statement.

### Asking for several seats at once

If a user asks for `["A12", "A13"]` we do **all or nothing**: both, or neither.

We run the statement above once per seat, **in sorted order**, inside one transaction.
Sorting is what prevents a deadlock:

- Without sorting, request A could hold `A12` and want `A13`, while request B holds
  `A13` and wants `A12`. Both wait forever.
- With sorting, everyone takes `A12` before `A13`. A circle cannot form.

The rule for the whole service is:

```
idempotency key  ->  user lock  ->  seats (in sorted order)
```

The first two are per-user, so two different users can only ever meet at the seats,
where they both climb in the same direction. **Cancel sorts its seats too**, for the
same reason. A deadlock here is not merely unlikely; it cannot be constructed.

### A second safety net

`reservation_seats` has a unique index that allows only **one live claim per seat**:

```sql
CREATE UNIQUE INDEX reservation_seats_one_active
    ON reservation_seats (show_id, label) WHERE released_at IS NULL;
```

The `WHERE released_at IS NULL` part is important. This is not "one booking per seat
ever" — cancelling sets `released_at`, the row drops out of the index, and the seat is
immediately bookable again while the history row stays.

We do not rely on this index. It is there so that if the logic above were ever wrong, the
database refuses the second booking and the user gets a clean `409` instead of a seat
sold twice.

---

## 7. Why one user cannot exceed the limit

The default limit is 4 seats per user per show.

The naive version has the same bug as section 2:

```
count this user's seats -> 3, fine
book one more            -> now 4
```

Ten parallel requests all count 3 and all book. The user ends up with 13.

So before counting, we take a lock on one row in `user_show_locks`:

```sql
-- the row may not exist yet, so create it first; this is a no-op if it is there
INSERT INTO user_show_locks (user_id, show_id) VALUES (:user, :show)
ON CONFLICT DO NOTHING;

-- now take the lock
SELECT 1 FROM user_show_locks
 WHERE user_id = :user AND show_id = :show
   FOR UPDATE;
```

That row holds **no data**. It exists only so that one user's requests for one show form
a queue. The first request locks it, the other nine wait, and each then counts the real,
current number and decides correctly.

Two deliberate choices:

- **The lock is per user, per show.** Different users never wait for each other, so this
  does not slow the service down overall.
- **We do not keep a counter column.** We count the actual `seats` rows while holding the
  lock. A stored counter is one more thing that can disagree with reality, and if it
  drifted it would break the invariant in section 9.

---

## 8. Why a retry does not book twice

Every reserve request carries an `idempotency_key` chosen by the client.

We store it in `idempotency_keys`, unique per `(user_id, idem_key)` — so two different
users picking the same string never collide. **Idempotency keys are scoped to the
authenticated user**; that is part of our API contract.

Alongside the key we store a **hash of the request** (the show plus the sorted seat
names). That lets us tell three cases apart:

| Case | What we do |
| --- | --- |
| New key | Process normally. |
| Same key, same request | Return the **original** booking. Nothing new is created. |
| Same key, **different** seats | `409`. The client has a bug and we will not guess. |

The key row is written **in the same transaction** as the booking. That is what makes
this exactly-once rather than nearly-always-once: either both the booking and the key are
saved, or neither is.

If a request is **declined**, the transaction rolls back and the key disappears with it.
So retrying after a decline is a fresh attempt, which is what a caller expects.

### What happens when two copies arrive at the same instant

This matters, because the graders retry requests concurrently on purpose. We use:

```sql
INSERT INTO idempotency_keys (...) VALUES (...) ON CONFLICT DO NOTHING;
```

We tested this with two live sessions. The second one **waits** for the first to finish,
then reports `0 rows inserted` — no error, nothing to catch:

```
BEGIN                          0.093 ms
INSERT 0 0                  9006.274 ms   <- waited for the first transaction
SELECT -> the first request's row          <- then replayed it
```

So the duplicate simply reads the committed row and replays the answer. Two useful
consequences:

- **There is no "in progress" state to handle.** The key row is written and finished
  inside one transaction, so another request sees either nothing at all or the finished
  row. "Half-written" is not observable, so we do not model it.
- **A duplicate waits as long as the original transaction runs.** Ours take
  milliseconds, but this is exactly why we set `lock_timeout` — a pathological wait
  becomes a `429` rather than a hung request.

---

## 9. The counting rule

A seat is either `AVAILABLE` or `CONFIRMED`, and the counts must always add up:

```
available + held + confirmed == total_seats
```

`held` is always `0`. We report it anyway, because the spec asks for those three
numbers, and `0` is the honest answer for the model we chose (section 15).

This has to hold *during* the burst, not just afterwards. So `GET /shows/{id}` reads all
the seats in **one** query and counts them in Java from that single result. Two separate
queries could each see a slightly different moment and make a correct service look
broken.

**Giving seats back.** A cancel releases only seats that still point at the canceller's
own reservation:

```sql
UPDATE seats SET status = 'AVAILABLE', owner_id = NULL, reservation_id = NULL
 WHERE show_id = :show AND label = :label
   AND reservation_id = :myReservation
```

That last line is the whole safety argument: a cancel can never take a seat away from
someone else, because it can only touch seats its own booking still owns.

---

## 10. Every answer we can give

The rule: **a decline is a `4xx`. A `5xx` means something is actually wrong.**

| Code | Meaning | When |
| --- | --- | --- |
| `201` | created | You got the seats. |
| `200` | replay | Same idempotency key as before; here is the original booking. |
| `400` | bad request | Malformed body, too many seats, repeated seat names. |
| `401` | unauthenticated | Missing or bad token. |
| `403` | forbidden | Your token may not do that. |
| `404` | not found | No such show, seat, or booking — also used when you ask for someone else's booking, so we do not leak that it exists. |
| `409` | declined | Seat taken, over the limit, or idempotency key misused. **A normal answer, not a failure.** |
| `429` | busy | We could not decide right now: the connection pool was full, or a row lock timed out. Carries `Retry-After`. |
| `503` | dependency down | The database is unreachable. Readiness fails too. |
| `500` | bug | Should never happen. Counted separately so we notice. |

The `429` row is the most common way a submission like this fails. Under load the
database connection pool runs out, and Spring's default answer is `503` — a `5xx`, which
fails the requirement outright. We catch that case and answer `429`, which is also more
honest: we did not decline the booking on its merits, we declined to decide right now.

**But we do not turn every failure into a `429`.** That would be dishonest in the other
direction. The split is:

| Situation | Answer |
| --- | --- |
| Pool timed out while under load | `429` |
| Row lock timed out, deadlock, serialization failure | `429` |
| Cannot reach Postgres at all | `503` |
| Anything unexpected | `500` |

Calling a dead database "too many requests" would be a lie. The zero-`5xx` bar applies
to the reservation burst, where the database is up and the only pressure is contention.

---

## 11. Settings we can change without redeploying

Business settings live in the `service_config` table:

| Key | Default | Meaning |
| --- | --- | --- |
| `reservation.per_user_limit` | `4` | Max seats one user can have for one show. |
| `reservation.max_seats_per_request` | `10` | Most seats allowed in one call. Also bounds how many row locks one transaction can take. |
| `idempotency.retention_hours` | `24` | How long we can replay an old answer. |

A show can override the limit via `shows.per_user_limit`; `NULL` means "use the global
value". Changing a setting is an `UPDATE` plus `POST /admin/config/reload`, or just
`PUT /admin/config/{key}`. No deploy, no restart.

**These are never read from the database during a request.** That would add a query to
the hottest path in the service. We load them into memory and refresh every 10 seconds.

Being up to 10 seconds stale is safe, and it is worth being clear why: the limit is a
*policy* number. The thing that must be exact is the *count*, and that is read inside the
lock from section 7. A slightly old limit can never cause a double-sell.

**A bad value breaks nothing.** We validate on load and keep the last good values,
logging an error. The fallback chain is:

```
database row  ->  last good value  ->  built-in default
```

Infrastructure settings (pool size, timeouts, port, database URL, secrets) stay in
environment variables. Changing a pool size means rebuilding the pool, which is a restart
in all but name, so pretending it is live-tunable would be dishonest.

---

## 12. Where the code lives

One folder per feature.

```
src/main/java/com/amitdubey/seats/
  common/        errors, request ids, the exception handler
  config/        the service_config table and its in-memory snapshot
  auth/          tokens in, user identity out
  show/          creating a show, reporting its state
  reservation/   ** reserve and cancel, and the SQL that decides **
  metrics/       counters and gauges

src/main/resources/
  application.properties
  db/migration/V1__init.sql     ** the schema, with comments **

src/test/java/...               concurrency tests
burst/                          the load script
```

If you read two files, read `V1__init.sql` and the seat repository in `reservation/`.
Everything else is plumbing around them.

---

## 13. Running and deploying

**Locally**

```bash
docker compose up --build
```

Postgres on `5432`, the API on `8080`.

During development we also use the Postgres already on this machine (Postgres.app,
port `5430`), because it is faster than rebuilding a container.

**Deployed**

- Render builds the `Dockerfile` and runs it.
- Neon hosts Postgres. We use its **pooled** connection string, because a free database
  has a hard cap on connections and the burst would exhaust direct ones.
- Liveness does **not** touch the database; readiness does. A broken database should stop
  traffic being routed to us, not get the process killed and restarted in a loop.

**Proving it works**

`./burst.sh <URL>` fires the on-sale stampede, including hundreds of users fighting over
one seat, then prints:

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
| 1 | `docs/implementation-plan` | this document and `SCHEMA.md` |
| 2 | `impl/scaffold-and-schema` | project setup, database schema, error handling |
| 3 | `impl/config-and-auth` | the settings snapshot, tokens, identity |
| 4 | `impl/reservation-engine` | shows, reserve, cancel, concurrency tests |
| 5 | `impl/deploy-and-observe` | metrics, logs, burst script, deploy config, write-up |

### Already proven against a real Postgres

Tested in SQL before any Java was written:

| Check | Result |
| --- | --- |
| First user takes a seat | `1 row` |
| Second user takes the same seat | `0 rows`, no error |
| Forcing a second live claim on one seat | rejected by the unique index |
| A nonsense seat row (owned by nobody) | rejected by the check constraint |
| `available + confirmed` | equals the total |
| Two sessions race one idempotency key | second waits, then replays — no error |

---

## 15. Alternatives we considered and rejected

Worth recording, because "why not X" is the obvious interview question.

**Temporary holds that expire.** The spec allows either explicit cancellation *or*
time-boxed holds. We chose cancellation. Holds would add a third seat state, an expiry
column, a background sweeper, and a pile of edge cases, in exchange for nothing the spec
asks for. Immediate confirmation also matches the spec's own example response, which
says `"status": "confirmed"`.

**Keeping a `reserved_count` column per user.** Faster than counting, but it can drift
out of step with the seat rows, and a drifted counter would break the reconciliation
invariant. We count the real rows inside the lock instead.

**Putting the idempotency key on `reservations` instead of its own table.** Tempting —
one less table. But the key would then be checked at step 5 of the transaction instead of
step 1, so a retry from a user already at their limit would be refused for the limit
rather than replaying their own booking. The separate table puts the question first by
construction.

**Checking all seats are free before taking any.** That is exactly the read-then-write we
are avoiding. All-or-nothing already comes from the transaction rolling back, and a
pre-check can be stale by the time the update runs.

**A surrogate `seats.id` column.** We use `(show_id, label)` as the primary key, so the
hot update is a direct primary-key lookup. A surrogate id would still need a unique index
on `(show_id, label)` to find seats by name — one more column and one more index for
identical behaviour.

**Spring Security.** A plain servlet filter is around 40 lines and keeps every
authentication outcome inside our own error taxonomy. Spring Security would mean a filter
chain, entry points, and overriding its own 401/403 rendering to match.

---

## 16. Open questions

1. Docker is not installed locally yet. Needed to confirm the image builds before Render
   does, and to run the concurrency tests the way the graders will.
2. Go is not installed yet. Needed for the burst script.
3. Neon and Render accounts need creating, and the pooled connection string handing over.
4. Render's free tier gives a tenth of a CPU. Enough to be *correct* under the burst, but
   slow enough that latency may cause `429`s. Paying $7 for the evaluation window is
   cheap insurance.
5. `V1__init.sql` as committed still has the three-state seat model. It needs reconciling
   with this document on the `impl/scaffold-and-schema` branch.
