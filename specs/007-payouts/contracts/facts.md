# Facts: payouts

## Produced: `casino.withdrawals`

Keyed by the player id (the contract's `key = playerId`, research R11). The request id is `payoutId`.
Each fact's id is `withdrawal-<requestId>-<sequence>`, so a redelivery of the same event is the same
fact. No family carries a destination, an address, a name or a reason's text.

| Event of `WithdrawalRequest` | Family | Fields beyond `playerId`, `payoutId`, `amountCents` (the paid amount), `currency`, `occurredAt` |
|---|---|---|
| `Opened` | `withdrawal_requested` | `terminal`, `gateway` |
| `Approved` | `withdrawal_approved` | `actorId`, `terminal`, `gateway` |
| `Completed` | `withdrawal_completed` | `terminal`, `gateway`, `providerReference` |
| `Cancelled` | `withdrawal_cancelled` | `actor: player`, `terminal`, `gateway`, `reason` (a code) |
| `Rejected` | `withdrawal_cancelled` | `actor: operator`, `terminal`, `gateway`, `reason: rejected` |
| `Failed` | `withdrawal_failed` | `failureReason` (a code: `provider_rejected`, `payout_failed`, `manual_payout_failed`, or the gateway's code), `terminal`, `gateway`, `providerReference` |
| `Reversed` | `withdrawal_reversed` | `reason` (a code), `terminal`, `gateway`, `providerReference` |

Not published: `AwaitingManualPayout`, `PayoutInitiated`, `OutcomeRecorded`, `MarkedNeedsReview`,
`ReferenceMismatched`, `Erased`.

**The contract change** (`Others.scala`, additive, every field `Option` with a default of `None`):

- `gateway` on all six;
- `terminal` on the five that lack it;
- `providerReference` on completed, failed and reversed;
- `reason` on cancelled.

Readers built on the old contract read the new facts unchanged.

## Produced: `platform.audit`

One record per event, naming the actor:

| Event | Actor |
|---|---|
| `Opened`, a player's `Cancelled`, `DestinationSaved`, `DefaultSet`, `DestinationDeleted` | the player |
| `Approved`, `Rejected`, an operator's `Completed` or `Failed`, `LimitsSet` | the operator by id |
| `PayoutInitiated`, `OutcomeRecorded` (gateway or outcome source), an outcome's `Completed`, `Failed` or `Reversed`, `ReferenceMismatched` | `psp-gateway` |
| `OutcomeRecorded` (poll source: every poll result, FR-029), `MarkedNeedsReview`, `AwaitingManualPayout`, a poll's settlement | the workflow |

A record carries the request id, the action, the amounts, the statuses and the reason's code. Free text
never goes on a record.

## Consumed

| Topic | Families | Into |
|---|---|---|
| `casino.players` | `player_kyc_verified`, `player_kyc_rejected`, `player_kyc_submitted`, `player_kyc_pending` | `PlayerPayouts.Kyc` |
| `casino.players` | `player_registered` (`givenName`, `familyName`, `email`), `player_profile_updated` (`givenName`, `familyName`) | `Names`, `Email` |
| `casino.players` | `player_email_changed` (new, research R7) | `Email` |
| `casino.players` | `player_closed` | `Closed` |
| `money.transactions` | `deposit_confirmed` (`gateway`, `scheme`, ordinal) | `Deposit` |

## Added to `casino.players` (produced by `players`)

- `player_registered.email: Option[Personal[String]] = None`;
- `player_email_changed(playerId, email: Personal[String], occurredAt)`, from `EmailPromoted` and
  `EmailChanged`.
