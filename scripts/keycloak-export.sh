#!/usr/bin/env bash
# Export realm crypto-trading from the running Keycloak (Compose profile `core`) back into the repository,
# after changes made in the admin console:
#
#   scripts/keycloak-export.sh [output-file]     default: deploy/keycloak/realm-crypto-trading.json
#
# Review `git diff` afterwards. The export is cleaned so that no secret ends up in the repository:
#   - signing / encryption keys are removed (Keycloak generates new ones on import);
#   - client secrets become placeholders: grafana -> ${GRAFANA_OAUTH_CLIENT_SECRET},
#     any other client -> ${KEYCLOAK_CLIENT_SECRET_<CLIENT_ID>} (add that key to .env.example);
#   - only the local development users (admin, alice) are kept, with placeholder passwords;
#     self-registered users are dropped.
# Placeholders are replaced from the container environment when the realm is imported.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT_DIR/deploy/keycloak/realm-crypto-trading.json}"
CONTAINER=crypto-trading-keycloak-1
REALM=crypto-trading
EXPORT_DIR=/tmp/realm-export

command -v jq >/dev/null || { echo "error: jq is not installed (brew install jq)" >&2; exit 1; }
docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true \
  || { echo "error: $CONTAINER is not running (scripts/dev.sh up core)" >&2; exit 1; }

# The running server already uses the management port; the export run does not need it.
docker exec "$CONTAINER" rm -rf "$EXPORT_DIR"
if ! docker exec "$CONTAINER" /opt/keycloak/bin/kc.sh export --dir "$EXPORT_DIR" --realm "$REALM" \
    --users realm_file --health-enabled=false --metrics-enabled=false >/dev/null 2>&1; then
  echo "error: export failed; run it by hand to see the log:" >&2
  echo "  docker exec $CONTAINER /opt/keycloak/bin/kc.sh export --dir $EXPORT_DIR --realm $REALM --users realm_file --health-enabled=false --metrics-enabled=false" >&2
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"; docker exec "$CONTAINER" rm -rf "$EXPORT_DIR" >/dev/null 2>&1 || true' EXIT
docker exec "$CONTAINER" cat "$EXPORT_DIR/$REALM-realm.json" | jq '
  def placeholder(id):
    if id == "grafana" then "${GRAFANA_OAUTH_CLIENT_SECRET}"
    else "${KEYCLOAK_CLIENT_SECRET_" + (id | ascii_upcase | gsub("[^A-Z0-9]"; "_")) + "}" end;
  del(.components["org.keycloak.keys.KeyProvider"])
  | .clients |= map(if has("secret") and (.publicClient | not) then .secret = placeholder(.clientId) else del(.secret) end)
  | .users |= (map(select(.username == "admin" or .username == "alice"))
      | map(del(.id, .createdTimestamp)
            | .credentials = [{type: "password", temporary: false,
                value: ("${KEYCLOAK_DEV_" + (.username | ascii_upcase) + "_PASSWORD}")}]))
' >"$TMP"

# Fail closed if anything secret-looking survived.
if jq -e '[.. | objects | (.secretData?, .privateKey?, .hashedSaltedValue?)] | map(select(. != null)) | length > 0' "$TMP" >/dev/null; then
  echo "error: the cleaned export still contains secret data; nothing written" >&2
  exit 1
fi

mv "$TMP" "$OUT"
trap 'docker exec "$CONTAINER" rm -rf "$EXPORT_DIR" >/dev/null 2>&1 || true' EXIT
echo "Exported realm $REALM to ${OUT#"$ROOT_DIR"/} (review with git diff)."
jq -r '.clients[] | select(.secret? and (.secret | startswith("${KEYCLOAK_CLIENT_SECRET_"))) | "note: add \(.secret | ltrimstr("${") | rtrimstr("}"))= to .env.example and the keycloak service environment"' "$OUT"
