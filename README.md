# seat-reservation

Seat reservation at scale — a JSON HTTP service that sells assigned seats for an
event and stays correct under a stampede.

Status: **scaffold**. See `CLAUDE.md` for the design invariants.

## Run locally

```bash
docker compose up --build
```

The API comes up on `http://localhost:8080`, Postgres on `5432`.

## Health

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health/liveness` | process is up (no dependency check) |
| `/actuator/health/readiness` | dependencies reachable; fails closed when the DB is down |
| `/actuator/prometheus` | Prometheus metrics |
