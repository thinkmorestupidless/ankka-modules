#!/usr/bin/env bash
# The operators the integration suites sign in as (tests/it/support.ts, `Labels`): one user of the
# `eitheror` realm on the casino Keycloak per label, with its realm roles, and the file the suites read.
# A person runs this, not a suite: it makes realm users with roles.
#
#   bash tests/it/operators.sh <slug> [<out file>]
#
# Environment: CASINO_KEYCLOAK_URL (https://auth-casino.127.0.0.1.sslip.io:8443), CASINO_KEYCLOAK_ADMIN_USERNAME
# and _PASSWORD (admin/admin on dev-kind), CURL_CA_BUNDLE (~/.ankka/local-ca.crt). A user that exists keeps
# its id; its password is set again and the roles added.
set -euo pipefail
brand="${1:?name the brand by its slug}"
out="${2:-$(cd "$(dirname "$0")/../.." && pwd)/test-results/it/operators.json}"
kc="${CASINO_KEYCLOAK_URL:-https://auth-casino.127.0.0.1.sslip.io:8443}"
export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$HOME/.ankka/local-ca.crt}"
realm=eitheror
password="It-${brand}-$(openssl rand -hex 6)-9!"

admin=$(curl -sf "$kc/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=${CASINO_KEYCLOAK_ADMIN_USERNAME:-admin}" \
  --data-urlencode "password=${CASINO_KEYCLOAK_ADMIN_PASSWORD:-admin}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
api() { curl -sf -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' "$@"; }

ensure_role() {
  api "$kc/admin/realms/$realm/roles/$1" >/dev/null 2>&1 || api -X POST "$kc/admin/realms/$realm/roles" -d "{\"name\":\"$1\"}"
}

# label → its realm roles
declare -a labels=(brand_admin brand_compliance brand_finance brand_support platform_admin payments other_brand made)
roles_of() {
  case "$1" in
    platform_admin) echo platform_admin ;;
    payments)       echo "brand_$brand resource_operator" ;;
    other_brand)    echo "brand_it_other" ;;
    made)           echo "brand_$brand" ;;
    *)              echo "brand_$brand $1" ;;
  esac
}

mkdir -p "$(dirname "$out")"
json='{"operators":{'
first=1
for label in "${labels[@]}"; do
  user="it-${label//_/-}@${brand}.eitheror.test"
  id=$(api "$kc/admin/realms/$realm/users?exact=true&username=$user" | python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')
  if [ -z "$id" ]; then
    api -X POST "$kc/admin/realms/$realm/users" -d "{\"username\":\"$user\",\"email\":\"$user\",\"emailVerified\":true,\"enabled\":true,\"firstName\":\"It\",\"lastName\":\"$label\",\"requiredActions\":[]}"
    id=$(api "$kc/admin/realms/$realm/users?exact=true&username=$user" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
  fi
  api -X PUT "$kc/admin/realms/$realm/users/$id/reset-password" -d "{\"type\":\"password\",\"value\":\"$password\",\"temporary\":false}"
  reps='['
  sep=''
  for role in $(roles_of "$label"); do
    ensure_role "$role"
    reps="$reps$sep$(api "$kc/admin/realms/$realm/roles/$role")"
    sep=','
  done
  api -X POST "$kc/admin/realms/$realm/users/$id/role-mappings/realm" -d "$reps]"
  [ $first = 1 ] || json="$json,"
  first=0
  json="$json\"$label\":{\"username\":\"$user\",\"password\":\"$password\"}"
done
echo "$json}}" > "$out"
chmod 600 "$out"
echo "wrote $out"
