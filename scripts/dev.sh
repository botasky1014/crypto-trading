#!/usr/bin/env bash
# Local middleware helper (Docker Compose).
#
#   scripts/dev.sh up <profile...>   start profiles (core, mq, obs, mock) and wait until healthy
#   scripts/dev.sh down              stop all containers (volumes are kept)
#   scripts/dev.sh ps                list containers and their health
#   scripts/dev.sh stats             one-off memory / CPU snapshot
#   scripts/dev.sh logs <service>    follow the logs of one service
#   scripts/dev.sh reset             stop everything and DELETE all volumes (asks first)
#
# Secrets come from .env.local in the repository root. `up` generates a random value for
# every key of .env.example that is missing from .env.local; existing values are never changed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/compose/docker-compose.yml"
ENV_FILE="$ROOT_DIR/.env.local"
ENV_EXAMPLE="$ROOT_DIR/.env.example"
ALL_PROFILES=(core mq obs mock)

die() { echo "error: $*" >&2; exit 1; }

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# Fill in missing keys of .env.local with random values.
ensure_env() {
  [ -f "$ENV_FILE" ] || { umask 077; : >"$ENV_FILE"; }
  local key generated=()
  while IFS= read -r key; do
    if ! grep -qE "^${key}=.+" "$ENV_FILE"; then
      local value
      case "$key" in
        NACOS_AUTH_TOKEN) value="$(openssl rand -base64 32)" ;;
        *) value="$(openssl rand -hex 16)" ;;
      esac
      # Drop an empty "KEY=" line if present, then append the generated value.
      sed -i '' "/^${key}=\$/d" "$ENV_FILE"
      printf '%s=%s\n' "$key" "$value" >>"$ENV_FILE"
      generated+=("$key")
    fi
  done < <(sed -n 's/^\([A-Z][A-Z0-9_]*\)=.*/\1/p' "$ENV_EXAMPLE")
  if [ "${#generated[@]}" -gt 0 ]; then
    echo "Generated random values in .env.local for: ${generated[*]}"
  fi
}

env_value() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }

# Every compose call sees all profiles, so `down` / `ps` cover whatever is running.
compose() {
  local args=() p
  for p in "${ALL_PROFILES[@]}"; do args+=(--profile "$p"); done
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "${args[@]}" "$@"
}

# Nacos 3 has no default admin password: set it once from .env.local.
init_nacos_admin() {
  local password body code
  password="$(env_value NACOS_ADMIN_PASSWORD)"
  # The HTTP status is always 200; the result code is in the JSON body.
  body="$(curl -s -X POST --data-urlencode "password=${password}" \
    http://127.0.0.1:8848/nacos/v3/auth/user/admin)"
  code="$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)"
  case "$code" in
    0|200) echo "Nacos admin user 'nacos' initialized (password: NACOS_ADMIN_PASSWORD in .env.local)" ;;
    409) ;;  # already initialized
    *) echo "warning: could not initialize the Nacos admin user: $body" >&2 ;;
  esac
}

cmd_up() {
  [ $# -gt 0 ] || die "no profile given, e.g. scripts/dev.sh up core"
  local p valid
  for p in "$@"; do
    valid=false
    for v in "${ALL_PROFILES[@]}"; do [ "$p" = "$v" ] && valid=true; done
    $valid || die "unknown profile '$p' (expected: ${ALL_PROFILES[*]})"
  done
  ensure_env
  local args=()
  for p in "$@"; do args+=(--profile "$p"); done
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "${args[@]}" up -d --wait --wait-timeout 300
  for p in "$@"; do [ "$p" = core ] && init_nacos_admin; done
  compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
}

cmd_reset() {
  echo "This stops all containers and DELETES all volumes (database, Redis, Nacos data)."
  read -r -p "Type 'reset' to continue: " answer
  [ "$answer" = reset ] || { echo "Aborted."; exit 1; }
  compose down --volumes --remove-orphans
}

[ -f "$ENV_EXAMPLE" ] || die ".env.example not found"
command -v docker >/dev/null || die "docker is not installed"
command -v jq >/dev/null || die "jq is not installed (brew install jq)"

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  down) ensure_env; compose down --remove-orphans ;;
  ps) ensure_env; compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' ;;
  stats) docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' \
           $(docker ps -q --filter label=com.docker.compose.project=crypto-trading) ;;
  logs) [ $# -eq 2 ] || die "usage: scripts/dev.sh logs <service>"; ensure_env; compose logs -f --tail 200 "$2" ;;
  reset) ensure_env; cmd_reset ;;
  -h|--help|help|"") usage 0 ;;
  *) echo "unknown command: $1" >&2; usage 1 ;;
esac
