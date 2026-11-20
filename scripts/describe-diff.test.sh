#!/usr/bin/env bash
# scripts/describe-diff.py over two small descriptions (spec 031 T035): a field added to a shape the
# balance answers, the transactions removed, the restricted funds added; and no line for a file and itself.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
kept="$here/fixtures/describe-diff/kept.json"
fresh="$here/fixtures/describe-diff/fresh.json"

expected='changed GET /v1/casino/players/{brand}/wallets/{currency}/balance
added GET /v1/casino/players/{brand}/wallets/{currency}/restricted-funds
removed GET /v1/casino/players/{brand}/wallets/{currency}/transactions'

set +e
got="$(python3 "$here/describe-diff.py" "$kept" "$fresh")"; code=$?
same="$(python3 "$here/describe-diff.py" "$kept" "$kept")"; same_code=$?
set -e

fail() { echo "describe-diff.test: $1" >&2; exit 1; }
[ "$code" = 1 ] || fail "a difference exits $code, not 1"
[ "$got" = "$expected" ] || fail "$(printf 'expected:\n%s\ngot:\n%s' "$expected" "$got")"
[ "$same_code" = 0 ] && [ -z "$same" ] || fail "a description and itself differ: $same"
echo "describe-diff.test: ok"
