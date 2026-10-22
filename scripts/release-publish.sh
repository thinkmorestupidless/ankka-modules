#!/usr/bin/env bash
# Publishes every rendered manifest in <dir> at <version> to ankka-vendor (spec 021 FR-004,
# contracts/release.md): `ankka vendor modules publish <file> --version <v>` once per module, and
# never `channels set`. Run by release.yml's `manifests` job after `ankka casino release render`, and
# by `just release-dry` with tests/fakes first on the path.
#
# With CASINO_RELEASE_REQUIRE_BUILDS=1 (the release job), a manifest naming a service with no
# services/<name>/build.sbt, so no image the `images` job built, fails the run before any publish,
# naming the service.
set -euo pipefail
dir="${1:?usage: release-publish.sh <dir> <version>}"
version="${2:?usage: release-publish.sh <dir> <version>}"
version="${version#v}"
root="$(cd "$(dirname "$0")/.." && pwd)"
vendor="${ANKKA_VENDOR_BIN:-ankka-vendor}"

shopt -s nullglob
# core first: the vendor refuses a module whose dependency has no release in its range yet.
manifests=()
[[ -f "$dir/core.json" ]] && manifests+=("$dir/core.json")
for m in "$dir"/*.json; do [[ "$m" == "$dir/core.json" ]] || manifests+=("$m"); done
[[ ${#manifests[@]} -gt 0 ]] || { echo "no rendered manifest in $dir" >&2; exit 1; }

missing=()
for m in "${manifests[@]}"; do
  for service in $(grep -o '"image" *: *"[^"]*"' "$m" | sed 's/.*\/\([a-z0-9-]*\):[^"]*"$/\1/'); do
    [[ -f "$root/services/$service/build.sbt" ]] || missing+=("$(basename "$m" .json)/$service")
  done
done
if [[ ${#missing[@]} -gt 0 ]]; then
  if [[ "${CASINO_RELEASE_REQUIRE_BUILDS:-0}" == "1" ]]; then
    for s in "${missing[@]}"; do echo "refused: service '${s#*/}' of module ${s%%/*} has no build, so no image at $version" >&2; done
    exit 1
  fi
  echo "note: ${#missing[@]} services have no build yet (${missing[*]}); the release job refuses these"
fi

for m in "${manifests[@]}"; do
  "$vendor" modules publish "$m" --version "$version"
done
