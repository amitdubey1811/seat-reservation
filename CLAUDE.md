# seat-reservation

A JSON HTTP service that sells assigned seats for an event. The entire point is
**correctness under contention**: never sell a seat twice, never exceed a user's
booking limit, never double-charge a retried request — under ~20k concurrent
reservations, with zero 5xx.

## Tech stack

- **Java 21** + **Spring Boot 3.4.1**
- **Spring Data JPA / Hibernate** for ordinary persistence, with **native
  `@Modifying` queries on the contended path**. The statement that decides a race is
  always hand-written SQL returning a rows-affected count — never an ORM write.
- **PostgreSQL** + **Flyway** migrations. Flyway owns the schema;
  `ddl-auto=validate` makes Hibernate verify it agrees at boot.
- **Micrometer/Actuator** for Prometheus metrics and health probes
- **java-jwt** (HS256) behind a plain servlet filter — no Spring Security, so every
  auth outcome maps to our own error taxonomy
- **Testcontainers** for concurrency tests against a real Postgres

## Package layout — package by layer

```
com.amitdubey.seats
  entity/        one @Entity per table, plus status enums
  dto/           request and response shapes
  repository/    Spring Data repositories, including the native atomic statements
  service/       business logic and transaction boundaries
  controller/    REST endpoints
  exception/     error taxonomy, ApiException, GlobalExceptionHandler, PgErrors
  filter/        request-id correlation filter, auth filter
  config/        Spring configuration and @ConfigurationProperties
  metrics/       counters and DB-backed gauges
```

Layered, not feature-sliced. Keep each type in the package for its layer.

## Non-negotiable invariants

- **Money is integer paise** (`long`). Never `float`, `double`, or `BigDecimal` for money.
- **Zero 5xx.** Every domain outcome is a 4xx with a machine-readable `reason`.
  Pool timeouts, lock timeouts, deadlocks (40P01) and serialization failures (40001)
  are translated to **429**, never 503/500.
- **`available + held + confirmed == total_seats`** at all times. Counts come from a
  **single aggregate query**, never three separate ones (read skew looks like a bug).
- **The atomic decision is one conditional UPDATE** guarded on current state:
  ```sql
  UPDATE seat SET status='HELD', held_by=:user, hold_expires_at=now()+:ttl
  WHERE show_id=:show AND label=:label
    AND (status='AVAILABLE' OR (status='HELD' AND hold_expires_at <= now()))
  ```
  `rowsAffected == 1` wins, `0` is a clean 409. **Never SELECT-then-UPDATE.**
- **Expiry is lazy, in the predicate above.** Correctness must never depend on a
  sweeper having run. The sweeper is cosmetic (keeps gauges tidy).
- **Lock order is global and acyclic**: `idempotency_key (user-scoped) → user_show_quota
  (user-scoped) → seat rows (ascending by label)`. Multi-seat requests sort labels before
  acquiring. This makes deadlock structurally impossible — do not add a lock outside
  this order.
- **`user_show_quota` holds no count.** It is a mutex row only; the live seat count is
  read from the `seat` table inside the lock, so lazy expiry cannot drift it.
- **Identity is token-derived.** Read `user_id` from the JWT claim and *ignore* any
  body field of that name — do not "validate" it, ignore it.
- **Seats are never inserted after show creation.** One row per `(show_id, label)` for
  the show's lifetime, so a duplicate seat is unrepresentable.

## Configuration policy

- **Policy knobs live in the `service_config` DB table** and are hot-reloaded into an
  in-memory snapshot (~10s): per-user limit, hold TTL, max seats per request, retention.
  Changing them is an `UPDATE` plus optional `POST /admin/config/reload` — never a redeploy.
- **Infrastructure knobs stay in env vars**: pool size, timeouts, port, DSN, secrets.
  Changing a pool size rebuilds the pool, which is a restart in all but name.
- **Config reads never touch the DB in the request path.** Read the snapshot.
- **Config is fail-safe, not fail-closed**: a bad/missing row keeps the last-good
  snapshot (then a compile-time default) and logs ERROR. A typo must not cause an outage.

## Testing

- Concurrency tests are the point. Every invariant gets a test that fires N parallel
  threads at a real Postgres (Testcontainers) and asserts the outcome distribution —
  e.g. 500 threads on one seat → exactly one 201.
- `mvn` here runs on JDK 23 by default. Pin it so local builds match the image:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

## Git conventions

- Commit incrementally and in small, reviewable steps — the submission is graded partly
  on commit history showing how the work was actually done.
- Messages: short, imperative, lowercase ("add conditional-update seat acquisition").
- Do NOT add `Co-Authored-By` lines.
