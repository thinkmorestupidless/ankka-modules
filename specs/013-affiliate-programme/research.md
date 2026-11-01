# Research: affiliate programme

Phase 0 of [plan.md](plan.md). The original is `eitheror-platform`'s
`substrate/affiliate-service/src/main/scala/io/eitheror/affiliate/` (abbreviated `S/`), surveyed
2026-11-01 with file:line citations kept beside this branch's notes. ankka facts are from tag
`v0.13.0`, the version every service here pins: `docs/build/topics.md`, `consumers.md`, `timers.md`,
`secrets.md`, `object-storage.md`, `http-endpoints.md`, `views.md`, `workflows.md`,
`platform/cross-project-access.md`, `reference/limitations.md`.

## R1. What the original is

One Akka HTTP service per brand deployment, all state in substrate twins whose properties are strings,
money as major-unit decimal strings. 110 routes:

| Group | Count | Fate here |
|---|---|---|
| Public (`/health`, click, join ×2, forgot-password, accept-invitation, apply) | 7 | kept but the click (spec 012's) and `/health` (the runtime's readiness) |
| Portal `/v1/my/{affiliate}/…` (`S/AffiliateRoutes.scala`) | 35 | kept; the brand becomes a filter (`?brand=`) |
| Operator `/v1/affiliates/{brand}/…` (`S/OperatorRoutes.scala`) | 52 | kept but `PATCH accounts/{aff}/status` (change 13); three routes reshaped for the two-parameter limit (R12) |
| Internal `/internal/v1/affiliates/{brand}/…` (`S/InternalRoutes.scala`, `S/AffiliateBackfill.scala`) | 16 | none: the eight `notify/*` become facts (spec 012), the backfills and `bonus-codes/resolve` have no caller once 012 and 011 are built (the code document replaces resolve) |

What the spec missed and the plan keeps (constitution I; written into the spec on this branch):
a membership's own RevShare percentage (`revShareOverride`, `S/InternalRoutes.scala:158-167`), the
operator's message to an affiliate (`POST messages`, `S/OperatorRoutes.scala:3400-3445`), first and
last sign-in (`AffiliateRoutes.scala:2231-2304`), the account event history (`GET events`,
`:1816-1900`), and creatives as addresses, not files (`S/OperatorRoutes.scala:3539-3624`).

Kept exactly, already in the spec: a close below the account's minimum payout threshold claims nothing
and the facts roll forward; an approved payout reverts to pending when a later close changes its total
(`S/CommissionEngine.scala:860-918`); fees are not floored, so a negative GGR writes a negative fee
(`:511-513`); RevShare is the HALF_UP commission on NGR floored at zero, emitted as a delta that may be
negative (`:459-493`, `:612-652`); override is always over the period (`:660-715`); fraud flags never
act on their own.

Not kept, beyond *Changes from the original*: a `0` CPA fact on a RevShare plan (`:948-961`; zero
facts are omitted, FR-014), the duplicated Customer.io and pending-approval channels (the TL
capabilities `affiliate_status_crm` and `affiliate_notify_pending`; the programme sends once), and
Double arithmetic on the deposit path (`S/InternalRoutes.scala:761-1171`; the facts are `Long`s).

**Alternatives rejected**: porting the internal routes as a compatibility layer (nothing calls them
once 012 is built; spec 012 removed their callers).

## R2. One service, Scala

`affiliate-programme` in the shared project `programme` (`shared/programme/manifest.json`), one sbt
build under `services/affiliate-programme/`, on `casino-contract`. Scala (constitution VI): it is the
commission ledger. Outbound HTTP (Customer.io, Keycloak's admin API, postbacks) is
`java.net.http.HttpClient` from consumers, workflow steps, timed actions and endpoints, as every
service here does; ankka has no outbound facility (`ServiceClient.scala:115-124`).

**Alternatives rejected**: splitting ledger, portal and communications into three services (one
database per service would put the ledger's reads behind calls; nothing here needs to scale apart).

## R3. Facts from a brand here: one consumer per brand

ankka 040 is in 0.13: `ChangeSource.fromTopic(project, name, decoder)` reads one topic of one named
project, the broker enforcing the grant (`ChangeSource.scala:112-126`;
`cross-project-access.md:56-64`). A component reads exactly one project's topic, and components are
registered at start, so a brand is picked up by a component built for it. The service reads
`PROGRAMME_BRANDS` (slugs) at start and registers `attribution-<slug>` per slug, from `Earliest` (the
topic keeps everything, `modules/core/module.json:507-510`). The modules tool renders the variable for
`programme/affiliate-programme` from the brands that run `core`, as it renders `MERCHANT_BRANDS`
(`tools/modules/.../Render.scala:129`); a new brand here is a re-render and a rolling restart of the
programme, beside the grant `core` already makes (`module.json:552-558`).

The consumer's context carries no project and no `ce-id` (`ChangeSource.scala:198-231`): the brand is
the component's, a fact whose body names another brand is recorded as mismatched (FR-008), and
deduplication is by the body's `factId`.

**Alternatives rejected**: every brand webhooks the programme, here too (one ingress, but HTTP is a
weaker carrier than the topic and spec 012 FR-019 makes the grant the way a programme reads a brand
here); a runtime-registered consumer (ankka has none).

## R4. Facts from a brand elsewhere: webhooks

Decided 2026-11-01: hosted brands and eitheror's brands elsewhere post spec 012's webhooks
(contracts/protocol.md *A webhook post*) to `POST /v1/programme/inbound/{brand}`, public, admitted by
the HMAC under the brand's shared secret (`brand/<slug>/webhook-secret` in the service's secret store),
compared in constant time. The fact is answered `2xx` only after `FactInbox` holds it, so a failure is
retried by the brand for a day. A body over 256 KiB, a protocol header other than `1`, a fact id
header unequal to the body's, or a brand that is not a hosted or remote brand of the programme is
refused `4xx` with nothing recorded. The feed (a machine client per brand, a cursor, page signatures)
is not built: it serves only brands on ankka, and no such brand elsewhere exists.

**Alternatives rejected**: pulling 012's feed (rules out every hosted brand that does not run ankka).

## R5. One pipeline after the channel

```text
topic consumer (brand here) ─┐
                             ├─► FactInbox (kv, id <brand>/<factId>): claim once ─► PlayerAttribution / LinkStats
webhook endpoint (elsewhere)─┘                                                       (by kind)
```

`FactInbox.claim(fact)` is the one place a fact becomes known: the first claim stores the fact and
answers `new`, a repeat answers `duplicate` and bumps the brand's duplicate count (FR-009, SC-004).
The claimer then applies it: a `click` to `LinkStats` and `ClickRecord`; every other kind to
`PlayerAttribution` (id `<brand>/<pseudonym>`). The inbox keeps the fact for replay; it is never read
by a view of figures.

## R6. The player and the ledger

`PlayerAttribution` (event sourced) is the programme's record of one pseudonymous player at one brand:
the binding from the `registration` fact (FR-010), the first-deposit guard (lifetime, FR-013), totals,
fraud counters, days from before the binding (recorded, never folded; decided 2026-11-01), and country
and click details until `player-closed` (FR-038). It decides what a fact means and records it; it calls
nothing (entities never call). A consumer of its events, `ToLedger`, reads the membership, the plan and
the brand's settings and records the consequence on the commission account.

`CommissionAccount` (event sourced, id `<affiliate>/<brand>/<currency>`) is ADR 0042's immutable facts
for one ledger (FR-012). Each command carries a deterministic fact id, so a repeat is a no-op. Its state
keeps, per period, the sums by fact type and the RevShare and override already emitted, and the
unsettled payable facts; a settled fact leaves the state and stays in the journal and the `Ledger` view.
The RevShare delta (FR-014) and the override delta (FR-015) are computed from that state by the pure
`commission` package, ported with the original's `CommissionEngineSpec` and `CommissionFormulasSpec`
cases (`S/../test/.../CommissionEngineSpec.scala`, R16).

Every read of figures (FR-017) is SQL over the `Ledger` view, one row per fact, summed at query time:
a fold of the facts, never an accumulated figure. The entity's sums are only what the next delta is
computed from, and are rebuilt from its own journal.

**Alternatives rejected**: keeping every fact in the entity's state (a busy affiliate writes four
facts per player per reporting day); folding by reading the view on the write path (a view is
eventually consistent, so a delta computed from it could double count; the original needed a
`requestId` lag guard for exactly this, `S/CommissionEngine.scala:360-375`).

## R7. Override

`Overrides`, a consumer of `CommissionAccount`'s `EarningsChanged` events (which carry the source's
period earnings after the change), walks the source's ancestors through `Affiliate.parent`, visiting
each once (a cycle ends the walk, FR-028), and asks each ancestor's account at the same brand and
currency to `recomputeOverride(source, period, earnings, percent)`. The ancestor's account writes the
delta against what it already emitted for that source and period. An ancestor with no active membership
at the brand gets nothing (FR-015; network.feature). Percent is the sub's `overridePercent`, as the
original (`S/InternalRoutes.scala:278-346`).

## R8. Settlement

A close (FR-018) is a `Close` workflow, id `<brand>/<period>`: it pages through the `Unsettled` view
(accounts at the brand with unsettled payable facts on or before the period's end) 50 accounts a step,
and for each asks the account to `close(period, periodEnd, threshold)`, then writes the `Payout`
(id `<affiliate>/<brand>/<currency>/<period>`). The account claims every unsettled payable fact up to
the end, adds what that payout already claimed, and answers the total, or nothing below the account's
threshold. A payout already paid is never re-claimed into; its late facts wait for the next period's
close (FR-019). Two closes at once claim the same facts for the same payout id and compute the same
total (FR-020). Workflow steps are bounded (30 s each, `workflows.md:395-460`), so the close scales with
the brand.

Approval with an override figure (decided 2026-11-01): the endpoint computes the difference between
the figure and the override the payout claims, writes it as an adjustment fact with id
`override-figure/<payoutId>` claimed by the payout, then approves with the new total. A second approval
of the same payout is refused by its status machine.

The export (FR-021) is a route answering CSV for approved payouts at a brand and period, decrypting each
affiliate's identity and payment details, and auditing the read.

## R9. Personal data, before ankka 042

ankka 042 is a spec only (`specs/042-personal-data-erasure/spec.md`; `limitations.md:48-50` on `main`).
Decided 2026-11-01: encrypt now. Each affiliate gets a 256-bit AES key at creation, stored as
`affiliate/<id>/data-key` in the service's secret store; the account's name, email, company, website,
traffic source and payment details are one `Sealed` value (AES-256-GCM, a fresh 96-bit nonce per write,
the affiliate id as associated data) in the entity, so the journal and every view hold ciphertext only
(SC-007). Endpoints decrypt (the secret store is offered to endpoints, consumers, workflow steps and
timed actions, never entities, `secrets.md:29-38`), with keys cached in process for 60 seconds as
`merchant` caches credentials (`merchant/security/Secrets.scala:22-69`).

A duplicate email (FR-003) is found through `EmailIndex`, a key value entity whose id is
HMAC-SHA256 of the lowercased address under `email-index-key`, naming the affiliate. Search by name in
the operator's account list decrypts the brand's page in the endpoint and filters there; the original
searched twin properties, and a brand's affiliates number in the thousands at most.

Erasure (FR-037) is an `Erasure` workflow per affiliate: it pauses until the hold ends, then deletes the
data key, removes the `EmailIndex` entry, deletes the affiliate's realm user, and records `Erased`.
After that nothing can read the fields, and every view shows them as erased. The affiliate's account is
first closed by a manager (`close`), which disables sign-in.

**Alternatives rejected**: the repository's marker-and-overwrite (the journal keeps the old values,
SC-007 unmet); payment details alone in the secret store (decided against 2026-11-01).

## R10. Tags and documents

The programme signs spec 012's tag (contracts/protocol.md *The tag*) with an Ed25519 key in its secret
store (`signing-key/<kid>`), `net` = `programme`, links `lnk-<base32>`, affiliates their ids. It serves,
public and cacheable: the key document (`GET /v1/programme/documents/keys`, `transition` absent: it is
the brand's), the revocation list (`…/revocations`, refreshed every 300 s), the legacy tag document
(`…/legacy-tags`, from imported links, withdrawn with `404` once the transition ends) and the code
document per brand (`…/codes/{brand}`, version and time, FR-023a). Shapes are 012's exactly. A key is
rotated by adding one and retiring the old after its published expiry (edge case).

Spec 012's brand fetches these as a public client (012 R3); no brand calls the programme otherwise.

## R11. Accounts, sign-in and managers

The realm is the casino's `eitheror` (`brands/_realm/eitheror-operators.json`), issuer `staff`, as every
shared project renders (`Render.scala:123-125`). Missing and added on this branch: the realm role
`affiliate_manager`, and in `contract.access.Routes` a class `affiliateManager()` (issuer `staff`, any
of `affiliate_manager`, `brand_admin`, `platform_admin`) and `brandsOf(principal)` (the `brand_<slug>`
roles held; `platform_admin` holds every brand). A route with `{brand}` in its path checks it as the
simulator's `Access` does (`game-provider-simulator/.../api/Access.scala:22-28`); a list route with no
brand filters rows by `brandsOf` (FR-004, FR-041). The portal admits `Routes.affiliate()` and checks
the target is the caller's own account or a descendant (`eitheror_scope_id` = affiliate id, as the
original provisions it, `S/NetworkTree.scala:225-328`).

Provisioning (affiliates on approval and invitation, managers on add) copies `merchant`'s
`KeycloakAdmin` (`merchant/operators/KeycloakAdmin.scala:48-114`), with the admin credential as a
project secret (`secret:keycloak-operators-admin/*`, as `shared/payments/manifest.json:22-27`). The
roles given: an affiliate `affiliate` and `brand_<slug>` per active membership; a manager
`affiliate_manager` and `brand_<slug>`. Revoking a manager removes both roles (new, change 16).
Invitation and reset tokens are `Token` key value entities keyed by the SHA-256 of the token, claimed
once atomically (the original's claim was not, `AffiliateInvitationTokenStore.scala:89-103`); 72 hours
for an invitation, 30 minutes for a reset, as the original.

## R12. Routes under ankka's limits

A route takes at most two path parameters, and no two endpoints share a prefix
(`http-endpoints.md:188-192`, `:723`). Four endpoints:

| Endpoint | Prefix | Admits |
|---|---|---|
| `OperatorEndpoint` | `/v1/affiliates` | `affiliateManager()` per route group, brand from the path; three public routes (`/{brand}/apply`, `/{brand}/forgot-password`, `/accept-invitation`) by `withAcl(Routes.public)` |
| `PortalEndpoint` | `/v1/my` | `Routes.affiliate()`; `?brand=` filters |
| `JoinEndpoint` | `/v1/join` | public |
| `ProgrammeEndpoint` | `/v1/programme` | documents (public), inbound webhooks (public, HMAC), hosted brands and the usage report (`platform_admin`) |

The original's three-parameter routes `POST /v1/affiliates/{brand}/accounts/{aff}/network/{sub}/{action}`
become `POST /v1/affiliates/{brand}/network/{sub}/{action}` (the ancestor is implied: an operator may
act on any sub). Every other path is the original's (contracts/routes.md).

## R13. Communications

Email: Customer.io `POST /v1/send/email` with the original's nine trigger keys and message data
(`AffiliateInviteEmailService.scala:47-77`, `:311-391`), per brand: the brand's sender configuration
(`appApiKey` in the secret store as `brand/<slug>/customerio-key`, region and transactional ids in the
brand's settings). A send is an `EmailSend` workflow with three attempts over five minutes; failure is
recorded, never blocks (FR-035). In-portal notifications are `Notification` entities with the
original's types.

Postbacks (FR-036): a `Postback` workflow per (fact, affiliate), copying `attribution`'s `Delivery`
(`services/attribution/.../carriers/Delivery.scala:65-163`): attempts at 0, 1, 5, 15 and 60 minutes,
then hourly until 24 hours, the retry driven by a timer so a test clock can move it
(012 research, *Changed at implementation*); the last outcome per event is a `PostbackOutcome` row the
affiliate and a manager read. Macros as the original (`S/Postback.scala:15-25`): `{event}`, `{clickid}`,
`{payout}`, `{status}`, percent-encoded; events `registration`, `ftd`, `deposit`, `revshare`.

## R14. Clocks and periods

Change 15: every period and day is in the brand's reporting zone (`BrandProgramme.reportingZone`,
default the brand's `reporting-zone` key, 012 finding 8). A fact's period is the month of its
`occurredAt` in that zone; a period ends at the first instant of the next month there. Day ranges are
read in the same zone. Recurring timers have no calendar (`limitations.md:319-323`), and nothing here
needs one: closes are a manager's action, as today, and the usage report is computed on read.

## R15. Hosted brands and the usage report

`BrandProgramme` (key value, id the brand slug) holds a brand's settings, programme page, default plan,
GGR source, sender configuration, `origin` (`here`, `elsewhere`, `hosted`) and `retired`. Brands here
are created at start from `PROGRAMME_BRANDS` by a `Seed` timed action, as `merchant` seeds
(`merchant/hierarchy/Seed.scala:20-44`); a hosted or remote brand by `platform_admin` through
`POST /v1/programme/brands` (FR-040), which also generates its webhook secret, shown once. The usage
report (FR-042) is SQL over `Ledger`, `Inbox` and `Memberships` for the brand and the calendar month in
its zone.

## R16. Testing

As `services/wallet/` and `services/attribution/`: a pure `commission` package with the original's
cases lifted (`CommissionEngineSpec`, `CommissionFormulasSpec`); entity, workflow and consumer kits with
`InMemorySecretStore`; `GherkinSuite` binding the ten `features/programme/*.feature`; an integration kit
over Postgres with `InMemoryBroker` carrying two brands' `affiliates.attribution`, a `MovableClock`,
`FakeCustomerIo`, `FakeKeycloak`, a `WebhookSender` signing as a brand, and a `PostbackReceiver`.
`LeakSuite` dumps every table and journal and greps for the test affiliates' names, emails and IBANs
(SC-007). `CloseSuite` runs a thousand closes with interleaved late facts (SC-006).

## R17. Recordings

Nothing of the programme's side is recorded yet (`tests/port/` holds attribution's twelve, which drive
the original's `notify/*` and click routes). A driver, `tests/original/programme-driver/` (Go, beside
`attribution-driver/`, reusing its recorder), drives the original's portal and operator routes on the
local kind stack with the affiliate service running, one scenario per feature scenario the original
can show (contracts/recording.md). Each recording becomes one parity test that replays it with the
brand carried as a filter; divergences are marked by change number.

## R18. Contract, realm, manifest and tool changes

- `contract.access.Routes`: `affiliateManager()`, `brandsOf(principal)`.
- `brands/_realm/eitheror-operators.json`: realm role `affiliate_manager`.
- `shared/programme/manifest.json`: env (`PROGRAMME_BRANDS` rendered), secrets
  (`keycloak-operators-admin`), the service's database and secret store.
- `tools/modules` `Render`: `PROGRAMME_BRANDS` for `programme/affiliate-programme`, from the brands that
  run `core`.

## R19. The import (FR-039)

The import is a route `POST /v1/programme/imports/{brand}` (`platform_admin`) taking a JSON bundle an
exporter produces from the original's twins (`tests/original/programme-export/`, Go). Every id is
deterministic from the original's (accounts keep theirs; facts keep theirs; payouts become
`<affiliate>/<brand>/<currency>/<period>`), so a second run writes nothing. Player ids become
pseudonyms before the bundle reaches the programme: the exporter asks the brand's `attribution` for
them, which holds the pseudonym key (012 finding 7). Spec 019 sequences the run; the programme only
accepts the bundle. Keycloak users are kept, so sign-in is unchanged; their `brand_spinvibe` role stands.

## R20. Verify at task time

- Suspension and CPA: whether the original writes a CPA fact for a suspended affiliate's player's first
  deposit (FR-016 says no payable fact; the guard is then set and the CPA lost). Fallback: as FR-016.
- Whether an ankka 0.13 service starts cleanly when a component registered at a previous start is gone
  (a brand removed from `PROGRAMME_BRANDS`). Fallback: keep the component, consuming nothing new.

## Spec corrections (applied on this branch)

1. *Changes from the original* item 5 and the Context: eight `notify/*` routes, not nine.
2. Item 8: the original forwards `clickId` when the player site sends it; not a change.
3. FR-002 gains the membership's own RevShare percentage; FR-034 gains messages, sign-in times, the
   event history, and creatives as addresses.
4. FR-027 and the network scenarios: an email invitation makes the sub active at once; an invite link
   makes it pending, serves until revoked, 50 applications an hour (decided 2026-11-01).
5. FR-019 and the settlement scenario: the override figure at approval is an adjustment (decided).
6. FR-014: days before the binding fold nothing (decided); periods in the reporting zone (change 15).
7. FR-008, FR-040, US10, the hosting scenarios and the glossary: webhooks, not the feed (decided).
8. FR-037, SC-007, change 9: encryption under a per-affiliate key (decided).
9. Changes 13 to 16 added.

## Findings for other specs

1. **001 / modules tool**: `PROGRAMME_BRANDS` rendered for the programme (R3, R18).
2. **011**: the original refused an affiliate code that collides with a brand's own offer code
   (`AffiliateRoutes.scala:68-80`); the programme cannot read a brand's offers, so a collision is
   resolved where both are seen, in `rewards`' matching (011 FR-021).
3. **012**: no webhook from a brand here to the programme; the programme reads the topic (R3).
4. **019**: the import's bundle carries pseudonyms the brand's `attribution` derived (R19).
5. **ankka**: a runtime way to consume a topic every granted project publishes would remove the
   restart per brand (R3); a calendar timer is not needed here.

## Declined remediations

## Design finished

2026-11-01: analyze reported nothing above LOW.

## Found during implementation

- **A later deposit pays CPA while the guard is unset** (T019). data-model.md's kind table records a
  `deposit` as totals only, but commission.feature says a first deposit below the minimum leaves the
  guard unset "so a later deposit that meets the minimum deposit pays CPA". The scenario governs: a
  `deposit` at or above the plan's minimum, with the guard unset, answers `FirstDeposit(true)` and sets
  the guard; once set, every deposit is `Deposit`. A plan that pays no CPA passes no minimum and never
  qualifies, so the guard stays unset until one does.
- **A close below the threshold releases what the payout held** (T020). settlement.feature says such a
  close claims nothing and carries the facts forward; a payout re-closed after a negative late fact can
  hold earlier claims, and these are released too, so every fact it held carries forward with the rest.
  Overrides for an ended period are dated its last millisecond, as the original's backfill does.
- **A first deposit is counted once** (T033). Spec 012 emits a `first-deposit` and a `deposit` for a
  player's first deposit (`features/attribution/facts.feature`), so counting both double-counted the
  deposit and charged its payment fee twice; the original counts it once (`depositCount: 1` in
  `tests/port/attribution/`'s first-deposit recording). The `first-deposit` fact decides CPA only; the
  `deposit` fact counts the deposit and writes its payment fee. data-model.md's kind table says so now.
