-- Seat reservation schema.
--
-- Design notes that matter more than the DDL:
--
--  * A seat is one physical row per (show_id, label), created once when the show is
--    created and never inserted again. Nothing in the service can insert a seat, so a
--    duplicate seat is unrepresentable rather than merely prevented.
--
--  * Seat acquisition is a single conditional UPDATE guarded on current state. There is
--    no SELECT-then-UPDATE anywhere; the row lock is taken by the UPDATE itself, which
--    is race-free at plain READ COMMITTED.
--
--  * Hold expiry is evaluated lazily in that UPDATE's predicate
--    (status='HELD' AND hold_expires_at <= now()), so correctness never depends on a
--    sweeper having run. The sweeper only keeps gauges tidy.
--
--  * user_show_locks deliberately stores no count. It is a mutex row; the authoritative
--    live-seat count for a user is read from `seats` inside that lock, so lazy expiry
--    cannot drift it out of agreement with the reconciliation invariant.
--
--  * Lock order is global and acyclic:
--      idempotency_keys (user-scoped) -> user_show_locks (user-scoped) -> seats (label ASC)
--    Cross-user transactions can only collide on seats, and always climb them in the same
--    direction, so no lock cycle is constructible.

-- ---------------------------------------------------------------------------
-- identity
-- ---------------------------------------------------------------------------
CREATE TABLE app_users (
    id         UUID        PRIMARY KEY,
    handle     TEXT        NOT NULL UNIQUE,
    role       TEXT        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- runtime-tunable policy
--
-- Policy knobs live here so they can be changed with an UPDATE instead of a redeploy.
-- They are read into an in-memory snapshot (refreshed on a schedule) and never queried
-- from the request path. Infrastructure knobs (pool size, timeouts, DSN) stay in env
-- vars, because changing those rebuilds the pool, which is a restart in all but name.
-- ---------------------------------------------------------------------------
CREATE TABLE service_config (
    key         TEXT        PRIMARY KEY,
    value       TEXT        NOT NULL,
    description TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_config (key, value, description) VALUES
    ('reservation.per_user_limit',        '4',
     'Max seats one user may hold or have confirmed for a single show. Global default; a show may override via shows.per_user_limit.'),
    ('reservation.hold_ttl_seconds',      '120',
     'Lifetime of a HELD seat before it lazily expires back to AVAILABLE.'),
    ('reservation.max_seats_per_request', '10',
     'Cap on seats in one reserve call. Bounds how many row locks a single transaction can hold.'),
    ('expiry.sweeper_interval_seconds',   '15',
     'How often expired holds are swept. Cosmetic only: expiry is enforced lazily in the acquisition predicate.'),
    ('idempotency.retention_hours',       '24',
     'How long completed idempotency keys remain replayable.');

-- ---------------------------------------------------------------------------
-- shows and seats
-- ---------------------------------------------------------------------------
CREATE TABLE shows (
    id             UUID        PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    -- NULL means "use reservation.per_user_limit from service_config".
    per_user_limit INT         CHECK (per_user_limit > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    show_id         UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    label           TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'AVAILABLE'
                                CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    held_by         UUID        REFERENCES app_users (id),
    hold_expires_at TIMESTAMPTZ,
    reservation_id  UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (show_id, label),

    -- An incoherent seat row (held by nobody, confirmed with no reservation, available
    -- but still carrying an owner) is rejected by the database rather than trusted to
    -- application discipline.
    CONSTRAINT seats_state_coherent CHECK (
        (status = 'AVAILABLE'
            AND held_by IS NULL AND hold_expires_at IS NULL AND reservation_id IS NULL)
     OR (status = 'HELD'
            AND held_by IS NOT NULL AND hold_expires_at IS NOT NULL AND reservation_id IS NOT NULL)
     OR (status = 'CONFIRMED'
            AND held_by IS NOT NULL AND hold_expires_at IS NULL AND reservation_id IS NOT NULL)
    )
);

-- Supports the per-user live-seat count taken inside the quota mutex.
CREATE INDEX seats_show_holder ON seats (show_id, held_by) WHERE held_by IS NOT NULL;

-- Supports the expiry sweeper.
CREATE INDEX seats_expiring ON seats (hold_expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------
-- reservations
-- ---------------------------------------------------------------------------
CREATE TABLE reservations (
    id           UUID        PRIMARY KEY,
    show_id      UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES app_users (id),
    status       TEXT        NOT NULL
                             CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    -- Integer minor units (paise). Never a float.
    amount_paise BIGINT      NOT NULL CHECK (amount_paise >= 0),
    seat_count   INT         NOT NULL CHECK (seat_count > 0),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX reservations_user_show ON reservations (user_id, show_id);

-- Added after `reservations` exists. This FK is why the reserve transaction inserts the
-- reservation row *before* touching seats: the new reservation is uncontended, so doing
-- it first costs nothing and buys referential integrity on the hot path.
ALTER TABLE seats
    ADD CONSTRAINT seats_reservation_fk
    FOREIGN KEY (reservation_id) REFERENCES reservations (id);

CREATE TABLE reservation_seats (
    reservation_id UUID        NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    show_id        UUID        NOT NULL,
    label          TEXT        NOT NULL,
    released_at    TIMESTAMPTZ,

    PRIMARY KEY (reservation_id, label),
    FOREIGN KEY (show_id, label) REFERENCES seats (show_id, label) ON DELETE CASCADE
);

-- Defense in depth. The conditional UPDATE already makes a double-sell impossible; this
-- index means that even if that logic were wrong, Postgres would refuse the second
-- active claim on a seat. Violations surface as 23505 and map to the same clean 409.
CREATE UNIQUE INDEX reservation_seats_one_active
    ON reservation_seats (show_id, label) WHERE released_at IS NULL;

-- ---------------------------------------------------------------------------
-- per-user quota mutex
--
-- Holds no count on purpose. Taken FOR UPDATE before any seat row, which serialises a
-- single user's concurrent reserves for a show (so the per-user limit holds under
-- concurrency) without creating any cross-user contention.
-- ---------------------------------------------------------------------------
CREATE TABLE user_show_locks (
    user_id UUID NOT NULL REFERENCES app_users (id),
    show_id UUID NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, show_id)
);

-- ---------------------------------------------------------------------------
-- idempotency
--
-- Scoped per user, so two users reusing the same key string never collide. The key row
-- is written in the SAME transaction as the reservation, which is what makes the
-- guarantee exactly-once rather than usually-once.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    user_id         UUID        NOT NULL REFERENCES app_users (id),
    idem_key        TEXT        NOT NULL,
    -- Hash of the canonical request (show + sorted seats). A matching key with a
    -- different hash is a client bug and is rejected with 409.
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT,
    response_body   JSONB,
    reservation_id  UUID        REFERENCES reservations (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, idem_key)
);

CREATE INDEX idempotency_keys_created ON idempotency_keys (created_at);
