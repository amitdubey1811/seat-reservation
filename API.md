# API — curl reference

Every command below was run against a local instance and produced the response shown.

**Restart your IntelliJ run first.** The instance on `8080` is from an older branch and
404s on `/reserve`.

```bash
export B=http://localhost:8080
```

---

## 1. Tokens

Our stand-in for an identity provider. Identity reaches the rest of the service only as a
signed claim — a `user_id` in any request body is never read.

**User token**

```bash
curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' \
  -d '{"handle":"alice"}'
```

**Admin token** — needed to create shows and change settings

```bash
curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' \
  -d '{"handle":"root","admin_secret":"local-admin-secret"}'
```

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs…",
  "token_type": "Bearer",
  "user_id": "83b1af82-7c6e-40d5-93ac-7be9119c6632",
  "handle": "root",
  "role": "ADMIN",
  "expires_in_seconds": 43200
}
```

**Keep them in shell variables** — everything below assumes these:

```bash
export ADMIN=$(curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' -d '{"handle":"root","admin_secret":"local-admin-secret"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
export ALICE=$(curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' -d '{"handle":"alice"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
export BOB=$(curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' -d '{"handle":"bob"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
```

| Variant | Result |
| --- | --- |
| Wrong `admin_secret` | `403 forbidden` — refused, not quietly downgraded to a user token |
| `"handle":"has spaces"` | `400 validation_failed` |

---

## 2. Create a show — admin only

```bash
curl -s -X POST "$B/shows" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5"],"price_paise":25000}'
```

`201` with every seat available:

```json
{
  "id": "804377c7-894a-444e-891a-28d242cc8b47",
  "name": "friday-night",
  "price_paise": 25000,
  "total_seats": 5,
  "per_user_limit": 4,
  "counts": { "available": 5, "held": 0, "confirmed": 0,
              "total_seats": 5, "reconciled": true },
  "seats": [ { "label": "A1", "status": "available" }, … ]
}
```

```bash
export SHOW=<the id from above>
```

Optional `"per_user_limit": 9` overrides the global limit for this show only.

| Variant | Result |
| --- | --- |
| User token instead of admin | `403 forbidden` |
| No token | `401 unauthenticated` |
| `"seats":["A1","A2","A1"]` | `400 duplicate_seats` |
| `"seats":[]` | `400 validation_failed` |
| `"price_paise":-1` | `400 validation_failed` |
| `"price_paise":250.75` | `400` — money is integer paise, never truncated silently |
| `"seats":["A1,A2"]` | `400` — a comma would split into two seats server-side |

### A big hall

```bash
python3 -c '
import json
labels=[f"{chr(65+r)}{s}" for r in range(25) for s in range(1,801)]
json.dump({"name":"big-hall","seats":labels,"price_paise":25000}, open("/tmp/big.json","w"))'

curl -s -X POST "$B/shows" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' --data-binary @/tmp/big.json \
  -o /dev/null -w "HTTP %{http_code} in %{time_total}s\n"
```

20,000 seats in one statement, ~370 ms.

---

## 3. Reserve

```bash
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1","A2"],"idempotency_key":"alice-key-1"}'
```

`201`:

```json
{
  "reservation_id": "806a10a7-93f8-4b00-9ba3-d6c7aa5176c7",
  "show_id": "804377c7-894a-444e-891a-28d242cc8b47",
  "user_id": "4c3be4ee-35ce-4cd8-aa2a-fdcbf3f2c251",
  "seats": ["A1", "A2"],
  "amount_paise": 50000,
  "status": "confirmed"
}
```

```bash
export RES=<the reservation_id>
```

**The key may travel as a header instead** — an intermediary that retries is likelier to
preserve a header than to rewrite a body. The header wins if both are present.

```bash
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: bob-header-1' \
  -d '{"seats":["A5"]}'
```

### Every outcome

| Case | Status | Body |
| --- | --- | --- |
| Fresh booking | `201` | the reservation |
| **Same key, same seats** | `200` | the *original* reservation, nothing created |
| Same key, different seats | `409` | `idempotency_key_reuse` |
| Seat held by someone else | `409` | `seat_taken` |
| Over the per-user limit | `409` | `per_user_limit` |
| Seat not in this show | `404` | `seat_unknown` |
| Same seat twice in one call | `400` | `duplicate_seats` |
| More seats than the cap | `400` | `too_many_seats` |
| No idempotency key at all | `400` | `validation_failed` |
| No token | `401` | `unauthenticated` |
| Unknown show | `404` | `show_not_found` |

### Proving the behaviour

```bash
# replay — returns the same reservation_id with 200, creates nothing
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1","A2"],"idempotency_key":"alice-key-1"}' \
  -o /dev/null -w "HTTP %{http_code}\n"     # 200

# bob cannot have alice's seat
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"bob-key-1"}'        # 409 seat_taken

# a spoofed user_id is ignored, not validated — the booking is the token's
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A4"],"idempotency_key":"spoof","user_id":"00000000-0000-0000-0000-000000000000"}'
```

### Hot-seat storm by hand

300 buyers, one seat. Exactly one `201`, the rest `409`, zero `5xx`.

```bash
for i in $(seq 1 300); do
  T=$(curl -s -X POST "$B/auth/token" -H 'Content-Type: application/json' \
      -d "{\"handle\":\"buyer$i\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$B/shows/$SHOW/reserve" \
    -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
    -d "{\"seats\":[\"A12\"],\"idempotency_key\":\"storm-$i\"}" &
done | sort | uniq -c
wait
```

PR 6 replaces this with `./burst.sh <URL>`, which mints tokens up front and reports the
distribution and reconciliation properly.

---

## 4. Read a reservation — owner only

```bash
curl -s "$B/reservations/$RES" -H "Authorization: Bearer $ALICE"
```

Another user gets `404`, not `403` — a stranger has no business learning the id exists.

---

## 5. Cancel — owner only, idempotent

```bash
curl -s -X POST "$B/reservations/$RES/cancel" -H "Authorization: Bearer $ALICE"
```

```json
{ "reservation_id": "fb28064f-…", "seats": ["A3","A4"],
  "amount_paise": 50000, "status": "cancelled" }
```

| Case | Status |
| --- | --- |
| Owner cancels | `200` |
| Owner cancels again | `200` — idempotent |
| Someone else cancels | `404` — and the seats are untouched |
| No token | `401` |
| Unknown reservation | `404` |

The freed seat is immediately re-bookable, and the history row is retained:

```bash
curl -s -X POST "$B/shows/$SHOW/reserve" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A3"],"idempotency_key":"bob-key-3"}' \
  -o /dev/null -w "HTTP %{http_code}\n"     # 201
```

---

## 6. Show state — public, no token

```bash
curl -s "$B/shows/$SHOW"
```

```json
"counts": { "available": 3, "held": 0, "confirmed": 2,
            "total_seats": 5, "reconciled": true }
"seats":  [ "A1=confirmed", "A2=confirmed", "A3=available", … ]
```

`reconciled` is the invariant stated outright: `available + held + confirmed ==
total_seats`. `held` is always `0` — we chose explicit cancellation over timed holds.

**Counts only** — 212 bytes instead of 757 KB on a 20,000-seat hall:

```bash
curl -s "$B/shows/$SHOW?seats=false"
```

| Variant | Result |
| --- | --- |
| Unknown id | `404 show_not_found` |
| `not-a-uuid` | `400 validation_failed` — never a 5xx |

---

## 7. Settings — admin only, no redeploy

```bash
curl -s "$B/admin/config" -H "Authorization: Bearer $ADMIN"
```

Returns both what the service is *using* and what the table *says*, so a change made
directly in the database without a reload shows up as a disagreement.

**Change a limit while it is serving:**

```bash
curl -s -X PUT "$B/admin/config/reservation.per_user_limit" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"value":"6"}'
```

Takes effect immediately. This is the answer to *"make the limit 6 right now"*.

**Pick up a change made directly in SQL:**

```bash
curl -s -X POST "$B/admin/config/reload" -H "Authorization: Bearer $ADMIN"
```

| Key | Default |
| --- | --- |
| `reservation.per_user_limit` | `4` |
| `reservation.max_seats_per_request` | `10` |
| `idempotency.retention_hours` | `24` |

| Variant | Result |
| --- | --- |
| `{"value":"abc"}` | `400` — and the live value does **not** change |
| Unknown key | `404 config_key_unknown` |
| User token | `403 forbidden` |

---

## 8. Health and metrics

```bash
curl -s "$B/actuator/health/liveness"    # 200 — process only, no database check
curl -s "$B/actuator/health/readiness"   # 200 — checks the database, fails closed
curl -s "$B/actuator/prometheus"
```

Liveness deliberately ignores the database: a blip should drain traffic, not restart the
container in a loop.

```bash
curl -s "$B/actuator/prometheus" | grep -E "^reservations_|^seat_backstop|^app_"
```

```
reservations_confirmed_total                              1.0
reservations_declined_total{reason="seat_taken"}          2.0
reservations_declined_total{reason="idempotent_replay"}   1.0
reservations_cancelled_total                              0.0
seat_backstop_fired_total                                 0.0
app_unexpected_errors_total                               0.0
app_requests_shed_total                                   0.0
```

Two of these are alarms rather than statistics:

- **`seat_backstop_fired_total`** must stay `0`. Non-zero means the conditional UPDATE let
  through a seat it should not have and only the database's unique index prevented a
  double-sell. This is the 2am page.
- **`app_unexpected_errors_total`** must stay `0`. Any `5xx` lands here.

`seats_available{show_id}` arrives in PR 6.

---

## Error shape

Every error, from any layer including the auth filter, has the same body:

```json
{
  "code": "seat_taken",
  "message": "Seat A1 is already taken.",
  "request_id": "a0c9470b-6825-4b96-84fc-82ef566f6961",
  "at": "2026-10-03T04:53:39.262360Z"
}
```

`code` is what to branch on. `request_id` is echoed in the `X-Request-Id` response header
and appears on every log line for that request — send your own to have it preserved:

```bash
curl -s -D- -o /dev/null "$B/shows/$SHOW" -H 'X-Request-Id: my-trace-123' | grep -i x-request-id
```

### Status codes

| Code | Meaning |
| --- | --- |
| `200` | fine, or an idempotent replay — nothing was created |
| `201` | created |
| `400` | bad request |
| `401` / `403` | no valid token / token may not do that |
| `404` | not found, also used for another user's reservation |
| `409` | **declined on its merits — a normal answer, not a failure** |
| `429` | could not decide right now: pool or lock contention. Carries `Retry-After` |
| `503` | the database is unreachable. Readiness is failing too |
| `500` | a bug. Counted, and should never appear |
