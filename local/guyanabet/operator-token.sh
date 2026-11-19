#!/usr/bin/env bash
# A staff token for the local operator, written to .ankka/local/staff-token (never committed).
# The first run makes the operator in the local `eitheror` realm (after realm.sh): ops@guyanabet.test,
# holding platform_admin, games and payments-operator, which merchant's, game-provider's and the house
# provider's operator routes ask for, at scope level `platform` (the claim merchant's routes read). Its password is a local-only value. Tokens last about 5 minutes:
# run it again for a fresh one.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
kc=http://127.0.0.1:18180
user=ops@guyanabet.test
password='local-only-Operator-1!'
mkdir -p "$here/.ankka/local"

admin=$(curl -fsS -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
  "$kc/realms/master/protocol/openid-connect/token" | jq -r .access_token)
auth=(-H "Authorization: Bearer $admin")

id=$(curl -fsS "${auth[@]}" "$kc/admin/realms/eitheror/users?exact=true&username=$user" | jq -r '.[0].id // empty')
if [ -z "$id" ]; then
  curl -fsS "${auth[@]}" -H 'Content-Type: application/json' "$kc/admin/realms/eitheror/users" \
    -d "{\"username\":\"$user\",\"email\":\"$user\",\"emailVerified\":true,\"enabled\":true,\"firstName\":\"Local\",\"lastName\":\"Operator\",\"attributes\":{\"eitheror_scope_level\":[\"platform\"]},
         \"credentials\":[{\"type\":\"password\",\"value\":\"$password\",\"temporary\":false}]}"
  id=$(curl -fsS "${auth[@]}" "$kc/admin/realms/eitheror/users?exact=true&username=$user" | jq -r '.[0].id')
  roles=$(for r in platform_admin games payments-operator; do curl -fsS "${auth[@]}" "$kc/admin/realms/eitheror/roles/$r"; done | jq -s .)
  curl -fsS "${auth[@]}" -H 'Content-Type: application/json' "$kc/admin/realms/eitheror/users/$id/role-mappings/realm" -d "$roles"
  echo "made $user" >&2
fi

curl -fsS -d grant_type=password -d client_id=casino-backoffice -d "username=$user" --data-urlencode "password=$password" \
  "$kc/realms/eitheror/protocol/openid-connect/token" | jq -r .access_token >"$here/.ankka/local/staff-token"
echo "a staff token in $here/.ankka/local/staff-token" >&2
