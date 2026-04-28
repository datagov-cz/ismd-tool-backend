#!/bin/bash
# Usage:
#   ./full-backend.sh up   [docker compose up options]    (e.g. --build, --wait, --remove-orphans)
#   ./full-backend.sh down [docker compose down options]  (e.g. -v, --remove-orphans)
set -a
source .env
set +a

COMMAND="${1:-up}"
shift || true

case "$COMMAND" in
  up)
    docker compose --profile full-backend up -d "$@"
    ;;
  down)
    docker compose --profile full-backend down "$@"
    ;;
  *)
    echo "Usage: $0 up [options] | down [options]"
    exit 1
    ;;
esac
