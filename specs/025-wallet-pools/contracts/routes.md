# Contract: the replies' new fields

Additions to spec 002's [routes.md](../../002-wallet/contracts/routes.md). No route, path, ACL or
request body changes. Service routes answer minor-unit integers; player and operator routes render
major-unit strings with two decimals, as for every amount. Names are the original's.

| Route | Reply gains |
|---|---|
| every accepted money reply (`MoneyReply`) | `depositPoolBalance`, `convertedPoolBalance` (after) |
| `POST W/bets` | `fundedDepositAmount` (the deposit part of `realLeg`) |
| `POST W/wins` | `wonDepositAmount` (the deposit part of the real share) |
| `POST W/holds`, `POST W/awards/cancel` | nothing beyond `MoneyReply`'s pools; their parts are on the transactions view's rows and the facts |
| `GET W/balance` (service, operator, player) | `depositPoolBalance`, `convertedPoolBalance` beside `realBalance` |
| `GET W/transactions` | each row gains `depositPart`, `convertedPart`, `depositPoolAfter`, `convertedPoolAfter` |
| `GET W/holds/preview` | `clawbackDepositAmount`, `voidedDepositAmount`, `paidDepositAmount`: the deposit part of each movement the hold would record, moving nothing (FR-008; names assumed, after `fundedDepositAmount`) |
| `open-migrated` (the migration's command; spec 019 names its route) | body gains `depositPool`, `convertedPool`; refused `POOLS_INVARIANT` when they do not sum to `realBalance` |

A repeat under a key answers the same fields: `Outcome` carries the pools and the bet's or win's
parts.
