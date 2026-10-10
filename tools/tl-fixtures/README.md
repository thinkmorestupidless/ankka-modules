# tl-fixtures

Converts the original's wallet handler fixtures
(`eitheror-platform/starter-packs/wallet/handlers/*.test.tl`) into one JSON case file per handler
under `services/wallet/src/test/resources/fixtures/`, which the wallet's fixture suites replay
against its entity (spec 002 research R13). It runs once, against a local checkout of the original;
the JSON is committed and the original is not needed afterwards.

```bash
just tl-fixtures                      # original checked out beside this repository
just tl-fixtures /path/to/eitheror-platform
```

It refuses to write a handler whose parsed case count differs from its `test "` count, and fails on
any statement it does not know, naming the file and line: a fixture read wrongly would hold the
rebuild to the wrong answer.

## Skipped

The handlers whose routes spec 002 drops (`specs/002-wallet/contracts/surface.md`):
`approve-withdrawal-request`, `mark-withdrawal-awaiting-payout`, `check-open-round`,
`read-contribution-rate`, `create-payout-destination`, `set-default-payout-destination`,
`delete-payout-destination` (21 cases).

## Converted (2026-10-09)

| Handler | Cases |
|---|---:|
| admin-adjust | 11 |
| award-bonus | 5 |
| cancel-bonus | 7 |
| cancel-withdrawal-request | 3 |
| close-pending-cap-bonus | 4 |
| complete-withdrawal-request | 1 |
| create-withdrawal-request | 20 |
| credit-win | 21 |
| deposit | 11 |
| fail-withdrawal-request | 2 |
| forfeit-bonus | 6 |
| game-provider-credit | 4 |
| place-bet | 42 |
| reject-withdrawal-request | 2 |
| reverse-withdrawal-request | 3 |
| rollback-bet | 16 |
| settle-round-as-loss | 16 |
| **total** | **174** |

The original holds 195 cases; 21 are skipped. (The spec's figure of 173 was counted by hand.)

## The JSON

```json
{
  "handler": "credit-win",
  "source": "eitheror-platform/starter-packs/wallet/handlers/credit-win.test.tl",
  "cases": [{
    "name": "…", "line": 21,
    "given": [ { "id": "wallet/test1", "concepts": ["Wallet"], "real_balance": 10000, … } ],
    "when":  { "command": "CreditWin", "params": { "round_id": "r1", "amount": 3000, … } },
    "then":  {
      "mutations": [
        { "index": 0, "op": "set_properties", "target": "wallet/test1", "properties": { … } },
        { "index": 3, "op": "add_concept",    "target": "transaction.new-id", "properties": { "concept": "…" } },
        { "index": 4, "op": "add_edge",       "target": "wallet/test1", "args": ["HAS_TRANSACTION", { "$any": true }] }
      ],
      "mutation_count": 5,
      "result": { … } | null,
      "error":  { "code": "…", "message": "…" } | null
    }
  }]
}
```

- Values keep their TL text: integers as JSON integers, decimals with their scale (`70.00`), `_`
  as `{"$any": true}`.
- `index` is the original's: a fixture may assert only some mutations (gaps are allowed, order is
  not); `mutation_count`, when present, is the original's `count mutation`.
- `then error("CODE")` becomes `"error": {"code": "CODE"}` with no mutations.
