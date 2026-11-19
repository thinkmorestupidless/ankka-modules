#!/usr/bin/env bash
# The brand's realm (casino-guyanabet, its casino-frontend client and players-admin) and the
# operators' realm (eitheror) in the local identity provider, once, after the first `ankka local up`.
# Made by the modules tool's own realm command, as on an installation. The players' shared values are
# read from local.json, so the realm and the service hold the same ones. A realm that exists is left
# as it is; `ankka local down --volumes` removes both.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
mkdir -p "$here/.ankka/local"
secrets="$here/.ankka/local/realm-secrets.env"
jq -r '.values.players | to_entries[] | select(.key | startswith("PLAYERS_")) | "guyanabet-players/\(.key | sub("^PLAYERS_"; ""))=\(.value)"' \
  "$here/local.json" >"$secrets"
cd "$root"
CASINO_KEYCLOAK_URL=http://127.0.0.1:18180 CASINO_KEYCLOAK_ADMIN_USERNAME=admin CASINO_KEYCLOAK_ADMIN_PASSWORD=admin \
  sbt -batch "modules/run realm guyanabet --secrets $secrets"
