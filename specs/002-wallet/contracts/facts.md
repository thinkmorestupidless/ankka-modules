# Contract: the facts the wallet publishes

Shapes are `contract.facts` (`MoneyTransactions`, `PaymentsPayments`); this file is the mapping from
events and the contract changes this feature makes. `ce-type` is `money.transactions.v1` or
`payments.payments.v1`; the family is the body's `type`; `ce-subject` is the key. ankka mints a new
`ce-id` on every publish, so each publisher sets it through the message's metadata to the id a reader
deduplicates on: the transaction id, `<transactionId>/<recordId>` where one event yields a fact per
record, or `<paymentId>/<outcome>`. The transaction id is in every body as well.

## Contract changes (clarification 2026-10-09, FR-031)

| Change | Where |
|---|---|
| `WalletWithdrawalReversed(playerId, currency, transactionId, withdrawalRequestId, amountCents, balances after, occurredAt)` | new family `withdrawal_reversed`; named apart from `casino.withdrawals`' `WithdrawalReversed`, which is `payouts`' |
| `ProviderCredited(playerId, currency, transactionId, providerRef, amountCents, landedOn: real \| restricted, awardId: Option, balances after, occurredAt)` | new family `provider_credited` |
| `RestrictedFundsForfeited.clawbackCents: MinorUnits` (0 when none) | added field; the cause rides in the existing `reason` (forfeit, cancel, hold, adjustment, loss) |
| `HoldPlaced.voidedCents: MinorUnits` (0 when none) | added field |
| `KeyedOutcomes[A]`: `seen: Map[String, Seen[A]]`, `Seen(digest, outcome)`, `check(key, digest)` → `Repeated` / `Reused`, `record`, `answer`; `Digest.of(fields)` | `contract.money.Key` (research R4); published as 0.2.0-SNAPSHOT |

`just schemas` regenerates `contract/schemas/money.transactions.v1.json`; `SchemaDriftSuite` fails
until it does. The additions are additive: no existing field or family changes.

## `money.transactions` (consumer `MoneyTransactionsPublisher`, over `WalletEntity`)

| Event | Family | Fields beyond the common ones |
|---|---|---|
| `DepositConfirmed` | `deposit_confirmed` | `amount_cents`, `payment_reference`, `deposit_ordinal`, `psp?` and `scheme?` (the deposit's gateway and scheme; added by spec 007) |
| `BetPlaced` | `bet_placed` | `round_id`, `game_id`, `amount_cents`, `wallet_type` |
| `WinCredited` | `win_credited` | `round_id`, `game_id`, `amount_cents`, `wallet_type` |
| `BetRolledBack` | `bet_rolled_back` | `round_id`, `amount_cents` |
| `RoundSettledAsLoss` | `round_settled_as_loss` | `round_id` |
| `RestrictedFundsAwarded` | `restricted_funds_awarded` | `award_id`, `amount_cents`, `wagering_requirement` |
| `BetPlaced` with conversions | one `restricted_funds_converted` per converted record, `ce-id` = `<transactionId>/<recordId>` | `award_id`, `amount_cents` (the balance converted) |
| `RestrictedFundsForfeited`, `RestrictedFundsCancelled`, `RoundSettledAsLoss` with forfeits, `AdminAdjusted` with a forfeit, `HoldPlaced` with forfeits | one `restricted_funds_forfeited` per record | `award_id`, `amount_cents` (balance zeroed), `reason` (cause), `clawback_cents` (on the first record of a cancel or hold, 0 on the others) |
| `AdminAdjusted` | `admin_adjusted` | `amount` (signed), `target`, `reason` (**not** the Personal text: `reason` on the fact is the adjustment's category; the free text stays in the wallet), `actor_id` |
| `HoldPlaced` | `hold_placed` | `withdrawal_request_id`, `amount_cents`, `voided_cents` |
| `HoldReleased` | `hold_released` | `withdrawal_request_id`, `amount_cents` |
| `WithdrawalReversed` | `withdrawal_reversed` | `withdrawal_request_id`, `amount_cents` |
| `ProviderCredited` | `provider_credited` | `provider_ref`, `amount_cents`, `landed_on`, `award_id?` |
| `HoldConfirmed`, `PendingCapClosed`, `PrimarySwitched`, `TurnoverOverrideSet`, `CommandRefused` | none | no money moved; audit only |

Common: `player_id`, `currency`, `transaction_id`, `real_balance_after`, `restricted_balance_after`,
`occurred_at` (the event's time).

On `admin_adjusted`: spec 001 FR-023 names the operator's free-text reason personal; the contract's
`AdminAdjusted.reason` is a plain `String`, so the publisher puts the adjustment's category there
(`goodwill`, `correction`, `other`) and never the text. If the spec wants the text on the fact it must
be `Personal[String]`; the plan takes the narrower reading.

A cancel with no record open still claws back (FR-017): it is one `restricted_funds_forfeited` with an
empty `award_id`, a zero amount and the clawback, so every movement is on the topic.

## `payments.payments`

`payment.captured` comes from `PaymentCapturedPublisher` over the wallet's events, the other families
from `PaymentsPublisher` over `PaymentEntity`: an ankka consumer publishes to one topic, so the wallet
has four publishers (money, captured, payments, audit).

| Source | Family |
|---|---|
| `WalletEntity.DepositConfirmed` | `payment.captured` (`payment_id` = payment reference, `payment_provider`, `amount_cents`, `currency`) |
| `PaymentEntity.PaymentOutcomeRecorded(initiated)` | `payment.initiated` (`terminal`, `amount_cents`, `currency`) |
| `… (failed)` | `payment.failed` (`code`, `reason`) |
| `… (expired \| abandoned)` | `payment.expired` / `payment.abandoned` |
| `… (refunded)` | `payment.refunded` (`amount_cents`, `currency`) |
| `… (dispute_opened)` | `payment.dispute.opened` (`amount_cents`, `currency`, `reason`) |
| `… (dispute_closed)` | `payment.dispute.closed` (`outcome`) |

## `platform.audit` (consumer `AuditPublisher`)

One `audit_record` per money event and per `CommandRefused`: subject `player/<id>`, entity
`wallet/<playerId>/<currency>`, action (the command's wire name), outcome (`accepted` or the code),
key, actor (the caller's service identity or the operator's id), amount, at. Shape per spec 018
FR-001; no personal field.
