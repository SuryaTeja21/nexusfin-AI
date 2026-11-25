#!/usr/bin/env bash
set -euo pipefail

# Registers the deployed Vercel URL into Keycloak's nexusfin-gateway client redirectUris
# because Keycloak 24 does not allow wildcard subdomains like https://*.vercel.app/*.
#
# Usage:
#   AUTH_DOMAIN=auth.<IP>.sslip.io \
#   KEYCLOAK_ADMIN=admin \
#   KEYCLOAK_ADMIN_PASSWORD=your-password \
#   VERCEL_APP_URL=https://your-project.vercel.app \
#   ./infra/oci/register-vercel-redirect.sh

: "${AUTH_DOMAIN:?Set AUTH_DOMAIN (e.g. auth.1.2.3.4.sslip.io)}"
: "${VERCEL_APP_URL:?Set VERCEL_APP_URL (e.g. https://nexusfin-ui.vercel.app)}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Set KEYCLOAK_ADMIN_PASSWORD}"

BASE_URL="https://${AUTH_DOMAIN}"
CLEAN_VERCEL="${VERCEL_APP_URL%/}"
REDIRECT_PATTERN="${CLEAN_VERCEL}/*"

TOKEN=$(curl -sf -X POST "${BASE_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "grant_type=password" \
  -d "username=${KEYCLOAK_ADMIN}" \
  -d "password=${KEYCLOAK_ADMIN_PASSWORD}" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

CLIENT_JSON=$(curl -sf "${BASE_URL}/admin/realms/nexusfin/clients?clientId=nexusfin-gateway" \
  -H "Authorization: Bearer ${TOKEN}")

CLIENT_UUID=$(python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])' <<< "${CLIENT_JSON}")

UPDATED_PAYLOAD=$(VERCEL_REDIRECT="${REDIRECT_PATTERN}" VERCEL_ORIGIN="${CLEAN_VERCEL}" python3 -c '
import os, sys, json
client = json.load(sys.stdin)[0]
uris = client.get("redirectUris") or []
target = os.environ["VERCEL_REDIRECT"]
if target not in uris:
    uris.append(target)
client["redirectUris"] = uris
origins = client.get("webOrigins") or ["*"]
if "*" not in origins and os.environ["VERCEL_ORIGIN"] not in origins:
    origins.append(os.environ["VERCEL_ORIGIN"])
client["webOrigins"] = origins
print(json.dumps(client))
' <<< "${CLIENT_JSON}")

curl -sf -X PUT "${BASE_URL}/admin/realms/nexusfin/clients/${CLIENT_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${UPDATED_PAYLOAD}"

echo "Registered ${REDIRECT_PATTERN} cleanly on Keycloak client nexusfin-gateway."
