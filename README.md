# seat-reservation

Seat reservation at scale — a JSON HTTP service that sells assigned seats for an event and
stays correct under a stampede.

Design: [PLAN.md](PLAN.md) · Data model: [SCHEMA.md](SCHEMA.md) · Delivery:
[PR-PLAN.md](PR-PLAN.md) · Invariants: [CLAUDE.md](CLAUDE.md)

Status: **shows and identity**. Reserve and cancel are not implemented yet.

## Run locally

```bash
docker compose up --build
```

The API comes up on `http://localhost:8080`, Postgres on `5432`.

To run against a Postgres you already have, point the service at it:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/seats DATABASE_USER=seats DATABASE_PASSWORD=seats mvn spring-boot:run
```

The database and every table in it must be owned by the connecting role. Connecting as a
role that merely has access fails with `permission denied for table
flyway_schema_history` (SQLSTATE `42501`), because Flyway has to own what it migrates.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/auth/token` | none | Mint a token. Stand-in for an identity provider |
| `POST` | `/shows` | admin | Create a show and all of its seats |
| `GET` | `/shows/{id}` | none | Per-seat status and counts. `?seats=false` for counts only |
| `GET` | `/admin/config` | admin | Effective policy settings and the rows behind them |
| `PUT` | `/admin/config/{key}` | admin | Change a setting with no redeploy |
| `POST` | `/admin/config/reload` | admin | Re-read the settings table immediately |

Identity always comes from the token. A `user_id` in a request body is not validated
against the token — it is never read.

### Try it

```bash
ADMIN=$(curl -s -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"handle":"root","admin_secret":"local-admin-secret"}' | jq -r .access_token)

curl -s -X POST localhost:8080/shows -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq
```

## Tests

```bash
mvn test
```

Integration tests run against a real Postgres via Testcontainers, so a clean clone needs
no setup beyond a running Docker daemon. Nothing is mocked: these tests exist to prove the
behaviour of real SQL under real concurrency, which an in-memory substitute could not show.

On a machine with Postgres but no Docker, point the suite at an existing database instead —
same tests, same assertions:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/seats_test \
TEST_DATABASE_USER=seats TEST_DATABASE_PASSWORD=seats \
mvn test
```

The suite truncates domain tables between tests, so give it **its own database**, not one
holding anything you want to keep. Seeded `service_config` rows are left alone.

## Health and metrics

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health/liveness` | Process is up. Deliberately does **not** check the database — a database blip should drain traffic, not restart the container in a loop |
| `/actuator/health/readiness` | Checks the database and fails closed when it is unreachable |
| `/actuator/prometheus` | Metrics, including the effective policy values and `app_unexpected_errors_total` |

## Configuration

Policy settings live in the `service_config` table and are changed with an API call, not a
redeploy. They are read into an in-memory snapshot and never queried from a request path.

| Key | Default | Meaning |
| --- | --- | --- |
| `reservation.per_user_limit` | `4` | Max seats one user may hold for a show |
| `reservation.max_seats_per_request` | `10` | Cap on seats in one reserve call |
| `idempotency.retention_hours` | `24` | How long an answer stays replayable |

Infrastructure settings stay in environment variables, because changing a pool size
rebuilds the pool — a restart in all but name.

| Variable | Default | Notes |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/seats` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `seats` / `seats` | Must own the database |
| `JWT_SECRET` | a local development value | **At least 32 bytes**, or startup fails |
| `ADMIN_SECRET` | `local-admin-secret` | Presenting it mints an admin token |
| `DB_POOL_SIZE` | `24` | |
| `DB_POOL_TIMEOUT_MS` | `2000` | A pool timeout becomes `429`, never `503` |
| `PORT` | `8080` | |

## Money

All amounts are integer paise in a `BIGINT`. ₹250 is `25000`. A fractional
`price_paise` is rejected with `400` rather than truncated — Jackson would otherwise read
`250.75` into a `long` as `250` and silently charge the wrong amount.
