# Facts: what each input becomes

Research R9, R10, R11, R13. The input is a family on a brand topic (spec 001 FR-011; the contract's
`contract.facts`); the output a family on `affiliates.attribution` (`AffiliatesAttribution.scala`), keyed
by the pseudonym (`click` by the link), `ce-id` the fact id. Common fields on every fact: `protocolVersion`
1, `factId`, `occurredAt` (the input's `occurredAt`), `brand`, and the binding's `network`, `affiliate`,
`link`, `campaign`, `subIds`, `clickId`, `pseudonym`.

| Input | Condition | Output | Amount, currency, reference | Fact id |
|---|---|---|---|---|
| `POST /v1/track/click` with a valid tag | | `click` (+ `country`, `deviceType`) | | `click/<clickId>` |
| `player_registered` | a valid tag, or an affiliate's code and no valid tag | `registration` (+ `country`) | | `registration/<pseudonym>` |
| `bonus_code_entered` (`casino.rewards`) | unbound, within the bonus code window | `registration` dated to the entry, and `ggr-daily` with `beforeBinding` for each closed tally before it | | `registration/<pseudonym>`; `ggr-daily/<pseudonym>/<currency>/<day>` |
| `payment.captured` | bound, at or after the binding | `deposit`; and `first-deposit` when it set `firstDepositAt` | `amountCents`, `currency`, the payment id | `deposit/<pseudonym>/<paymentId>`; `first-deposit/<pseudonym>` |
| `payment.dispute.opened` | bound | `chargeback` | the disputed `amountCents`, the payment id | `chargeback/<pseudonym>/<paymentId>` |
| `payment.refunded` | bound | `chargeback` | the refunded `amountCents`, `<paymentId>/refund` | `chargeback/<pseudonym>/<paymentId>/refund` |
| `payment.dispute.closed`, `payment.initiated`, `failed`, `expired`, `abandoned` | | nothing | | |
| `withdrawal_requested` | bound | `withdrawal-requested` | `amountCents`, `currency`, `payoutId` | `withdrawal-requested/<pseudonym>/<payoutId>` |
| `withdrawal_completed` | bound | `withdrawal-completed` | the same | `withdrawal-completed/<pseudonym>/<payoutId>` |
| `withdrawal_failed` | bound | `withdrawal-failed` | the same | `withdrawal-failed/<pseudonym>/<payoutId>` |
| `withdrawal_reversed` | bound | `withdrawal-reversed` | the same | `withdrawal-reversed/<pseudonym>/<payoutId>` |
| `withdrawal_approved`, `withdrawal_cancelled` | | nothing (spec 007 FR-026) | | |
| `restricted_funds_awarded` | bound | `bonus-cost` | `amountCents` (positive), the award id | `bonus-cost/<pseudonym>/<awardId>` |
| `provider_credited` landing on `restricted` | bound | `bonus-cost` | `amountCents`, `providerRef` | `bonus-cost/<pseudonym>/<providerRef>` |
| `restricted_funds_converted`, `restricted_funds_forfeited` | | nothing (spec 011 FR-028; correction 1) | | |
| `bet_placed` | real delta < 0 | a stake leg on the round's day | | |
| `win_credited`, `provider_credited` landing on `real` | real delta > 0 | a win leg on the round's day; `ggr-round` when the network's stream is on | the round's stake, win, GGR since its last fact; `<roundId>` or `<roundId>/<dedupeId>` | `ggr-round/<pseudonym>/<roundId>/<dedupeId>` |
| `bet_rolled_back` | real delta > 0 | a rollback leg (stake reduced); `ggr-round` as above | | |
| `round_settled_as_loss` | | a loss leg (nothing moves); `ggr-round` as above | | |
| a `DayTally` closing with movement | the player bound | `ggr-daily` | `stake`, `win`, `ggr = stake − win`, `reportingDay`, `<currency>/<day>`; `beforeBinding` false | `ggr-daily/<pseudonym>/<currency>/<day>` |
| a leg for a closed day | bound | `ggr-adjustment` | the delta's `ggr`, `reportingDay`, `<currency>/<day>/<dedupeId>` | `ggr-adjustment/<pseudonym>/<currency>/<day>/<dedupeId>` |
| `player_closed` | bound | `player-closed` `closed` | | `player-closed/<pseudonym>` |
| `player_self_excluded` with `until` absent | bound | `player-closed` `self-excluded` | | `player-closed/<pseudonym>` |
| `player_self_excluded` with `until`, `player_reopened`, every other `casino.players` family | | nothing | | |
| the erasure route | bound, no `player-closed` yet | `player-closed` `closed` | | `player-closed/<pseudonym>` |
| any input for an unbound player | | nothing emitted; money kept pending 48 hours (research R8); `firstDepositAt` set | | |

The real delta of a `money.transactions` fact is `realBalanceAfter` minus the wallet's previous fact's
(research R10); a fact whose delta is 0 for a counted family records a leg of 0, which keeps the round's
day and emits nothing. A fact about money is emitted only when the input's time is at or after the
binding's `boundAt`.

No fact carries a player id, a name, an address, a date of birth, a document, a fingerprint, a raw
network address, a reason text or a note (FR-014; SC-006: `LeakSuite`).
