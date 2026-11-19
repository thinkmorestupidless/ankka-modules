#!/usr/bin/env bash
# Resolves the dependencies of every sbt build in the repository (the root and each service) into the
# caches CI keeps: ~/.cache/coursier, ~/.ivy2/cache and ~/.sbt. CI saves them once, under a key
# made from the build files, and every job restores them, so a job resolves nothing from Maven Central
# unless its build changed (.github/workflows/sbt-deps.yml and the `sbt-deps` job in ci.yml).
#
# Each build is tried three times: this is the one place a run meets Central, and Central has
# answered "not found" for artifacts it serves.
set -euo pipefail
cd "$(dirname "$0")/.."

retry() {
  local n
  for n in 1 2 3; do
    "$@" && return 0
    echo "attempt $n failed: $*" >&2
    sleep $((n * 10))
  done
  return 1
}
export -f retry

# The root first: the services resolve the contract and the provider core from ~/.ivy2/local.
retry sbt -batch update contract/publishLocal providerCore/publishLocal

# Every service that is an sbt build (player-site is not).
for b in services/*/build.sbt; do dirname "$b"; done | xargs -P 4 -I{} bash -c 'cd "{}" && retry sbt -batch update'
