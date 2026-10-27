# Data model: players

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

Every field that identifies a person is `Personal[String]` (`contract.personal`), in state, events
and rows alike, and is blanked by the `Erased` event (R8). Times are `Instant`, carried in events,
never read in a fold. Ids are UUIDs this service mints. Field names are the JSON and are protocol
once a journal holds them.

## PlayerEntity (event sourced, id = player id)

### State

| Field | Type | Notes |
|---|---|---|
| `status` | `pending_email_verification` \| `active` \| `suspended` \| `self_excluded` \| `blocked` \| `closed` \| `erasure_pending` \| `erased` | FR-031 |
| `trail` | Vector[AccountEvent] | every transition: `changeType`, `previousValue`, `newValue`, `actor` (`operator` \| `player` \| `system`), `actorId`, `reason`, `at` |
| `profile` | Profile | below; every field `Personal` |
| `locked` | Set[field] | the lock-once fields that hold a value (FR-024) |
| `kycVerified` | Boolean | from `player_kyc_verified` / `player_kyc_rejected` facts; locks `CorrectDateOfBirth` (FR-025) |
| `emails` | Vector[EmailAddress] | `address: Personal`, `verified`, `primary`, `addedAt`; exactly one primary (FR-026) |
| `consents` | Consents | `emailMarketing`, `smsMarketing`: each `given`, `at`, `source`; `messagesSuppressed` (the operator's flag, FR-030) |
| `cookieDecisions` | Map[deviceId, CookieDecision] | every field required; replaced on repeat (FR-028) |
| `favourites` | Set[gameId] | FR-029 |
| `vipTier`, `potVip` | Option[String], Boolean | FR-033 |
| `fraud` | Option[FraudFlag] | `caseIds`, `linkedPlayerIds`, `signals`, `flaggedAt` (FR-036) |
| `sessionLimitMinutes` | Option[Int] | from `rg_limit_applied` (FR-019) |
| `todaysGameSessions` | Vector[(sessionId, startedAt, endedAt?)] | pruned to the event's UTC day in the fold (R5) |
| `openPlayerSessions` | Set[sessionId] | kept by `SessionOpened` / `SessionEnded` (R4) |
| `pendingBonusCode` | Option[String] | cleared when `rewards` consumes it (its fact), FR-012 |
| `registeredAt`, `lastSignInAt`, `lastRefusedSignInAt` | Instant, Option[Instant], Option[Instant] | FR-023 |
| `registration` | Registration | `tag`, `clickId`, `bonusCode` as presented; `networkAddress: Personal`, `networkCountry`, `deviceFingerprint: Personal` (FR-004) |
| `verificationEmail` | `lastSentAt`, `pending: Boolean` | the resend rate; R16 |
| `suspension` | Option[(reason, at)] | FR-032 |
| `closedAt`, `exclusionAt` | Option[Instant] | FR-032; the exclusion time under the one name readers use |
| `erasure` | Option[(correlationId, holdUntil)] | FR-038 |
| `withdrawalOpen` | Boolean | from `casino.withdrawals` facts; an erasure hold (R10) |

### Profile (every field `Personal[String]`)

`displayName`, `firstName`, `lastName`, `dateOfBirth`, `phone`, `addressLine1`, `city`, `stateProv`,
`postalCode`, `country`, `language`, `mobile`, `province`, `bankAccount`, `institutionCode`,
`transitNumber`. Lock-once: `dateOfBirth`, `addressLine1`, `city`, `stateProv`, `postalCode`,
`country` (the original's `LockOnceProfileFields`). Clearable: `mobile`, `province`, `bankAccount`,
`institutionCode`, `transitNumber`. Field names are the original's camelCase wire names.

### Events

`Registered`, `EmailVerified`, `VerificationEmailSent`, `VerificationEmailPending`, `SignedIn`
(stamps `lastSignInAt`), `SignInRefused`, `SessionOpened`, `SessionEnded`, `GameSessionOpened`,
`GameSessionEnded`, `SessionLimitSet`, `ProfileEdited` (the fields changed, with their new `Personal`
values), `DateOfBirthCorrected` (operator, reason), `EmailAdded`, `EmailAddressVerified`,
`EmailPromoted`, `EmailRemoved`, `EmailChanged`, `ConsentChanged`, `CookieDecisionRecorded`,
`FavouriteAdded`, `FavouriteRemoved`, `Suspended`, `SuspensionUpdated` (reason, actor; an audit record, no fact), `Reinstated`, `SelfExcluded`, `ExclusionLifted`,
`Closed` (actor, reason), `Reopened`, `VipTierChanged` (previous, new), `PotVipSet`,
`DuplicateFlagged` (caseId, linked, signals), `DuplicateCleared`, `KycVerified`, `KycWithdrawn`,
`WithdrawalOpened`, `WithdrawalSettled`, `ErasureFiled` (correlationId, holdUntil), `Erased`. There
is no `Blocked` event: `blocked` is a status the migration (019) carries and nothing here sets
(spec FR-031). Every status change appends
to `trail`.

### Commands and their refusals, in order

| Command | Refusals |
|---|---|
| `Register` (workflow step) | `already_registered` (idempotent under the id: a repeat returns the first answer) |
| `VerifyEmail` | none; a second verification changes nothing |
| `ResendVerification` | `already_verified`; `rate_limited` (within 5 min of `lastSentAt`) |
| `SignIn(details)` | `email_not_verified`; `account_restricted` naming the status |
| `SessionOpened(sessionId)` | answers the other open sessions |
| `GameSessionOpened(id, at)` | `account_restricted` naming the status; `session_expired` (no open player session); `session_limit_reached` (limit, used); answers the minutes left |
| `SetSessionLimit(minutes?)` | answers the open game sessions and their remainders |
| `EditProfile(fields)` | `no_fields_provided`; `invalid_country` (allow-list non-empty, not held; the list is passed in by the route); locked fields are dropped, not refused |
| `CorrectDateOfBirth(value, reason, operator)` | `reason_required`; `date_of_birth_required`; `invalid_date_of_birth`; `underage`; `kyc_locked` (verified by `kyc`, until withdrawn) |
| `AddEmail`, `PromoteEmail`, `RemoveEmail`, `ChangeEmail` | `invalid_address`; `address_already_in_list`; `address_not_found`; `not_verified`; `already_primary`; `cannot_remove_primary` |
| `ChangeConsent`, `RecordCookieDecision` | `no_fields_provided`; `invalid_cookie_consent_body`; `missing_device_id` |
| `Suspend(reason, actor)` | none; already suspended with another reason: `SuspensionUpdated`, the reason and time moved, no fact; with the same reason: no event |
| `Reinstate(actor)` | `not_suspended` |
| `SetSelfExcluded(at)` / `LiftExclusion` (RG's commands) | `not_excluded` |
| `Close(actor, reason)` | `already_closed` (idempotent) |
| `Reopen(operator, reason)` | `not_closed`; `hold_lapsed` |
| `SetVipTier`, `SetPotVip` | none |
| `FlagDuplicate(caseId, linked, signals)` | none; idempotent under the case id |
| `ClearDuplicate` | none |
| `FileErasure(correlationId, holdUntil)` | `not_closed` |
| `Erase` | none; idempotent |
| `WriteMarketingMessage` is `MessageEntity`'s; the route checks `messagesSuppressed` and `closed` here first | `marketing_suppressed`; `account_closed` |

Queries: `get` (the profile, for the player and the operator), `status` (for launch and the realm),
`consents`, `favourites`, `emails`, `exportPart`.

## PlayerSessionEntity (event sourced, id = session id)

| Field | Type |
|---|---|
| `playerId`, `realmSessionId` | String |
| `startedAt`, `endedAt` | Instant, Option[Instant] |
| `networkAddress`, `userAgent`, `deviceFingerprint` | `Personal[String]` |
| `status` | `active` \| `completed` \| `expired` \| `forced` \| `suspended` \| `superseded` |
| `gameSessions` | Vector[sessionId] |

Events `Opened`, `GameSessionAdded`, `Ended(reason)`. Commands `Open`, `AddGameSession`, `End(reason)`
(idempotent). The end reason is the status.

## GameSessionEntity (event sourced, id = session id)

`playerId`, `playerSessionId`, `gameId`, `currency`, `demo`, `startedAt`, `endedAt`, `durationMinutes`
(whole minutes, set at end), `status`, `endReason` (`player` \| `limit` \| `forced` \| `logout` \|
`expired` \| `suspended` \| `superseded`). Events `Opened`, `Ended(reason, at)`. Commands `Open`,
`End(reason)` (idempotent, `session_not_found` for an unknown id is the route's).

## MessageEntity (event sourced, id = message id; the campaign's delivery id for origin `crm`)

`playerId`, `category`, `origin` (`operator` \| `crm`), `subject` and `body` (`Personal`, rendered with
the merge tags at write), `createdAt`, `createdBy`, `readAt`, `deletedByPlayerAt`, `delivered`
(from `push_delivered`), and for an operator's message the `subjectTemplate` and `bodyTemplate` it was
written as (`Personal`; added at T033, an absent one reads `""`). Events `Created`, `Read`, `Deleted`,
`Delivered`, `Erased` (blanks the four texts). `Created` for an existing id returns the first answer.

## NoteEntity (key value, id = note id)

`playerId`, `text: Personal`, `author`, `createdAt`, `pinned`. Operators only.

## FraudCaseEntity (event sourced, id = case id)

`playerIds`, `signals` (`identity`, `device`), `status` (`open` \| `reviewed` \| `dismissed`, stored
lower-case; the wire carries the original's `OPEN` \| `REVIEWED` \| `DISMISSED`, `contracts/wire.md`),
`openedAt`, `reviewedBy`, `reviewedAt`, `reviewNotes` (`Personal`: the dismissal's free text may name
either player). Events `Opened`, `Reviewed`, `Dismissed` (with the notes; an event without them reads
as none), `NotesErased`. Dismissal commands `ClearDuplicate` on every player. The erasure of any linked
player commands `eraseNotes` on each case `FraudCasesView` lists for them, which blanks the notes whole
and leaves the status and reviewer.

## LookupIndexEntity (key value, id = token)

`playerIds: Set[String]`. Commands `Add(playerId)` (answers the ids already held), `Remove(playerId)`.
Two tokens per player (identity, device), R9.

## BrandConfigEntity, CurrencyConfigEntity, AllowListEntity (key value, id = `brand`)

`BrandConfig`: `name`, `legalName`, `addresses`, `playerSiteBase`, `reportingZone` (IANA, default
`UTC`), `singleSession` (false), `multiAccountDetection` (true), `retentionDays` (the licence
profile's default), `features: Map[String, Boolean]`. `CurrencyConfig`: `offered: Vector[Currency]`
with per-currency settings. `AllowList`: `countries: Set[String]` (empty = all).

## Workflows

| Workflow | Id | Steps | State |
|---|---|---|---|
| `RegistrationWorkflow` | `sha256(<slug>:<address lower-cased>)` | `start` mints the id and waits in `realm` for the route's realm answer → `player` → `match` → `email` (`email-unanswered` retries for a minute) → `forget`; `undo-realm` on a failed `player`, a realm call that failed or an answer that never came | the attempt, the id minted, the realm user, the outcome (`playerId` or the refusal), the sends, the lookup tokens; nothing personal |
| `SessionEndWorkflow` | player session id | `games` → `session` → `player` | reason, the game sessions ended |
| `ErasureWorkflow` | `erasure/<playerId>` | `file` → `hold` (timer) → `apply` (every service's route, own `Erase`) → `realm` → `done` | correlation id, hold, each service's certificate |
| `ExportWorkflow` | `export/<playerId>/<requestId>` | `own` → `others` → `done` | the assembled response, `missing: Vector[service]`, `expiresAt` (24 h) |

`PendingRegistration` (key value, id = the workflow's id) holds the request the `player` and `email`
steps read: the address, currency, profile, consents and what registration presented, never the
password. `forget` deletes it, so the workflow's journal holds no personal value (T034).

## Timers

| Timer | Kind | Name | Handler |
|---|---|---|---|
| game session limit | single | `game-session-limit/<sessionId>` | `SessionLimits.end(sessionId)` → `GameSessionEntity.End(limit)`, `PlayerEntity.GameSessionEnded` |
| realm reconcile | recurring, 60 s | `realm-reconcile` | `RealmReconcile.run`: live sessions from the realm; `SessionEndWorkflow(expired)` for each open one not live; the gauge |
| erasure hold | single | `erasure-hold/<playerId>` | resumes `ErasureWorkflow` at `apply` |

## Consumers

| Consumer | Source | Does |
|---|---|---|
| `PlayersPublisher` | events of `PlayerEntity`, `MessageEntity`, `FraudCaseEntity` | `casino.players` families (`contracts/facts.md`) |
| `SessionsPublisher` | events of the two session entities | `casino.sessions` families |
| `AuditPublisher` | operator actions' events | `platform.audit` records (spec 018 FR-001's shape) |
| `RgLimitsConsumer` | `casino.players`, `StartFrom.Latest`, family `rg_limit_applied` | `PlayerEntity.SetSessionLimit`; reschedules the timers |
| `KycFactsConsumer` | `casino.players`, `StartFrom.Latest`, families `player_kyc_verified`, `player_kyc_rejected` (spec 009) | `PlayerEntity.KycVerified` / `KycWithdrawn`: the date-of-birth lock |
| `WithdrawalsConsumer` | `casino.withdrawals`, `StartFrom.Latest` | `WithdrawalOpened` / `WithdrawalSettled` on the player (the erasure hold) |
| `PushDeliveredConsumer` | `casino.push`, `StartFrom.Latest` | `MessageEntity.Delivered` |
| `RewardsConsumer` | `casino.rewards`, `StartFrom.Latest`, `bonus_code_entered` | clears `pendingBonusCode` |

## Views

| View | Source | Row | Queries |
|---|---|---|---|
| `PlayersView` | `PlayerEntity` state | `playerId`, `status`, `vipTier`, `potVip`, `flagged`, `displayName: Personal`, `primaryEmail: Personal`, `country`, `currency`, `registeredAt`, `lastSignInAt` | the operator's list (paged, by status, tier, flag), search by display name or address (the `Personal` value's text, redacted after `Erased`) |
| `SessionsView` | both session entities | `sessionId`, `kind`, `playerId`, `parent`, `gameId`, `status`, `startedAt`, `endedAt`, `durationMinutes`, `reason` | by player; open player sessions (the reconciler); open game sessions by player (rescheduling) |
| `MessagesView` | `MessageEntity` | `messageId`, `playerId`, `category`, `origin`, `createdAt`, `readAt`, `deleted`, `delivered` | by player, paged; the unread count is the route's over the rows |
| `NotesView` | `NoteEntity` | `noteId`, `playerId`, `pinned`, `createdAt`, `author` | by player, pinned first |
| `FraudCasesView` | `FraudCaseEntity` | `caseId`, `playerIds`, `signals`, `status`, `openedAt` | open cases; by player |
| `WalletSummaryView` | `money.transactions`, `StartFrom.Earliest` | `playerId`, `currency`, `realBalanceAfter`, `restrictedBalanceAfter`, `lastMovementAt` | by player (FR-034) |
| `RecentlyPlayedView` | the `RecentlyPlayed` entity's state (`<player>/<game>`), written by `RoundsActivity` from `casino.rounds`, `StartFrom.Earliest` | `playerId`, `gameId`, `lastPlayedAt` | by player, newest first (FR-029) |
| `ActivityView` | `money.transactions`, `casino.rounds`, `casino.rewards`, `casino.players` (account families), `casino.sessions`, each through its consumer into an `ActivityEntry` (R11, corrected at T025); the view reads the entity's state | `playerId`, `at`, `category` (`transactions` \| `games` \| `bonus` \| `account` \| `rg`), `summary`, `factId` | by player and category, paged (FR-029, FR-034); never notes or messages |

## Invariants

- Exactly one primary email address; it is the realm's sign-in address.
- A lock-once field with a value is never changed by a player's edit; only `CorrectDateOfBirth`
  changes the date of birth, and never to under 18.
- `openPlayerSessions` holds only sessions whose entity is `active`; the reconciler repairs drift.
- A status transition is one event and one trail entry; a repeat of the same transition is no event.
- `Erased` leaves every `Personal` field empty and every amount, status and time unchanged.
- No row, event or fact carries a lookup token, a raw secret or a token the realm issued.

## Transitions

```
pending_email_verification --VerifyEmail--> active
active --Suspend--> suspended --Reinstate--> active
active --SetSelfExcluded (RG)--> self_excluded --LiftExclusion (RG)--> active
active | suspended | self_excluded | blocked --Close (operator | player)--> closed --Reopen (operator, within hold)--> active
(blocked: only from the migration; no command reaches it)
closed --FileErasure--> erasure_pending --Erase--> erased
```

Sign-in is refused for every status but `active`; a game session opens only for `active`.
