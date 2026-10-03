# seat-reservation

A JSON HTTP service that sells assigned seats for an event and stays correct when thousands
of buyers want the same seat at the same moment.

**Live:** https://seat-reservation-gmpc.onrender.com

| | |
| --- | --- |
| [WRITEUP.md](WRITEUP.md) | the design decisions, and what I would do next |
| [PLAN.md](PLAN.md) | how it works, in plain language |
| [SCHEMA.md](SCHEMA.md) | every table, and the exact SQL each endpoint runs |
| [API.md](API.md) | every endpoint as a runnable curl |

---

## The short version

Seat acquisition is a single conditional `UPDATE`, and the answer is how many rows it changed:

```sql
UPDATE seats SET status='CONFIRMED', owner_id=:user, reservation_id=:reservation
 WHERE show_id=:show AND label=:label AND status='AVAILABLE'
```

`1` means you got the seat. `0` means somebody else has it — and **that is not an error**.
There is no exception to catch and nothing to retry, which is why contention in this service
cannot turn into a `5xx`.

---

## Run it

```bash
docker compose up --build
```

API on `8080`, Postgres on `5432`. Both ports are overridable if yours are taken:

```bash
DB_PORT=5433 APP_PORT=8082 docker compose up --build
```

Against a Postgres you already have:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/seats \
DATABASE_USER=seats DATABASE_PASSWORD=seats \
mvn spring-boot:run
```

The connecting role must **own** the database and its tables. Connecting as a role that
merely has access fails with `permission denied for table flyway_schema_history`
(SQLSTATE `42501`), because Flyway has to own what it migrates.

---

## Prove it

```bash
./burst.sh https://seat-reservation-gmpc.onrender.com
```

Or `make burst URL=...`, or with knobs:

```bash
./burst.sh http://localhost:8080 --n 500 --seats 300 --retries 100
```

`ADMIN_SECRET` must match the service you are pointing at — export it, or put it in `.env`.
The script runs the load generator with Go if installed, and inside a container if not, so
a clean checkout works either way.

Four phases, then a verdict:

| Phase | What it proves |
| --- | --- |
| warm-up | excluded from every figure — a free instance sleeps, and measuring into a cold start is measuring nothing |
| hot-seat storm | N buyers, **one seat**: exactly one `201`, the rest `409`, zero `5xx` |
| general stampede | buyers spread across the hall, as a real on-sale looks |
| idempotent retries | one key fired concurrently: one booking, everyone told about the same one |
| reconciliation | `available + held + confirmed == total_seats`, and the metrics agree with the API |

**It exits non-zero** if a seat was sold twice, any `5xx` appeared, or the invariant broke.
It is a check, not a report.

> This burst found a real production bug on its first run against the deployed service —
> a connection-pool timeout surfacing as `500` instead of `429`. See WRITEUP.md.

---

## Tests

```bash
mvn test
```

77 tests. Integration tests run against a real Postgres via Testcontainers, so a clean clone
needs only a Docker daemon. Nothing is mocked: these exist to prove the behaviour of real SQL
under real concurrency, which an in-memory substitute cannot show.

Without Docker, point them at a database you own — same tests, same assertions:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seats_test \
TEST_DATABASE_USER=seats TEST_DATABASE_PASSWORD=seats \
mvn test
```

The suite truncates between tests, so give it **its own** database.

The ones that matter: `HotSeatStormTest` (300 buyers, one seat), `PerUserLimitTest`,
`IdempotencyTest`, `MultiSeatTest`, `IdentityTest`, `PoolExhaustionTest`.

---

## Endpoints

| Method | Path | Auth | |
| --- | --- | --- | --- |
| `POST` | `/auth/token` | — | mint a token; stand-in for an identity provider |
| `POST` | `/shows` | admin | create a show and all its seats |
| `GET` | `/shows/{id}` | — | per-seat status and counts; `?seats=false` for counts only |
| `POST` | `/shows/{id}/reserve` | user | take seats |
| `POST` | `/reservations/{id}/cancel` | owner | give them back |
| `GET` | `/reservations/{id}` | owner | read one booking |
| `GET` | `/admin/config` · `PUT /admin/config/{key}` | admin | change limits with no redeploy |

Identity always comes from the token. A `user_id` in a request body is **not validated
against** the token — it is never read.

```bash
export B=https://seat-reservation-gmpc.onrender.com
ADMIN=$(curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' \
  -d '{"handle":"root","admin_secret":"YOUR_ADMIN_SECRET"}' | jq -r .access_token)

curl -s -X POST "$B/shows" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq
```

Full set with every error case in [API.md](API.md).

---

## Health, metrics and logs

| Endpoint | |
| --- | --- |
| `/actuator/health/liveness` | process only. Deliberately does **not** check the database — a blip should drain traffic, not restart the container in a loop |
| `/actuator/health/readiness` | checks the database, fails closed. Render routes on this |
| `/actuator/prometheus` | metrics, public |

```bash
curl -s "$B/actuator/prometheus" | grep -E "^(reservations_|seats_|show_seats_|seat_backstop|app_)"
```

| Metric | |
| --- | --- |
| `reservations_confirmed_total` | counter |
| `reservations_declined_total{reason}` | `seat_taken`, `per_user_limit`, `idempotent_replay`, … |
| `seats_available{show_id}` | gauge, **read from the database**, so it cannot drift from the API |
| `show_seats_confirmed` / `show_seats_capacity` | the invariant, checkable from metrics alone |

Three of these are alarms rather than statistics, and all three should read zero:

- **`seat_backstop_fired_total`** — the unique index refused a seat the conditional `UPDATE`
  thought it had won. Non-zero means the primary logic is broken and only Postgres is
  preventing a double-sell. This is the 2am page.
- **`app_unexpected_errors_total`** — any `5xx`.
- **`reconciliation_mismatch_total`** — seats lost or duplicated.

**Logs** are JSON, one object per line, with `request_id` as a top-level field, so a single
request can be pulled out of a burst. Visible in the Render dashboard under *Logs*. Locally,
`-Dspring.profiles.active=pretty` gives human-readable output instead.

---

## Configuration

Policy settings live in a `service_config` table and change with an API call, not a redeploy.
They are read into an in-memory snapshot and never queried from a request path.

| Key | Default | |
| --- | --- | --- |
| `reservation.per_user_limit` | `4` | max seats one user may hold for a show |
| `reservation.max_seats_per_request` | `10` | cap per call; bounds row locks per transaction |
| `idempotency.retention_hours` | `24` | how long an answer stays replayable |

```bash
curl -s -X PUT "$B/admin/config/reservation.per_user_limit" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"value":"6"}'
```

Infrastructure settings stay in environment variables, because changing a pool size rebuilds
the pool — a restart in all but name.

| Variable | Default | |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/seats` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `seats` / `seats` | must own the database |
| `JWT_SECRET` | a local development value | **≥ 32 bytes**, or startup fails |
| `ADMIN_SECRET` | `local-admin-secret` | presenting it mints an admin token |
| `DB_POOL_SIZE` | `24` local, `10` deployed | |
| `PORT` | `8080` | Render injects this |

See [.env.example](.env.example).

---

## Deployment

Render builds the `Dockerfile` from [render.yaml](render.yaml); Neon hosts Postgres. Both in
**Singapore**, which is not incidental: measured from outside the region a single statement
costs ~66 ms, and a reservation is nine statements — about 1.2 s per booking. In-region the
same transaction is **~3 ms** server-side.

All five secrets are `sync: false` in the blueprint, so Render prompts for them and nothing
sensitive is in git.

**On the free tier:** the instance sleeps after ~15 minutes idle, and Neon scales to zero
alongside it. The first request after a quiet spell can take **2–3 minutes** while both wake.
It comes up healthy — the burst script's warm-up phase waits for exactly this — but the first
click may be slow.

---

## Money

Integer paise in a `BIGINT`. ₹250 is `25000`. A fractional `price_paise` is rejected with
`400` rather than truncated — Jackson would otherwise read `250.75` into a `long` as `250`
and silently charge the wrong amount.
