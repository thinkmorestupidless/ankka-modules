# Facts: rewards

## Produced on `casino.rewards` (key = player id; `ce-id` `<reward>/<seq>`)

Every reward family carries `playerId, bonusId (reward id), bonusName, offerId, offerType,
occurredAt`, and gains `source`, `code?`, `tag?` (research R11).

| Family | From | Further fields |
|---|---|---|
| `bonus_granted` | `Activated` (funds) | `amountCents, currency, bonusCode?` |
| `free_spins_granted` | `Activated` (free rounds) | `rounds, gameIds` |
| `bonus_completed` | `Converted`; `Closed` of a reward never converted | — |
| `bonus_forfeited` | `Forfeited` | `reason`, + `amountCents?, currency?` |
| `bonus_cancelled` | `Cancelled`, `Revoked` | `amountCents, currency`, + `reason` |
| `grant_expired` | `Expired` from `ACTIVE` | — |
| `free_spins_settled` | `FreeRoundsFinalised`, `LatePaid` | `winCents, currency` (the amount paid) |
| `bonus_code_entered` | `PlayerRewards.CodeEntered` | `code`, + `outcome` (`matched`, `invalid`, `ineligible`) |

Nothing is published for `Targeted`, `Selected`, `Deselected`, `Switched`, `AwardRefused`,
`ProviderStatusRecorded`, `RoundCounted`, a pending selection expiring, or a close after conversion.

## Produced on `platform.audit`

Offer created, edited, deactivated, reordered; operator award; revoke (mode, reason as `Personal`);
each with the operator's id.

## Consumed

| Topic | Families |
|---|---|
| `casino.players` | `player_registered` (registeredAt), `player_duplicate_check_started`, `player_duplicate_check_ended`, `player_no_deposit_blocked`, `player_no_deposit_block_cleared`, `player_fraud_flagged`, `player_fraud_cleared`, `vip_tier_changed` |
| `money.transactions` | `deposit_confirmed` (amount, currency, `depositOrdinal`, `paymentReference`), `restricted_funds_converted`, `restricted_funds_forfeited`, `restricted_funds_closed` (W5) |
| `casino.rounds` | `round_settled` with `freeRoundsGrant` |

## Contract additions

- `CasinoRewards`: the fields above marked `+`; all `Option` or defaulted.
- `CasinoPlayers` (producer `players`):
  - `player_duplicate_check_started(playerId, registeredAt, occurredAt)`;
  - `player_duplicate_check_ended(playerId, outcome: clear | strong | weak | skipped, occurredAt)`;
  - `player_no_deposit_blocked(playerId, occurredAt)`;
  - `player_no_deposit_block_cleared(playerId, actorId, occurredAt)`.
- `MoneyTransactions` (producer `wallet`): `restricted_funds_closed(playerId, currency,
  transactionId, awardId, reason: cap_expired | cap_exhausted | real_balance_zero, realBalanceAfter,
  restrictedBalanceAfter, occurredAt)`.
- `just schemas` regenerates `schemas/*.json`; `Compatibility` passes (additive).
