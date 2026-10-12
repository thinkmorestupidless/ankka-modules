# Contract: `round_settled` on `casino.rounds`

Produced by the `RoundFacts` consumer over `Round` events, one message per `RoundSettled` or
`RoundCancelled` event (FR-019), typed in `contract.facts` (`CasinoRounds`), schema
`contract/schemas/casino.rounds.v1.json`. Spec 001's topic mechanics apply: binary CloudEvents,
`ce-type: casino.rounds.v1`, `ce-subject` and record key the wallet `player/currency`, `ce-id`
`<round entity id>#<event sequence>` so a redelivery is the same id.

| Field | Type | Meaning |
|---|---|---|
| `type` | `"round_settled"` | |
| `player_id` | string | |
| `currency` | ISO 4217 | the wallet's |
| `round_id` | string | the round entity id, `<provider>/<provider round id>` |
| `provider` | string | `st8` |
| `provider_round_id` | string | as the provider sent it, or the transaction id when it sent none |
| `game_id` | string | the game code; empty for a buyin or payout outside a game |
| `session_id` | string | the game session; empty when the callback carried no ticket |
| `stake_cents` | integer, minor | the round's total stake |
| `win_cents` | integer, minor | the credit; 0 for a loss or a cancel |
| `net_result_cents` | integer, minor | `win_cents - stake_cents` |
| `outcome` | `win` \| `loss` \| `cancelled` \| `credit` | `credit` for a standalone credit or a payout |
| `transaction_ids` | string[] | the wallet's transaction ids (kept for readers already written) |
| `provider_transaction_ids` | string[] | **added**: the provider's ids in the round, in order |
| `wallet_transaction_ids` | string[] | **added**: the wallet's ids, one per movement, in the same order; empty entries for free rounds |
| `free_rounds_grant` | string, optional | **added**: the grant the round was tagged with (FR-013); `rewards` settles the win from it (spec 011 FR-024) |
| `occurred_at` | date-time | the settlement event's time |

Readers: `rewards` (a grant's winnings), `reporting` (day-end and the dispute report, matching
`provider_transaction_ids` to `wallet_transaction_ids` without a call, spec 015 FR-017/FR-018),
anything deriving session totals (spec 008's session carries none). The three added fields are
additive to the family; `just schemas` must leave no diff after the contract change is committed.
