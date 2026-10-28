# Data model: attribution

Phase 1 of [plan.md](plan.md). Four event sourced entities, seven key value entities, one workflow, four
views, five consumers of the brand's topics, three fact publishers, two carrier consumers, two timed
actions. No `Personal` value is stored anywhere here: a player is a player id (not personal, 001 FR-024),
a visitor is a click id and a salted address digest (research R5), and every fact carries a pseudonym.
Every id is a `String`; every time an `Instant`; every amount a `Long` of minor units; every day an ISO
date in the reporting zone.

## Shared types

- `Binding(network, affiliate, link, campaign: Option, subIds: Vector, clickId: Option, pseudonym, boundAt, touch)`;
  `touch` ∈ `tag`, `registration_code`, `later_code`.
- `TagOutcome`: `Valid(binding fields, kid, iat)` or `Refused(reason)`; reasons `malformed`, `legacy`,
  `legacy_unknown`, `unknown_network`, `withdrawn_key`, `key_unreachable`, `bad_signature`,
  `revoked_link`, `revoked_affiliate`, `stale`, `network_not_ready`.
- `Touch(kind: tag | code | link, value, at, outcome)`: a later touch (FR-009).
- `MoneyEvent(kind, currency, amount, reference, at, dedupeId)`: kinds `deposit`, `chargeback`,
  `withdrawal-requested`, `withdrawal-completed`, `withdrawal-failed`, `withdrawal-reversed`,
  `bonus-cost`.
- `Fact`: the contract's `AffiliatesAttributionFact` (one of the fourteen kinds).
- `Cursor`: base64url of `<network>:<seq>`; the empty cursor is the start.

## `Settings`: key value, id `brand`

| Field | Meaning | Default |
|---|---|---|
| `attributionWindow` | how long after `iat` a tag binds (FR-008) | 30 days |
| `bonusCodeWindow` | how long after registration a later code binds (FR-007) | 30 days |
| `firstDay` | the brand's first day on the protocol (FR-004) | set at first start |
| `transitionEnd` | `Option[(day, endedBy)]`, `endedBy` ∈ `12_months`, `90_days_without_legacy` | none |
| `lastLegacyDay` | the last day a legacy tag was presented | none |
| `legacyDaily` | `Map[day, count]` of legacy tags presented, kept 90 days (SC-008) | empty |
| `signingKeys` | `Vector[(kid, publicKey, madeAt, retiredAt: Option)]` (research R14) | empty |

Commands: `setWindows(attribution, bonusCode, by, at)` (each 1 to 365 days), `start(day)` (sets
`firstDay` once; a repeat is a no-op), `legacySeen(day)`, `endTransition(day, by)` (once),
`addSigningKey(kid, publicKey, at)`, `retireSigningKey(kid, at)` (refused for the last live key), `get`.

## `Network`: key value, id the network id

| Field | Meaning |
|---|---|
| `keyDocument`, `revocationList` | addresses (FR-025) |
| `legacyTagDocument`, `codeDocument` | addresses, optional; the programme's are set |
| `roundStream` | `ggr-round` on for this network (FR-016), default off |
| `machine` | `Option[String]`, `<organization>/<name>` of the machine admitted to its feed |
| `pseudonymKeyMade` | whether `network/<id>/pseudonym-key` exists in the store (the key itself is never here) |
| `removed` | `Option[Instant]` (FR-025: no new binding; nothing else changes) |
| `addedBy`, `addedAt`, `updatedAt` | |

Commands: `add(fields, by, at)` (refused when it exists and is not removed), `update(fields, by, at)`,
`setRoundStream(on)`, `setMachine(m)`, `pseudonymKeyMade(at)`, `remove(by, at)`, `get`. A network id is
1 to 64 characters of `[a-z0-9-]`.

## `NetworkDocuments`: key value, id the network id

The cache of the four documents (research R3): per document `{fetchedAt, expiresAt, body: Option, lastError: Option}`,
with the parsed keys (`Map[kid, publicKey]`), revocations (`links: Set`, `affiliates: Set`), legacy tags
(`Map[uuid, (affiliate, link)]`) and codes (`Map[lowercased code, (offer, tag)]`). Written by
`DocumentRefresh` and by a consumer's on-demand fetch; `fresh(kind, now)` says whether a document may be
used. Public documents, not secrets.

## `Click`: key value, id the click id

| Field | Meaning |
|---|---|
| `binding fields` | network, affiliate, link, campaign, subIds, from the tag |
| `at` | the landing time |
| `deviceType` | `mobile`, `desktop`, `unknown` |
| `country` | `Option[String]`, ISO 3166-1 alpha-2 |
| `addressDigest` | `Option[String]`, salted SHA-256 (R5), never in a fact |
| `utm` | `Map[String, String]` of the five `utm_*` values |
| `refused` | `Option[reason]` when the tag was refused (recorded, no fact) |

Commands: `record(click)` (a repeat under the same id is a no-op: at most one click per id, FR-006),
`get`. `expireAfter(60 days)`: a click is read only to refuse a repeat of its id and by the operator;
binding reads the registration fact's tag and click id, never the click, and the fact was emitted. The
short life also narrows research finding 1 (the digest's status), which is spec 001's to settle.

## `AttributionRecord`: event sourced, id the player id

The brand's regulated record (FR-007, FR-009, FR-028).

| State field | Meaning |
|---|---|
| `registeredAt` | from `player_registered`; the bonus code window counts from it |
| `binding` | `Option[Binding]`, set once |
| `refused` | `Option[(tag, reason, at)]`: the stale or refused tag at registration (FR-005) |
| `touches` | `Vector[Touch]`, the last 100 |
| `firstDepositAt` | `Option[Instant]`, set at the first `payment.captured` whether or not bound (FR-015) |
| `pending` | `Vector[MoneyEvent]` while unbound, at most 200 and 48 hours (research R8) |
| `emitted` | `Set[dedupeId]` of money events already recorded, the last 1,000 |
| `disputes` | `Set[paymentId]` with a chargeback recorded |
| `closed` | `Option[(category, at)]` once a `player-closed` was recorded (FR-027: once) |
| `erased` | `Option[Instant]` |

| Command | Event | Rule |
|---|---|---|
| `registered(at, tag outcome, code outcome, clickId)` | `Registered`, then `Bound` or `TagRefused`, and `TouchRecorded` for a code beside a tag | once; a repeat is a no-op (the fact's `playerId` is the id) |
| `bind(binding)` | `Bound` | refused `AlreadyBound` when bound; then the caller records a touch |
| `touch(touch)` | `TouchRecorded` | always; the last 100 kept |
| `money(event)` | `DepositRecorded` (with `first: Boolean`), `ChargebackRecorded`, `WithdrawalRecorded`, `BonusCostRecorded`; or `PendingRecorded` while unbound | a seen `dedupeId` is a no-op; a deposit sets `firstDepositAt` when unset; a chargeback for a payment already in `disputes` with the same reference is a no-op |
| `close(category, at)` | `Closed` | once; a second is a no-op |
| `erase(at)` | `Erased`, and `Closed(closed)` when none was recorded | once |
| `get` | | the record, for the operator and the erasure answer |

`Bound` carries the binding and the pending events at or after `boundAt`, which the publisher emits
then (R13); the rest of the pending list is dropped. Invariants: at most one `Bound`; `firstDepositAt`
never moves; nothing a network says reaches a command here (FR-024).

## `WalletCursor`: key value, id `<player>/<currency>`

`lastRealAfter: Long`, `recent: Vector[dedupeId]` (the last 200). Command `advance(dedupeId, realAfter)`
→ `Delta(realAfter − lastRealAfter)` or `Seen`. Every `money.transactions` family advances it, in the
topic's order per wallet (research R10). The first fact of a wallet starts from 0, as the wallet's
balance does.

## `RoundIndex`: key value, id `<player>/<currency>/<round>`

`day: String`, `firstStakeAt`. Command `remember(day, at)` (the first wins), `get`. `expireAfter(7 days)`.

## `DayTally`: event sourced, id `<player>/<currency>/<day>`

| State field | Meaning |
|---|---|
| `stake`, `win` | real minor units for the day; GGR is `stake − win` |
| `seen` | `Set[dedupeId]` |
| `rounds` | `Map[roundId, (stake, win, emittedStake, emittedWin)]` for `ggr-round` |
| `closed` | `Option[Instant]` |
| `adjustments` | count, for the operator's read |

| Command | Event | Rule |
|---|---|---|
| `leg(kind, roundId, dedupeId, realDelta, at)` | `LegRecorded` while open; `Adjusted(delta)` once closed | a seen id is a no-op; kinds `stake`, `win`, `rollback`, `loss` |
| `close(at)` | `Closed` | a no-op when closed; `Closed` carries the totals |
| `get` | | |

A `LegRecorded` for a settling kind (`win`, `rollback`, `loss`) also carries the round's totals since its
last emission, for `TallyFacts` to make a `ggr-round` fact when the network's stream is on (R10).

## `NetworkFeed`: event sourced, id the network id

`lastSeq: Long`, `recent: Vector[factId]` (the last 10,000). Command `append(fact)` → `Appended(seq, fact)`
or `Seen`. The feed's ledger (research R14); its events are what `FeedRows` and `WebhookFanout` read.

## `WebhookSubscription`: key value, id a UUID

`network`, `address` (https only), `active`, `secretName` (`webhook/<id>/secret`), `createdBy`,
`createdAt`, `updatedAt`. Commands `create`, `update(address, active)`, `delete` (also deletes the
secret, from the endpoint), `get`.

## `Delivery`: workflow, id `<subscriptionId>/<factId>`

| State field | Meaning |
|---|---|
| `subscription`, `factId`, `network` | |
| `fact` | the fact as JSON text, signed on each attempt |
| `firstAttemptAt`, `attempts`, `lastStatus`, `lastError` | |
| `status` | `delivering`, `delivered`, `undelivered`, `discarded` |

Steps: `post` (reads the secret in the step, posts, ends `delivered` on `2xx`; otherwise pauses with the
next delay of research R15, or ends `undelivered` when 24 hours have passed since `firstAttemptAt`).
Commands: `start(fact)` (a no-op on an existing workflow), `resend` (from `undelivered`: `firstAttemptAt`
reset, back to `post`), `discard` (from `undelivered`). `RecoverStrategy`: a thrown error is a failed
attempt, not a failed workflow.

## Views

| View | Over | Rows and declared queries |
|---|---|---|
| `Records` | `eventsOf(AttributionRecord)` | one row per player: binding, touch count, first deposit, closed; `byPlayer(id)`, `countsByNetwork` (the status route) |
| `OpenDays` | `eventsOf(DayTally)` | one row per open tally: player, currency, day; `openBefore(day)` for `DayCloser` |
| `FeedRows` | `eventsOf(NetworkFeed)` | one row per fact: network, seq, factId, kind, fact; `page(network, afterSeq, limit)` ordered by seq |
| `Deliveries` | `stateOf(Delivery)` | one row per workflow: subscription, network, factId, status, attempts, lastStatus; `undelivered(network)` |

## Consumers of the brand's topics

| Consumer | Topic (from `Earliest`, with the contract) | Does |
|---|---|---|
| `PlayersFacts` | `casino.players` | `player_registered` → verify the tag and the code (research R6), derive the pseudonym, `AttributionRecord.registered`; `player_closed` → `close(closed)`; `player_self_excluded` with no `until` → `close(self-excluded)`; the rest ignored |
| `RewardsFacts` | `casino.rewards`, registered only when `CASINO_MODULES` names `casino` | `bonus_code_entered` → resolve the code; `bind` within the bonus code window or `touch` (R7) |
| `PaymentsFacts` | `payments.payments` | `payment.captured` → `money(deposit)`; `payment.dispute.opened`, `payment.refunded` → `money(chargeback)`; the rest ignored |
| `WithdrawalsFacts` | `casino.withdrawals` | the four families → `money(withdrawal-*)`; approved and cancelled ignored |
| `MoneyFacts` | `money.transactions` | every family → `WalletCursor.advance`; `bet_placed` → `RoundIndex.remember`, `DayTally.leg(stake)`; `win_credited`, real `provider_credited` → `leg(win)`; `bet_rolled_back` → `leg(rollback)`; `round_settled_as_loss` → `leg(loss)`; `restricted_funds_awarded`, restricted `provider_credited` → `money(bonus-cost)` |

The dedupe id of a topic message is its `ce-id` as the producer set it (the wallet: the transaction id,
or `<transactionId>/<recordId>`); when the runtime does not surface it, `<family>/<transactionId>/<awardId or
empty>` (research *For ankka*).

## Fact publishers (to `affiliates.attribution`, keyed by the pseudonym, `ce-id` the fact id)

| Publisher | Over | Emits |
|---|---|---|
| `ClickFacts` | `stateOf(Click)` | `click` for a click with no `refused`, keyed by the link |
| `RecordFacts` | `eventsOf(AttributionRecord)` | `Bound` → `registration`, the pending money events, the before-binding `ggr-daily` facts (read from `OpenDays`'s sibling query over closed tallies); `DepositRecorded` → `deposit` and `first-deposit`; `ChargebackRecorded`, `WithdrawalRecorded`, `BonusCostRecorded` → their kinds; `Closed` → `player-closed`; nothing while unbound |
| `TallyFacts` | `eventsOf(DayTally)` | `Closed` with movement → `ggr-daily`; `Adjusted` → `ggr-adjustment`; a settling `LegRecorded` → `ggr-round` when the player's network has the stream on; reads `AttributionRecord.get` for the binding; nothing for an unbound player |

## Carrier consumers and timed actions

| Component | Kind | Does |
|---|---|---|
| `FeedAppender` | consumer of `affiliates.attribution` (own topic, `Earliest`) | `NetworkFeed(fact.network).append(fact)` |
| `WebhookFanout` | consumer over `eventsOf(NetworkFeed)` | `Delivery.start` per active subscription of the network |
| `DayCloser` | timed action, recurring every 5 minutes | closes `OpenDays.openBefore(today)` whose day ended ≥ `CLOSE_GRACE` ago; ends the transition when due (R16); prunes `Settings.legacyDaily` |
| `DocumentRefresh` | timed action, recurring every minute | refreshes every network's expired or due documents into `NetworkDocuments` |

## Settings (`Settings.scala`, from the environment)

| Variable | Meaning | Default |
|---|---|---|
| `CASINO_BRAND`, `CASINO_PROJECT`, `CASINO_REAL_MONEY` | as every core service | required |
| `CASINO_REPORTING_ZONE` | the reporting zone (IANA), configuration key `reporting-zone` | `UTC` |
| `CASINO_MODULES` | the brand's modules, comma-separated, `core` among them; `casino` registers `RewardsFacts` | empty (the tool renders at least `core`) |
| `COUNTRY_HEADER` | the header carrying the visitor's country | `CF-IPCountry` |
| `DOCUMENT_TIMEOUT` | per-request bound on a document fetch | `10s` |
| `CLOSE_GRACE` | how long after midnight a day closes | `15m` |
| `WEBHOOK_TIMEOUT` | per-attempt bound on a webhook post | `10s` |
| `ANKKA_AUTH_*`, `ANKKA_SECRET_KEY` | as the manifest renders them | |

Service secrets: `network/<id>/pseudonym-key`, `signing-key/<kid>`, `webhook/<id>/secret`, `click-salt`.

## Pure logic (no component)

`Tag` (parse, verify, the legacy rule), `Documents` (parse the four), `Pseudonyms`, `Signing` (Ed25519
sign and verify, key generation), `Jcs` (canonical JSON), `RealMoney` (the counted delta per family),
`Days` (the zone's day of an instant, the next close), `FactIds`, `Facts` (record and tally events into
contract facts), `Backoff` (the webhook schedule), `Pages` (cursor encoding, page assembly and signing).

## Changed at implementation (2026-10-25)

- `Delivery`'s retry is a `retry` command a timed action (`DeliveryRetry`, timer `delivery-retry/<id>`) calls;
  the workflow keeps the attempt rules of `DeliveryRules`.
- `WalletCursor.recent` keeps each recent id with its delta, so a repeat answers `Seen(delta)`.
- `NetworkDocuments` is refreshed ahead (`DocumentRefresh.Ahead` is two minutes), and its `put` ignores a copy
  fetched before the one held.
- Every fact on `affiliates.attribution` carries `ce-subject` equal to its key.

