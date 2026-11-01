# Data model: payouts

Money is `Long` minor units with its `Currency`. Every time is an `Instant` (UTC). `Personal` marks a
value that names a person (constitution V; 008 R8). Wire names are snake_case for events and camelCase
on HTTP, as the other services here.

## `WithdrawalRequest` (event sourced, id = request id)

The record of FR-008: what was asked, what was decided, what the provider said. The workflow writes
to it, and the endpoints read from it. No rule that needs another component lives here.

### State

| Field | Type | Notes |
|---|---|---|
| `playerId` | `String` | |
| `amount` | `Long` | as requested |
| `paidAmount` | `Long` | from the hold's answer (after a cap's void); the facts carry it (R11) |
| `currency` | `Currency` | the wallet's |
| `terminal` | `TerminalRef` | `id`, `name`, `gateway`, `scheme`, `automated`, `hasStatus`, `reviewWindow`, as the listing held them at the request |
| `payee` | `Payee` | below |
| `forfeit` | `Option[Forfeit]` | `bonusForfeited`, `forfeitedAmount`, `clawbackAmount`, from the hold's answer |
| `status` | `pending_review \| approved \| awaiting_manual_payout \| completed \| rejected \| cancelled \| failed \| reversed` | |
| `payoutStatus` | `none \| initiated \| needs_review \| resolved` | |
| `providerReference` | `Option[String]` | |
| `decisions` | `Vector[Decision]` | `approved \| rejected`, `operatorId`, `reason: Option[Personal[String]]`, `at` |
| `outcomes` | `Vector[OutcomeRecord]` | every gateway answer, outcome, poll and manual settlement: `source` (`gateway`, `outcome`, `poll`, `operator`, or `workflow` for an in-doubt initiation it records with no gateway answer: `details_erased`, `initiation_unconfirmed`), `answer`, `reasonCode`, `note: Option[Personal[String]]`, `at` |
| `createdAt`, `initiatedAt`, `settledAt` | `Instant`, `Option[Instant]`, `Option[Instant]` | |
| `erased` | `Boolean` | |
| `currency` | `Option[String]` | from `player_registered.currency`, the wallet's (spec 002); the terminal listing's currency when the query names none |

**Payee** (all `Personal` but the method and coin):

| Field | Type |
|---|---|
| `destinationId` | `Option[String]` |
| `holder` | `Option[(given: Personal[String], family: Personal[String])]`, copied from the destination at the request |
| `iban` | `Option[Personal[String]]`, copied likewise; shown as its last four |
| `walletAddress` | `Option[Personal[String]]` |
| `coin` | `Option[String]` |
| `method` | `Option[eto \| ach]` |
| `mobile`, `account`, `institution`, `transit` | `Option[Personal[String]]` |

The destination's fields are copied onto the request, so a payout pays the destination as it stood when
the player asked, and a later deletion changes nothing in flight.

### Commands, events and refusals

| Command | From | Accepted when | Event | Refused |
|---|---|---|---|---|
| `Open` | workflow, after the hold | no state | `Opened` (everything above but decisions and outcomes) | `already_open` (a repeat is answered with the state, idempotent) |
| `Cancel(by)` | workflow | `pending_review` | `Cancelled(by: player \| operator, reasonCode)` | `WITHDRAWAL_REQUEST_NOT_CANCELLABLE` |
| `Approve(operatorId)` | workflow | `pending_review` | `Approved` | `REQUEST_NOT_PENDING` |
| `Reject(operatorId, reason)` | workflow | `pending_review` | `Rejected` | `REQUEST_NOT_PENDING` |
| `AwaitManual` | workflow | `approved`, not automated | `AwaitingManualPayout` | — |
| `Initiated(answer, reference)` | workflow | `approved` | `PayoutInitiated` (payout status `initiated`) | — |
| `Record(outcome)` | workflow | not settled | `OutcomeRecorded` | — |
| `NeedsReview(reason)` | workflow | `approved`, payout status `initiated` | `MarkedNeedsReview` | — |
| `Complete(source, reference, operatorId?)` | workflow | `approved` or `awaiting_manual_payout` | `Completed` (payout status `resolved`) | `WITHDRAWAL_NOT_AWAITING_PAYOUT` |
| `Fail(source, reasonCode, note, operatorId?)` | workflow | `approved` or `awaiting_manual_payout` | `Failed` (payout status `resolved`) | `WITHDRAWAL_NOT_AWAITING_PAYOUT` |
| `Reverse(reasonCode)` | workflow | `completed` | `Reversed` | `WITHDRAWAL_NOT_IN_COMPLETED_STATE` |
| `ReferenceMismatch(given)` | workflow | any | `ReferenceMismatched` (recorded, nothing moves) | — |
| `Erase` | erasure route | any | `Erased` (every `Personal` blanked) | — |

A command repeated after its event (a second `Complete` from the same source) replies with the state and
writes nothing. The operator's settlement routes (FR-016) accept `awaiting_manual_payout`, or
`approved` with payout status `needs_review`; the workflow checks that before it sends `Complete` or
`Fail`.

## `WithdrawalWorkflow` (workflow, id = request id)

### State

| Field | Type | Notes |
|---|---|---|
| `step` | the step name | |
| `request` | the validated request (R4's input) | |
| `hold` | `Option[HoldAnswer]` | the wallet's answer; the request exists once it is `Some(ok)` |
| `refusal` | `Option[Refusal]` | the wallet's refusal, read by the endpoint |
| `money` | `Set[MoneyStep]` | `hold`, `release`, `confirm`, `reverse`: the steps done, never sent again (R9) |
| `gateway` | `Option[GatewayAnswer]` | the last initiation answer |
| `pendingSettle` | `Option[Settle]` | `complete`, `fail(reasonCode)` or `reverse`, between deciding and the wallet's answer |
| `polls` | `Int` | |

### Steps

| Step | Does | Then |
|---|---|---|
| `hold` | `POST …/holds` keyed `<id>/hold` | ok → `open`; a refusal → recorded, end; transport → retry |
| `open` | `WithdrawalRequest.Open` | pause for review |
| *(paused)* | commands: `cancel`, `approve`, `reject` | `release` or `pay` |
| `release` | `POST …/holds/release` keyed `<id>/release`, outcome `cancelled`, `rejected` or `failed` | the request's `Cancel`, `Reject` or `Fail`; end |
| `pay` | pure `Approval` | automated → `initiate`; else `AwaitManual`, pause |
| `initiate` | `POST /v1/payments/withdrawals` | R7's table |
| `wait` | a timer: 30 min then 5 min (`hasStatus`), or the `reviewWindow` | `poll` or `needs-review` |
| `poll` | `GET /v1/payments/withdrawals/{id}` | R8 |
| `needs-review` | `NeedsReview`; pause | commands: `outcome`, `confirmPayout`, `markFailed` |
| `confirm` | `Complete` first (the provider paid: the request is completed whatever follows; spec Edge Cases), then `POST …/holds/confirm` keyed `<id>/confirm`, retried until the wallet answers (FR-024) | pause for a reversal |
| `reverse` | `POST …/holds/reverse` keyed `<id>/reverse` | `Reverse`; end |

**Commands**:

- `start(request)`: the atomic claim. A second start answers the first's state. A different body under
  the same id is `KEY_REUSED`.
- `status`: what the endpoint polls.
- `cancel(player)`, `approve(operator)`, `reject(operator, reason)`.
- `outcome(outcome, reference, reasonCode)`: accepted once approved or awaiting manual payout, and not
  yet settled. On a settled request, the same outcome is answered 200 and moves nothing. A different
  outcome is refused 409, except `reversed` on a completed one. On a request pending review, cancelled
  or rejected, the outcome is refused 409 and recorded.
- `confirmPayout(operator, note)`, `markFailed(operator, reason)`.

A completed workflow stays paused for a reversal; it ends when the request is reversed. ankka keeps a
paused workflow's state only, so 10,000 settled requests cost their rows, not running processes.

## `PlayerPayouts` (event sourced, id = player id)

### State

| Field | Type | Notes |
|---|---|---|
| `known` | `Boolean` | any `casino.players` fact seen |
| `kyc` | `unverified \| submitted \| pending \| verified \| rejected` | from the four KYC families |
| `closed` | `Boolean` | from `player_closed` (recorded, gates nothing: spec Assumptions) |
| `names` | `Option[(given: Personal[String], family: Personal[String])]` | from `player_registered`, `player_profile_updated` |
| `email` | `Option[Personal[String]]` | from `player_registered.email`, `player_email_changed` |
| `deposits` | `Map[Gateway, Set[Scheme]]` | from `deposit_confirmed`; a missing scheme is recorded as none |
| `destinations` | `Vector[Destination]` | below |
| `recent` | `Vector[RecentRequest]` | `id`, `amount`, `currency`, `createdAt`, `counts: Boolean`; requests older than 32 days are dropped at the next command |
| `erased` | `Boolean` | |

**Destination**: `id`, `given`, `family`, `iban` (all `Personal`), `label: Personal[String]`,
`default: Boolean`, `status: active | deleted`, `createdAt`.

### Commands, events and refusals

| Command | Event | Refused |
|---|---|---|
| `Kyc(status, at)` | `KycSeen` | — |
| `Names(given, family, at)` | `NamesSeen` | — |
| `Email(address, at)` | `EmailSeen` | — |
| `Currency(code, at)` | `CurrencySeen` | — |
| `Closed(at)` | `ClosedSeen` | — |
| `Deposit(gateway, scheme, ordinal)` | `DepositSeen` (deduplicated by the ordinal) | — |
| `SaveDestination(given, family, iban, label, default)` | `DestinationSaved` (the first is default) | `PROFILE_INCOMPLETE`; `INVALID_IBAN`; `PAYOUT_DESTINATION_AML_BLOCKED` (the original's code for a name that does not match); 400 naming a missing field |
| `SetDefault(id)` | `DefaultSet` | `DESTINATION_NOT_FOUND`; `DESTINATION_DELETED` |
| `DeleteDestination(id)` | `DestinationDeleted` (blanked; no default promoted) | `DESTINATION_NOT_FOUND`; `DESTINATION_DELETED` |
| `Requested(id, amount, currency, at)` | `RequestCounted` | — |
| `Uncounted(id)` | `RequestUncounted` (cancelled, rejected, failed, reversed) | — |
| `Erase` | `Erased` | — |

`Requested` is sent by the endpoint after the hold, before it answers the player. So the next request's
limit check sees it: read-your-writes, R3. `RequestsConsumer` sends it again from `Opened`, so a crash
between the hold and the call is repaired (idempotent by id). It sends `Uncounted` from the closing
events.

## `WithdrawalLimits` (event sourced, id = slug)

| State | Type |
|---|---|
| `limits` | `Map[Currency, (weekly: Option[Long], monthly: Option[Long])]` |

| Command | Event | Refused |
|---|---|---|
| `Set(limits, operatorId)` | `LimitsSet(previous, next, operatorId)` | 400 naming a negative or decimal amount, or an unknown currency |

Zero or none is no limit (FR-005a). `ConfigAuditPublisher` writes each `LimitsSet` to `platform.audit`.

## `TerminalCatalogue` (key value, id = slug)

| State | Type |
|---|---|
| `terminals` | `Map[Currency, Vector[Terminal]]`: the gateway's listing fields (R5) |
| `refreshedAt` | `Map[Currency, Instant]` |

`Refresh(currency, terminals)` replaces one currency's list. `CatalogueTimer` (`terminals-<slug>`,
recurring every 60 s) reads the gateway for each currency the catalogue holds. A read finding a currency
absent asks the gateway once and stores it. A failed refresh keeps the last list.

## `WithdrawalQueue` (view, from `WithdrawalRequest`)

One row per request:

- `requestId`, `playerId`, `kyc` (as the request's gate read it), `amount`, `currency`;
- `terminalName`, `method`, `destinationLastFour`;
- `createdAt`, `initiatedAt`, `status`, `payoutStatus`, `amountBand` (`lt500`, `500to5000`, `gt5000`, in
  major units of the currency).

Queries:

- `byStatus(status, payoutStatus?, kyc?, method?, band?, player?, page, pageSize)`: newest first,
  `pageSize` 1–200, default 50. `status` defaults to `pending_review`; `ALL` reads every status;
- `byPlayer(playerId)` for the player's list and the operator's `withdrawal-history`;
- `stale(before)`: approved, payout status `initiated`, initiated before `before`. FR-022's count.

A row holds no `Personal` value: the last four of an IBAN is not one (the original showed it), and the
player id is the subject's id.

## Consumers

| Consumer | Source | Does |
|---|---|---|
| `PlayersConsumer` | `casino.players` | the KYC families, `player_registered`, `player_profile_updated`, `player_email_changed`, `player_closed` → `PlayerPayouts` |
| `DepositsConsumer` | `money.transactions` | `deposit_confirmed` → `PlayerPayouts.Deposit` |
| `RequestsConsumer` | `WithdrawalRequest` events | `Opened` → `Requested`; `Cancelled`, `Rejected`, `Failed`, `Reversed` → `Uncounted` |
| `WithdrawalPublisher` | `WithdrawalRequest` events | → `casino.withdrawals` (contracts/facts.md) |
| `AuditPublisher` | `WithdrawalRequest` events | → `platform.audit` |
| `PlayerAuditPublisher` | `PlayerPayouts` events (the destinations) | → `platform.audit` |
| `ConfigAuditPublisher` | `WithdrawalLimits` events | → `platform.audit` |

## Timers

- `terminals-<slug>`: recurring, 60 s, the catalogue's refresh.
- The workflow's own waits (`pause` with a duration): no named timer.
