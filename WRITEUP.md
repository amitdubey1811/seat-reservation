# Write-up

Live: https://seat-reservation-gmpc.onrender.com · Burst: `./burst.sh <URL>` · 77 tests

---

## 1. The atomic decision

Acquisition is a single conditional `UPDATE`, and the decision is its rows-affected count:

```sql
UPDATE seats
   SET status = 'CONFIRMED', owner_id = :user, reservation_id = :reservation
 WHERE show_id = :show AND label = :label
   AND status  = 'AVAILABLE'
```

`1` row changed means this caller won the seat. `0` means someone else has it.

**Why it is race-free.** Postgres takes the row lock as part of performing the update, so
there is no window between checking and taking — they are the same operation. A second
transaction targeting the same row waits for the first to commit, then re-evaluates the
`WHERE` clause against the committed tuple, finds the seat is no longer `AVAILABLE`, and
changes nothing. This holds at plain `READ COMMITTED` with no explicit locking, no retry
loop, and no serializable isolation.

**Why `0` rows matters more than `1`.** A losing caller gets a row count, not an exception.
There is nothing to catch and nothing to retry, which is the structural reason contention in
this service cannot become a `5xx`. 300 buyers storm one seat: one sees `1`, and 299 see `0`.

**Multi-seat, and why it cannot deadlock.** All-or-nothing: one statement per seat, in one
transaction, with labels **sorted** first. Sorting is what makes the acquisition order
identical for every request in the service. Without it, request A could hold `A12` and want
`A13` while B holds `A13` and wants `A12`; with it, everyone climbs in the same direction and
a cycle cannot form. The full order is:

```
idempotency key  →  user lock  →  seats (ascending by label)
   (per user)        (per user)         (shared)
```

The first two are per-user, so two different buyers can only ever meet on seat rows.
Cancellation sorts its seats too. This is not "deadlocks are unlikely" — it is that a lock
cycle cannot be constructed.

**A second line of defence.** `reservation_seats` carries a partial unique index on
`(show_id, label) WHERE released_at IS NULL` — one *live* claim per seat, which still permits
cancel-then-rebook because cancelling sets `released_at` and the row leaves the index. If the
conditional `UPDATE` were ever wrong, Postgres refuses the second claim and the caller gets a
clean `409`.

I tested that backstop by deliberately removing `AND status = 'AVAILABLE'`. Result:
`{201=1, 500=299}` — **no seat was sold twice**, the index caught every double-claim. That
exposed two things: my error mapping turned the violation into a `500` instead of a `409`
(fixed), and once fixed, the suite no longer detected the mutation at all, because correct
outcomes arrived either way. So `seat_backstop_fired_total` now exists and the storm test
asserts it stays zero. That counter is the single most valuable alarm here: non-zero means
the primary logic is broken and only the database is preventing a double-sell.

---

## 2. Per-user limit

The naive version has the same shape of bug: count, then insert. Ten parallel requests each
count 3 and each insert.

Before counting, the transaction takes a row in `user_show_locks` with `SELECT … FOR UPDATE`.
That row holds **no data** — being locked is its entire purpose. One user's concurrent
requests for one show queue behind it; different users never wait on each other.

Deliberately **no counter column**. The count is taken from the `seats` table itself while
the lock is held. A stored counter is a second copy of the truth, and a drifted one would
break the reconciliation invariant — the thing the whole design exists to protect.

Proven by removing the lock: a limit-4 buyer ended up with **5 seats**.

---

## 3. Idempotency

**Where the key lives.** Its own table, `(user_id, idem_key)` as the primary key — scoped per
user, so two buyers may choose the same string. Alongside it, a SHA-256 of the canonical
request: show id plus the **sorted** seat labels, so `[A1,A2]` and `[A2,A1]` are recognised as
the same request.

**How exactly-once is enforced.** The key row is written in the *same transaction* as the
booking. Either both commit or neither does. That is the whole mechanism — there is no
second system to keep in step.

**The claim is the first statement of the transaction**, before any lock or count:

```sql
INSERT INTO idempotency_keys (user_id, idem_key, request_hash, created_at)
VALUES (…) ON CONFLICT DO NOTHING
```

Order matters here. If the key check came after the limit check, a retry from a buyer already
at their limit would be declined *for the limit* instead of replaying the booking they already
own — the idempotency requirement and the limit requirement would collide, and the limit would
win. Asking "have I answered this?" first makes that impossible by construction.

**Concurrent duplicates.** Verified with two live psql sessions: the second
`INSERT … ON CONFLICT DO NOTHING` **waits** for the first transaction to resolve, then reports
zero rows — no `23505`, nothing to catch.

```
BEGIN                          0.093 ms
INSERT 0 0                  9006.274 ms   ← waited for the first transaction
SELECT -> the first request's row          ← then replayed it
```

If the first committed, the row is visible and is replayed. If it rolled back, this caller
inserts and proceeds. **This is also why there is no "in progress" state**: the key row and
its reservation are written in one transaction, so a half-written key is not observable, and
modelling it would be modelling something that cannot happen.

**Same key, different body** → `409 idempotency_key_reuse`. The client has a bug and we will
not guess which request it meant.

**A declined request rolls its key back with it**, so retrying after a decline is a fresh
attempt — which is what a caller expects.

**No stored response body.** A replay follows `reservation_id` and rebuilds the answer from
the booking, so there is one source of truth and a replay reflects the booking's current
state rather than a stale snapshot.

---

## 4. Holds and expiry

The spec allows either explicit cancellation **or** time-boxed holds. I chose cancellation.

A seat is `AVAILABLE` or `CONFIRMED`. There is no third state, no expiry column, no sweeper,
and no clock to reason about. `held` is reported as `0` because the spec asks for three
numbers and zero is the honest answer for this model.

Cancellation is owner-only and idempotent. The release is guarded on the reservation id:

```sql
UPDATE seats SET status='AVAILABLE', owner_id=NULL, reservation_id=NULL
 WHERE show_id=:show AND label=:label AND reservation_id = :myReservation
```

That last predicate is the entire safety argument: a release can only touch seats its own
booking still owns, so it is structurally incapable of freeing a seat since confirmed to
somebody else. Tested: cancel → someone else rebooks → the original owner cancels again →
their seat is untouched.

**What I would add with more time:** a `HELD` state with a TTL, expiry evaluated *lazily in
the acquisition predicate* (`status='HELD' AND hold_expires_at <= now()`) rather than by a
sweeper, so correctness never depends on a background job having run. The sweeper would exist
only to keep gauges tidy.

---

## 5. Consistency versus availability under a partition

This service chooses **consistency**, and it is not a close call: the failure modes are not
symmetric. Refusing to sell a seat costs a sale. Selling one seat twice costs a seat somebody
has already paid for and planned around.

Concretely, when Postgres is unreachable:

- Readiness fails, and Render stops routing traffic to the instance.
- Requests that do arrive get `503`, not a guess.
- Nothing is queued for later reconciliation, because reconciling a double-sold seat after the
  fact means telling a customer they no longer have their seat.

Within a single Postgres there is no partition to tolerate — that is much of why a single
database is the right choice at this size. The honest limit is that the database is a single
point of failure, which I would address with a replica and failover rather than by weakening
the guarantee.

A related judgement: **saturation is a `429`, an unreachable database is a `503`.** Dressing a
dead dependency up as "too many requests" would be a lie, and the two call for different
responses — one is wait and retry, the other is stop sending traffic.

---

## 6. Observability — what I would be paged for at 2am

Three counters, all of which should read zero. Any of them going non-zero is worth waking up
for; nothing else here is.

| Alarm | What it means |
| --- | --- |
| **`seat_backstop_fired_total`** | The unique index refused a seat the conditional `UPDATE` believed it had won. The primary logic is broken and only Postgres is preventing a double-sell. **This is the page.** |
| **`reconciliation_mismatch_total`** | `available + confirmed` no longer equals the show's seat count. Seats have been lost or duplicated. |
| **`app_unexpected_errors_total`** | Any `5xx`. Declines are `4xx` by design, so this counts only defects. |

Deliberately **not** pages: `reservations_declined_total{reason="seat_taken"}` spiking is a
popular show, and `app_requests_shed_total` rising is the service protecting itself. Both are
dashboard lines.

`seats_available{show_id}` is published as a gauge **read from the database**, not accumulated
in memory. An in-memory counter drifts the moment a process restarts or a transaction rolls
back, and then the metrics quietly contradict the API. The cost is staleness bounded by the
refresh interval; briefly behind is recoverable, permanently wrong is not.

Logs are JSON with `request_id` as a top-level field, so one request can be pulled out of a
burst of twenty thousand. The id is accepted from an inbound `X-Request-Id` and echoed back.

**What the burst actually found.** On its first run against the deployed service it produced
**47 × `HTTP 500`**, and the metrics named the cause immediately:

```
app_unexpected_errors_by_type_total{type="CannotCreateTransactionException"}  47
hikaricp_connections_timeout_total                                           52
app_requests_shed_total                                                       0   ← the 429 path never fired
```

The connection pool was timing out under load — exactly the case the `429`/`503` split was
built for. But `JpaTransactionManager` wraps a failed connection acquisition in
`CannotCreateTransactionException`, which extends `TransactionException`, **not**
`DataAccessException`, so it bypassed the handler written for it and fell through to the
catch-all. No unit test could have caught this; nothing in the suite exhausts a pool. It took
real load on a small instance. Fixed, with `PoolExhaustionTest` reproducing it in seconds —
and I confirmed that test catches the bug by reverting the fix (21 × `500`) before restoring it.

That episode is the honest answer to "is it observable": the instrumentation identified an
unknown production defect in under a minute, by exception type and by the counter that *should*
have incremented and didn't.

---

## 7. AI usage — directed versus decided

> **This section is mine to own, and I have tried to be exact about which decisions were mine
> and which were the tool's.** Please read it as a description of how the work was actually
> done.

I used Claude Code throughout, as the assignment invites. What that meant in practice:

**Decisions I directed.** The stack (Java 21, Spring Boot, PostgreSQL). Using JPA/Hibernate
rather than plain JDBC — the tool argued for JDBC on the grounds that an ORM hides the
statement that decides the race, and I overruled it because JPA is what I work in and will be
asked to extend live. The package layout: I moved it from layered to feature-sliced per entity
mid-way, and had the convention written into `CLAUDE.md` so it stayed consistent. Putting the
policy limits in a `service_config` database table so they can change without a redeploy — a
design review suggested removing it as over-engineering, and I kept it. `application.properties`
over YAML. Deploying to Render with Neon, and the decision not to pay for a larger instance.

**Decisions the tool made that I accepted after reading the argument.** Sorting seat labels
before acquiring them, with the deadlock-impossibility argument that follows from it. Making
`user_show_locks` a pure mutex with no counter column. Claiming the idempotency key as the
first statement of the transaction rather than a later one. Splitting `429` from `503` instead
of mapping every infrastructure failure to one of them. Reading the seat gauges from the
database rather than accumulating them in memory.

**Where the tool was wrong, and how I know.** Several times, and the useful pattern is that
verification caught it rather than review:

- It documented the unique-index backstop as producing "a clean 409". It produced a `500`.
  Only mutation-testing the acquisition guard revealed that.
- Its first metrics gauges collided with Prometheus's `_total` counter-suffix convention and
  were **silently dropped** — no error anywhere. Only comparing the gauges against the API
  surfaced it.
- Its burst script had three bugs of its own, including one that crashed `make burst`
  outright on macOS's default bash, and one that reported "idempotency is broken" when the
  real cause was that another phase had taken the seat.
- A test it wrote depended on an asynchronous config refresh and was quietly flaky.

**What I would say about the collaboration.** The valuable part was not code generation — it
was that every claim got tested rather than asserted. The conditional `UPDATE` was verified in
raw SQL before any Java existed. `ddl-auto=validate` was proven to fire by deliberately
breaking an entity. The readiness probe was proven to fail closed by killing a TCP proxy in
front of the database mid-flight. The concurrency guarantees were proven by removing each
mechanism and watching the suite fail. I would not have run that many negative tests by hand,
and most of the real bugs in this repository were found by them.

**The depth is mine.** I can explain any statement in `SeatRepository`, why the lock order is
what it is, and why a `0` row count is better than an exception — because those are the parts I
pushed back on, redirected, and had re-verified.

---

## 8. What I would do next

**Before anything else — remove the free-tier cold start.** On Render's free plan the instance
sleeps after ~15 minutes and Neon scales to zero alongside it; the first request can take 2–3
minutes. It comes up healthy, but that is a poor first impression. A scheduled ping every 10
minutes fixes it for free; a paid instance fixes it properly.

**Shorten the hot path from nine statements to seven.** The lock upsert and its `FOR UPDATE`
collapse into one `INSERT … ON CONFLICT DO UPDATE` (the no-op update takes the row lock), and
the final key-attach disappears if the reservation is inserted before the key. Both are
recorded in `SCHEMA.md` as considered-and-not-taken, because clarity was worth more than two
round trips until it is measured not to be.

**Holds with lazy expiry**, as described in §4.

**A read replica for `GET /shows/{id}`.** It is the only endpoint that would be hammered
during an on-sale and the only one that tolerates slight staleness.

**Partial-fulfilment as an explicit option.** Today multi-seat is all-or-nothing. "Give me any
3 together" is the request people actually want, and it is a different algorithm — adjacency
search under contention — not a tweak.

**Load-shedding before the pool, not at it.** Right now saturation surfaces as a pool timeout
translated to `429`. A small admission-control queue with a bounded wait would make that a
deliberate decision rather than a symptom.

**Archive old idempotency keys.** `idempotency.retention_hours` exists and is honoured on read,
but nothing deletes expired rows yet.
