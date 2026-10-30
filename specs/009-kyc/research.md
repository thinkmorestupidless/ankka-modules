# Research: kyc

Decisions for the plan, each with what it rests on. The original is `eitheror-platform` at `e7d8f2de`;
paths without a prefix are under `substrate/casino-service/src/main/scala/io/eitheror/casino/`.

## R1. What the original does, read whole (2026-10-30)

**Finding**: the code differs from the spec as first written, and from its own oracle suites, in
these places. Each is now a decision in the spec's Clarifications or a divergence in the plan.

| Original | Where | Here |
|---|---|---|
| The deposit "gate" is `notify/deposit`, called fire-and-forget **after** the wallet credited; a 403 is logged | `CasinoServiceMain.scala:413-830`; `DepositOnPaymentCaptureFunctionMain.scala:264-285` | the check runs before the deposit starts (FR-019); divergence |
| `cumulative_deposits_minor_units` is written only by an undeployed TL function, so both thresholds compare the one deposit | `player-deposit-threshold-check` (no registration); the gate reads it only as a JSON number | lifetime deposits are real (FR-021); divergence |
| Any operator role with brand scope reaches every per-player KYC route through a fallback arm | `BackofficePlayerRoutes.scala:2188, 2472-2475` | the compliance roles (FR-002; Clarifications 2026-10-30) |
| An incomplete profile makes a derived `verified` **pending** | `rollup.go`, `handler.go`; `IncompleteProfileKeepsAccountPending` | pending, as the original (FR-012; Clarifications 2026-10-30) |
| No documents at all is `unverified` | `rollup.go:190-229` (d) | written into FR-012 |
| The Curaçao default has a 30-day window and suspends on expiry; a deadline is written at every registration | `SubstrateTwinClient.scala:72-81, 437-466` | kept (Clarifications 2026-10-30) |
| The rollup writes `kyc_status` on every fresh invocation, unchanged or not | `handler.go` step 8 | a derivation that changes nothing writes nothing (FR-014) |
| Confirm takes any key: no prefix, no existence check; the view link then signs it | `BackofficePlayerRoutes.scala:247-311` | only an upload this service offered, found in the store (FR-007); divergence |
| Approve records no reviewer; approve and reject of an unknown key answer 200 | `:781-905` | reviewer recorded (FR-010); an unknown document is 404; divergence |
| The policy save never sets `CUSTOM`, and validates nothing | `BackofficeKycPolicyRoutes.scala:117-189` | `CUSTOM` on override (FR-027), values validated; divergence |
| Switching to automatic does not recompute | `:471-514` | recomputes at once (FR-015); divergence |
| `PUT kyc/status pending` records a `KycRejected` change | `:522-577` | `player_kyc_pending` with reason `agent`; divergence |
| `required-doc-types` writes the status with no account event | `:586-669` | `player_kyc_pending` with reason `required_category` |
| Two player view-link routes; `kyc_view_log` overwritten per issue | `:315-383`; `PlayerRoutes.scala:1436-1501` | one route; every issue an event and an audit record (FR-037) |
| `EventIngestConsumer` proposes `kyc_review` per captured payment by a pending player | `EventIngestConsumer.scala:118-190` | not carried over (Clarifications 2026-10-30) |
| The large-deposit TL function is approved in its config but never built or registered; its trigger adds a 1,000,000 floor | `starter-packs/casino/functions/player-kyc-gate-on-large-deposit/` | carried as the large-deposit flag, policy threshold only (FR-038) |
| Rejection texts live in the player site (`uiStrings.ts`), en, de, fr, pt | `casino-player-frontend-next` | the service answers the code; the page renders it (frontends out of scope) |
| `kyc_profile_updated_at` moves only on the player's own profile edit | `PlayerRoutes.scala:89-99`; operator PATCH does not (`PlayerProfileRoutes.scala:793`) | `player_profile_updated` names no actor, so any change of a required field counts; divergence |
| Encryption at rest only with `minio.kmsSecretKey`, set only on kind | `chart/templates/minio/deployment.yaml:33-41` | the installation's store: Google Cloud Storage encrypts every bucket; Garage does not (R17) |

The oracle suites disagree with the code in five places (Playwright 29's reject code, 30's
`kycWindowDays` null and `CUSTOM`, 33's approve status, 50's four categories; Go scenario 28's
`IncompleteProfileStaysUnverified`). Where they disagree, the code is the parity reference, except
where a decision above says otherwise; `tests/original/e2e-rg-kyc.patch`'s scenario 28 test waits for
`pending` from this plan on (T-list).

## R2. The recordings: scenarios 28 and 29 through `e2e-rg-kyc.patch`

**Decision**: replay the 37 KYC tests `tests/original/e2e-rg-kyc.patch` adds (24 in
`scenario28_kyc_documents_review_e2e_test.go`, 13 in `scenario29_kyc_policy_gates_e2e_test.go`), recorded
against a local original by the user, as 010's R1 does for scenarios 26 and 27. One parity test per
recording, `.ignore`d until the run; `WireTranslation` maps the original's paths and shapes onto this
service's (`contracts/wire.md`). Every divergence of R1 the recordings reach is marked in
`Parity.divergences` and must match the plan's *Divergences*.

**Rationale**: constitution I and its workflow rule. The patch exists (010's work); only the run needs
the user (stopping ankka for the original's stack, `tests/original/README.md`).

**What the recordings do not reach** (`e2e-rg-kyc.md` *Not covered*): the queue and tab reads, the
window, retention, the profile-change downgrade, the lowered-threshold flag, the legacy-identity rows.
These are held to the 61 Go rollup and handler cases and `substrate_test.go`'s 7 (R4), the Scala route specs' cases, and the features.

## R3. One event sourced entity per player: `KycRecord`

**Decision**: `KycRecord` (id = player id) holds the documents, offered uploads, the status and mode,
the player's required categories, the source of funds status, the deadline, the flags, the ready time,
the profile's completeness facts and lifetime deposits per currency. Every rule reads several of these
together; each change is one event.

**Alternatives**: an entity per document (a review would then need the account's other documents to
derive the status: two reads and a race); a view for lifetime deposits (spec as first written; the gate
would read a view seconds behind, and SC-003 wants one read).

## R4. The derivation: `Rollup`, a pure port of `rollup.go` with its tests

**Decision**: `Rollup.status(documents, required, profile, current, verifiedAt, documentsChangedAt)`
and `Rollup.readyForFinalReview(...)`, ported from `rollup.go` and `handler.go` with all 61 cases of
`rollup_test.go` and `handler_test.go` as `RollupSuite` rows, and the seven `substrate_test.go` cases as
`PolicySuite` rows on the required categories' fallback. `KycRecord` calls it after every command that
can change an input (a document recorded or reviewed, required categories, mode, profile) and writes a
status event only when the answer differs (FR-014).

The freshness window goes: it existed because the trigger fired on every twin write.
`handler_test.go`'s `StaleTimestampIsNoOp` becomes "a command that changes no input derives nothing".

**Order** (FR-012): rejected; pending; verified (required non-empty and each satisfied, FR-013); with
no document `unverified`; else pending. A derived verified on an account not verified is `pending`
while the profile is incomplete, and `unverified` while the profile changed after the last verification
and both the verification and the last document change. In manual mode only the ready time moves and the
profile-change downgrade applies (FR-016, "in either mode").

## R5. The gates: two routes on the service endpoint, answered 200

**Decision**: `POST /v1/casino/brands/<slug>/kyc/deposit-check` and
`GET /v1/casino/brands/<slug>/kyc/{playerId}/play-check`, the paths the built callers use
(`services/players/.../clients/Neighbours.scala:10-33`; `services/game-provider/.../clients/Neighbours.scala:17`).
Both answer **200**: `{allowed: true}` or `{allowed: false, code, message, kycStatus}` (the play check
also `error`, as spec 003's contract reads). `players` reads any non-2xx as kyc not answering and turns
it into 503 (`Kyc.read`); a 403 refusal would be reported as an outage.

The deposit check is a command on `KycRecord`, carrying the policy the endpoint read from
`PolicyEntity`: the decision and the flags it raises (threshold, source of funds, once each) are one
event, so SC-003's one read holds and a flag is never raised twice under concurrency. The play check is a
read.

The deposit check takes `currency` (FR-019). `players` does not send it today (`DepositRoutes.scala:27`
parses it and drops it): a check with no currency is answered as one in a currency other than the KYC
currency, so it is refused `DEPOSIT_BLOCKED_KYC_CURRENCY` while a threshold is set and decided by the gates
alone while none is (every brand on the Curaçao default). `players` passes it (*Elsewhere*).

**Messages** (verbatim from `CasinoServiceMain.scala:445-495`, `SessionRoutes.scala:285-300`):

| Code | Status | Message |
|---|---|---|
| `DEPOSIT_BLOCKED_KYC` | unverified | `Please verify your identity before depositing` |
| | pending | `Your identity documents are under review` |
| | rejected | `Your identity documents were not accepted — please re-submit` |
| `DEPOSIT_BLOCKED_KYC_THRESHOLD` | any but verified | `Your account requires identity verification before further deposits` |
| `DEPOSIT_BLOCKED_SOF` | | `Your account requires a source of funds review before further deposits` |
| `DEPOSIT_BLOCKED_KYC_CURRENCY` | | `Deposits in this currency are not accepted until your account is verified` (new) |
| `PLAY_BLOCKED_KYC` | unverified | `Please verify your identity before playing` |
| | pending | `Your identity documents are under review` |
| | rejected | `Your identity documents were not accepted — please re-submit` |

## R6. Lifetime deposits: per currency on the record, deduplicated by the wallet's ordinal

**Decision**: `DepositsConsumer` reads `deposit_confirmed` from `money.transactions` and tells
`KycRecord` `CountDeposit(currency, amount, ordinal, transactionId)`. The record keeps, per currency, the
total and the highest `depositOrdinal` counted (the wallet's per player and currency, from 1); a message
whose ordinal is not above it is a repeat and changes nothing. The gate reads the KYC currency's total. A
deposit confirmed for a player with no record creates it (spec Edge Cases).

The same command applies FR-038: a deposit in the KYC currency whose own amount exceeds the deposit
threshold flags a player not verified (`large_deposit`). The consumer reads the policy from
`PolicyEntity` and passes it.

**Rationale**: topics are at least once; the ordinal makes the count exact without keeping every
transaction id. Totals per currency keep the record right if a brand's KYC currency is ever changed.

**Alternatives**: a set of counted transaction ids (unbounded); counting only the KYC currency (a
change of currency would start from zero).

## R7. Documents and the bucket

**Decision**: the service asks the platform for a bucket (`provisionObjectStorage`, already in
`modules/core/module.json`) and exposes it (`exposeObjectStorage: true`), so it is given
`ANKKA_S3_PUBLIC_ENDPOINT` to sign against. It uses the AWS SDK for Java v2 as `platform/object-storage.md`
configures it: path style, `ANKKA_S3_REGION`, checksums only when required, and the
`GenerationAsVersionId` interceptor for Google Cloud Storage.

- **Upload link**: `RequestUpload(category, contentType, by)` checks the lock (FR-006) and the content
  type, mints a document id, and records an `UploadOffered` with the object name
  `subjects/<player>/kyc/<category>-<epoch millis>.<ext>`. The endpoint then signs a PUT for that object
  with the content type signed, valid for an hour, against the public endpoint. Offers older than an hour
  are dropped from state on the next command.
- **Confirm**: `ConfirmUpload(documentId)` refuses an id never offered (404 `document_not_found`). The
  endpoint first reads the object's head through `ANKKA_S3_ENDPOINT`. No object: 404 `upload_not_found`.
  Larger than the brand's limit (`KYC_MAX_UPLOAD_BYTES`, default 5,242,880), or another content type: the
  object is deleted and the confirm refused `file_rejected` naming the rule. A presigned PUT cannot cap a
  size, so the check is at confirm.
- **View link**: a GET signed for fifteen minutes. `IssueView(documentId, requester)` records
  `ViewIssued` (FR-037, every issue) and the endpoint signs.
- **Operator upload**: the same commands with `by = operator(id, reason)`. The reason is `Personal`. The
  document shows as submitted by support.

**Browser origins**: the vendor's manifest schema has `exposeObjectStorage` but not
`objectStorageOrigins`. The player site proxies the signed PUT and GET today (`/api/kyc-upload`,
`/api/kyc-view`), server to store, and needs no origin. A page uploading straight from the browser waits
for the vendor's schema to carry origins (*For ankka*).

## R8. Review, modes and required categories

**Decision**: commands on `KycRecord`: `Review(documentId, outcome, reasonCode, operator)` (FR-010; the
reason code validated before the document's state, as the original), `SetMode`, `SetStatus` (pending or
verified, verified refused `profile_incomplete` with the missing fields), `SetRequired(categories)`
(FR-017), `Override(verified | rejected, reason, operator)`. Override is the original's `POST kyc/approve`
and `PATCH kyc/reject`, which bypass the profile gate (`review.feature`: "regardless of the documents").
Every status change records its actor and reason and becomes a fact and an audit record.

## R9. The policy: `PolicyEntity`, event sourced, one per brand

**Decision**: `PolicyEntity` (id = brand slug). With no event it answers the Curaçao default, marked as
a default. `ApplyProfile(profile)` sets every field from the table below. `Change(fields)` validates
each field and sets the profile to `CUSTOM`. Every change is a `PolicyChanged(operator, before, after)`,
so the audit names each field (FR-028). It is event sourced, as 010's settings are, because a consumer of
a key value entity may skip intermediate values.

| Profile | Deposit gate | Play gate | Currency | Deposit threshold | SoF threshold | Window | Required | Suspend |
|---|---|---|---|---|---|---|---|---|
| CURACAO (default) | NONE | NONE | none | none | none | 30 days | identity | yes |
| MGA | NONE | NONE | EUR | 200000 | 1000000 | none | identity, proof_of_address | no |
| UKGC | KYC_APPROVED | KYC_APPROVED | GBP | none | 200000 | 3 days | identity, proof_of_address | yes |

Values from `BackofficeKycPolicyRoutes.scala:39-62` and `SubstrateTwinClient.scala:72-81`. The currency
column is new (Clarifications 2026-10-30). A threshold without a currency is refused `currency_required`.

**Tightened policy** (FR-022): `PolicyFlagger`, a consumer of `PolicyChanged`, finds the players whose
lifetime deposits in the KYC currency exceed a lowered threshold through `KycQueue` (R12) and tells each
record `FlagForPolicy`. The record flags once.

## R10. The window: a timer per player

**Decision**: `RegistrationConsumer` reads `player_registered` and tells `KycRecord`
`SetDeadline(registeredAt, policy)`. Under a policy with a window, that records the deadline, and a timer
`kyc-window-<player>` is set at it, replaced by name. `WindowTimer` calls `WindowLapsed(deadline)`, which
does nothing for a verified or closed player or a deadline already handled. Otherwise it flags `window`,
publishes `player_kyc_review_required`, and answers whether to suspend.

To suspend, the timer calls `players`' `POST /v1/casino/brands/<slug>/players/{playerId}/suspend` with
`{reason: "kyc_window_expired", deadline}`. A failure throws, and the timer runs again with backoff. The
record keeps `SuspensionRequested(deadline)`, so a retry after a 200 is not made.

A migrated deadline already past flags and never suspends (FR-025): the opening event marks it `migrated`.
`player_closed` cancels the timer and clears the flags (FR-031).

**Rationale**: a timer is the deadline, as 010's breaks are. Today `players`' suspend is idempotent by
reason only (`PlayerEntity.scala:288-294`). The record's own `SuspensionRequested` makes this side
correct whatever `players` does, and *Elsewhere* asks `players` to honour the deadline as its contract
says.

## R11. Profile facts: completeness without the profile

**Decision**: `ProfileConsumer` reads `player_profile_updated` and tells the record `ProfileChanged`
with:
- which of the eight fields are present;
- the date the player turns eighteen, as `Personal[LocalDate]`, from the date of birth;
- whether a required field changed (`changedFields`).

Completeness is checked against today's date on every derivation, so a player who turns eighteen
completes without a new fact. No field's value is kept.

The players fact carries `givenName, familyName, dateOfBirth, telephone, addressLine, city, postCode` as
`Personal` and `country` plain (`CasinoPlayers.scala:59-64`). They map to the original's `firstName …
country`.

## R12. Reads: `KycQueue` and the record

**Decision**: `KycQueue`, a view of `KycRecord`'s state, has one row per player: status, mode, documents
awaiting review, the oldest pending document's time, the ready time, open flags by reason, source of
funds status, and lifetime deposits in each currency. It has queries per filter (FR-035) and a count per
filter. A list sorts oldest first unless `sort=newest`, as the original
(`BackofficePlayerRoutes.scala:1559-1640`). The player's and operator's document reads come from the
record itself (FR-036).

## R13. Facts and audit

**Decision**: `KycPublisher`, a consumer of `KycRecord`'s events, publishes FR-030's families to
`casino.players`. `AuditPublisher` publishes `platform.audit` records from `KycRecord` and `PolicyEntity`:
status changes, reviews, clearances, views and policy changes. Neither ever carries a `Personal` value.

All seven families already exist in the contract (`CasinoPlayers.scala:87-113`). **Contract change**
(additive): `player_kyc_pending.reason: Option[String]`, and `large_deposit` in the documented reasons of
`player_kyc_review_required`.

## R14. Routes under ankka 0.13's limits

**Decision**: four endpoints, one prefix each, the slug a literal, at most two path parameters, the ACL
per route (008 R14, 010 R14):

| Endpoint | Prefix | ACL |
|---|---|---|
| `PlayerEndpoint` | `/v1/casino/kyc/<slug>` | `Routes.player`; the path's player checked against `Routes.playerId` |
| `OperatorEndpoint` | `/v1/casino/backoffice/<slug>/kyc` | `Routes.operator` plus the compliance roles (FR-002); `Routes.operator` checks only `brand_<slug>`, so the endpoint checks the role |
| `ServiceEndpoint` | `/v1/casino/brands/<slug>/kyc` | `Routes.serviceOf("players")` for the deposit check, erasure and export; `Routes.serviceOf("game-provider")` for the play check |
| `PublicEndpoint` | `/v1/casino/kyc-requirements/<slug>` | `Routes.public` |

The compliance roles are `brand_admin`, `brand_compliance`, `compliance_officer`, `resource_admin` and
`platform_admin` (Clarifications 2026-10-30). The policy routes take the original's list
(`brand_admin, brand_compliance, resource_admin, platform_admin`) plus `compliance_officer`.

## R15. Erasure and export

**Decision**: `POST /erasure/players/{id}` and `GET /exports/players/{id}` on the service endpoint, for
`players` only (010 R15).
- **Erasure** lists every object and version under `subjects/<id>/` and deletes each. It then records
  `Erased`, which blanks every `Personal` value: the age date, operator upload reasons, override and
  dismissal notes. Ids, categories, times, statuses and reason codes stay (FR-033). It answers a
  certificate and runs again on every call, so a late upload goes (FR-033).
- **Export** returns the document records without the objects (008 R10).

While ankka 042 is missing, the journal keeps earlier events' values: 008's R8, named the same way.

## R16. `kyc-provider-key` goes

**Finding**: `modules/core/module.json` gives `kyc` a secret `KYC_PROVIDER_KEY` from `kyc-provider-key`
(`contract/keys.md:47`). goldvibe declares `goldvibe-kyc/API_KEY`, and the modules tool's tests use it.
The spec has no identity provider and no secret beyond the bucket credential (Dependencies).

**Decision**: remove the key from the core manifest, `contract/keys.md`, goldvibe's values and the
tool's fixtures and tests. A brand would otherwise be held for a key nothing reads (`LocalApplySuite`:
"core held: missing kyc-provider-key").

## R17. Encryption at rest is the installation's store

**Finding**: Google Cloud Storage encrypts every bucket, with the installation's key when it names one.
Garage holds objects unencrypted, protected by the volume and the credential
(`reference/limitations.md`).

**Decision**: FR-009 holds on an installation whose store is Google Cloud Storage, which a real-money
installation is. On Garage (kind) it depends on the volume, and the README says so. Nothing in the
service encrypts: a key it held would be one more secret to lose.

## R18. Settings

`KYC_MAX_UPLOAD_BYTES` (default 5,242,880) and the content types are fixed in code
(`image/jpeg`, `image/png`, `application/pdf`; FR-005). `players`' address is `localServices` locally, as
in 010. No secret.

## For ankka (checked against 0.13.0, the runtime every service pins)

- **Object storage** (034/039): in the runtime. A bucket per service, signed URLs against the public
  endpoint, versions on Google Cloud Storage. Garage keeps one version.
- **`Personal` and `erase(subject)`** (042): not in the runtime. The marker and redaction are this
  service's (R15). The bucket's objects are deleted by the service itself through S3.
- **Timers by name with replace** (032): in the runtime (R10).
- **Browser origins on a bucket**: in ankka's descriptor (`objectStorageOrigins`), not yet in the
  vendor's manifest schema (R7).

## Findings

*Written at task time.*

- **T006, the 61 Go cases.** 53 are ported as rows named after their Go tests: 47 in `RollupSuite` (23 of
  `rollup_test.go`, 24 of `handler_test.go`) and 6 in `CompletenessSuite` (the profile rows of
  `rollup_test.go`). 8 test the original's wire and apply to no typed state: `ParamsDecodeNumericProfileValues`,
  `ParseKycRequiredDocTypesShapes`, `ParseKycDocuments`, `ParseKycDocumentsEmptyOrMalformed`,
  `ParseRequiredDocTypesShapes`, `InvalidParams`, `MissingRequiredParams` and `NumericProfileValuesFromRuntime`.
  `AnyDocTypeRejectionForcesRejected` rejects a `selfie`, which is no category here; a category not
  required (`payment_method`) stands in. Where the original rewrote an unchanged status, the row expects
  nothing (FR-014). The categories, statuses, modes and outcomes went into `record/Model.scala` with the
  port; T011 adds the rest of the model there.
- **T013, when the record derives.** A record with no document derives nothing: the original's trigger
  was `twin.kyc_documents != ""`, so an operator's status on a player with no document is never
  recomputed by a profile fact. Either mode switch derives (FR-012 names "the mode changes"); a switch
  to manual can only move the ready time. An operator's `SetStatus` or `Override` is not re-derived in
  automatic mode, or the derivation would undo it at once; in manual mode the ready time follows it.
  `Open` on a record that is not empty is refused `record_exists`, for the migration (spec 019) alone.
  The review's outcome, the mode and the operator's status are parsed by the endpoint, which answers
  the contract's 400s; the record's commands take them typed.
- **T025, mode and required changes are audited (Trevor, 2026-10-30).** T025 asked for a mode change
  and a required-category change each in `platform.audit` with the operator; contracts/facts.md listed
  no action for either, so neither was audited unless it moved the status. Decision: two actions,
  `kyc.mode_changed` (mode before and after) and `kyc.required_changed` (the categories before and
  after), with the operator as actor, so every operator decision on how a player is verified is
  attributable. contracts/facts.md gains both; no other spec names KYC's actions.
- **T044, the queue's filter values.** routes.md names `?filter=&reason=&sort=` and the counts, not the
  values. They follow the counts' names: `awaiting`, `ready`, a status (`unverified`, `pending`,
  `verified`, `rejected`), `flagged` with an optional `reason` (a flag's wire name; none lists every open
  flag), and `sof` for the source of funds flag. Anything else is `400 invalid_filter`. Each row carries
  `sinceSeconds` from its filter's time (ready, flagged, oldest upload, last change). A read lists at most
  1,000 rows; the counts are not capped. Naming only: the frontends' move (T054) reads these.
- **T032, the queue's defaults.** The row's codec leaves a default out (`status` "unverified", `sof`
  "unverified", `awaiting` 0), so the queries compare with `IS DISTINCT FROM` or `COALESCE`, and a
  parameter used as a JSON key goes through `jsonb_extract_path_text`: ankka's query check does not read
  `->> :name` as a parameter.

## Design finished

2026-10-30: analyze reported nothing above LOW.
