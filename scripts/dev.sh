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
# `up core` also creates the Nacos namespaces, publishes missing initial configs (deploy/nacos/)
# and sets up the Nacos service accounts (svc-dev, config-admin).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/compose/docker-compose.yml"
ENV_FILE="$ROOT_DIR/.env.local"
ENV_EXAMPLE="$ROOT_DIR/.env.example"
ALL_PROFILES=(core mq obs mock)
NACOS_API=http://127.0.0.1:8848/nacos
# Nacos accounts with least privilege (namespace `dev`); the admin user `nacos` is for the console only.
#   svc-dev       all services: read config, register / discover services
#   config-admin  only the account service (admin config API, design.md 5.10): read and write config
# Passwords come from .env.local and are re-applied on every run, so they always match the file.
# A resource is "<namespace>:<group>:<type>/<name>"; actions are r, w, rw.
NACOS_NS=dev
NACOS_ACCOUNTS=(
  "svc-dev|NACOS_SERVICE_PASSWORD|${NACOS_NS}:*:config/*|r"
  "svc-dev|NACOS_SERVICE_PASSWORD|${NACOS_NS}:*:naming/*|rw"
  "config-admin|NACOS_CONFIG_ADMIN_PASSWORD|${NACOS_NS}:*:config/*|rw"
)

die() { echo "error: $*" >&2; exit 1; }

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
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

nacos_admin_token() {
  curl -s -X POST --data-urlencode username=nacos \
    --data-urlencode "password=$(env_value NACOS_ADMIN_PASSWORD)" \
    "$NACOS_API/v3/auth/user/login" | jq -r '.accessToken // empty' 2>/dev/null
}

init_nacos_accounts() {
  local token entry user key resource action password body done_users=" "
  token="$(nacos_admin_token)"
  if [ -z "$token" ]; then
    echo "warning: could not log in to Nacos; service accounts were not set up" >&2
    return
  fi
  local auth=(-H "accessToken: $token")
  for entry in "${NACOS_ACCOUNTS[@]}"; do
    IFS='|' read -r user key resource action <<<"$entry"
    # The role has the same name as the user.
    if [[ "$done_users" != *" $user "* ]]; then
      password="$(env_value "$key")"
      if curl -s "${auth[@]}" "$NACOS_API/v3/auth/user/list?pageNo=1&pageSize=100&search=accurate&username=$user" \
          | jq -e --arg u "$user" '.data.pageItems[] | select(.username == $u)' >/dev/null; then
        body="$(curl -s -X PUT "${auth[@]}" --data-urlencode "username=$user" \
          --data-urlencode "newPassword=$password" "$NACOS_API/v3/auth/user")"
      else
        body="$(curl -s -X POST "${auth[@]}" --data-urlencode "username=$user" \
          --data-urlencode "password=$password" "$NACOS_API/v3/auth/user")"
        echo "Nacos user '$user' created (password: $key in .env.local)"
      fi
      [ "$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)" = 0 ] \
        || echo "warning: could not set up Nacos user '$user': $body" >&2
      if ! curl -s "${auth[@]}" "$NACOS_API/v3/auth/role/list?pageNo=1&pageSize=100&search=accurate&username=$user&role=$user" \
          | jq -e --arg u "$user" '.data.pageItems[] | select(.role == $u and .username == $u)' >/dev/null; then
        body="$(curl -s -X POST "${auth[@]}" --data-urlencode "role=$user" \
          --data-urlencode "username=$user" "$NACOS_API/v3/auth/role")"
        [ "$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)" = 0 ] \
          || echo "warning: could not add Nacos role '$user': $body" >&2
      fi
      done_users+="$user "
    fi
    if ! curl -s "${auth[@]}" "$NACOS_API/v3/auth/permission/list?pageNo=1&pageSize=100&role=$user" \
        | jq -e --arg r "$resource" --arg a "$action" \
          '.data.pageItems[] | select(.resource == $r and .action == $a)' >/dev/null; then
      body="$(curl -s -X POST "${auth[@]}" --data-urlencode "role=$user" \
        --data-urlencode "resource=$resource" --data-urlencode "action=$action" \
        "$NACOS_API/v3/auth/permission")"
      if [ "$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)" = 0 ]; then
        echo "Nacos permission granted: $user $action $resource"
      else
        echo "warning: could not grant Nacos permission '$user $action $resource': $body" >&2
      fi
    fi
  done
}

# Create the namespaces and publish the initial configs of deploy/nacos/<namespace>/<dataId>.
# Existing configs are never overwritten: after the first start they are edited in Nacos.
init_nacos_configs() {
  local token dir ns file data_id body code
  token="$(nacos_admin_token)"
  if [ -z "$token" ]; then
    echo "warning: could not log in to Nacos; initial configs were not published" >&2
    return
  fi
  for dir in "$ROOT_DIR"/deploy/nacos/*/; do
    [ -d "$dir" ] || continue
    ns="$(basename "$dir")"
    if ! curl -s -H "accessToken: $token" "$NACOS_API/v3/admin/core/namespace/list" \
        | jq -e --arg ns "$ns" '.data[] | select(.namespace == $ns)' >/dev/null; then
      body="$(curl -s -X POST -H "accessToken: $token" --data-urlencode "namespaceId=$ns" \
        --data-urlencode "namespaceName=$ns" "$NACOS_API/v3/admin/core/namespace")"
      code="$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)"
      if [ "$code" != 0 ]; then
        echo "warning: could not create Nacos namespace '$ns': $body" >&2
        continue
      fi
      echo "Nacos namespace '$ns' created"
    fi
    for file in "$dir"*; do
      [ -f "$file" ] || continue
      data_id="$(basename "$file")"
      code="$(curl -s -G -H "accessToken: $token" --data-urlencode "dataId=$data_id" \
        --data-urlencode groupName=DEFAULT_GROUP --data-urlencode "namespaceId=$ns" \
        "$NACOS_API/v3/admin/cs/config" | jq -r '.code // empty' 2>/dev/null)"
      [ "$code" = 0 ] && continue  # already exists: keep what is in Nacos
      body="$(curl -s -X POST -H "accessToken: $token" --data-urlencode "dataId=$data_id" \
        --data-urlencode groupName=DEFAULT_GROUP --data-urlencode "namespaceId=$ns" \
        --data-urlencode type=yaml --data-urlencode "content@$file" \
        "$NACOS_API/v3/admin/cs/config")"
      code="$(printf '%s' "$body" | jq -r '.code // empty' 2>/dev/null)"
      if [ "$code" = 0 ]; then
        echo "Nacos config '$ns/$data_id' published"
      else
        echo "warning: could not publish Nacos config '$ns/$data_id': $body" >&2
      fi
    done
  done
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
  for p in "$@"; do
    if [ "$p" = core ]; then init_nacos_admin; init_nacos_configs; init_nacos_accounts; fi
  done
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
