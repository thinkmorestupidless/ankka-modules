# Facts: what reporting reads, and what this branch adds

Reporting produces one fact: `audit_record` on `platform.audit` (research R15). It reads `money.transactions`,
`payments.payments`, `casino.players`, `casino.sessions`, `casino.withdrawals`; `casino.rounds` and
`casino.rewards` when the brand runs `casino`; `markets.events` and `exchange.trades` when it runs
`predictions` (research R12). The readers' rules are research R4.

## Added fields and families

Every addition is optional (`contract/.../facts/Compatibility.scala`): an old reader ignores it, an old
fact lacks it. Scala in `contract/src/main/scala/io/eitheror/casino/contract/facts/`; schemas regenerated
with `just schemas`.

| Topic, family | Addition | Written by | Absent means |
|---|---|---|---|
| `casino.players`, `player_registered` | `test_account: Boolean` | `players` | not a test account |
| `casino.players`, **new** `player_test_account_changed` | `player_id`, `test_account: Boolean`, `actor_id`, `occurred_at` | `players`, when an operator's profile update changes `isTestAccount` | — |
| `casino.rounds`, `round_settled` | `game_name: String`, `game_type: String` | `game-provider`, from its catalogue (`type` is the catalogue's category) | the game id stands for the name |
| `payments.payments`, `payment.dispute.closed` | `amount_cents: MinorUnits`, `currency: Currency`; `outcome` ∈ `won`, `lost` (the brand's) | `wallet`, from the merchant's outcome | the amount of the dispute's `payment.dispute.opened` |
| `money.transactions`, `bet_placed`, `win_credited`, `bet_rolled_back`, `restricted_funds_awarded`, `restricted_funds_converted`, `restricted_funds_forfeited`, `restricted_funds_closed`, `admin_adjusted`, `provider_credited`, `hold_placed` (the last three added during implementation, 2026-11-08) | `records: [RecordProgress]`, `RecordProgress = {award_id, status, balance_cents: MinorUnits, progress_cents: MinorUnits, requirement_cents: MinorUnits}`: each restricted funds record the movement changed, after it | `wallet` | the movement changed no record |
| `exchange.trades`, `trade_made`'s leg | `fee_cents: MinorUnits` | `exchange`, when spec 023 charges the taker fee | no fee |

`round_settled.session_id` is the game session's id (`casino.sessions`' `game_session_started.session_id`);
spec 003's text says so (research R20 item 2).

## Disputes, end to end (new)

1. `psp-gateway` (spec 005): a provider's notification that names a dispute on a deposit becomes an
   outcome on the merchant's outcome route: Unity Finance's deposit state `chargeback` → `dispute_opened`
   (amount and currency of the deposit), `chargebacked` → `dispute_closed` with `disputeOutcome: lost`.
   The eight other adapters report what their providers document (research R20 item 3).
2. `merchant` (spec 004) forwards `dispute_opened` and `dispute_closed` to the brand's wallet as the other
   outcomes (spec 004 FR-022).
3. `wallet` (spec 002) publishes `payment.dispute.opened` and `payment.dispute.closed` with the amount,
   the currency and the enumerated outcome; an outcome outside `won`, `lost` is refused `400
   invalid_outcome`.

No money moves at a dispute here, as the original: the wallet records the outcome; recovering the money is
finance's.

## The audit record

`audit_record`, `service: "reporting"`, `action` `report.generated` or `report.served`, `actor_kind`
`operator`, `subject_kind` `document`, `subject_id` the reference, `outcome` `ok`, `changes`
`[{field:"kind", after}, {field:"period", after}]`, `record_class` `operational`. No personal field.
