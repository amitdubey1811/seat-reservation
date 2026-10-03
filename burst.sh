#!/usr/bin/env bash
#
# Fire an on-sale stampede at a running seat-reservation service and report whether it
# behaved correctly. Exits non-zero if it did not, so this can be used as a check rather
# than read as a report.
#
#   ./burst.sh https://your-service.onrender.com
#   ./burst.sh http://localhost:8080 --n 500 --seats 300
#
# ADMIN_SECRET must match the deployed service; it is needed to create the show. Either
# export it, or put it in a .env file beside this script.
#
# Runs the load generator with Go when it is installed, and inside a container when it is
# not — so a clean checkout works either way. Anyone running `docker compose up` already
# has the fallback.

set -euo pipefail

BASE_URL="${1:-}"
if [[ -z "$BASE_URL" || "$BASE_URL" == -* ]]; then
  echo "usage: $0 <BASE_URL> [--n 300] [--seats 200] [--retries 50]" >&2
  echo "example: $0 https://seat-reservation.onrender.com" >&2
  exit 64
fi
shift

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Credentials come from the environment, or a .env that is never committed.
if [[ -z "${ADMIN_SECRET:-}" && -f "$HERE/.env" ]]; then
  ADMIN_SECRET="$(grep -E '^ADMIN_SECRET=' "$HERE/.env" | head -1 | cut -d= -f2- || true)"
fi
if [[ -z "${ADMIN_SECRET:-}" ]]; then
  echo "ADMIN_SECRET is not set, and no .env was found beside this script." >&2
  echo "It must match the ADMIN_SECRET of the service you are pointing at." >&2
  exit 64
fi

# Translate --flag to the program's -flag.
#
# ${ARGS[@]+"${ARGS[@]}"} rather than "${ARGS[@]}": under `set -u`, bash 3.2 — still the
# default shell on macOS — treats an empty array as an unbound variable and aborts. That
# would break the no-extra-flags case, which is exactly what `make burst` does.
ARGS=()
for arg in "$@"; do
  ARGS+=("${arg/#--/-}")
done

if command -v go >/dev/null 2>&1; then
  # Run from inside burst/: it is its own module, so `go run <path>` from the repo root
  # cannot resolve it.
  cd "$HERE/burst"
  exec go run . -url "$BASE_URL" -admin-secret "$ADMIN_SECRET" ${ARGS[@]+"${ARGS[@]}"}
fi

if command -v docker >/dev/null 2>&1; then
  echo "Go is not installed; running the load generator in a container instead."
  exec docker run --rm \
    -v "$HERE/burst:/burst:ro" \
    -w /burst \
    --network host \
    golang:1.23-alpine \
    go run . -url "$BASE_URL" -admin-secret "$ADMIN_SECRET" ${ARGS[@]+"${ARGS[@]}"}
fi

echo "Neither Go nor Docker is available. Install either one:" >&2
echo "  brew install go        # or" >&2
echo "  brew install --cask docker" >&2
exit 69
