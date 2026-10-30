# Data model: kyc

Money is `Long` minor units. Every time is an `Instant` (UTC). `Personal` marks a value that names a
person (constitution V; 008 R8). Wire names are snake_case for events and camelCase on HTTP, as the other
services here.

## `KycRecord` (event sourced, id = player id)

### State

| Field | Type | Notes |
|---|---|---|
| `documents` | `Vector[Document]` | in recorded order; "latest" per category is the last recorded (R4) |
| `offers` | `Map[DocumentId, Offer]` | uploads offered and not yet confirmed; dropped an hour after they are offered |
| `status` | `unverified \| pending \| verified \| rejected` | starts `unverified` |
| `mode` | `automatic \| manual` | starts `automatic` |
| `required` | `Vector[Category]` | required of this player (FR-017); empty when none |
| `sof` | `unverified \| cleared` | |
| `verifiedAt` | `Option[Instant]` | the last time it became verified |
| `documentsChangedAt` | `Option[Instant]` | the last document recorded or reviewed |
| `readyAt` | `Option[Instant]` | ready for final review (FR-018) |
| `profile` | `ProfileFacts` | below |
| `deadline` | `Option[Deadline]` | `at`, `migrated`, `handled`, `suspensionRequested` |
| `flags` | `Vector[Flag]` | open and dismissed |
| `deposits` | `Map[Currency, Deposits]` | `total: Long`, `lastOrdinal: Int` |
| `closed` | `Boolean` | from `player_closed` |
| `erased` | `Boolean` | |

**Document**:

| Field | Type |
|---|---|
| `id` | the document's id |
| `category` | `Category` |
| `objectName` | `subjects/<player>/kyc/<category>-<millis>.<ext>` |
| `contentType` | the content type |
| `size` | `Long` |
| `uploadedAt` | `Instant` |
| `by` | `Player \| Support(operatorId, reason: Personal[String])` |
| `review` | `Option[Review(outcome: approved \| rejected, reasonCode: Option[ReasonCode], operatorId, at)]` |

**Offer**: `documentId, category, contentType, objectName, by, offeredAt`.

**ProfileFacts**: `present: Set[ProfileField]`, `adultFrom: Option[Personal[LocalDate]]` and
`changedAt: Option[Instant]`. `changedAt` is the last fact that changed a required field (R11).

**Flag**:

| Field | Type |
|---|---|
| `reason` | `threshold \| source_of_funds \| window \| large_deposit` |
| `at` | `Instant` |
| `dismissed` | `Option[(operatorId, note: Personal[String], at)]` |

A flag is open until dismissed, or until the account is verified (`threshold`, `window`, `large_deposit`)
or the source of funds is cleared (`source_of_funds`). This is FR-024. An open flag of a reason is never
raised a second time.

**Category**: `identity_front`, `identity_back`, `proof_of_address`, `payment_method`,
`bank_account_details`, `other`; the legacy `identity` is read from the migration only.

**ReasonCode**: `document_unclear`, `address_mismatch`, `document_expired`, `invalid_document`, `other`.

### Commands, events and refusals

| Command | Who | Refused when | Events |
|---|---|---|---|
| `RequestUpload(category, contentType, by)` | player, operator | closed or erased (`account_closed`); unknown category (`invalid_category`); content type not accepted (`file_rejected`); category locked (`document_locked`) | `UploadOffered` |
| `ConfirmUpload(documentId, size, contentType)` | player, operator | id not offered (`document_not_found`); offer expired (`upload_expired`) | `DocumentRecorded`, then the derivation's |
| `Review(documentId, outcome, reasonCode?, operator)` | operator | reason code unknown or missing on a rejection (`invalid_rejection_reason`; checked first); unknown document (`document_not_found`); already reviewed (`document_already_reviewed`) | `DocumentReviewed`, then the derivation's |
| `IssueView(documentId, requester)` | player, operator | unknown document or not this player's (`document_not_found`); erased (`document_erased`) | `ViewIssued` |
| `SetMode(mode, operator)` | operator | — | `ModeChanged`; automatic: `ReadyCleared` and the derivation's |
| `SetStatus(pending \| verified, operator)` | operator | verified with an incomplete profile (`profile_incomplete`, the missing fields) | `StatusChanged(actor operator, reason agent)` |
| `SetRequired(categories, operator)` | operator | an unknown category (`invalid_category`) | `RequiredChanged`; non-empty: `ModeChanged(manual)`, and `StatusChanged(pending, reason required_category)` unless pending or rejected |
| `Override(verified \| rejected, reason?, operator)` | operator | — | `StatusChanged(actor operator, reason)`; the reason is `Personal` |
| `ClearSof(operator)` / `ResetSof(operator)` | operator | already so (no event) | `SofCleared` / `SofReset` |
| `DismissFlag(reason, note, operator)` | operator | no open flag of that reason (`flag_not_found`) | `FlagDismissed` |
| `DepositCheck(policy, currency?, amount)` | `players` | — (an answer, not a refusal) | none, or `Flagged(threshold \| source_of_funds)` |
| `CountDeposit(currency, amount, ordinal, policy)` | `DepositsConsumer` | — | `DepositCounted` (unless a repeat); `Flagged(large_deposit)` (FR-038) |
| `ProfileChanged(present, adultFrom, requiredChanged)` | `ProfileConsumer` | — | `ProfileUpdated`, then the derivation's |
| `SetDeadline(registeredAt, policy)` | `RegistrationConsumer` | already set | `DeadlineSet` |
| `WindowLapsed(deadline)` | `WindowTimer` | — | nothing if verified, closed, handled or another deadline; else `Flagged(window)`, `DeadlineHandled(suspend: Boolean)` |
| `SuspensionRequested(deadline)` | `WindowTimer`, after `players` answered | — | `SuspensionRequested` |
| `FlagForPolicy(policy)` | `PolicyFlagger` | — | `Flagged(threshold \| source_of_funds)` for each lowered threshold now exceeded |
| `Close` | `ClosureConsumer` | — | `Closed` (the timer cancelled by the consumer) |
| `Open(migrated record)` | the migration (spec 019) | not empty | `Opened` |
| `Erase` | `players`' erasure | — | `Erased` (blanks `adultFrom`, upload reasons, override reasons, dismissal notes) |

**The derivation** (R4, FR-012 to FR-018) runs after `DocumentRecorded`, `DocumentReviewed`,
`RequiredChanged`, `ModeChanged(automatic)` and `ProfileUpdated`. Its outputs are as follows:

| Mode | Output |
|---|---|
| automatic | `StatusChanged(previous, new, actor system, reason)` only when the status changes |
| manual | `ReadySet(at)` / `ReadyCleared` only on a transition |
| either | `StatusChanged(verified → unverified, reason profile_changed)` |

A `StatusChanged` to verified also closes open `threshold`, `window` and `large_deposit` flags.

**The deposit check** (FR-019, R5), in order:

1. Deposit gate `KYC_APPROVED` and status not verified: `DEPOSIT_BLOCKED_KYC`.
2. The policy has a threshold and the currency is not the KYC currency (or absent):
   `DEPOSIT_BLOCKED_KYC_CURRENCY`.
3. A deposit threshold, status not verified, and total plus amount over it:
   `DEPOSIT_BLOCKED_KYC_THRESHOLD`, flagging `threshold` once.
4. A source of funds threshold, sof not cleared, and total plus amount over it: `DEPOSIT_BLOCKED_SOF`,
   flagging `source_of_funds` once.
5. Otherwise allowed.

## `PolicyEntity` (event sourced, id = brand slug)

State `Policy`:

| Field | Type |
|---|---|
| `depositGate` | `NONE \| KYC_APPROVED` |
| `playGate` | `NONE \| KYC_APPROVED` |
| `currency` | `Option[Currency]` |
| `depositThreshold` | `Option[Long]` |
| `sofThreshold` | `Option[Long]` |
| `windowDays` | `Option[Int]` |
| `required` | `Vector[Category]` |
| `suspendOnExpiry` | `Boolean` |
| `profile` | `UKGC \| MGA \| CURACAO \| CUSTOM` |
| `isDefault` | `Boolean` |

Each profile's defaults are in research R9. With no event, the state is the Curaçao default with
`isDefault`.

| Command | Refused when | Event |
|---|---|---|
| `ApplyProfile(profile, operator)` | unknown (`unknown_profile`, the known ones) | `PolicyChanged(operator, before, after)` |
| `Change(fields, operator)` | a gate not `NONE`/`KYC_APPROVED`; a negative threshold or window; a threshold with no currency (`currency_required`); a category unknown or `identity_back` required (`invalid_category`) | `PolicyChanged`, with the profile `CUSTOM` |

## `KycQueue` (view of `KycRecord`, one row per player)

| Column | Notes |
|---|---|
| `playerId` | |
| `status` | |
| `mode` | |
| `awaiting` | documents with no review |
| `oldestAwaitingAt` | |
| `readyAt` | |
| `flags` | the open reasons |
| `sof` | |
| `depositsByCurrency` | |
| `updatedAt` | |

It has no `Personal` column.

Queries, each with a count (FR-035):

| Query | Rows |
|---|---|
| `awaiting` | `awaiting > 0` |
| `byStatus(s)` | rows with that status |
| `ready` | `readyAt` set |
| `flagged(reason)` | an open flag of that reason |
| `sof` | an open `source_of_funds` flag |
| `overThreshold(currency, amount, status != verified)` | for `PolicyFlagger` |

Lists are oldest first by the filter's time unless `sort=newest`.

## Timers

| Name | Set by | Calls |
|---|---|---|
| `kyc-window-<player>` | the `DeadlineSet` handler (`RecordTimers`, a consumer of the record's events) | `WindowTimer.lapse(player, deadline)`: `WindowLapsed`, then `players`' suspend when told to, then `SuspensionRequested` |

`Closed` cancels the timer. A thrown call is retried with the platform's backoff.

## Consumers

| Consumer | Reads | Does |
|---|---|---|
| `DepositsConsumer` | `money.transactions` `deposit_confirmed` | `CountDeposit` with the policy |
| `ProfileConsumer` | `casino.players` `player_profile_updated` | `ProfileChanged` |
| `RegistrationConsumer` | `casino.players` `player_registered` | `SetDeadline` with the policy |
| `ClosureConsumer` | `casino.players` `player_closed` | `Close` |
| `RecordTimers` | `KycRecord` events | sets and cancels `kyc-window-<player>` |
| `PolicyFlagger` | `PolicyEntity` events | `FlagForPolicy` for each row of `overThreshold` |
| `KycPublisher` | `KycRecord` events | `casino.players` families (`contracts/facts.md`) |
| `AuditPublisher` | `KycRecord` and `PolicyEntity` events | `platform.audit` records |

The three `casino.players` readers skip the families this service produces itself.
