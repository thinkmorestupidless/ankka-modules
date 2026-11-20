#!/usr/bin/env bash
# Makes one operator per role R19 records (README.md, "The roles") in the original's `eitheror`
# realm on the kind installation `eitheror-dev`, and writes the personas file `record.ts` reads.
# Run by a person: it creates realm users with roles, which an agent may not do.
#
#   tests/original/backoffice-screens/personas.sh <personas.json>
#
# Touches only kind-eitheror-dev, through a kubeconfig holding that one context; never the default
# context. Idempotent: an existing persona keeps its id and has its password and roles set again.
set -euo pipefail

out=${1:?usage: personas.sh <personas.json>}
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
kind get kubeconfig --name eitheror-dev > "$dir/kubeconfig"
k() { KUBECONFIG="$dir/kubeconfig" kubectl --context kind-eitheror-dev -n eitheror "$@"; }

kc=${KEYCLOAK_URL:-https://auth.eitheror.local}
admin_password=$(k get secret eitheror-bundle-keycloak -o jsonpath='{.data.admin-password}' | base64 -d)
# master-realm admin tokens last about a minute: a fresh one for each persona
refresh() {
  token=$(curl -sk "$kc/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode "password=$admin_password" |
    python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
}
api() { curl -sk -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"; }

# persona role: the realm roles it holds (the role first; `operator` is not given, so only the role
# decides what the original renders). Scope is the platform's, as dev-operator's is, so the
# recording's working scope (the brand) is the only brand narrowing.
personas=(
  "brand_admin:brand_admin brand_goldvibe"
  "brand_compliance:brand_compliance brand_goldvibe"
  "resource_finance:resource_finance brand_goldvibe"
  "support_agent:support_agent brand_goldvibe"
  "affiliate_manager:affiliate_manager"
  "affiliate:affiliate"
)

password='Screens-T131-only!'
echo '[' > "$out.tmp"
echo "  {\"role\": \"platform_admin\", \"username\": \"dev-operator\", \"password\": $(k logs job/eitheror-bundle-keycloak-realm-sync | sed -n 's/.*dev-operator ready (username: dev-operator \/ password: \(.*\))$/\1/p' | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}," >> "$out.tmp"
n=0
for entry in "${personas[@]}"; do
  role=${entry%%:*}
  roles=${entry#*:}
  user="screens-${role//_/-}"
  refresh
  id=$(api "$kc/admin/realms/eitheror/users?username=$user&exact=true" | python3 -c 'import sys,json; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')
  if [ -z "$id" ]; then
    api -X POST "$kc/admin/realms/eitheror/users" -d "{\"username\":\"$user\",\"email\":\"$user@eitheror.test\",\"firstName\":\"Screens\",\"lastName\":\"$role\",\"enabled\":true,\"emailVerified\":true,\"attributes\":{\"eitheror_scope_level\":[\"platform\"],\"eitheror_scope_id\":[\"*\"]}}"
    id=$(api "$kc/admin/realms/eitheror/users?username=$user&exact=true" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
  fi
  api -X PUT "$kc/admin/realms/eitheror/users/$id/reset-password" -d "{\"type\":\"password\",\"value\":\"$password\",\"temporary\":false}"
  reps=$(for r in $roles; do api "$kc/admin/realms/eitheror/roles/$r"; echo; done | python3 -c 'import sys,json; print(json.dumps([json.loads(l) for l in sys.stdin if l.strip()]))')
  api -X POST "$kc/admin/realms/eitheror/users/$id/role-mappings/realm" -d "$reps"
  n=$((n + 1))
  sep=$([ "$n" -lt "${#personas[@]}" ] && echo , || true)
  echo "  {\"role\": \"$role\", \"username\": \"$user\", \"password\": \"$password\"}$sep" >> "$out.tmp"
  echo "made $user with $roles"
done
echo ']' >> "$out.tmp"
mv "$out.tmp" "$out"
echo "personas written to $out"
