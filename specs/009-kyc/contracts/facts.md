# Facts: kyc

## Produced on `casino.players`, keyed by player, subject the player (FR-030)

Every family already exists in the contract (`CasinoPlayers.scala:87-113`). The contract change is
additive (research R13).

| Event of `KycRecord` | Family | Fields |
|---|---|---|
| `DocumentRecorded` | `player_kyc_submitted` | `playerId, documentId, category, occurredAt` |
| `DocumentReviewed` | `player_kyc_document_reviewed` | `playerId, documentId, category, decision, reasonCode?, actorId` (the operator) |
| `StatusChanged(→ verified)` | `player_kyc_verified` | `previousValue, newValue, actor (system \| operator), actorId?, reason?` |
| `StatusChanged(→ rejected)` | `player_kyc_rejected` | the same |
| `StatusChanged(→ pending)` | `player_kyc_pending` | `previousValue, newValue`, **`reason`** (`agent \| required_category \| profile_changed \| document`), new |
| `StatusChanged(verified → unverified)` | `player_kyc_rejected` | as the original's `kyc_rejected` account event, with reason `profile_changed` |
| `Flagged(reason)` | `player_kyc_review_required` | `reason`: `threshold \| source_of_funds \| window \| large_deposit` |
| `SofCleared` | `player_sof_cleared` | `actorId` |

Notes:

- A reason given by an operator is `Personal`. The fact carries `reason` only when it is one of the
  codes above or `profile_changed`; free text never goes on a fact.
- `players`' `KycFactsConsumer` and `ActivityView` already read `player_kyc_verified` and
  `player_kyc_rejected`.
- `payouts` (spec 007) reads `submitted`, `pending`, `verified` and `rejected`.
- `engagement` (spec 014) reads the review and status families.

## Produced on `platform.audit`

One record per status change, review, view issued, source of funds change, flag dismissal, operator
upload, mode change, required-category change and policy change (FR-037; the mode and required
actions from research *Found during implementation*, T025). The record carries:

| Field | Value |
|---|---|
| `action` | `kyc.status_changed`, `kyc.document_reviewed`, `kyc.view_issued`, `kyc.sof_changed`, `kyc.flag_dismissed`, `kyc.upload_by_operator`, `kyc.mode_changed`, `kyc.required_changed`, `kyc.policy_changed` |
| `actorKind`, `actorId` | `system`, `player` or `operator`, with the id |
| `subjectKind`, `subjectId` | `player` and the id, or `brand` and the slug |
| `changes` | for a policy change, every field before and after; for a status change, the previous and new status; for a mode change, the mode; for a required change, the categories, comma-separated |
| `reasonCode` | for a review, the reason code |

The record names that a note or reason was given. It never carries the note's text.

## Consumed

| Topic | Family | Fields read |
|---|---|---|
| `money.transactions` | `deposit_confirmed` | `playerId, currency, amountCents, depositOrdinal, transactionId` |
| `casino.players` | `player_registered` | `playerId, registeredAt` |
| `casino.players` | `player_profile_updated` | `changedFields`, and the eight completeness fields (presence, and the date of birth to an age date) |
| `casino.players` | `player_closed` | `playerId` |
