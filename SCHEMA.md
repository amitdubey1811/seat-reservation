# Schema and Data Flow

Every table, what writes it, and the exact order of statements each endpoint runs.

[PLAN.md](PLAN.md) explains *why* the design is this shape. This document is the
*what* and *when*.

> This describes the target schema. `V1__init.sql` as committed still carries the
> earlier three-state seat model and needs reconciling with this document.

---

## Reading order

If you read nothing else, read [§4 `seats`](#4-seats--the-source-of-truth) and
[the reserve flow](#reserve--post-showsidreserve). Those two are the system.

---

# Part 1 — The tables

## 1. `app_users` — who exists

```sql
CREATE TABLE app_users (
    id         UUID        PRIMARY KEY,
    handle     TEXT        NOT NULL UNIQUE,
    role       TEXT        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

| | |
| --- | --- |
| **Written by** | `POST /auth/token` only, as find-or-create |
| **Read by** | `POST /auth/token` |
| **Not read by** | **reserve** — the user id comes from the token, so the hot path never looks a user up |

`id` becomes the JWT subject. `role` gates `POST /shows`.

## 2. `service_config` — runtime-tunable policy

```sql
CREATE TABLE service_config (
    key         TEXT        PRIMARY KEY,
    value       TEXT        NOT NULL,
    description TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Seeded by the migration, so a clean checkout needs no setup:

| key | value |
| --- | --- |
| `reservation.per_user_limit` | `4` |
| `reservation.max_seats_per_request` | `10` |
| `idempotency.retention_hours` | `24` |

| | |
| --- | --- |
| **Written by** | `PUT /admin/config/{key}` |
| **Read by** | a background refresh every 10 seconds, into an in-memory snapshot |
| **Not read by** | any request — that would add a query to the hottest path |

## 3. `shows` — the event

```sql
CREATE TABLE shows (
    id             UUID        PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    per_user_limit INT         CHECK (per_user_limit > 0),  -- NULL = use global config
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

| | |
| --- | --- |
| **Written by** | `POST /shows`, once |
| **Read by** | reserve (price and limit), `GET /shows/{id}` |

`total_seats` is the right-hand side of the reconciliation invariant.
`price_paise` is integer minor units — ₹250 is `25000`.

## 4. `seats` — the source of truth

```sql
CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    label          TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'AVAILABLE'
                               CHECK (status IN ('AVAILABLE', 'CONFIRMED')),
    owner_id       UUID        REFERENCES app_users (id),
    reservation_id UUID,  -- FK added after `reservations` exists
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (show_id, label),

    CONSTRAINT seats_state_coherent CHECK (
        (status = 'AVAILABLE' AND owner_id IS NULL     AND reservation_id IS NULL)
     OR (status = 'CONFIRMED' AND owner_id IS NOT NULL AND reservation_id IS NOT NULL)
    )
);

CREATE INDEX seats_show_owner ON seats (show_id, owner_id) WHERE owner_id IS NOT NULL;
```

| | |
| --- | --- |
| **Inserted by** | `POST /shows` — **once, and never again** |
| **Updated by** | reserve (take) and cancel (release) |
| **Read by** | `GET /shows/{id}`, the per-user count, failure disambiguation |

Three design points:

- **`(show_id, label)` is the primary key**, so the hot update is a direct primary-key
  lookup with no secondary index to maintain.
- **No code inserts a seat after show creation.** A duplicate seat is not prevented, it
  is unrepresentable.
- **`seats_state_coherent`** means the database rejects a nonsense row — available but
  still owned, or confirmed with no booking — rather than trusting application
  discipline. If it ever fires, we have a bug and we want to know loudly.

`seats_show_owner` is a partial index supporting the per-user count in reserve.

## 5. `reservations` — one booking

```sql
CREATE TABLE reservations (
    id           UUID        PRIMARY KEY,
    show_id      UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES app_users (id),
    status       TEXT        NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    amount_paise BIGINT      NOT NULL CHECK (amount_paise >= 0),
    seat_count   INT         NOT NULL CHECK (seat_count > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX reservations_user_show ON reservations (user_id, show_id);

-- Why the seats FK is added here: reserve inserts the reservation before touching
-- seats, and the new reservation row is uncontended, so doing it first costs nothing
-- and buys referential integrity on the hot path.
ALTER TABLE seats
    ADD CONSTRAINT seats_reservation_fk
    FOREIGN KEY (reservation_id) REFERENCES reservations (id);
```

| | |
| --- | --- |
| **Written by** | reserve (insert), cancel (status change) |
| **Read by** | `GET /reservations/{id}`, idempotent replay |
| **Never** | deleted — cancel is a state change, so history survives |

`amount_paise` is computed server-side as `price_paise × seat_count`. The client never
sends an amount.

## 6. `reservation_seats` — which seats belong to which booking

```sql
CREATE TABLE reservation_seats (
    reservation_id UUID        NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    show_id        UUID        NOT NULL,
    label          TEXT        NOT NULL,
    released_at    TIMESTAMPTZ,            -- NULL means this claim is live

    PRIMARY KEY (reservation_id, label),
    FOREIGN KEY (show_id, label) REFERENCES seats (show_id, label) ON DELETE CASCADE
);

CREATE UNIQUE INDEX reservation_seats_one_active
    ON reservation_seats (show_id, label) WHERE released_at IS NULL;
```

| | |
| --- | --- |
| **Written by** | reserve (insert), cancel (sets `released_at`) |
| **Read by** | reporting a booking's seats |

**The `WHERE released_at IS NULL` predicate is the whole point.** This is *not* "one
booking per seat for all time" — that would make a seat unbookable after its first
cancellation. Cancel sets `released_at`, the row leaves the index, the seat is
immediately bookable again, and the history row stays.

This index is defense in depth. The conditional update in reserve already makes a
double-sell impossible; this means that even if that logic were wrong, Postgres refuses
the second live claim and the caller gets a clean `409` rather than a seat sold twice.

## 7. `user_show_locks` — a mutex, nothing else

```sql
CREATE TABLE user_show_locks (
    user_id UUID NOT NULL REFERENCES app_users (id),
    show_id UUID NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, show_id)
);
```

**No data columns at all.** Its only purpose is to be locked, so one user's parallel
reserves for one show take turns. Different users never contend on it.

Deliberately *not* a counter column: a counter is one more thing that can disagree with
the seat rows, and a disagreement would break the reconciliation invariant.

## 8. `idempotency_keys` — have I answered this already?

```sql
CREATE TABLE idempotency_keys (
    user_id        UUID        NOT NULL REFERENCES app_users (id),
    idem_key       TEXT        NOT NULL,
    request_hash   TEXT        NOT NULL,   -- sha256(show_id | sorted seat labels)
    reservation_id UUID        REFERENCES reservations (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, idem_key)
);

CREATE INDEX idempotency_keys_created ON idempotency_keys (created_at);
```

| | |
| --- | --- |
| **Written by** | reserve — insert at step 1, updated with `reservation_id` at the end |
| **Read by** | reserve, on a duplicate key |

- **`PRIMARY KEY (user_id, idem_key)`** — keys are scoped to the authenticated user, so
  two users can pick the same string. This is part of the API contract.
- **`request_hash`** is what distinguishes "honest retry" from "same key, different
  seats", which must be a `409`.
- **There is no `status` column.** The row is written and completed inside one
  transaction, so another session sees either nothing or the finished row.
  "Half-written" is not observable, so we do not model it.
- **No stored response body.** On replay we follow `reservation_id` and rebuild the
  answer from the booking. One source of truth, and a replay reflects the booking's
  current state rather than a stale snapshot.

---

# Part 2 — What each request does, in order

## Lock order, globally

Every transaction that takes more than one lock takes them in this order:

```
idempotency_keys  ->  user_show_locks  ->  seats (ascending by label)
     (per user)          (per user)              (shared)
```

The first two are per-user, so two different users can only ever meet at the seats,
where they both climb in the same direction. **Cancel sorts its seats too.** A lock
cycle cannot be constructed.

Every reserve and cancel transaction also sets:

```sql
SET LOCAL lock_timeout = '1s';
```

so a pathological wait becomes a `429` instead of a hung request.

## reserve — `POST /shows/{id}/reserve`

```json
Authorization: Bearer <token>
{ "seats": ["A13", "A12"], "idempotency_key": "abc-123" }
```

### Before any SQL

| Step | Action |
| --- | --- |
| 1 | Verify the JWT → `user_id`. **Any `user_id` in the body is not read at all.** |
| 2 | Validate: non-empty, no repeated labels, `count ≤ max_seats_per_request` (in-memory snapshot) |
| 3 | **Sort the labels ascending** → `["A12", "A13"]` |
| 4 | `request_hash = sha256(show_id | "A12,A13")` |

### Then one transaction

| # | Statement | Branches |
| --- | --- | --- |
| 1 | `INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (…) ON CONFLICT DO NOTHING` | `1` → new, continue. `0` → duplicate, go to 1a |
| 1a | `SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id=? AND idem_key=?` | hash differs → **409 `idempotency_key_reuse`**. hash matches → load that reservation, **200 replay** |
| 2 | `INSERT INTO user_show_locks (user_id, show_id) VALUES (…) ON CONFLICT DO NOTHING` | creates the mutex row if this is the user's first request for the show |
| 3 | `SELECT 1 FROM user_show_locks WHERE user_id=? AND show_id=? FOR UPDATE` | **the mutex.** This user's other requests queue here |
| 4 | `SELECT price_paise, per_user_limit FROM shows WHERE id=?` | no row → **404 `show_not_found`** |
| 5 | `SELECT count(*) FROM seats WHERE show_id=? AND owner_id=? AND status='CONFIRMED'` | `count + n > limit` → **409 `per_user_limit`** |
| 6 | `INSERT INTO reservations (id, show_id, user_id, status, amount_paise, seat_count) VALUES (…, 'CONFIRMED', price × n, n)` | |
| 7 | **`UPDATE seats SET status='CONFIRMED', owner_id=?, reservation_id=? WHERE show_id=? AND label=? AND status='AVAILABLE'`** — once per label, in sorted order | `1` → won this seat. `0` → go to 7a |
| 7a | `SELECT 1 FROM seats WHERE show_id=? AND label=?` | exists → **409 `seat_taken`**. missing → **404 `seat_unknown`** |
| 8 | `INSERT INTO reservation_seats (reservation_id, show_id, label)` — one row per seat | `23505` here → **409 `seat_taken`** (the backstop fired) |
| 9 | `UPDATE idempotency_keys SET reservation_id=? WHERE user_id=? AND idem_key=?` | ties the key to the booking |

`COMMIT` → **201**.

Any decline rolls the entire transaction back. Nothing is half-written, so there is no
compensating logic anywhere in the service.

**Why step 1 is first.** If the key check came after step 5, a retry from a user already
at their limit would be declined for the limit instead of replaying the booking they
already own — the idempotency requirement and the limit requirement would collide, and
the limit would win. Asking "have I seen this?" first makes that impossible by
construction.

**Step 7 is the whole system.** `1` row changed means you won; `0` means someone else
has it. The loser gets no error, so contention can never become a `5xx`.

### Happy path cost, one seat

9 statements. Only `seats` is contended between different users.

## cancel — `POST /reservations/{id}/cancel`

| # | Statement | Branches |
| --- | --- | --- |
| 1 | `SELECT user_id, status, show_id FROM reservations WHERE id=? FOR UPDATE` | no row, **or `user_id` ≠ token user** → **404**. Not 403 — we do not leak that someone else's booking exists |
| 2 | already `CANCELLED` → return **200** | cancel is idempotent |
| 3 | `UPDATE seats SET status='AVAILABLE', owner_id=NULL, reservation_id=NULL WHERE show_id=? AND label=? AND reservation_id=?` — per label, **sorted** | the `reservation_id=?` guard is the safety argument |
| 4 | `UPDATE reservation_seats SET released_at=now() WHERE reservation_id=? AND released_at IS NULL` | drops out of the unique index → seats re-bookable |
| 5 | `UPDATE reservations SET status='CANCELLED', updated_at=now() WHERE id=?` | |

`COMMIT` → **200**.

Step 3's `reservation_id = :myReservation` is why a release can never resurrect a seat
already confirmed to someone else: it can only touch seats its own booking still owns.

## show state — `GET /shows/{id}`

Reads only, no writes.

| Mode | Statements |
| --- | --- |
| default | `SELECT price_paise, total_seats FROM shows WHERE id=?` then `SELECT label, status FROM seats WHERE show_id=? ORDER BY label`. **Counts are computed in Java from that one result set** |
| `?seats=false` | one `SELECT count(*) FILTER (WHERE status='AVAILABLE') AS available, count(*) FILTER (WHERE status='CONFIRMED') AS confirmed FROM seats WHERE show_id=?` |

Counting from the same rows we return is what makes the seat list and the counts
describe the same instant. Two separate queries could each see a different moment and
make a correct service look like it had broken its invariant mid-burst.

`held` is reported as `0`. The spec asks for three numbers; with explicit cancellation
and no timed holds, `0` is the honest answer.

## create show — `POST /shows` (admin)

| # | Statement |
| --- | --- |
| 1 | validate: labels non-empty, no duplicates, `price_paise ≥ 0` |
| 2 | `INSERT INTO shows (id, name, price_paise, total_seats, per_user_limit)` |
| 3 | **one batched multi-row insert** of every seat |

Step 3 must be batched. A 20,000-seat hall cannot be 20,000 round trips.

## token — `POST /auth/token`

| # | Statement |
| --- | --- |
| 1 | `INSERT INTO app_users (id, handle, role) VALUES (…) ON CONFLICT (handle) DO NOTHING` |
| 2 | `SELECT id, role FROM app_users WHERE handle=?` |
| 3 | mint the JWT in memory — no database |

Our stand-in for an identity provider. Documented as such: the point is that identity
reaches the rest of the service **only** through a signed token.

## admin config

| Endpoint | Statements |
| --- | --- |
| `GET /admin/config` | in-memory snapshot, no SQL |
| `PUT /admin/config/{key}` | `UPDATE service_config SET value=?, updated_at=now() WHERE key=?` then refresh the snapshot |
| `POST /admin/config/reload` | `SELECT key, value FROM service_config` into the snapshot |

---

# Part 3 — The write matrix

| | `app_users` | `shows` | `seats` | `reservations` | `reservation_seats` | `user_show_locks` | `idempotency_keys` | `service_config` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `POST /auth/token` | **insert** | | | | | | | |
| `POST /shows` | | **insert** | **insert** | | | | | |
| `POST …/reserve` | | read | **update** | **insert** | **insert** | **insert + lock** | **insert + update** | |
| `POST …/cancel` | | | **update** | **update** | **update** | | | |
| `GET /shows/{id}` | | read | read | | | | | |
| `GET /reservations/{id}` | | | | read | read | | | |
| `PUT /admin/config/{key}` | | | | | | | | **update** |

Only two tables are written on the contended path, and only `seats` is contended
*between* users.

---

# Possible optimisations, not yet taken

Recorded so the choice is visible rather than accidental.

| Change | Saves | Why not yet |
| --- | --- | --- |
| Collapse steps 2 and 3 into `INSERT … ON CONFLICT (user_id, show_id) DO UPDATE SET show_id = user_show_locks.show_id` — the no-op update takes the row lock | 1 statement | Less obvious to read. The two-statement form says exactly what it does |
| Insert the reservation before the idempotency key, making `reservation_id` `NOT NULL` and dropping step 9 | 1 statement, 1 nullable column | Means inserting a booking before we know we will win, then rolling it back on replay. Reads oddly |
| Merge steps 4 and 5 into one join | 1 round trip | Marginal, and it mixes two unrelated questions in one query |

Each would shave a round trip off the hot path. Worth revisiting if latency on Render's
free tier turns out to matter; not worth the loss of clarity before then.
