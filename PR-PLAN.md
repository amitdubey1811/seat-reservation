# Delivery Plan — PR by PR

Six pull requests, in order. Each one builds, runs, and is independently verifiable.

Read [PLAN.md](PLAN.md) for the design and [SCHEMA.md](SCHEMA.md) for the data flow.

---

## Status at a glance

| PR | Branch | Goal | State |
| --- | --- | --- | --- |
| 1 | `docs/implementation-plan` | Design documents | ✅ merged |
| 2 | `impl/scaffold-and-schema` | Project, schema, error handling | ✅ merged |
| 3 | `impl/config-and-auth` | Settings snapshot, tokens, identity | ✅ merged |
| 4 | `impl/shows` | Create a show, report its state | ✅ merged |
| 5 | `impl/reservation-engine` | Reserve, cancel, concurrency tests | 🟡 implemented and tested, not committed |
| 6 | `impl/deploy-and-observe` | Metrics, logs, burst script, deploy | ⬜ not started |

## Ground rules for every PR

- Branch off `main`, open a PR, merge with a **merge commit — never squash.** The
  assignment grades incremental commit history, and squashing destroys exactly that.
- Commit messages: short, imperative, lowercase. No `Co-Authored-By` lines.
- A PR is not done until it **builds from clean** and its acceptance checks pass.
- Each PR leaves `main` deployable. No PR depends on a later one to compile.

## Dependency order

```
PR1 docs
      ↓
PR2 foundation ──→ PR3 config + auth ──→ PR4 shows ──→ PR5 reserve ──→ PR6 deploy
```

Strictly sequential. PR5 is the one that matters; PRs 2–4 exist to make it testable.

---

# PR 1 — Design documents

**Branch** `docs/implementation-plan` · **Depends on** nothing

### Goal

Write down the design before building it, so the code has nothing to explain.

### Files

| File | State |
| --- | --- |
| `PLAN.md` | the design, in plain language |
| `SCHEMA.md` | every table, and each endpoint's statement order |
| `PR-PLAN.md` | this document |

### Commits

```
add implementation plan
revise plan for single-step confirmation and add schema walkthrough
add pr-by-pr delivery plan
```

### Acceptance

- [x] The atomic decision is named and its race-freeness argued
- [x] Deadlock avoidance is explained as structural, not probabilistic
- [x] Idempotency behaviour is specified for all three cases
- [x] Rejected alternatives are recorded with reasons

---

# PR 2 — Foundation

**Branch** `impl/scaffold-and-schema` · **Depends on** PR 1

### Goal

A service that boots, migrates a database, reports health, and can never answer a
domain outcome with a `5xx`.

### Files

| File | New / changed |
| --- | --- |
| `pom.xml` | new — Spring Boot 3.4.1, Data JPA, Flyway, Actuator, java-jwt, Testcontainers |
| `Dockerfile` | new — multi-stage, non-root, `MaxRAMPercentage=70` |
| `docker-compose.yml` | new — healthchecked Postgres 17 + app |
| `CLAUDE.md`, `.gitignore` | new |
| `src/main/resources/application.properties` | new — pool, Flyway retries, actuator health groups |
| `src/main/resources/db/migration/V1__init.sql` | new — the whole schema |
| `entity/` — 15 files | new — 8 `@Entity` classes, 4 composite-key classes, 3 enums |
| `exception/ApiError.java` | new — the complete error taxonomy |
| `exception/ApiException.java` | new — stackless, thrown thousands of times a second |
| `exception/ErrorResponse.java` | new |
| `exception/GlobalExceptionHandler.java` | new |
| `filter/RequestIdFilter.java`, `filter/RequestId.java` | new — correlation id into MDC |
| `exception/PgErrors.java` | new — SQLSTATE inspection |
| `SeatReservationApplication.java` | new |

### Commits

```
✅ init: spring boot skeleton, dockerfile, compose
✅ add flyway schema with seat, quota and idempotency tables
✅ add error taxonomy, request id filter and exception handler
✅ use application.properties instead of yaml
✅ set hikari idle-timeout below max-lifetime
✅ move error handling and filters into dedicated packages
✅ simplify seat state to available and confirmed
✅ add jpa with flyway-owned schema
✅ add jpa entities mirroring the schema
✅ split pool timeout from database unavailability
```

### Remaining work

**`simplify seat state to available and confirmed`** — reconciles `V1__init.sql` with the
revised plan. Edits `V1` in place rather than adding a `V2` that undoes it, because
nothing is pushed and a fresh clone should see one clean schema.

- `seats`: drop `hold_expires_at`, rename `held_by` → `owner_id`, status check to two
  values, coherence check to two branches, drop the `seats_expiring` index
- `reservations`: drop `expires_at`, status check to `CONFIRMED` / `CANCELLED`
- `idempotency_keys`: drop `status`, `response_status`, `response_body`
- `service_config`: drop the two hold-related keys

**`split pool timeout from database unavailability`**

- `ApiError`: remove `REQUEST_IN_PROGRESS` (proven unobservable), add
  `DEPENDENCY_UNAVAILABLE` → `503`
- `GlobalExceptionHandler`: `SQLTransientConnectionException` (pool exhausted) → `429`;
  SQLSTATE `08xxx` (cannot connect) → `503`; contention SQLSTATEs → `429`; rest → `500`

### Acceptance

- [x] `mvn -DskipTests package` succeeds on JDK 21
- [x] Flyway applies cleanly to an empty database
- [x] App boots; `/actuator/health/liveness` returns `200`
- [x] `/actuator/health/readiness` returns `200` with the database up
- [x] **Database killed after boot: readiness `503` (`db: DOWN`), liveness still `200`**
- [x] Seven SQL mechanism checks pass against the revised schema, including
      cancel-then-rebook with history retained
- [x] `ddl-auto=validate` proven to reject a deliberate entity/schema mismatch
- [ ] `docker compose up --build` works end to end *(blocked: Docker not installed)*

### Watch out

Liveness must not include the `db` indicator. If it does, a database blip gets the
container killed and restarted in a loop instead of just drained.

---

# PR 3 — Settings and identity

**Branch** `impl/config-and-auth` · **Depends on** PR 2

### Goal

Policy values changeable without a redeploy, and identity that can only come from a
signed token.

### Files

| File | Purpose |
| --- | --- |
| `config/ServiceConfigKeys.java` | key constants plus compile-time fallback defaults |
| `config/ConfigSnapshot.java` | immutable record held in an `AtomicReference` |
| `repository/ServiceConfigRepository.java` | reads `service_config` |
| `service/ServiceConfigService.java` | scheduled refresh, validation, last-good fallback |
| `controller/ConfigAdminController.java` | `GET` / `PUT` / `POST …/reload` |
| `config/AuthProperties.java` | JWT secret, TTL, admin secret — from env |
| `service/JwtService.java` | HS256 mint and verify |
| `dto/AuthenticatedUser.java` | record `(id, handle, role)` |
| `filter/CurrentUser.java` | per-request holder |
| `repository/UserRepository.java` | find-or-create in `app_users` |
| `filter/AuthFilter.java` | bearer parsing; writes `ErrorResponse` itself |
| `controller/AuthController.java` | `POST /auth/token` |
| `application.properties` | changed — JWT and admin secret bindings |

### Commits

```
add service config reader with compile-time fallbacks
add hot-reloaded config snapshot
add config admin endpoints
add jwt mint and verify
add auth filter and current user resolution
add dev token endpoint
```

### Tests

| Test | Asserts |
| --- | --- |
| `JwtServiceTest` | round trip; tampered signature rejected; expired token rejected. **No database needed** |
| `ServiceConfigServiceTest` | a bad value keeps the last-good snapshot and does not throw |

### Acceptance

- [ ] `POST /auth/token` returns a usable token
- [ ] Missing / malformed / expired token → `401` in our own error shape
- [ ] A `USER` token on an admin endpoint → `403`
- [ ] `PUT /admin/config/reservation.per_user_limit` changes behaviour with no restart
- [ ] A garbage config value logs `ERROR`, keeps the old value, and serves traffic
- [ ] No config read happens inside a request

### Watch out

`AuthFilter` runs **outside** `@RestControllerAdvice`, so it must serialise
`ErrorResponse` itself or auth failures will come back as Tomcat's HTML error page.

---

# PR 4 — Shows

**Branch** `impl/shows` · **Depends on** PR 3

### Goal

Create a show with all its seats, and report its state so the reconciliation invariant
is observable.

### Files

| File | Purpose |
| --- | --- |
| `controller/ShowController.java` | `POST /shows`, `GET /shows/{id}` |
| `service/ShowService.java` | validation, amount arithmetic |
| `repository/ShowRepository.java` | batched seat insert, single-query counts |
| `dto/CreateShowRequest.java` | |
| `dto/ShowResponse.java` | |
| `dto/SeatView.java` | |

### Commits

```
add show and seat repositories with single-statement seat insert
add show creation and state endpoints
reject fractional money instead of truncating it
report validation errors with snake_case field names
add integration test harness for a real postgres
add show creation and state tests
document endpoints, tests and configuration
```

### Tests

| Test | Asserts |
| --- | --- |
| `ShowCreationTest` | 20,000 seats inserted in one batch; duplicate labels rejected `400`; `price_paise` negative rejected |
| `ShowStateTest` | `available + held + confirmed == total_seats` on a fresh show; `?seats=false` agrees with the full response |

### Acceptance

- [x] `POST /shows` as admin creates every seat in `AVAILABLE`
- [x] A non-admin token → `403`, no token → `401`
- [x] A 20,000-seat show is created in **one statement**, in 372 ms
- [x] `GET /shows/{id}` counts come from the same rows it returns
- [x] `?seats=false` agrees with the full listing exactly (212 B vs 757 KB)
- [x] `held` is reported as `0`
- [x] Fractional `price_paise` rejected rather than truncated — mutation-tested
- [x] Duplicate labels, empty list, negative price, comma in a label → `400`
- [x] Malformed show id → `400`, unknown show → `404`, never `5xx`

### Watch out

Seat labels must be validated unique **before** insert, or the batch fails on the
primary key with a confusing error partway through.

---

# PR 5 — The reservation engine

**Branch** `impl/reservation-engine` · **Depends on** PR 4

### Goal

Every one of the assignment's six correctness bars, proven by a test that actually
races threads.

### Files

| File | Purpose |
| --- | --- |
| `repository/SeatRepository.java` | **the conditional UPDATE.** The file to read first |
| `repository/ReservationRepository.java` | |
| `repository/IdempotencyRepository.java` | insert-on-conflict, hash compare |
| `repository/UserShowLockRepository.java` | upsert then `FOR UPDATE` |
| `service/ReservationService.java` | the nine steps, in order, in one method |
| `controller/ReservationController.java` | `POST …/reserve`, `POST …/cancel`, `GET …` |
| `dto/ReserveRequest.java` | |
| `dto/ReservationResponse.java` | |
| `metrics/ReservationMetrics.java` | confirmed counter, declines by reason |

### Commits

```
add conditional-update seat acquisition and release
add user lock, idempotency and reservation seat repositories
add reservation service with ordered lock acquisition
add reserve, cancel and read endpoints
add reservation metrics with declines by reason
map the unique-index backstop to a clean decline
add concurrency burst harness
add hot seat storm and reconciliation tests
add per-user limit, idempotency, multi-seat and identity tests
raise testcontainers for docker engine 29 compatibility
```

### Tests — this is the point of the PR

| Test | Setup | Asserts |
| --- | --- | --- |
| `HotSeatStormTest` | 500 threads, 500 users, one seat | **exactly one** `201`, 499 `409 seat_taken`, zero `5xx` |
| `PerUserLimitTest` | 1 user, 10 parallel reserves, limit 4 | at most 4 seats owned; rest `409 per_user_limit` |
| `IdempotentRetryTest` | same key fired 50× concurrently | one reservation exists; all callers get the same `reservation_id` |
| `IdempotencyConflictTest` | same key, different seats | `409 idempotency_key_reuse` |
| `MultiSeatAtomicityTest` | request `[A12, A13]` where `A13` is taken | `A12` still `AVAILABLE`; no partial booking |
| `DeadlockFreeTest` | two users, reversed seat lists, many rounds | no `40P01`, no `5xx` |
| `CancelRebookTest` | reserve → cancel → reserve same seat | second reserve succeeds; history row retained |
| `CrossUserCancelTest` | user B cancels user A's booking | `404`; A's seats untouched |
| `SpoofedIdentityTest` | body carries another `user_id` | booking belongs to the **token's** user |
| `ReconciliationTest` | during and after a burst | `available + held + confirmed == total_seats` |

### Acceptance

Mapped directly to the assignment's stated bars:

- [x] **Bar 1** no seat confirmed twice — 300 buyers, one seat, `1×201 / 299×409`
- [x] **Bar 2** zero `5xx` across the burst — asserted in every concurrency test
- [x] **Bar 3** reconciliation holds during and after — `reconciled: true` post-storm
- [x] **Bar 4** idempotent retries move nothing extra — 50 concurrent copies, one booking
- [x] **Bar 5** per-user limit holds under concurrency — 10 parallel on limit 4 → 4
- [x] **Bar 6** identity is token-derived — spoofed `user_id` ignored entirely
- [x] 75 tests green via **Testcontainers with no environment configuration**
- [x] Mutation-tested: removing the acquisition guard, and removing the per-user lock,
      each make the suite fail

### Watch out

- `SET LOCAL lock_timeout = '1s'` must be inside the transaction, or a pathological
  wait hangs a connection instead of becoming a `429`.
- Seat labels must be sorted **before** the loop, not inside it.
- The test suite needs Docker for Testcontainers. Fallback: honour
  `TEST_DATABASE_URL` so these can run against local Postgres today.

---

# PR 6 — Deploy and observe

**Branch** `impl/deploy-and-observe` · **Depends on** PR 5

### Goal

A live URL that survives a cold start, and enough instrumentation to watch it behaving
correctly in real time.

### Files

| File | Purpose |
| --- | --- |
| `metrics/SeatGauges.java` | `seats_available` gauge, **queried on scrape**, 1s cache |
| `src/main/resources/logback-spring.xml` | JSON logs with `request_id` |
| `burst/main.go` | the load generator |
| `burst.sh` | one-command wrapper |
| `Makefile` | `make burst`, `make up`, `make test` |
| `render.yaml` | Render blueprint |
| `README.md` | changed — run, deploy, burst instructions |
| `WRITEUP.md` | the required submission write-up |

### Commits

```
add prometheus gauges backed by database queries
add structured json logging with request id
add burst script with hot seat storm
add render blueprint
add readme with run and burst instructions
add writeup
```

### Metrics required by the assignment

| Metric | Type | Note |
| --- | --- | --- |
| `reservations_confirmed_total` | counter | |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay` |
| `seats_available{show_id}` | gauge | **derived from the database at scrape time**, not incremented in memory, so it always agrees with `GET /shows/{id}` |
| `app_unexpected_errors_total` | counter | must stay at zero |
| `app_requests_shed_total` | counter | the `429`s |

### Acceptance

- [ ] Public URL responds, and **survives a cold start** coming up healthy
- [ ] `./burst.sh <URL>` runs from a clean clone and prints confirmed / declined-by-reason / `5xx` / reconciliation
- [ ] Zero `5xx` across our own burst
- [ ] Metrics reconcile with `GET /shows/{id}` to the unit
- [ ] Logs carry a correlation id and are publicly viewable (or recorded)
- [ ] `WRITEUP.md` covers all seven required topics, including honest AI-usage disclosure

### Watch out

- Render free tier is 0.1 CPU. Correct but slow, and slow produces `429`s. Budget $7
  for the evaluation window.
- Neon autosuspends; use the **pooled** connection string and verify the cold-start path
  deliberately rather than discovering it when the graders do.

---

## What is blocking

| Blocker | Blocks | Needed from |
| --- | --- | --- |
| Docker not installed | PR 2 compose check, PR 5 Testcontainers | `brew install --cask docker` |
| Go not installed | PR 6 burst script | `brew install go` |
| Neon project + pooled connection string | PR 6 deploy | you |
| Render account linked to the repo | PR 6 deploy | you |

None of these block PRs 2–5 from being written and verified against the local
Postgres on port 5430.

## Rough sizing

| PR | Effort |
| --- | --- |
| 2 remaining | ~30 min |
| 3 | ~1.5 h |
| 4 | ~1 h |
| 5 | ~3 h — most of it the tests |
| 6 | ~2.5 h — deployment is where surprises live |

Deployment goes first once PR 5 lands, not last. A live URL that is down is the single
most common way a strong submission fails, and finding that out early is worth more
than a polished write-up.
