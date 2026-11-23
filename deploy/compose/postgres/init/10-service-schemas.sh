#!/usr/bin/env bash
# Runs once, when the data volume is empty (docker-entrypoint-initdb.d).
# Creates one login role and one schema per service in database $POSTGRES_DB.
# Each role owns only its own schema and cannot use any other schema.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db="$POSTGRES_DB" \
  -v pw_account="$DB_PASSWORD_ACCOUNT" \
  -v pw_market="$DB_PASSWORD_MARKET" \
  -v pw_order="$DB_PASSWORD_ORDER" \
  -v pw_ledger="$DB_PASSWORD_LEDGER" \
  -v pw_strategy="$DB_PASSWORD_STRATEGY" \
  -v pw_audit="$DB_PASSWORD_AUDIT" \
  -v pw_keycloak="$DB_PASSWORD_KEYCLOAK" \
  -v pw_apicurio="$DB_PASSWORD_APICURIO" <<'SQL'
-- Nobody gets implicit access to the database or to the public schema.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- One role + one schema per service. Role and schema share the name.
-- "order" is a reserved word, so every identifier is quoted.
CREATE ROLE "account"  LOGIN PASSWORD :'pw_account';
CREATE ROLE "market"   LOGIN PASSWORD :'pw_market';
CREATE ROLE "order"    LOGIN PASSWORD :'pw_order';
CREATE ROLE "ledger"   LOGIN PASSWORD :'pw_ledger';
CREATE ROLE "strategy" LOGIN PASSWORD :'pw_strategy';
CREATE ROLE "audit"    LOGIN PASSWORD :'pw_audit';
CREATE ROLE "keycloak" LOGIN PASSWORD :'pw_keycloak';
CREATE ROLE "apicurio" LOGIN PASSWORD :'pw_apicurio';

CREATE SCHEMA "account"  AUTHORIZATION "account";
CREATE SCHEMA "market"   AUTHORIZATION "market";
CREATE SCHEMA "order"    AUTHORIZATION "order";
CREATE SCHEMA "ledger"   AUTHORIZATION "ledger";
CREATE SCHEMA "strategy" AUTHORIZATION "strategy";
CREATE SCHEMA "audit"    AUTHORIZATION "audit";
CREATE SCHEMA "keycloak" AUTHORIZATION "keycloak";
CREATE SCHEMA "apicurio" AUTHORIZATION "apicurio";

GRANT CONNECT ON DATABASE :"db" TO "account", "market", "order", "ledger",
  "strategy", "audit", "keycloak", "apicurio";

-- Default schema of each role is its own schema.
ALTER ROLE "account"  SET search_path = "account";
ALTER ROLE "market"   SET search_path = "market", public;
ALTER ROLE "order"    SET search_path = "order";
ALTER ROLE "ledger"   SET search_path = "ledger";
ALTER ROLE "strategy" SET search_path = "strategy";
ALTER ROLE "audit"    SET search_path = "audit";
ALTER ROLE "keycloak" SET search_path = "keycloak";
ALTER ROLE "apicurio" SET search_path = "apicurio";

-- TimescaleDB: the extension lives in schema public (installed by the image's
-- 000_install_timescaledb.sh). Only market uses it, so only market may use public.
CREATE EXTENSION IF NOT EXISTS timescaledb;
GRANT USAGE ON SCHEMA public TO "market";
SQL
