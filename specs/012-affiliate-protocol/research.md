# Research: attribution

Phase 0 of [plan.md](plan.md). The original is `eitheror-platform` at `659c2d6b` (the commit spec 006's
recordings were made at): `substrate/affiliate-service/src/main/scala/io/eitheror/affiliate/`
(`ClickTrackingRoutes.scala`, `InternalRoutes.scala`, `CommissionEngine.scala`),
`substrate/casino-service/src/main/scala/io/eitheror/casino/AffiliateServiceClient.scala` and
`CasinoServiceMain.scala`, `tl/runtime/capabilities/affiliate_notify_ggr/handler.go`, and the player
site's `tools/casino-player-frontend-next/src/lib/utm.ts`. ankka facts are from the 0.13.0 docs the
ankka MCP serves (`platform/cross-project-access.md`, `build/timers.md`, `build/secrets.md`,
`build/consumers.md`); the repository pins 0.13.0 (`services/*/build.sbt`).

## R1. What the original does, and what it gets wrong

**The click.** `POST /v1/track/click` (`ClickTrackingRoutes.scala:29-137`): body `btag` (required, else
`400`), `ip`, `userAgent`, `deviceType`, `utmSource`, `utmMedium`, `utmCampaign`, `utmContent`; the
`clickId` the link carried is read by the browser (`utm.ts`) and sent, but the route ignores it and mints
a UUID; answers `{"ok":true,"clickId":"<uuid>"}`, or `"unknown"` for a `btag` no link twin names. One click
per browser session is the browser's rule (`utm.ts:113-117`, `sessionStorage` `eo_click_tracked`), never
the server's. `ipHash` is an unsalted SHA-256 and empty in practice; no country is kept.

**The notifications.** Seven internal routes under `/internal/v1/affiliates/{brand}/notify/*`, header
`X-Internal-Secret`, major-unit decimal strings (`InternalRoutes.scala:607-1970`):

| Route | Called by | What it does |
|---|---|---|
| `registration` | casino-service at registration (`AffiliateServiceClient.scala:31-76`) | first tag wins: an existing `affiliate-registration-{b}/{playerId}` twin is kept; `clickId` is never forwarded (bug 2) |
| `deposit` | casino-service on every confirmed deposit (`CasinoServiceMain.scala:653`) | totals; the first in the player's CPA period is the FTD (`ftdPlayerPeriodId`, per month) |
| `ggr` | the TL capability `affiliate_notify_ggr`, per settled round, off by default (`values.yaml:337-343`) | one fact per round, idempotent on the round id |
| `withdrawal` | casino-service on an operator's *approve* (`WithdrawalRoutes.scala:114-130`) | adds to the player's withdrawals |
| `withdrawal-reversal` | every transition to FAILED and the payout sweeper (`PayoutReconciliationSweeper.scala:330,347`) | subtracts |
| `bonus-cost` | casino-service after a bonus granted for a player code (`CasinoServiceMain.scala:743`) | the call sends `claimId`; the route requires `transactionId`: always `400` |
| `chargeback` | an internal route of casino-service "called by merchant-service or psp-gateway" (`CasinoServiceMain.scala:978`) that nothing calls | never reached |

A bonus code at registration is written to the player twin only (`BonusRoutes.scala:2626-2780` for later
codes writes the same), never to the registration twin: bug 1. The commission fold (`CommissionEngine.scala`,
ADR 0042) and the one-shot postback (`firePostback`, `InternalRoutes.scala:85-116`) leave the brand for
spec 013.

**GGR.** `handler.go` re-reads the round twin and takes `deposit_stake`/`deposit_win` when present
(spec 253: deposit-origin money only), else `real_stake`/`real_win` (spec 250: real money only), else the
totals; a round with no real money or a free-spins round is skipped; GGR is stake minus win in cents. The
three scenario-15 tests of these rules (`RestrictedOnlyRoundEarnsNoAffiliateGGR`,
`SplitRoundEarnsRealGGROnly`, `ConvertedMoneyRoundEarnsNoAffiliateGGR`) and scenario 17 skip themselves
without the affiliate service, which the KinD stack 003's recordings were made on did not run: no
recording of either exists (R19).

**Decision**: the click route is served verbatim at its path and with its answer; everything else is
replaced as the spec's *Changes from the original* says, with the divergences plan.md lists.

## R2. The tag: a compact signed token under `btag`

**Decision**: the token is three base64url segments, `header.payload.signature`, the JWS compact form,
so any affiliate tool can mint and check one with a standard library:

- header `{"alg":"EdDSA","kid":"<key id>","typ":"eo-tag"}`;
- payload `{"v":1,"net":"<network>","aff":"<affiliate>","lnk":"<link>","cmp":"<campaign>","sub":["s1",…],"iat":<seconds>}`,
  `cmp` absent when there is none, `sub` at most five;
- the signature Ed25519 over `header.payload`, through the JDK's `java.security.Signature("Ed25519")`
  (no library).

Ed25519 because a signature is 86 characters, the whole token under 400 for ordinary ids, so it survives
a URL and email tooling, and because the JDK verifies it natively. Longer than 1 KiB, not three segments,
not base64url, a header naming another `alg`, or a payload missing `net`, `aff`, `lnk`, `kid` or `iat`:
`malformed`. A value matching the UUID pattern is a legacy tag (R6). The grammar replaces 008 FR-003's
`^[A-Za-z0-9_.:-]{1,128}$`: a tag is at most 1,024 characters of `[A-Za-z0-9_.-]` (finding 2).
[contracts/protocol.md](contracts/protocol.md) is the normative text.

**Alternatives rejected**: an HMAC under a shared secret (a secret per network per brand, which FR-002
forbids: the brand holds the network's keys and nothing else); RSA or ECDSA (longer or DER-variable
signatures for no gain); an opaque id resolved at the network (FR-023 forbids a call at registration).

## R3. The documents a network publishes

Four JSON documents, each fetched as a public client, each saying how long it may be cached
([contracts/protocol.md](contracts/protocol.md)):

| Document | Who | Holds |
|---|---|---|
| key document | every network, and the brand (R14) | `keys[]` of `{kid, kty: "OKP", crv: "Ed25519", x}`, `cacheSeconds`; the brand's also `transition` (R16) |
| revocation list | every network | `links[]`, `affiliates[]`, `refreshSeconds`, `asOf` |
| legacy tag document | the programme, during the transition | `tags{<uuid>: {affiliate, link}}`, `version`, `cacheSeconds` |
| code document | a network that issues bonus codes; the programme per brand (013 FR-023a) | `brand`, `version`, `at`, `codes[]` of `{code, offer, tag}`, `cacheSeconds` |

**Decision**: `NetworkDocuments` (key value, one per network) caches all four with their expiry and the
last error; `DocumentRefresh`, a recurring timer every minute, refreshes what has expired or is due;
a click whose tag names a `kid` the cache lacks fetches the key document at once, from the click route,
before deciding (the feature's first scenario); registration never fetches (FR-023): a `kid` the cache
lacks is `withdrawn_key` when a document is cached and `key_unreachable` when none is, the tag kept. A document that cannot be fetched keeps what was cached until it expires,
after which its tags are refused `key_unreachable`, and the status route names the network unreachable.
Fetching is `java.net.http.HttpClient` with a 5 s connect and `DOCUMENT_TIMEOUT` per request, as
`psp-gateway`'s `ProviderHttp` does it, no redirects, at most 1 MiB. The code document is the addition
to FR-023 (spec correction 3): without it a brand cannot tell an affiliate's code from its own, and
`player_registered.bonusCode` and `bonus_code_entered.code` are raw text (008 FR-004, 011 FR-027).

## R4. The pseudonym

**Decision**: HMAC-SHA256 of the player id under the network's pseudonym key, base64url without padding
(43 characters). The key is 32 random bytes the operator's route generates (`POST …/networks/{id}/pseudonym-key`,
refused when one exists), kept as the service secret `network/<id>/pseudonym-key` and never rotated. The
secret store is not offered to entities, so the pseudonym is derived in the consumer that binds
(`PlayersFacts`) and recorded on the binding; publishers read it from the record and never touch the
store. The key is cached in the consumer's process after its first read, which is safe because it is
never rotated. A key that does not exist refuses the binding's tag `network_not_ready`, recorded as
organic with the reason, since a binding with no pseudonym cannot be emitted.

The migration (019 FR-006) computes the same digest under the same key, so the programme's imported
pseudonyms match; 019 says "the programme's network key", which must mean this one (finding 7).

## R5. The click route

**Decision**: `POST /v1/track/click`, `Routes.public`, the original's body fields plus `country`, answering
`{"ok":true,"clickId":"<id>"}` for a valid tag and `{"ok":false,"clickId":"<id>","reason":"<reason>"}` for
a refused one, `400` only for a missing `btag` as the original. The click id is the `clickId` the body
carries when it is a non-empty string of at most 128 `[A-Za-z0-9_.:-]`, else one the brand mints (a UUID);
a repeated click id is the same click (the `Click` entity's `record` is a no-op on a repeat), which is
how "one per browser session" holds with the browser's own rule unchanged. The country is the body's
`country` when sent, else the header `COUNTRY_HEADER` names (`CF-IPCountry` by default; verify at task
time what the installation's gateway sets, fallback: none), else absent. The device type is the body's,
else from the user agent as the original (`mobile` when it names a phone or tablet, `desktop` when it
names a browser, else `unknown`). The address digest is SHA-256 of the first `X-Forwarded-For` entry
under a per-brand salt (service secret `click-salt`, made at first start), kept on the click and in no
fact; without the salt it identifies no one, so it is not `Personal` (finding 1 asks 001 to say so).

**Alternative rejected**: minting a click id always, as the original: that is bug 2's root, since the
affiliate's own click id is what the postback must echo.

## R6. Verifying a tag and binding a player

The order, in `Tag.verify` (pure) over `NetworkDocuments` and `Network`:

1. length and shape (R2): `malformed`;
2. a UUID: the legacy rule, R16: `legacy` after the period, `legacy_unknown` when the document does not
   list it; else the programme's network, that link's affiliate and link, no sub-ids;
3. the network: not known or removed: `unknown_network`;
4. the key: not in the cached key document: `withdrawn_key` (or `key_unreachable` when nothing is
   cached); the click route fetches once first when the cache lacks the `kid`, registration never (FR-023);
5. the signature: `bad_signature`;
6. the revocation list: `revoked_link`, `revoked_affiliate`;
7. at registration only, the window: `iat` earlier than `registered_at` minus the attribution window:
   `stale`.

`PlayersFacts` on `player_registered`: a valid tag binds (touch `tag`, bound at `registered_at`, the
fact's click id); a refused tag is recorded with its reason and the player is organic; a bonus code
with no binding is looked up in every known network's cached code document, case-insensitively, and
binds through the code's tag (touch `registration_code`, no click id); a code beside a binding tag is
a later touch. At most one binding, ever (FR-007): the entity refuses a second `bind` with `AlreadyBound`
and the consumer records the touch instead. A click is not required for a binding: the tag alone names
everything, and the click id is the fact's.

## R7. A code presented later, and the bonus code window

**Decision**: `RewardsFacts` consumes `bonus_code_entered` on `casino.rewards` and resolves the code as
R6 does; an unbound player within the bonus code window of their `registered_at` binds (touch
`later_code`, bound at the entry's time); anyone else gets a later touch. The window is a brand setting
in `Settings`, installation default 30 days, the attribution window's, so one number governs how long an
affiliate's claim lives; FR-007 now names it (spec correction 2). FR-030 named `bonus_granted`: a grant
follows the deposit that confirms the offer, so binding on it would miss a code whose offer the player
never funded and would date the binding to the deposit; the presentation is the touch (spec correction 4).
`casino.rewards` is the casino module's topic (021): the consumer is registered only when `CASINO_MODULES`
names `casino` (finding 4; verify at task time whether a consumer of an undeclared topic stops the
service).

## R8. The attribution record

**Decision**: `AttributionRecord`, event sourced, id the player id. Events: `TagRefused`, `Bound`,
`TouchRecorded`, `DepositRecorded`, `ChargebackRecorded`, `WithdrawalRecorded`, `BonusCostRecorded`,
`Closed`, `Erased`. The money events are what the fact publishers emit from (R13), so every fact about a
player is an event on the brand's regulated record, replayable. An unbound record keeps the money events
it is told of as *pending*, at most 200 and 48 hours, so a deposit the consumer reads before the
registration (the topics are keyed differently: `payments.payments` by payment id) is emitted at the
binding when its time is at or after the bound-at. A later-code binding therefore emits nothing for
money before it, and no `first-deposit` for a player whose first deposit preceded the binding; the
first-deposit guard (`firstDepositAt`) is set whether or not the record is bound, so it is for life
(FR-015). Open for Sheldon (1).

## R9. Deposits, chargebacks, withdrawals, bonus cost

| Input | Fact | Amount and reference |
|---|---|---|
| `payment.captured` | `deposit`, and `first-deposit` when `firstDepositAt` was unset | `amountCents`, the payment id |
| `payment.dispute.opened` | `chargeback` | the disputed `amountCents`, the payment id |
| `payment.refunded` | `chargeback` | the refunded `amountCents`, `<paymentId>/refund` |
| `payment.dispute.closed` | nothing | the contract's `chargeback` amount is unsigned; a dispute won is the programme's rule (finding 3) |
| `withdrawal_requested/completed/failed/reversed` | the four kinds | `amountCents`, the request id (`payoutId`) |
| `restricted_funds_awarded` | `bonus-cost` | `amountCents`, the award id |
| `provider_credited` landing on `restricted` | `bonus-cost` | `amountCents`, `providerRef` |

Why a refund is a chargeback: the programme's net deposits must fall when a deposit goes back, and
`chargeback` is the one counter-fact the protocol has; the reference's `/refund` suffix lets the programme
tell them apart for its fraud thresholds (finding 3). Why bonus cost from the wallet's award and not
`bonus_granted`: 011 FR-028 recognises the cost at the award and never negative, the award is the wallet's
core fact, and `casino.rewards` is a product module's; the feature outline's forfeited and converted rows
contradicted 011 and are removed (spec correction 1). Why `payments.payments` and not `deposit_confirmed`:
FR-030 names it, and its families carry the provider.

## R10. GGR: real money, per day, from the wallet's movements

**What counts.** The original's rule (R1) is real money only; the port keeps it. `round_settled` on
`casino.rounds` carries totals with no wallet split, and `casino.rounds` is the casino module's; every
`money.transactions` family carries `realBalanceAfter`, so the real movement of any family is the
difference from the wallet's previous fact, in the topic's own order. **Decision**: `WalletCursor` (key
value, one per wallet) holds the last `realBalanceAfter` and the recent dedupe ids; `MoneyFacts` advances
it on every family and counts the delta for `bet_placed` (stake), `win_credited` and `provider_credited`
landing on real (win), `bet_rolled_back` (stake back) and `round_settled_as_loss` (nothing moves).
Restricted funds never move the real balance, so a bonus-funded round earns nothing, and a free round's
win landing restricted earns nothing, as the original skipped it. Money converted from restricted funds is
real from then on and counts; the original's spec 253 excluded it, and the wallet port carries no
deposit-origin provenance (spec 002 dropped it). Divergence, open for Sheldon (2).

**Which day.** A round's day is the day, in the reporting zone, of its first stake; later legs of the
round (wins, rollbacks, the loss) go to that day. `RoundIndex` (key value, `<player>/<currency>/<round>`
→ day, `expireAfter` 7 days) remembers it; a leg for a round the index lacks is dated by its own time.
This is what the feature means by "a round of a closed day that settles after the close".

**The tally.** `DayTally`, event sourced, id `<player>/<currency>/<day>`: real stake, real win, the dedupe
ids seen, the rounds' running totals for `ggr-round`, and whether it is closed. `DayCloser`, a recurring
timer every 5 minutes, closes every open tally whose day ended at least `CLOSE_GRACE` (15 minutes) ago
in the reporting zone, found through the `OpenDays` view; `close` is a no-op on a closed tally (timers
and consumers are at least once). A leg arriving after the close is an `Adjusted` event with its delta:
one `ggr-adjustment` per late leg, never a changed daily fact. A day with no real movement closes
silently and emits nothing.

**`ggr-round`.** For a network whose `roundStream` is on: at each leg that settles something (a win, a
rollback, the loss), one fact with the round's stake, win and GGR since the last `ggr-round` fact for
that round; the common round of one stake and one settlement is exactly one fact with the round's
totals, and a round of many credits emits the deltas since, so sums over a round or a day equal the daily
fact. Off by default (FR-016).

**Before the binding.** When a later code binds a player (R7), `RecordFacts` emits one `ggr-daily` per
closed tally of that player before the binding, `beforeBinding` true, a field added to the contract's
`GgrDaily` (additive, FR-013; spec 001 FR-013): the contract had no marker (finding 5). Open tallies close
normally after the binding.

**Alternatives rejected**: `casino.rounds` as the source (no wallet split, a product module's topic);
totals regardless of funding (pays affiliates on bonus money, which the original never did); a single
record per player holding the tallies (one journal of every bet beside the regulated binding).

## R11. Closure and erasure

**Decision**: `player_closed` emits `player-closed` `closed`; `player_self_excluded` with no `until`
emits `self-excluded`; a period exclusion emits nothing; `player_reopened` is not consumed (finding 6:
the fact is "once", and a reopened player who was reported closed stays closed to the programme). Erasure
is `POST /v1/casino/brands/{brand}/players/{player}/erasure`, a `Routes.service` route admitted to
`players`, as `merchant`'s `ErasureEndpoint` is: it records `Erased`, emits `player-closed` `closed` when
no closed fact was emitted (008 FR-038 closes before erasing, so ordinarily none is needed), and
answers what the record holds. The record holds no `Personal` value (data-model.md), so erasure changes
no field; the click's address digest is not personal (R5). FR-030's "no brand service calls attribution"
gains the erasure exception (spec correction 5), since every brand service serves it.

## R12. Networks, settings and the service's configuration

- `Network` (key value, id the network id): key document, revocation list, legacy tag document and code
  document addresses, `roundStream`, the registered machine (`<organization>/<name>`) that may read its
  feed, `removed`. Operator routes under `/v1/casino/backoffice/{brand}/attribution/networks`.
- `Settings` (key value, id `brand`): attribution window (30 days), bonus code window (30 days), the
  protocol's first day, the transition's end and what ended it, the last day a legacy tag was presented.
  The first day is set at the first start; the windows by the operator.
- The reporting zone is `CASINO_REPORTING_ZONE`, a core configuration key `reporting-zone` rendered to
  every core service (`UTC` when the brand's values leave it out), because FR-030 forbids a call to
  `players` and 008 FR-041's `brand-config` is not built; 015 and 008 take the same key (finding 8).
- `COUNTRY_HEADER`, `DOCUMENT_TIMEOUT`, `CLOSE_GRACE`, `CASINO_MODULES` are the service's own
  variables, with defaults, in `modules/core/module.json`'s `attribution` entry (data-model.md *Settings*).

## R13. Facts: ids, keys, publishers

Every fact's id is deterministic and carries no player id: `click/<clickId>`, `<kind>/<pseudonym>/<reference>`
(`registration/<pseudonym>`, `deposit/<pseudonym>/<paymentId>`, `ggr-daily/<pseudonym>/<currency>/<day>`,
`ggr-adjustment/<pseudonym>/<currency>/<day>/<dedupeId>`, `ggr-round/<pseudonym>/<round>/<dedupeId>`,
`player-closed/<pseudonym>`). It is set as `ce-id`, the record key is the pseudonym (the link for
`click`), and the content is a function of the record's state at the event, so a redelivery is the same
message (FR-022; the consumers doc: do not rely on a generated `ce-id`).

Three publishers, one per source: `ClickFacts` over `stateOf(Click)`; `RecordFacts` over
`eventsOf(AttributionRecord)` (registration at `Bound`; the money kinds at their events when the record
is bound and the event's time is at or after the bound-at; the pending ones at `Bound`; the
before-binding days at `Bound`); `TallyFacts` over `eventsOf(DayTally)` (`Closed`, `Adjusted`, and the
round legs for a network with the stream on), which reads the record for the binding. A player whose
record is unbound at a tally's close emits nothing then; the binding, if it ever comes, emits the day as
before the binding (R10). Every publisher produces to `affiliates.attribution` with the contract, as
the wallet's do.

## R14. The feed and the brand's signing key

**Decision**: `FeedAppender` consumes the brand's own `affiliates.attribution` from `Earliest` and
appends each fact to `NetworkFeed(network)`, event sourced, which assigns the next sequence number and
refuses a fact id it has seen (a window of the last 10,000, enough for any redelivery); `FeedRows` is a
view over its events, one row per fact with `network`, `seq`, the fact. The feed is therefore exactly
what the topic carried, in the order the brand emitted it per network, kept for the ledger's retention.

`GET /v1/casino/brands/{brand}/attribution/networks/{network}/feed?cursor=<c>&limit=<n>` answers the facts
with `seq` greater than the cursor's, at most `limit` (default and maximum 1,000), the next cursor
(base64url of `<network>:<seq>` of the last fact, or the one given when the page is empty), the key id,
and an Ed25519 signature over the JCS canonical form of the `facts` array. Reading from a cursor twice
returns the same facts, since the feed is append-only. The ACL is the new `Routes.machine()`
(a contract change, R18): `Callers.granted` admits a machine whose grant names the route, and the
handler admits only the machine `Network.machine` names, `404` otherwise, so a machine registered for
one network reads no other's facts (FR-020). The grant is made by an owner with the CLI once the operator
has registered the machine on the network: `ankka organizations machines register <org> <name>` and
`ankka projects grants make machine:<org>/<name> route attribution GET '/v1/casino/brands/{brand}/attribution/networks/{network}/feed' -p <slug>`
(quickstart §6; finding 9: grants to machines are runtime data no manifest can declare). The topic grant
for a machine with a Kafka client is the same CLI (`topic affiliates.attribution consume`), on an
installation that exposes `broker-external`.

The brand's signing key is Ed25519, made by the operator's `POST …/attribution/signing-keys` (a new key
id, the private key as service secret `signing-key/<kid>`, the public key in `Settings.signingKeys`),
retired by `DELETE`, published at `GET /v1/casino/brands/{brand}/attribution/keys` (public) with the
transition status (R16). Pages and webhooks are signed under the newest key.

## R15. Webhooks: a workflow per fact and subscription

**Decision**: `WebhookSubscription` (key value, id a UUID): network, address, active, `secretName`; the
shared secret in the store as `webhook/<id>/secret`, entered by the operator and never read back.
`WebhookFanout`, a consumer over `eventsOf(NetworkFeed)`, starts `Delivery(<subscription>/<factId>)`
for every active subscription of the fact's network; a start on an existing workflow is a no-op.
`Delivery` posts the fact as JSON with `X-Attribution-Signature: sha256=<HMAC-SHA256 of the body under the
secret>`, `X-Attribution-Fact-Id`, `X-Attribution-Kind`, `X-Attribution-Protocol: 1`; a `2xx` ends it
`delivered`; anything else pauses with the next delay of 1, 5, 15, 60 minutes, then hourly, until
24 hours from the first attempt, then ends `undelivered` with the last status. `resend` restarts the
attempts from an undelivered state; `discard` ends it `discarded`. `Deliveries` (view) lists the
undelivered per network for `GET …/attribution-webhooks/undelivered`. SC-005's "no fact reaches the dead
letter while the subscriber is reachable" is the 24-hour schedule.

**Alternatives rejected**: a timer per attempt (the workflow's pause is the timer); one workflow per
subscription draining a queue (a slow subscriber then holds every fact of its network).

## R16. The transition period

**Decision**: `Settings.firstDay` is set at the first start of the service (the brand's first day on
the protocol); `DayCloser` ends the period at the first of `firstDay` + 12 months or 90 days after
`lastLegacyDay` (or after `firstDay` when none was ever presented), recording the day and the reason;
`PlayersFacts` sets `lastLegacyDay` whenever a legacy tag is presented. The brand's key document
(R14) carries `transition: {firstDay, endsAt?, endedOn?, endedBy?, legacyTagsLast30Days}`, which the
programme reads as a public client, so FR-004's "the programme MUST be able to read it" needs no grant
and no route of its own (spec correction 6); SC-008's share of legacy tags is the same figure, kept
as a daily count in `Settings` for 90 days. A legacy tag is resolved through the programme's legacy tag
document (R3) and only when the programme is a known network with that address.

## R17. Testing

- Pure: `Tag` (every refusal of R6 as a table), `Documents`, `Pseudonyms` (a known vector), `Signing`,
  `Jcs`, `RealMoney`, `Days` (the zone's midnight, a bet at 23:30 on its day), `FactIds`, `Facts`.
- Entity kits: `AttributionRecord` (every row of its command table), `DayTally`, `NetworkFeed`,
  `WalletCursor`, `RoundIndex`, `Network`, `Settings`, `Click`, `WebhookSubscription`; the workflow kit
  for `Delivery`; `ConsumerTestKit` with an `InMemorySecretStore` and stubbed clients for each consumer
  and publisher.
- Integration (`AnkkaTestKit` over Postgres, `InMemoryBroker` carrying the six topics, `TimerRuntime`
  on a `MovableClock`, a `FakeNetwork` serving the four documents and a webhook receiver, a machine
  token stub): `LandingSuite`, `BindingSuite`, `MoneySuite`, `GgrSuite` (days across midnight in a
  non-UTC zone, a late leg, the round stream), `CarriersSuite` (topic, feed pages and cursors, webhooks
  through the dead letter), `NetworksSuite` (fetch, rotation, revocation, removal, the transition),
  `ClosureSuite`, `LeakSuite` (SC-006: every fact on every carrier against every personal value sent),
  `OperatorSuite`, `GherkinSuite` over the six feature files, `ParitySuite` (R19).

## R18. Contract and manifest changes

- `contract.access.Routes.machine()`: `Callers.granted` over `Caller.Machine`; the feed handler, not
  the ACL, checks the caller against `Network.machine`;
  spec 001 FR-020's six classes gain a seventh for machines (finding 10).
- `contract.facts.GgrDaily.beforeBinding: Boolean` (default false; the schema regenerated).
- `modules/core/module.json`: the `attribution` entry's variables (data-model.md *Settings*), the
  `reporting-zone` key; `contract/keys.md` rows; `brands/goldvibe/values.json` a `reporting-zone`.
- CI: an `attribution` filter and job; `justfile`: `attribution-test`, `attribution-it`, `attribution-run`.

## R19. Recordings: a driver, and the suite's four affiliate scenarios

No recording of the original's affiliate behaviour exists (R1). **Decision**: `tests/original/attribution-driver/`
(Go, beside `ingest-driver/`) drives the original's `affiliate-service` and `casino-service` on the
original's KinD stack with the affiliate service installed, one scenario per feature scenario the original
can show ([contracts/recording.md](contracts/recording.md)); and the suite's `TestScenario17_AffiliateGate3_CPAPipeline`
and the three scenario-15 GGR tests are recorded in the same run. The replay translates each recorded
notification into the brand fact that now carries it and each recorded twin read into the attribution
facts expected, with the translation in `recording.md`; the click route replays verbatim. SC-001's
daily GGR is the sum of the recorded per-round GGR for the day. One parity test per recording.

## R20. Dropped, and why nothing replaces it

The seven notify routes and `X-Internal-Secret`; the postback and its macros; the commission fold, plans,
overrides, fraud flags, the portal (spec 013); the per-month FTD guard; `affiliate_notify_ggr` and its
`values.yaml` switch; the backfill routes (`AffiliateRoundsBackfill`, `AffiliatePlayerRecordsBackfill`:
spec 019 imports); the link index (the programme's legacy tag document); `ipHash` unsalted.

## Spec corrections (applied to spec.md, the features and the glossary on this branch)

1. **Bonus cost** (R9): FR-030 takes it from `restricted_funds_awarded` and a restricted
   `provider_credited` on `money.transactions`, recognised at the award and never negative (011 FR-028);
   `facts.feature`'s forfeited and converted rows are removed; the glossary's *bonus cost* says so.
2. **The bonus code window** (R7): FR-007 names it, a brand setting with an installation default of
   30 days, as the glossary and `bonus-codes.feature` already did.
3. **The code document** (R3): FR-023 adds it to the documents a brand fetches.
4. **A later code binds at its presentation** (R7): FR-030 names `bonus_code_entered`, not `bonus_granted`.
5. **GGR from the wallet's real movements; chargebacks from disputes and refunds; the erasure call**
   (R9, R10, R11): FR-030's sources, and *Changes from the original* says converted money now counts.
6. **The transition status is in the brand's key document** (R16): FR-004.

## Findings for other specs

1. **001 FR-024**: a click's address digest under a brand-held salt is not personal; the list should
   say so (R5).
2. **008 FR-003**: the tag grammar is R2's; `players` drops a tag over 1,024 characters or outside
   `[A-Za-z0-9_.-]`, and verifies nothing (R2).
3. **013**: a refund arrives as a `chargeback` with a `/refund` reference, and a dispute closed emits
   nothing; the fraud threshold and a dispute won are the programme's rules (R9). 013 FR-014 writes fee
   facts per GGR fact of either kind: `ggr-round` facts and the `ggr-daily` fact of the same day must not
   both be folded (the stream is opt-in and the daily fact is the default; 013 should fold one).
   013 FR-023a's code document and FR-026's legacy tag document take the shapes
   [contracts/protocol.md](contracts/protocol.md) defines.
4. **021**: a core service consuming a product module's topic. `attribution` registers its
   `casino.rewards` consumer only when `CASINO_MODULES` names `casino`; the modules tool should render
   that variable from the brand's modules (R7). Verify at task time whether ankka refuses a consumer of a
   topic the project does not declare.
5. **Contract**: `GgrDaily.beforeBinding` added; `Routes.machine` added (R18).
6. **008 FR-032a**: `player_reopened` is not in 001 FR-011 or the contract; `attribution` emits nothing
   for it, and the programme keeps a reopened player closed (R11).
7. **019 FR-006**: "the programme's network key" is the brand's pseudonym key for the programme's
   network (R4), which the import must read from `attribution`'s store, never copy.
8. **008 FR-041 / 015 FR-006**: the reporting zone is the core configuration key `reporting-zone`,
   rendered to every core service; `brand-config` need not carry it (R12).
9. **021 / ankka**: a grant to a machine is made by an owner at runtime (R14); the modules tool
   cannot declare it, and the quickstart's runbook is where it lives. For ankka: a machine's token
   lives 15 minutes and a client id is answered 12 times a minute per control plane instance, so a
   network polling the feed every minute is within bounds.
10. **001 FR-020**: a seventh route class, machine (R14, R18).
11. **003**: nothing asked; `casino.rounds` is not read (R10).

## Open for Sheldon

1. A player who deposits while organic and is bound by a code later gets no `first-deposit` fact and no
   facts for that money (R8); the alternative is to emit them at the binding, dated to the deposit, as the
   GGR days are, marked before the binding.
2. Money converted from restricted funds counts in affiliate GGR once it is real (R10); the original
   excluded it. The alternative is a `deposit-origin` marker on the wallet's bet and win facts (spec 002),
   which is a wallet change.
3. The bonus code window defaults to 30 days, the attribution window's (R7).
4. A refunded deposit is reported as a chargeback with a `/refund` reference (R9).

### The message for Sheldon (drafted 2026-10-25)

> Four calls on the affiliate protocol (`attribution`), each built one way with the other ready:
>
> 1. **A late bonus code binds forward only.** A player who registers with no link, deposits, and
>    enters an affiliate's code a week later is attributed from the code on: their play before it is
>    reported as days before the binding (the spec's rule), but that first deposit is not reported and
>    earns no CPA. The other way: report the earlier deposits at the binding too, marked as before it,
>    and let the programme decide.
> 2. **Converted bonus money counts in affiliate GGR.** Once a bonus converts to real money, bets from it
>    count like any real money. The original excluded "converted" money. The other way needs the wallet
>    to mark each bet with where its money came from. Recorded on the original (2026-10-28): a lost 10.00
>    bet of converted money earns the affiliate 0 there and 10.00 here; a lost 15.00 bet whose 10.00 real
>    part was half converted in the same bet earns 5.00 there and 10.00 here.
> 3. **The bonus code window is 30 days**, the same as the attribution window.
> 4. **A refund is reported as a chargeback**, with a reference that says it was a refund, so the
>    programme can treat it differently for fraud flags.

## For ankka (not blocking, drafted 2026-10-25)

*Filed 2026-10-28* in thinkmorestupidless/ankka: #197 (keys port 7629), #198 (machine issuer), #199
(`ce-id`), #200 (`ce-subject`), #201 (a pause's clock), #202 (a grant scoped to a path parameter).

- A consumer of a topic the project does not declare: refused at start, or waiting? *Answered
  2026-10-28*: it waits and reads the topic once declared; `ankka services get` lists it (ankka
  `docs/build/topics.md`). No issue needed.
- A topic consumer's message metadata (`ce-id`): the consumers doc shows `messageContext.subject` and
  `sequenceNumber`; whether the broker message's `ce-id` is readable decides the wallet cursor's dedupe
  (fallback: family + transaction id + award id, data-model.md).
- `Callers.granted` for machines on a route with a path parameter the handler must compare against the
  machine (R14): admitted by the ACL, narrowed by the handler; a grant per network would be cleaner.
- *Found on kind, 2026-10-28* (T073, platform 0.14.2), each leaving every machine route refused:
  the control plane's keys port 7629 is started only when `ankka.http.tls.enabled` is set
  (`ControlPlane.scala` ~413), which no deployment sets since TLS comes from the certificate directory,
  so services cannot fetch the keys; and `MachineSettings` derives the issuer from
  `ankka.controlplane.deploy.base-domain` while `reference.conf` holds it under `kubernetes`, so tokens
  and a registration's `tokenUrl` name `http://localhost:9000`. Both worked around on kind by setting the
  flag and `ANKKA_MACHINE_ISSUER` on the control plane. Also: a 0.13 service's log carries ~1,400 lines a
  minute from a lag-reading Kafka client opened every 15 s per consumer.

## Changed at implementation (2026-10-25)

- **R10**: a consumer cannot read `ce-id`; the dedupe ids are the fallback, and `WalletCursor` answers a
  repeated id with the delta it gave the first time (T057).
- **R3**: the minute's refresh fetches a document that expires within two minutes, not only one that has
  expired. Fetching only after expiry left a gap in which a registration, which never fetches, refused a
  valid tag `key_unreachable` for good (found by ScaleSuite, T070). `NetworkDocuments.put` ignores a copy
  fetched before the one held, since the refresh and a click's fetch can finish out of order.
- **R13, R14**: a published fact's `ce-subject` is its key. ankka's default subject is the source entity's
  id, which put the player id on every player fact (found by LeakSuite, T067).
- **R14**: a webhook retry waits on a service timer (`DeliveryRetry`), since a workflow's pause is timed by
  the wall clock and a test clock cannot move it (T051).
- **Local runs**: with no `ANKKA_KAFKA_BOOTSTRAP_SERVERS` the service reads and publishes in memory, as
  ingest does; ankka refuses to start a topic consumer with no subscriber (T072).
- **For ankka**, added: a workflow's `thenPause` reads the wall clock, not the service's clock; and
  `ce-subject` defaulting to the source entity's id is a quiet way to publish an internal id on a topic
  meant for another party, so its documentation should say so.

