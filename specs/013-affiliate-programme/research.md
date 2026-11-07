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

## Changed at implementation (2026-11-07)

What the decisions above became once built. Each line names the finding under *Found during
implementation* that carries the detail.

- **R5, R6**: a first deposit is counted once although spec 012 sends a `first-deposit` and a
  `deposit`; a later deposit pays CPA while the guard is unset; a fact claimed but never handled is
  handled when it comes again.
- **R6, R20 (first item)**: a suspended affiliate is paid no CPA, as FR-016 says; the original's deposit
  route never reads the status.
- **R8**: a close below the threshold releases what it held; a rejected payout is frozen as a paid one
  is; a repeated close answers the period's open payouts; moving a payout to pending is refused as a
  transition; an override figure is an adjustment, not earnings; SC-006's thousand closes run at the
  unit level.
- **R9**: closing and erasing need every brand of the account; closing takes the realm roles; an
  erasure is filed only for a closed account and its four actions run in one step; O18 lowercases
  the address; the leak search reads every table over JDBC.
- **R10**: the legacy withdrawal reads each brand's key document; a bonus code may name an offer;
  link counts are rows counted.
- **R11**: an invitation to a known email is refused; the invitation token names the account; revoking
  a manager keeps the role while another brand's remains; a realm user deleted under an account
  stalls no provisioning.
- **R12**: the read-only view is six routes, and postback outcomes a seventh page; a revenue read
  answers a list of figures per brand and currency.
- **R13**: notifications the original did not write; which facts send a postback; a postback's last
  outcome is an entity per affiliate, brand and event; a brand's Customer.io key is written on its
  settings by an administrator.
- **R15**: a brand is added in one request; the usage report counts memberships per plan currency;
  the webhook's two channels are shown once each.
- **R19**: the bundle is version 1, written by `tests/original/programme-export`, which derives
  pseudonyms with a key read at run time; the import checks the whole bundle and seals identities
  before keeping anything, runs a section at a time, 200 items a turn, sends and pays nothing, and a
  commission account takes one import.
- **R17**: the recordings were made last (T034), on the original at the commit their files name.

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
- **The recordings are made last** (T034, asked 2026-11-01). The original's kind stack was down when
  T034 came up, and rebuilding it needs the person's hands. The person chose to defer the recording to
  the end of the build, before the review: the routes exist by then, so each recording is replayed at
  once. Until then ParitySuite's thirteen-recordings check is red.

- **A suspended affiliate is paid no CPA** (T039, R20's first item). The original's deposit route
  (`S/InternalRoutes.scala:761-1171`) reads no affiliate status: a suspended affiliate's player's
  first deposit wrote CPA there. FR-016 governs, as R20's fallback says: the fact is recorded, the
  first-deposit guard is set, and no CPA is written, then or after reactivation. `CommissionSuite`
  holds it.
- **A revenue read answers a list of figures** (T046, asked 2026-11-02). The original's revenue read
  was one flat object for one brand. The programme keeps figures per brand and currency, so M1, O22
  and O37 answer `{affiliateId, period, figures: [{brandId, currency, …the original's fields}]}`
  (contracts/routes.md). The person chose this over an array at the top level and over a flat object
  when unambiguous. Parity wraps a recorded flat answer as the one element it expects.
- **The counts a revenue read gives come from the player's facts** (T046). The original's revenue holds
  `depositCount`, `totalDepositAmount`, `ftdCount` and `registrationCount` per period; the `Ledger`
  view holds only commission facts. `PlayerFacts`, a keyed view of `PlayerAttribution`'s events (one
  row per applied fact, stamped with the period in the brand's reporting zone, which `Intake` sets
  on the fact), is what those counts fold from: registrations bound, `first-deposit` facts, and
  `deposit` facts with their sum. GGR recorded and not folded (before the binding, or of the other
  source) is `excludedGgr`.

- **The second and third deposit counts** (T047). The original's revenue also counts the deposits that
  were a player's second and third in their life (`deposit2Count`, `deposit3Count`). `PlayerFacts`
  stamps each `deposit` row with its ordinal among the player's deposits at the brand, in every
  currency, and the figure counts ordinals 2 and 3 in its period.
- **An email invitation to an address that already has an account is refused** (T052, decided
  2026-11-02). The original made an account per brand, so its invitation checked only the brand's
  accounts. Here one account per email holds every membership and one parent, so M20 answers
  `409 email_already_registered` when the email names any account; the existing account keeps its
  parent and memberships. The person chose this over adopting a parentless account and over adding
  memberships without a parent. An invite link (P4) to a known email applies with that account at
  the link's brand and leaves its parent as it is.
- **A network read across several brands** (T052). A node's `status` and `planId` are its first
  membership's among the brands read, as the original's single brand gave one; each node also lists
  `memberships` (brand, status, plan), and `revenueForPeriod` is the figures list. Health counts a
  descendant as active if any of its memberships is, else pending, else suspended.
- **The invitation token names the account** (T052). `Token.issue` records the sub's affiliate id, not
  a realm user, since `Provisioning` makes the realm user after the invitation; P6 (T078) finds the
  realm user through the account.
- **An override figure is not earnings** (T057). FR-019 writes an approval's override figure as an
  adjustment the payout claims; adjustments are earnings their ancestors' overrides are taken on
  (FR-015), so the figure would have cascaded up the tree. The original never made the figure a fact
  (`S/OperatorRoutes.scala:3094-3112`), so nothing cascaded there. The adjustment carries the source
  `override-figure` and `Earnings` leaves it out; the payout's figures still count it under
  `adjustments`. Its id is `override-figure/<payoutId>/<n>`, a later approval of a reverted payout
  writing the next difference.
- **A rejected payout is frozen as a paid one is** (T056). The spec and the original leave a rejected
  payout's facts stamped; a close of its period again would have reopened it, which FR-019's machine
  does not allow. A close skips it, and its late facts wait for the next close.
- **A repeated close answers the period's open payouts** (T056). Each close is a run of the `Close`
  workflow, `<brand>/<period>/<run>`, which `CloseRun` numbers: a close asked for while one runs joins
  it, and one asked for after carries the previous run's payouts forward, so it answers the pending and
  approved payouts of the period with their totals now, as the original's close does (FR-020).
- **Moving a payout to pending is refused as a transition** (T057). The original answered `400` for any
  status other than approved, rejected or paid; settlement.feature's outline wants a `paid` payout
  moved to `pending` refused naming `paid`, so `pending` is `409 status_transition` with the message.
- **The thousand closes run at the unit level** (T054). SC-006's thousand closes with late facts between
  them run in `CloserSuite`, over the account's and the payouts' entity kits, in seconds; `CloseSuite`
  holds the same rules through the routes.
- **The withdrawal reads each brand's key document** (T064, asked 2026-11-02). FR-026 withdraws the
  legacy tag document once every brand with imported tags reports its transition ended in its own key
  document, and the programme held no address for one. `BrandProgramme` gains `keyDocumentUrl`, set when
  a brand is added or updated; a brand of this installation defaults to `ATTRIBUTION_BASE_URL` with
  012's path. The route fetches the document of every brand with an imported tag and refuses `409
  transition_not_ended`, naming each brand whose `transition.endedOn` is null or whose document cannot
  be fetched. The person chose this over withdrawing on the administrator's word and over a body that
  lists the evidence.
- **A bonus code may name an offer** (T062). The original's bonus code named no offer; 012's code
  document carries one and 011 selects the offer it names. `POST bonus-codes` takes an optional
  `offer`, carried into the code document when given.
- **A link's counts are rows counted, not a row of counts** (T063). data-model.md's `LinkStats` is a
  view over `ClickRecord` and `PlayerAttribution`. A keyed row of counts would be read, added to and
  written back, and a change delivered twice would count twice. `Clicks` keeps a row per click
  instead, `PlayerFacts` a row per registration with the link it bound through, and `LinkStats`
  counts each link's rows when a link is read (FR-024).
- **A link answers its landing address** (T061). The programme holds no brand's site, so a link's
  `landingUrl` is the brand's landing path, `/` when none, with `btag` the signed tag and the utm
  parameters, relative to the brand's site; the portal puts the brand's address before it.
- **Revoking a manager keeps the manager role while another brand's remains** (T068, asked
  2026-11-02, the person chose this). Change 16's route takes `brand_<slug>` always, and
  `affiliate_manager` only when the user then holds no other `brand_*` role, so a manager of A and B
  revoked at A still manages B. contracts/routes.md's row says so.
- **A manager's invitation and reset are the original's emails** (T068). A new manager, or a reset,
  gets a token (`manager-invite`, 72 hours; `manager-reset`, 30 minutes, its `user` the realm's id)
  and the brand's `AffiliateManagerInvite` or `AffiliateManagerPasswordReset` message, linking to
  the backoffice's `affiliates/accept-invitation` or `affiliates/reset-password` as the original.
  `EmailSend` takes the manager's address in `to`: a manager has no sealed identity to open.
- **A fraud flag's id is a UUID of the player, brand and reason** (T070). The original's id was
  this UUID. data-model.md's `<brand>/<pseudonym>/<reason>` would put slashes in O49's path; the
  entity is `<brand>/<flagId>`, raised once for each all the same. The reason is the kind
  (`bonus-cost`, `chargeback`), and the original's text (`3+ chargebacks`) is the description. The
  original's id carried the count, so it raised a flag at every count past the threshold.
- **A retired brand refuses with `409 brand_retired`** (T071). data-model.md says a retired brand
  refuses applications and status changes and names no code. Apply, approve, reject, create an
  account, and the network transitions on either side answer `409 brand_retired`; reads, closes and
  payouts go on. Added to contracts/routes.md's *New refusals*.
- **O7's rows carry this month's figures per currency** (T069). The original's row was the account
  with `currentMonthFtds`, `currentMonthNgr` and `currentMonthCommission`, in one currency. A row
  here is `{account, currentMonth: [figure]}`, each figure the revenue read's for the brand's
  current month in its zone. The brand's rows are read once and grouped by affiliate.
- **The read-only view is six routes** (T072). `view/{page}` would be a third path parameter, and
  ankka 0.13 takes two. Each page is a route of its own, at the same paths contracts/routes.md
  names, and each answers what the portal's read answers for the brand.
- **A manager reads postback outcomes on a seventh read-only view page** (T077, asked 2026-11-02,
  the person chose this). FR-036 has a manager see an affiliate's last outcome per event;
  contracts/routes.md had the portal's route only. `GET /{brand}/accounts/{aff}/view/postbacks`
  answers what `GET /v1/my/{aff}/postbacks` answers for that brand; routes.md's O29–O34 row names it.
- **Which facts send a postback** (T077). As the original: a registration (`status=ok`, no payout);
  a first deposit as `ftd` with the CPA paid in major units, or `0` on a plan that pays RevShare,
  and none on a CPA plan that did not pay; a positive RevShare delta as `revshare`. The original
  sent none for a plain deposit; FR-036 and R13 name it, so a deposit sends `deposit` with `payout=0`
  and `status=ok`. The click id is the registration's, kept on the player. The address is read when
  the fact is written and the macros filled then; the postback is a GET, as the original's.
- **A postback's last outcome is an entity per affiliate, brand and event** (T077).
  `PostbackOutcome`, id `<affiliate>/<brand>/<event>`, written by each attempt; the latest attempt
  wins. Its view answers the portal and the manager.
- **Notifications the original did not write** (T076). The feature has an affiliate told in the
  portal of a payment, an invitation and a reset request, where the original only emailed: the
  kinds are `PAYOUT_PAID`, `INVITED` and `PASSWORD_RESET_REQUESTED`. `PAYOUT_APPROVED` and
  `PAYOUT_REJECTED` are the original's, with its text; `AffiliatePaymentProcessed` carries
  `amount` (major units), `currency`, `paid_at` (epoch seconds) and `period_label`. The original's
  `payment_method` is not sent: the programme holds no payment method.
- **The portal's notifications cover every membership** (T076). Other portal reads take the
  brands where the account is active or suspended; M24 takes every brand the account has applied
  to, as the original listed every notification, so an applicant and a rejected one are told.
- **A token is given back when the password cannot be set** (T078). As the original's `release`:
  a realm failure, or an affiliate whose realm user is not provisioned yet (`503
  substrate_unavailable`), leaves the token to be tried again. The original also marked the email
  verified; the realm client here has no such call, and the realm sets it at the first sign-in.
- **The forgotten-password dedupe is per process** (T078), as the original's: five minutes per
  address, in memory, so two instances may each send one.
- **A first sign-in before the memberships' view has caught up is recorded with no brand** (T078),
  so `AFFILIATE_FIRST_LOGIN` is never lost to the view's lag.
- **Closing and erasing need every brand of the account** (T082, decided 2026-11-02). Both reach
  the whole account, so a manager who does not hold every brand where the account has a membership
  is answered `409 account_shared`, naming the other brands; a `platform_admin` holds them all. The
  memberships are read from the view, as every read of an account's brands is, so a membership
  approved a moment before may not yet count.
- **Closing takes the realm roles** (T082): `affiliate` and every `brand_*` role go from the
  account's realm user, and `ACCOUNT_CLOSED` is audited. The user stays until the erasure, so a
  closed account's identity is still there for finance's questions during the hold.
- **An erasure is filed only for a closed account and held from the filing day** (T082): `409
  account_not_closed` otherwise. The hold ends at the start of the UTC day `filedAt` plus
  `PROGRAMME_RETENTION_DAYS` (1825, the length `players` keeps); the route answers `202` with the
  hold, and filing again answers the erasure already held.
- **The erasure's four actions run in one step** (T082): destroy the data key, release the email
  index, delete the realm user, record `Erased`. Each is harmless done twice, so a retried step
  repeats none wrongly, and splitting them would add states without making any of them safer.
  Facts, payouts and the audit name the affiliate by id only and are not touched.
- **An erased account reads as `erased`** (T082): its name and email answer `erased`, every other
  identity field is absent.
- **O18 stores the address lowercased** (T082), trimmed, as enrolment does; the index digests the
  same, so a change of case alone moves nothing. An address another live account
  holds is `409 email_already_registered`; the old address is released for a new application.
- **The leak search reads every table over JDBC rather than `pg_dump`** (T080), as attribution's
  LeakSuite does, so the suite needs no `pg_dump` on the machine that runs it; the search reads
  `bytea` as UTF-8, which is what a dump would show of the journals' payloads. A planted value is
  found, so the search is shown to look.
- **O26 and M23 answer one shape** (T083): `playerRef` (the brand's pseudonym), `brandId`,
  `referredByAffiliateId`, `campaign`, `registeredAt`, `country`, `hasFtd`, `firstDepositAt`,
  deposits and withdrawals per currency, and `closed`. M23 covers the caller and the network below
  them, as the original's did; a closed player's country is absent and its figures remain.
- **A realm user deleted under an account stalls no provisioning** (found in the T080–T083
  regression run). The account keeps naming its realm user after an erasure, and a user can be
  deleted in Keycloak by hand; a later membership change then met `NotFound`, which the consumer
  retried forever, holding back every account's provisioning. Removing a role from a user who is
  gone is now done; granting to one finds the user by email or makes it again, as at first.
- **A brand is added in one request** (T087). `POST /v1/programme/brands` takes `brandId`, `origin`
  (`hosted` by default, or `elsewhere`), `name`, `reportingZone`, `keyDocumentUrl`, and the
  manager routes' own bodies as `settings`, `programme`, `defaultPlan` and `firstManager`; each is
  applied by the code of the route it comes from, so its checks and audits are the same. It answers
  `201` with the brand, `webhookUrl` (under the new setting `PUBLIC_URL`, the service's public
  address, as psp-gateway names it) and `webhookSecret`: 32 random bytes in hex, kept in the store and
  never read back by a route. `PATCH` takes `name`, `keyDocumentUrl` and `retired: true`; a retired
  brand stays retired, and its webhook answers `404 brand_unknown`.
- **The usage report counts memberships per plan currency** (T087, decided 2026-11-02). FR-042 asks
  for every figure per currency, and a membership has none of its own: an active membership is
  counted under its plan's CPA currency. Facts received are counted per the fact's currency, `none`
  for a fact with no amount; commission recorded is the payable facts recorded in the month, and
  commission paid the payouts paid in it. The month is the brand's reporting zone's. A membership
  counts as active in the month when it is active now and became so before the month ended.
- **A fact claimed but never handled is handled when it comes again** (T086). A failure after
  `FactInbox` claims a fact (the service stopping, a store unreachable) left the claim with no
  outcome, and the redelivery was answered `duplicate`, so the fact was lost on the topic and the
  webhook alike. A claim with no outcome now goes on; the player's own record of the fact ids it
  has seen keeps the second pass from writing twice.
- **The webhook's two channels are shown once each** (T084). No brand both publishes on a topic
  here and posts to the webhook, so HostingSuite hands the fact to `Intake` on the topic channel as
  a brand consumer does, then posts it; the claim answers the post `duplicate`.
- **The import's bundle** (T090, T091; R19). Version 1, written by `tests/original/programme-export`
  and read as `imports/Bundle.scala`: settings and the programme page, plans by name (the plan id is
  `Plan.idOf(brand, name)`, as the original's `planId`), each account with its one membership, links
  under their btag (kept as the legacy tag), bonus codes, invite links, creatives, notes, audit,
  fraud flags, players by pseudonym with their first deposit, every commission fact with the payout
  that claimed it as `<affiliate>/<brand>/<currency>/<period>`, and every payout. Money is in minor
  units and percentages in basis points; a decimal of more than two places is refused, not rounded.
  The original keeps no currency on most facts, payouts or thresholds, so the exporter gives them
  the brand's one currency (`-currency`). Where it keeps no time for a revocation or a deletion, the
  export's own time stands for it. An account status the programme lacks (the original reads
  `TERMINATED`, though nothing writes it) stops the export rather than being guessed.
- **The exporter derives pseudonyms itself, with the key read at run time** (T090). `attribution`
  serves no route that turns a player id into a pseudonym, and 012 finding 7 says the import reads the
  key from attribution's store, never a copy. The exporter takes the key as a file at run time
  (`-pseudonym-key-file`; spec 019's runner supplies it from the store) and applies attribution's
  own function, HMAC-SHA256 base64url, pinned by a test vector. No player id leaves in clear: the
  audit's meta keys naming a person or carrying a token (`email`, `setPasswordUrl`, …) stay behind.
- **The import checks the whole bundle first, and seals identities before keeping anything**
  (T091). `POST /v1/programme/imports/{brand}` (`platform_admin`) refuses `400` a bundle of another
  brand, another version, a repeated id, an unknown plan, fact kind or status; `404` a brand the
  programme lacks; and `409 email_already_registered`, naming the accounts, when another account of
  the programme already holds an account's email: an import does not merge two accounts. It then
  seals each identity and digests each email in the route, so the import's state holds no identity
  in clear (SC-007), and starts the workflow `import`, id `<brand>/<first 16 hex of the body's
  SHA-256>`: the same bundle again is the same import, and answers it. It answers `200` with the
  counts when the import ends within 10 s, else `202`; posting the bundle again reads it.
- **The import runs as one workflow step per section, 200 items a turn** (T091). Plans, settings,
  accounts, links, codes, invite links, creatives, notes, audit, flags, players, the ledger (one
  write per commission account), payouts. A section past 200 items comes back to its step, so no
  step outlasts its timeout or starts its section over when retried. The state drops the bundle at
  the end.
- **What an import writes sends nothing and pays nothing** (T091). Each imported record arrives by
  a command of its own that its consumers recognise: `Membership.Imported` (no notice, no default
  link, no role change: the realm user and its roles are the original's), `Payout.Imported` (no
  notice), `FraudFlag.restore` marks the flag imported (its dismissal is not told again),
  `PlayerAttribution.Applied(imported = true)` (no CPA, payment fee or postback; the binding and the
  first-deposit guard are set, so a later first deposit pays no second CPA), and facts written with
  the reason `import`, with no RevShare folded and no override recomputed: the original's are among
  them. The player facts the import applies are counted by revenue as the original counted them
  (registrations and first deposits); deposits before the import are not carried, since the
  original keeps them per hashed player only.
- **A commission account takes one import** (T091). An account remembers its latest 10,000 fact
  ids, and a history longer than that would be written twice by a second import; an account that
  an import wrote is marked, and takes none after. A later export of the same data writes nothing,
  and a later export with more facts adds none to an account already imported: the import moves a
  frozen brand (spec 019), not a running one.

- **A brand's Customer.io key is set on its settings, by an administrator** (T094, asked 2026-11-07).
  Nothing put `brand/<slug>/customerio-key` in the store, so every email failed "no Customer.io key".
  `PUT /{brand}/settings` takes `customerIoKey`. Only `brand_admin` or `platform_admin` may send it;
  for an affiliate manager the request is `403`. It is written to the store, never kept in the brand's
  state, and never answered: `GET` says only `customerIoKeySet`.
- **What the parity replays found** (T034's recordings, 2026-11-07).
  - **An unreachable portal target is `403`** (asked 2026-11-07). The original refuses before it reads
    the target, with its ACL's `403` and text; the contract had said `404 account_not_found`. A target
    the caller reaches that does not exist stays `404`.
  - **Account creation answers the email** (O8, asked 2026-11-07): `201 {affiliateId, email, status}`,
    the original's two fields and the programme's status.
  - **The audit keeps the programme's shape** (asked 2026-11-07): spec change 17. The invite-link
    application is now a row, `AFFILIATE_PENDING_APPROVAL` by `invite-link`, as the original's.
  - **The original keeps no bonus-cost or chargeback fact.** It writes them under `bonus-<tx>` and
    `chargeback-<tx>`, which are not UUIDs, and the substrate refuses them without the route noticing.
    Its revenue counts a deduction only in the request that carried it (`knownFacts`), so a chargeback's
    recompute drops an earlier bonus cost: the recorded NGR leaves it out. The feature folds both, and
    so does the programme; the replay marks it. The export has no such fact to carry, so an imported
    figure's NGR is the original's with those deductions given back. The commission facts already
    carry them, and the commission is the same; ImportParitySuite accepts exactly that difference.
  - **Brand settings and the programme page carry the original's earlier runs.** Both runs read them
    before changing them, and found what the runs before had left. The replay first sets them to that
    answer, the state the run started from.
