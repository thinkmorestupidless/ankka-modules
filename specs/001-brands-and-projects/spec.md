# Feature Specification: Brands and Projects — The Shape Every Service Is Built Into

**Feature Branch**: `001-brands-and-projects`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The foundation every casino and payments service spec builds on: how a
brand becomes an ankka project, how projects and services are named, how a brand is created from one
manifest and pins its own versions, the topic contract every fact crosses, the realms that identify a
player, an operator, an affiliate and a partner, which fields are personal data, how money is counted,
how the running original is recorded and held to, and what the installation must have before a brand
takes real money."

## Context

`eitheror-platform` runs Spinvibe today on a generic twin substrate: concepts, TL functions, Go action
handlers, Neo4j and Cloudflow, with the casino, wallet, merchant, affiliate and payments services on
top. The build plan ("eitheror on ankka: casino and payments build plan", 2026-10-08) replaces that
with typed services on ankka: a set of services deployed once per brand, and shared services for
payments, the affiliate programme and the reasoner. Every later spec in this repository describes one
of those services. This spec describes what they all share, so that no two of them decide it
differently.

The plan left four of these decisions open or interim, and this spec settles them:

- **One project per brand, not two.** The plan split a brand into a `money` project (wallet, payouts)
  and a `casino` project (everything else). The split bought nothing the plan named — rollback, versions
  and databases are already per service — and it cost the hottest path in the product: every bet,
  win and rollback from `game-provider` to `wallet` would cross a project boundary, which under ankka
  040 is a grant to maintain and a second audit trail to read. A brand is one project, named after the
  brand, with its wallet inside it. The topic the wallet produces keeps its name, `money.transactions`,
  because a topic's name is already scoped to its project.
- **Deposits reach a brand by a call, not a shared topic.** ankka's broker closes a project's topics to
  every other project, and the cross-project *topic* grants the plan assumed (C12) were never built.
  ankka 040 grants a route or a topic to a named principal. The shared `merchant` hands a captured
  deposit to the brand's wallet by calling a route the brand granted it, with the payment id as the
  idempotency key; the brand's `payments.payments` topic is produced by the brand's own wallet from
  that call, for the brand's own readers.
- **Audit stays shared.** The 2026-10-08 plan update made audit a per-brand service only because no
  topic could be read across projects. With 040, each brand grants its `platform.audit` topic to the
  shared `audit` service, which holds the only cross-brand record and never a real id.
- **Affiliates split in two.** The brand's `attribution` service records who sent a player and emits
  attribution facts on `affiliates.attribution` (spec 012); the shared `programme` project owns
  accounts, plans, the commission ledger, payouts and the portal (spec 013), reading each brand's
  attribution topic by grant. An affiliate can therefore work across brands, which it cannot today.

Everything else below restates the plan as requirements a test can hold a service to, and names the
ankka features (038–044) it rests on.

## Clarifications

### Session 2026-10-08

- Q: Is a brand one ankka project or two (`money` and `casino`)? → A: One, named after the brand.
  Every bet crosses from `game-provider` to `wallet`; making that a cross-project call under 040 adds
  a grant and a second audit trail to the hottest path for no benefit the plan named. The services
  the plan put in `money` (wallet, payouts) live in the brand project beside the others.
- Q: How does a deposit captured in the shared `payments` project reach a brand's wallet? → A: By a
  route the brand grants `merchant` under ankka 040, called with the payment id as the idempotency
  key. The plan's C12 cross-project topic grant was never built, and 040 grants routes.
- Q: Is audit per brand or shared? → A: Shared, in `reasoner`. Each brand grants its `platform.audit`
  topic to `audit` under 040. The 2026-10-08 interim decision to make audit per brand is reversed.
- Q: Does the affiliate service stay in the brand? → A: No. A small brand service, `attribution`,
  records attribution and emits facts on the brand's `affiliates.attribution` topic (spec 012); the
  shared `programme` project consumes them (spec 013). The plan's per-brand `affiliates` service,
  which computed commission, is replaced by those two.
- Q: Does the casino keep its own Keycloak, or do the brand realms move into the platform's? → A: The
  casino keeps its own. Its realms carry the RG-status and GAMSTOP authenticators, the audit listener
  and the brand roles, none of which belong in the platform's Keycloak, which authenticates ankka's
  own operators only. Every service verifies the casino's realms through ankka 022.
- Q: Which versions of a message type must a shared service accept? → A: Every version any brand still
  produces or reads. A message type changes only by adding a new version beside the old; the old is
  retired once no brand produces or reads it.
- Q: How long is a fact kept on a topic? → A: A fact topic is declared with the longest retention the
  installation allows (ankka 043), and the installation's bound is raised above the shipped default
  before any brand takes real money. Only `casino.sessions` is ephemeral (and, since reconciliation
  3, `casino.push`).
- Q: What is the subject of a player's personal data? → A: The player id, which is the same id in the
  brand's services and in the facts the brand emits to `payments` and `reasoner`. A shared project
  holds the id and nothing else that identifies the player — except `programme`, which never holds
  the id at all: it knows a player only by the per-network pseudonym spec 012 defines.
- Q: What drives the recordings of the original? → A: The original's own Go end-to-end suite, run
  against a local original with every PSP stubbed, recorded by ankka-port. Its Playwright suite is the
  oracle for the frontends, which change only base URLs.
- Q: Is the installation's broker one node or three? → A: Three, from the installation's first day.
  ankka 043 makes a three-node broker a fresh-install shape and growing a single node out of scope, so
  an installation that will take real money is created with three.

### Session 2026-10-08 (reconciliation)

Settled with specs 012 and 013 once all three existed.

- Q: Which service in the brand emits attribution facts, and how does it learn of a registration? →
  A: `attribution`, a service of its own in the brand project (FR-002), specified by 012. It
  consumes the brand's own topics and calls nothing: `players` carries the tag, the click id and any
  bonus code presented at registration on the `player_registered` fact, and `rewards` carries the
  code on `bonus_granted`, so `attribution` binds from facts alone.
- Q: What is the attribution topic called, and how is it keyed? → A: `affiliates.attribution`,
  produced by `attribution`, keyed by the player's per-network pseudonym (by the link for a click),
  declared as a fact topic under FR-015, granted to `programme`/`affiliate-programme` to consume.
- Q: What does the programme know a player as? → A: The per-network pseudonym of 012 FR-013: a keyed
  digest of the player id under a key `attribution` holds for that network, stable for the player's
  life, different at every brand and every network. `programme` never holds a player id (FR-018).
- Q: What passes from the programme back to a brand? → A: Documents the brand fetches and caches as a
  public client: the programme's key document, its revocation list and, for the transition, its
  legacy tag document (012 FR-023). No grant and no call into the brand.

### Session 2026-10-08 (reconciliation, wave 1)

Settled with specs 002–007 and 020 once they existed.

- Q: Does `payments` hold personal data after all? → A: Yes, exactly the customer fields a payment
  provider requires for a deposit, which the cashier has always carried (spec 006 FR-008, spec 004
  FR-013). They are `Personal` values under the player id as `payments`' own subject (ankka 042
  keeps a subject per project), held on the session and the payment only, and erased by the request
  the brand's `players` files in `payments` under its erasure right (FR-018, FR-025). The earlier
  "player id and nothing else" was written before the cashier was read.
- Q: Who serves the cashier routes? → A: `ingest` (spec 006) is the merchant-facing edge: it verifies
  the merchant key against the digests `merchant` publishes on `payments.merchants`, shapes the
  request, makes it idempotent by the merchant's reference, rate-limits the merchant and forwards a
  command to `merchant` by service identity. `merchant` (spec 004) owns the session, the payment and
  the deposit workflow and serves those commands to `ingest` and `psp-gateway` only. A brand is a
  merchant with its own key, so it needs no grant into `payments` (FR-016, FR-020).
- Q: How does a brand list a player's deposit attempts? → A: From its own `payments.payments`, which
  gains `payment.initiated` (FR-011); the internal route the original's `casino-service` called on
  `merchant-service` is dropped (spec 004).
- Q: Which service administers which realm? → A: `players` the brand's `casino-<slug>` realm;
  `merchant` the `eitheror` realm, for the payments backoffice's operators (FR-021).
- Q: Who reads what `payouts` needs? → A: `payouts` builds views from `casino.players` and
  `money.transactions` (FR-011 readers); `casino.players` gains `player_kyc_submitted` from `kyc`.
- Q: Does the generic provider API survive? → A: No. Spec 003 found it a dev stub with decimal
  amounts, no idempotency and every end-to-end test skipped; it is dropped, and spec 020's simulator
  speaks ST8's protocol only, keeping its core-and-adapter seam for a second real provider.
- Q: What moves between services that the plan placed differently? → A: From `merchant`: ST8
  credentials to `game-provider` (003), the CRM configuration to `engagement` (014), the seeded KYC
  policy to `kyc` (009), the sub-brand's deposit turnover terms to `wallet` (002). The clawback a
  hold or cancel applies is the wallet's to compute (002), not the caller's.

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Who enforces responsible-gambling limits, breaks and exclusions, and where? → A:
  `responsible-gambling` decides (spec 010) and every money or play path asks it before acting, failing
  closed: the wallet before crediting a deposit (`may-deposit`, with its exact 1/7/30-day sums) and
  before funding a bet (`may-bet`); `game-provider` at launch (`may-play`); `players` at session
  issue and on the realm's behalf (`may-log-in`). The original enforces none of these at deposit or
  bet; the defect list below records it.
- Q: Who answers the realm's authenticators? → A: `players` alone (FR-022 is unchanged): it answers
  account status from its own state and asks `responsible-gambling` within the project for breaks and
  exclusions. The realm registers no machine under ankka 040, and a GAMSTOP refusal it makes is
  reported to `players`, which passes it to `responsible-gambling` (spec 008 FR-013, spec 010 FR-022).
- Q: Who produces the KYC families, and are the gates facts or queries? → A: `kyc` produces them
  (FR-011). The withdrawal gate is a fact `payouts` reads; the deposit and play gates are queries to
  `kyc` within the project, because they need the amount and the policy (spec 009 FR-019, FR-020),
  asked by `players` at deposit initiation (spec 008 FR-043) and by `game-provider` at launch (spec
  003 FR-007).
- Q: How does a session's duration limit reach the game session? → A: Without a call: `players`
  keeps each player's `SESSION_MINUTES` limit from `rg_limit_applied` facts and counts the minutes of
  its own sessions, so it sets the timer itself (spec 008 FR-019). `responsible-gambling` answers
  `may-play` from its own view of `casino.sessions` (spec 010 FR-008).
- Q: Who records a self-exclusion? → A: `responsible-gambling` decides and records it and tells
  `players` to set the status; `players` ends the sessions and produces `player_self_excluded` as it
  does for every status transition. An operator excludes a player through `responsible-gambling`'s
  route, never through `players` (spec 010 FR-019, spec 008 FR-032).
- Q: Which fact carries the deposit number? → A: `deposit_confirmed`, with the wallet's deposit
  ordinal (spec 002 FR-031): the count of confirmed deposits on that wallet, so `rewards` evaluates a
  deposit-number target from the fact alone (spec 011 FR-012).
- Q: Whose are the bonus codes a player enters? → A: The brand's `rewards` matches its own codes
  first, then the programme's code document for the brand (spec 013 FR-023a), fetched as a public
  client like the key document (FR-016).
- Q: Which recordings exist for responsible gambling and KYC? → A: None in the Go suite, which skips
  every such scenario; FR-030 now says what replaces them.

### Session 2026-10-08 (reconciliation 3)

Settled with specs 014–019 once they existed.

- Q: What does the contract gain from the third wave? → A: Three message families from `players`
  for the inbox (`player_message_created`, `player_message_read`, `player_message_deleted`); an
  ephemeral `casino.push` topic from `engagement`; `bonus_completed` and `bonus_code_entered` on
  `casino.rewards`; and fields `reporting` folds without a call: the balance after every wallet
  movement, the payment provider on every payment outcome, the provider's round figures and wallet
  transaction ids on `round_settled`, the offer's name and type on every grant family (FR-011).
- Q: Who reads the brand's own audit topic? → A: `players` and `reporting`, within the project, for
  the account trail and the compliance exports (spec 018 FR-025); the shared `audit` by grant.
  `support-agent` reads no topic at all: its tools call the owning services (spec 017), so it leaves
  the reader lists.
- Q: What is in `reasoner`? → A: `audit` alone. Spec 018 found the plan's `proposals` to be another
  product's research agent, the substrate's propose/execute gate with no casino caller, and mimir's
  TL proposals with nothing to propose into; none is carried over (FR-003).
- Q: Where does a brand's reporting zone live? → A: In `players`' brand configuration (spec 008
  FR-041), one IANA zone per brand, read by `reporting` (spec 015 FR-006) and `attribution` (spec
  012 FR-016) so the brand's day and the affiliates' `ggr-daily` close on the same instant.
- Q: Whose token does a partner carry? → A: The `eitheror` realm's, for a confidential client per
  partner scoped to the brand (spec 016 FR-015); the partner API verifies it and issues nothing.
  FR-020's partner class is corrected.
- Q: Is `partner-api` in the pilot? → A: No. It has no partner today (spec 016); it is built last and
  only when one is contracted, and the pilot brand's manifest excludes it (FR-007). `support-agent`
  ships off (spec 017 FR-001).

### Session 2026-10-08 (clarify)

- Q: Which brand is the pilot? → A: Goldvibe. It holds no player data in production, so it is
  created fresh on ankka under the slug `goldvibe` (Phase 3) before Spinvibe migrates (Phase 4, spec
  019); its manifest is the pilot manifest FR-007 describes.
- Q: Does TL survive as a sold feature? → A: No, not in the casino rebuild. The TL function packs
  are rewritten as consumers, timers and workflows in the owning services (specs 010 and 011 build
  their timers in-service); TL stays in the original and no TL backend for ankka is specified.
- Q: May a living feature quote a family name from the contract? → A: Yes. FR-011's spellings are
  canonical; a step that names a fact quotes its family exactly, and a step spelling it otherwise is
  corrected to the contract. What `engagement` tells the CRM (a track) is spec 014's own vocabulary,
  not a family, and a feature names it as a track. Specs 007, 010, 011 and 014 are reconciled to this
  at the next `/speckit-bdd-features` run.
- Q: Does the shared payments hold only a player's id? → A: No; FR-018 stands. `payments` holds
  the player id and the customer fields a payment provider requires, as personal data under its own
  subject; the shared audit holds the id and nothing else that identifies the player; the programme
  holds no id. The US4 scenario that said "the player's id and nothing else" for both shared parts is
  rewritten to say so.
- Q: How are the glossary's proposed terms settled? → A: Wholesale: every term is accepted and
  *Proposed* removed. "run" becomes "simulator run" and "destination" stays a saved bank account (a
  provider's address is a payment address); the two-sense terms (campaign, webhook, dead letter,
  invitation, cursor, claim window, wagering window, attribution window) are split, one term per
  sense. Terms are settled here because this spec owns the contract's words.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A brand is created from one manifest and runs on its own (Priority: P1)

An operator of the installation creates a new brand by applying one manifest: a project named after
the brand, every brand service at a pinned version, the brand's topics, the grants to and from the
shared projects, and the brand's realm. The brand then serves its own players at its own addresses,
and nothing another brand does — a deploy, a rollback, an outage — reaches it.

**Why this priority**: Every other spec deploys into this shape. Until a brand can be created and
torn down from one manifest, no service can be tested where it will run.

**Independent Test**: Create a brand from the manifest on a local installation, confirm every service
is ready and every topic and grant exists, deploy a second brand from the same manifest, change one
service's version in the second, and confirm the first is untouched.

**Acceptance Scenarios**:

- added `features/brands/manifest.feature`: a brand is created from its manifest with every part at the version the manifest pins
- added `features/brands/manifest.feature`: moving one brand to a new version leaves another brand untouched
- added `features/brands/access.feature`: a brand refuses to answer about any brand but its own
- added `features/brands/manifest.feature`: a manifest naming a part or a fact the contract does not list is refused before anything is created
- added `features/brands/manifest.feature`: the same key in two brands names two different things

---

### User Story 2 - A fact crosses between services on the contract's topics (Priority: P1)

A service that owns a fact — a bet placed, a payment captured, a player registered — produces it on
the topic the contract names, in the message family and version the contract names, keyed so that the
facts about one player stay in order. Any service that needs the fact reads it from the topic and
builds what it needs; it never reads another service's store.

**Why this priority**: The topic contract is the only thing two services in different specs agree on
without reading each other. A fact produced off-contract is invisible to the services that need it.

**Independent Test**: Publish one message of each family from a test producer, read each from a test
consumer, and check the key, the family, the version and the fields the contract names.

**Acceptance Scenarios**:

- added `features/brands/facts.feature`: a bet placed is published as a fact about the wallet it belongs to
- added `features/brands/facts.feature`: a view rebuilt from a brand's facts a year later sees every fact
- added `features/brands/facts.feature`: adding a field to a kind of fact keeps its version
- added `features/brands/facts.feature`: changing what a kind of fact means is a new version beside the old
- added `features/brands/facts.feature`: a deposit captured by the shared payments credits the brand's wallet once however many times it is delivered
- added `features/brands/facts.feature`: the shared audit keeps a brand's audit records naming the player by a pseudonym

---

### User Story 3 - The right person reaches the right route (Priority: P1)

A player signs in to their brand's realm and reaches only their own wallet and account. An operator
signs in to the operator realm and reaches the brands their roles name. An affiliate reaches the
programme's portal. A partner reaches the partner API with a partner token. One service calls another
with no token at all; the platform's identity for the caller is enough.

**Why this priority**: Decision 0045 in the eitheror brain records an outage caused by brand access
checked on the wrong claim. The rule must be written once, here, and every service spec must point at
it.

**Independent Test**: With a player token, an operator token with and without the brand's role, an
affiliate token and a service identity, call one route of each class and check who is admitted.

**Acceptance Scenarios**:

- added `features/brands/access.feature`: a player who signed in at their brand is served there and refused at another brand
- added `features/brands/access.feature`: an operator is refused at a brand their roles do not name however they signed in
- added `features/brands/access.feature`: one part of a brand is admitted to another by its identity with no sign-in
- added `features/brands/access.feature`: the shared payments is admitted to the deposit the brand allowed it
- added `features/brands/access.feature`: the shared payments is refused anything the brand did not allow it
- added `features/brands/access.feature`: a public action is served with no sign-in
- added `features/brands/access.feature`: a payment provider's webhook is served only when its signature verifies
- added `features/brands/access.feature`: a registration is reported to the brand's player records and to no other part of the brand

---

### User Story 4 - A player's personal data is marked and can be erased (Priority: P2)

Every field that identifies a player is held as personal data under the player's id, in every
service of the brand and in every fact the brand emits. When a player's data is erased, every such
field in every store and on every topic becomes unreadable, while balances, bets, payments and
commission facts stay readable under the player id.

**Why this priority**: A regulated operator must erase on request and must keep money facts for
years. Both are only possible if every service marks the same fields the same way from its first
event.

**Independent Test**: Register a player, play, deposit, withdraw, erase the player, and read every
service's store, every topic and every shared project's store for any field the contract marks as
personal.

**Acceptance Scenarios**:

- added `features/brands/personal-data.feature`: every field that identifies a player is kept as personal data under the player
- added `features/brands/personal-data.feature`: a personal field in a fact is personal data under the player in the fact too
- added `features/brands/personal-data.feature`: an erased player's personal fields read as erased and every amount, id and time reads as before
- added `features/brands/personal-data.feature`: the shared payments holds a player's id and the fields a payment provider requires, the shared audit the id alone, and the programme no id
- added `features/brands/personal-data.feature`: an erasure requested under a retention hold waits for the hold to lapse and is then applied unasked

---

### User Story 5 - Money is exact and a repeated command does nothing twice (Priority: P2)

Every amount is a whole number of the currency's minor units. Every command that moves money carries
a key the caller chose, and sending it again returns the first answer and moves nothing.

**Why this priority**: The original's fix #662 traced a wrong balance (a 5,000 win credited as
-153.96) to a 32-bit integer overflowing in a Go guest. The rule costs nothing to state and every
money spec depends on it; a parity recording covers a win above 2^31 minor units.

**Independent Test**: Send a bet of "1050" minor units twice under one key and check one debit; send
an amount with a fraction and check it is refused.

**Acceptance Scenarios**:

- added `features/wallet/money.feature`: an amount is a whole number of minor units
- added `features/wallet/money.feature`: a money command sent again under its key returns the first answer and moves nothing
- added `features/wallet/money.feature`: an amount that is not a whole number of minor units is refused naming the amount
- added `features/wallet/money.feature`: a money command without a key is refused

---

### User Story 6 - The original is recorded and the rebuild held to it (Priority: P2)

Before a service is rebuilt, the original is run locally with every external system stubbed, its own
end-to-end suite is run against it, and what it answered is recorded. Each service spec names the
recordings it covers; each rebuilt service replays them in its own tests and must answer as the
original did.

**Why this priority**: Constitution I. A spec that cannot say how a requirement was established is a
guess, and a rebuild with no recording to fail against cannot be shown to match.

**Independent Test**: Run the recording once, count the recordings, and check that every scenario in
the Go suite produced one and every PSP the original calls was answered by a stub.

**Acceptance Scenarios**:

- added `features/brands/recordings.feature`: running the original's end-to-end suite with every provider stubbed leaves one recording per scenario and nothing leaves the machine
- added `features/brands/recordings.feature`: recording refuses to start while a payment provider the original calls has no stub
- added `features/brands/recordings.feature`: every requirement in a spec names what established it
- added `features/brands/recordings.feature`: a rebuilt part replays every recording its spec names and a changed answer fails naming the recording and the field
- added `features/brands/recordings.feature`: the original's frontend suite passes against a rebuilt brand with only its addresses changed

---

### User Story 7 - The installation is fit for real money before a brand takes it (Priority: P3)

A brand takes real money only on an installation that backs up every project database with
point-in-time recovery and a copy outside the cluster, can erase a player, keeps facts as long as the
contract needs, has three broker nodes, ships its telemetry, and sits behind the cloud's edge
protection.

**Why this priority**: These are platform features (ankka 038–044) rather than casino behaviour, but
the first brand on ankka cannot go live without them, and the manifest must refuse to pretend
otherwise.

**Independent Test**: Apply a brand manifest marked for real money against an installation missing
one of the conditions and check the refusal names it.

**Acceptance Scenarios**:

- added `features/brands/manifest.feature`: a brand marked for real money is refused on an installation that is not fit for it
- added `features/brands/manifest.feature`: a brand marked for real money is created on an installation meeting every condition
- added `features/brands/manifest.feature`: a brand not marked for real money is created on a local installation

---

### Edge Cases

- A brand slug that is also the name of a shared project, or a name the platform reserves
  (`payments`, `programme`, `reasoner`, `platform`, `local`, `machine`): refused before anything is
  created.
- A brand deleted and created again under the same slug: the platform refuses the slug (deleted
  tenancy ids are not reused); a brand comes back only by restoring its project (ankka 041).
- A message family produced at version 2 while a brand still reads only version 1: the producer
  produces both until the listing of readers shows none at version 1.
- A fact topic whose retention the installation cannot grant in full: the topic is declared at the
  installation's maximum, and the brand's status says the maximum is below the contract's intent.
- A route a service spec marks as granted to a shared project, but the brand's manifest does not grant:
  the service refuses the caller; the manifest, not the service, is wrong.
- A personal field found in a message on a topic in the clear: the parity test for the producing
  service fails; there is no runtime scrub.
- An operator holding roles for several brands: served at each, refused at every other.
- A player erased who later registers again in the same brand with the same email address: a new
  player id; the realm, which holds the email address, is told of the erasure by the players service
  and removes the person.

## Requirements *(mandatory)*

### Functional Requirements

**Projects and names**

- **FR-001**: Each brand MUST be exactly one ankka project whose id is the brand's slug: lowercase
  letters, digits and `-`, starting with a letter. The slugs `payments`, `programme`, `reasoner`,
  `platform`, `local` and `machine` MUST be refused.
- **FR-002**: A brand project MUST hold these services and no others: `wallet`, `payouts`, `players`,
  `kyc`, `responsible-gambling`, `rewards`, `game-provider`, `attribution`, `partner-api`,
  `engagement`, `reporting`, `support-agent`. Each is specified by one spec in this repository
  (`attribution` by spec 012).
- **FR-003**: The installation MUST hold three shared projects: `payments` (`merchant`, `psp-gateway`,
  `ingest`), `programme` (`affiliate-programme`), `reasoner` (`audit` only: the plan's `proposals` is
  not carried over, spec 018).
- **FR-004**: A key a brand service keeps state under MUST carry no brand: a wallet is
  `player/currency`, a player is the player id, a session the session id. Two brands MAY hold the same
  key for different things. A player id MUST be minted at random by `players` and never derived from
  the brand and an email address, so an erased player who registers again is a new player (spec 008).
- **FR-005**: Every route a brand service serves MUST keep the `{brand}` segment the original's route
  has, and a brand project MUST refuse any request whose `{brand}` is not its own, naming its own brand.
- **FR-006**: A service MUST be reached at `<service>-<brand>.<base>` through the installation's
  gateway; custom hostnames per brand are the player site's deployment, not the platform's.

**Manifests and versions**

- **FR-007**: A brand MUST be created from one manifest kept in this repository under
  `brands/<slug>/`: the project, every brand service with its image version, every topic of the
  contract with its settings, every grant the brand makes and receives, the brand's realm, and whether
  the brand takes real money. The pilot brand's manifest MUST exclude `partner-api` (spec 016 is
  built last, and only when a partner is contracted) and MUST leave `support-agent` off (spec 017
  FR-001); both are added to a brand's manifest when the brand needs them.
- **FR-008**: Each brand MUST pin its own version of each service. A release MUST reach one brand
  first; any other brand moves when its manifest is changed, never because another brand's was.
- **FR-009**: A shared service MUST accept every API version and every message version any brand still
  produces or reads, as listed in the manifests. A shared service's spec MUST say which versions it
  accepts.
- **FR-010**: Applying a manifest MUST be refused, before anything is created, when it names a service
  or topic the contract does not list, a brand slug FR-001 refuses, or a real-money brand on an
  installation that fails FR-040.

**The topic contract**

- **FR-011**: Every fact between services MUST cross on one of these topics, each existing once in
  every brand project, each produced by exactly one service of that project, and each carrying the
  families listed, at version 1 unless a later spec adds a version:

  | Topic | Producer | Families | Readers in the brand | Granted to |
  |---|---|---|---|---|
  | `money.transactions` | `wallet` | deposit_confirmed (carrying the wallet's deposit ordinal), bet_placed, win_credited, bet_rolled_back, round_settled_as_loss, restricted_funds_awarded, restricted_funds_converted, restricted_funds_forfeited (carrying the clawback applied), admin_adjusted, hold_placed (carrying the amount voided against a cap), hold_released, withdrawal_reversed, provider_credited (the last two added by spec 002 on 2026-10-09) — every family carrying the real and restricted balance after the movement | responsible-gambling, rewards, payouts, kyc, attribution, partner-api, engagement, reporting | — |
  | `payments.payments` | `wallet` | payment.initiated, payment.captured, payment.failed, payment.expired, payment.abandoned, payment.refunded, payment.dispute.opened, payment.dispute.closed — every family carrying the payment provider | attribution, engagement, reporting | — |
  | `casino.players` | `players`, `responsible-gambling`, `kyc` | from `players`: player_registered, player_logged_in, player_logged_out, player_suspended, player_reinstated, player_self_excluded, player_closed, vip_tier_changed, player_profile_updated, player_consent_changed, player_fraud_flagged, player_fraud_cleared, player_message_created, player_message_read, player_message_deleted; from `kyc`: player_kyc_submitted, player_kyc_document_reviewed, player_kyc_verified, player_kyc_rejected, player_kyc_pending, player_kyc_review_required, player_sof_cleared; from `responsible-gambling`: rg_case_opened, rg_case_closed, rg_limit_applied | players, kyc, rewards, payouts, attribution, partner-api, engagement, reporting | — |
  | `casino.sessions` | `players` | game_session_started, game_session_ended, player_session_started, player_session_ended | responsible-gambling, partner-api, engagement, reporting | — |
  | `casino.rounds` | `game-provider` | round_settled, carrying the provider, the provider's round id, the game, the session, the currency, the total stake, the win, the outcome and the wallet transaction ids (spec 003 FR-019) | rewards, attribution, partner-api, reporting | — |
  | `casino.withdrawals` | `payouts` | withdrawal_requested, withdrawal_approved, withdrawal_completed, withdrawal_cancelled, withdrawal_failed, withdrawal_reversed | attribution, partner-api, engagement, reporting | — |
  | `casino.rewards` | `rewards` | bonus_granted, bonus_cancelled, bonus_forfeited, bonus_completed, grant_expired, free_spins_granted, free_spins_settled, bonus_code_entered — each grant family carrying the offer's id, name and type (spec 011 FR-027) | attribution, partner-api, engagement, reporting | — |
  | `affiliates.attribution` | `attribution` | the fourteen fact kinds spec 012 FR-011 lists, keyed by the player's per-network pseudonym (by the link for `click`) | — | `programme`/`affiliate-programme` |
  | `casino.push` | `engagement` | push_delivered, presence_changed (ephemeral, spec 014) | players, reporting | — |
  | `platform.audit` | every service | audit_record (the shape spec 018 FR-001 owns) | players, reporting | `reasoner`/`audit` |

  `payments.payments` is produced by the brand's wallet from the captured deposit `merchant` hands it
  (FR-016) and from the payment outcomes `merchant` reports the same way, so the brand's readers never
  depend on the shared project's topics. `player_registered` carries the affiliate tag, the click id
  and any bonus code presented at registration, as presented and unverified, and `bonus_granted`
  carries the code it was granted for, so `attribution` (spec 012) binds a player from facts alone.
  `payment.initiated` is reported by `merchant` at the start of every deposit attempt (spec 004
  FR-022), so a brand lists a player's attempts from its own facts. The `player_kyc_*` and
  `player_sof_cleared` families are `kyc`'s (spec 009 FR-030), so `payouts` can tell a player under
  review from one who never applied, and `players` builds its verification view from them without a
  call. `player_profile_updated` (spec 008 FR-025) carries the eight profile fields `kyc` needs for
  completeness as `Personal` values, so `kyc` never asks for them. `deposit_confirmed` carries the
  wallet's deposit ordinal (spec 002 FR-031), so `rewards` targets a deposit number from the fact.
  `player_self_excluded` is `players`' alone, produced when `responsible-gambling` has it set the
  status (spec 010 FR-018). `player_message_created`, `player_message_read` and
  `player_message_deleted` (spec 008 FR-030) carry the message id and are what `engagement` pushes a
  signal for; `casino.push` is `engagement`'s own ephemeral topic (spec 014 FR-022, FR-023), read by
  `players` for a message's delivered state and by `reporting`. The brand's own `platform.audit` is
  read within the project by `players` (the account trail) and `reporting` (spec 018 FR-025);
  `support-agent` reads no topic — its tools call the owning services (spec 017).

  The family names in this table are canonical: a living feature that names a fact quotes the
  family exactly as written here, a parity test checks that spelling, and no service produces a
  family under another name. A name `engagement` sends the CRM is a track (spec 014), not a family,
  and a feature names it as one.

  One topic of a shared project is part of the contract: `payments.merchants`, in the `payments`
  project, produced by `merchant`, with the families `merchant_key_issued` and
  `merchant_key_deactivated`, each carrying the key id, the sub-brand and the key's digest and never
  a raw key; read by `ingest` (spec 006) and by nothing in a brand; no grant.
- **FR-012**: A message MUST be keyed by the id of the entity it is about, so that the facts about one
  wallet, player, payment or session stay in order, and MUST carry the player id as its subject where
  a player is involved. `affiliates.attribution` is the one exception: it is keyed by the player's
  per-network pseudonym and carries no player id (spec 012 FR-012, FR-013).
- **FR-013**: A message family MUST change only by adding fields. A change that removes a field, changes
  a field's meaning or changes the key MUST be a new version beside the old, named `<family>.v<n>`; the
  old version MUST be produced until no brand's manifest lists a reader of it.
- **FR-014**: The field names of a family that exists in the original's JSON schemas
  (`shared/json-schemas/`) MUST be kept, so the exports partners read today do not change shape.
- **FR-015**: Every topic but `casino.sessions` MUST be declared as a fact topic: cleanup by deletion,
  retention the longest the installation's bound allows (ankka 043), and three copies with two in
  sync. `casino.sessions` MUST be declared with retention 90 days and `casino.push` with retention one
  day (spec 014); they are the two ephemeral topics. A brand's status MUST say when a fact topic's
  retention is below the installation's bound.

**Across projects**

- **FR-016**: A brand MUST grant, under ankka 040: to `payments`/`merchant`, the wallet's deposit
  route and the wallet's payment-outcome route; to `payments`/`psp-gateway`, the payouts outcome
  route; to `programme`/`affiliate-programme`, the `affiliates.attribution` topic to consume; to
  `reasoner`/`audit`, the `platform.audit` topic to consume. A brand MUST receive from `payments` a
  grant on `psp-gateway`'s payout route for its `payouts` service. No other grant MUST exist between a
  brand and a shared project, and none between two brands. A brand's `attribution` service reads the
  programme's key document, revocation list and legacy tag document as any public client would (spec
  012 FR-023), and `rewards` reads the programme's code document for the brand the same way (spec 013
  FR-023a); that is not a grant and the programme calls nothing in a brand. A brand starts a
  deposit as a merchant of `payments`, under its merchant key on `ingest`'s public routes (FR-020,
  spec 006), and needs no grant into `payments`.
- **FR-017**: Every call across a grant that moves money MUST carry an idempotency key the caller
  chose: the payment id for a deposit, the withdrawal request id for a payout.
- **FR-018**: `payments` MUST hold, about a player, the player id and exactly the customer fields a
  payment provider requires for a deposit — given and family names, email address, telephone and
  mobile numbers, postal address with city, province or state and post code, and the network
  address — each a `Personal` value (ankka 042) under the player id as `payments`' own subject, kept
  on the cashier session and the payment and nowhere else (spec 004 FR-032, spec 006 FR-008), and
  erased by the request the brand files there (FR-025). `reasoner` MUST hold the player id and no
  other field that identifies the player. `programme` MUST hold no player id: it knows a player only by the
  per-network pseudonym the brand's `attribution` service derives (spec 012 FR-013), and nothing else
  that identifies the player.

**Identity**

- **FR-019**: The casino MUST keep its own Keycloak with one realm `casino-<slug>` per brand for its
  players and one realm `eitheror` for operators, affiliates and partners. The platform's own
  Keycloak authenticates ankka's operators and no one else.
- **FR-020**: Every route MUST be of one class: **public** (served with no token: registration, login
  flows, a tracking click, a join link, a provider's webhook, which is served only when its signature
  verifies, and a merchant's cashier request, which is served only when its merchant key verifies on
  `ingest` — spec 006); **player** (a `casino-<slug>` token, served for that brand only); **operator** (an
  `eitheror` token whose realm roles name the brand, checked on the roles and never on the client
  that issued the token, per decision 0045); **affiliate** (an `eitheror` token with the affiliate
  role, served by the programme); **partner** (an `eitheror` token of a partner's confidential
  client, scoped to the brand, verified by the partner API — spec 016 FR-015; the partner API issues
  nothing); **service**
  (admitted by the caller's platform identity, with no token, within the project or by a 040 grant).
- **FR-021**: A service MUST verify a player's or an operator's token through ankka 022 against the
  casino's realms, and MUST NOT hold a realm's administrative credential except `players`, which
  calls the realm's administration of `casino-<slug>` for registration, password and email flows,
  and `merchant`, which provisions the payments backoffice's operators into the `eitheror` realm
  (spec 004 US6).
- **FR-022**: A brand's realm MUST report registrations and account changes to the brand's `players`
  service and to no other service.

**Personal data**

- **FR-023**: These fields MUST be held as personal data (ankka 042) under the player's id wherever
  they appear — in state, in a view, in a message, in an object: given and family names, email
  address, telephone number, postal address, date of birth, nationality, identity and address
  documents, the player's network address, device identifiers, and free text an operator or a player
  wrote about the player. A service spec MAY add fields and MUST NOT remove one.
- **FR-024**: Amounts, currencies, ids, times, statuses, game identifiers and a player's country MUST
  NOT be personal data, so ledgers, aggregates and commission facts survive erasure. A player's
  per-network pseudonym (spec 012 FR-013) is likewise not a `Personal` field: it is the only name of
  a player the programme holds, it survives erasure, and the key that derives it never leaves the
  brand.
- **FR-025**: A player's erasure MUST be requested through the brand's `players` service, which files
  it in the brand project and in each shared project that holds the player's id (`payments`, under
  the erasure right the brand is granted there, ankka 040), under one correlation id, with the hold
  the brand's retention rules require.

**Money**

- **FR-026**: Every amount MUST be a whole number of minor units of a named currency, and a boundary
  that receives a decimal MUST refuse it, naming the amount.
- **FR-027**: Every command that moves money MUST carry an idempotency key, MUST record the keys it
  has seen, and MUST answer a repeat with the first outcome and no movement.
- **FR-028**: A wallet MUST be one currency; a player with two currencies has two wallets.

**The port method**

- **FR-029**: Before a service is rebuilt, the original MUST be run locally with the game provider
  answered by `fake-st8`, Unity Finance by `uf-stub`, and the other seven payment providers
  (bouncemoney, cozeni, dreamzpay, eupaymentz, gigadat, morphosis, t365) by stubs this repository
  adds; ConnecX is not carried over (005) and is not stubbed. Recording MUST refuse to start while any provider the original calls is unstubbed.
- **FR-030**: Recordings MUST be made by ankka-port driven by the original's Go end-to-end suite, one
  recording per scenario, kept under `tests/port/<service>/` in this repository, and the original's
  own suite result MUST be kept beside them. Where the suite skips every scenario of a capability —
  responsible gambling and KYC today (specs 010, 009) — the scenarios MUST be added to the Go suite
  and run against the original before that service is recorded; until they exist, the service's
  Playwright suites and the original's unit fixtures are its oracle, named in its spec.
- **FR-031**: Every requirement in a service spec MUST name how it was established: a recording, the
  original's source, or a decision in this spec, the plan or the eitheror brain.
- **FR-032**: Every rebuilt service MUST carry one parity test per recording its spec names, run in its
  own test kit, never against the original.
- **FR-033**: The original's Playwright suite MUST pass against a rebuilt brand with only base URLs
  changed; it is the frontends' acceptance test and no frontend code is in scope.

**The installation**

- **FR-034**: The installation MUST be self-hosted by eitheror on Google Kubernetes Engine, behind
  Cloud Armor, with telemetry exported through ankka 026 to the installation's Grafana stack.
- **FR-035**: Every project MUST be backed up with point-in-time recovery (ankka 041), and a
  real-money installation MUST require the copy outside the cluster.
- **FR-036**: Erasure (ankka 042) MUST be on, with the keyring at two instances or more.
- **FR-037**: The installation's topic retention bound (ankka 043) MUST be raised above the shipped
  default to what FR-015 needs before any fact topic is declared for a real-money brand.
- **FR-038**: The installation's broker MUST have three nodes from creation.
- **FR-039**: The pilot brand MAY run on Garage; before a brand's documents or backups are relied on
  for a regulator, the installation MUST use Google Cloud Storage through the cloud provider (ankka
  039, 044), with secrets in Secret Manager (ankka 038).
- **FR-040**: A manifest marked for real money MUST be refused on an installation that fails any of
  FR-035 to FR-038, naming the condition, and a brand's status MUST list the conditions checked.

### Key Entities

- **Brand**: a commercial casino with its own players, realm, services and versions; one ankka
  project named by its slug.
- **Manifest**: the one description a brand is created from: services and versions, topics, grants,
  realm, and whether it takes real money.
- **Shared project**: `payments`, `programme` or `reasoner`; holds no brand's personal data beyond the
  player id — and, in `payments`, the customer fields FR-018 names — and reaches a brand only by grant.
- **Topic**: one of the ten brand topics the contract names, existing once per brand, produced by one
  service; or `payments.merchants`, the shared project's one.
- **Message family**: a named kind of fact with a version; changes only by addition, else a new
  version.
- **Grant**: a route or topic a project opened to a named principal in another project (ankka 040).
- **Realm**: `casino-<slug>` for a brand's players; `eitheror` for operators, affiliates and partners.
- **Route class**: public, player, operator, affiliate, partner or service.
- **Personal field**: a field held under a player's id and erasable (ankka 042).
- **Recording**: one scenario's requests and the original's answers, kept in this repository and
  replayed by a parity test.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A brand is created from its manifest on a local installation in under 15 minutes, every
  service ready, and a second brand from the same manifest in the same time with no change to the
  first.
- **SC-002**: Every message observed on any topic in a running brand belongs to a family and version
  FR-011 lists, is keyed as FR-012 says, and carries no personal field in the clear; one deviation
  fails the contract's test.
- **SC-003**: With a player, an operator, an affiliate, a partner and a service identity, every route
  of every service answers as its class in FR-020 says; a route with no class is a failure of the
  service's spec.
- **SC-004**: An erased player's personal fields read as erased in every brand service, every shared
  project and every topic within the bound ankka 042 states, and every amount about the player reads
  unchanged.
- **SC-005**: Every money command repeated under its key moves nothing the second time, across every
  service, in the parity suite.
- **SC-006**: Every scenario of the original's Go end-to-end suite has a recording, every service spec
  names only recordings that exist, and every requirement in every service spec names how it was
  established.
- **SC-007**: A real-money manifest is refused on an installation missing any of FR-035 to FR-038 and
  accepted on one meeting all of them, in a test that removes each condition in turn.

## Assumptions

- The plan's project and service tables are taken as read except where Clarifications say otherwise;
  the component tables per service belong to that service's spec.
- ankka 038–044 are implemented before the first real-money brand; the pilot brand may be built and
  tested on a local installation without them, and FR-040 is what stops it taking real money early.
- The pilot brand is Goldvibe (`goldvibe`), which holds no player data in production, so Phase 3 of
  the plan (launch the pilot) comes before Phase 4 (migrate Spinvibe, spec 019). Goldvibe is created
  fresh on ankka and is not migrated.
- The original's Go end-to-end suite holds 118 test functions today and its Playwright suite 284
  tests across 77 files; the counts are evidence of coverage, not a target, and a scenario the suite
  lacks is added to the suite, not to a recording by hand.
- TL function packs are not carried over: the 45 casino functions, the payments functions and the Go
  capabilities are rewritten as consumers, timers and workflows in the owning service, as the plan
  says. TL is not sold as part of the casino rebuild, so no TL backend for ankka's WebAssembly ABI
  (ankka 030) is specified, and the RG and reward timers (specs 010, 011) are built in-service.
- Customer.io, ST8, the PSPs, ip-api, frankfurter, Chatwoot and Strapi stay external and unchanged.
  Unleash stays a library where a flag is read. MinIO is replaced by the installation's object store.
- The plan's `proposals` service was the lab-grown-food research agent, another product's; spec 018
  carries it over nowhere, and `reasoner` holds `audit` alone (FR-003).
- The thinkmorestupidless ↔ eitheror agreement is not a matter for a spec; ankka is Apache 2.0.

## Dependencies

- ankka 022 (identity), 024 (replayable topics), 026 (telemetry), 027 (managed broker), 029 (agent
  approvals), 032 (recurring timers), 033 (rollback), 034 (object storage): merged.
- ankka 038 (secret store backends), 039 (GCS), 040 (cross-project access), 041 (backups and
  recovery), 042 (personal data erasure), 043 (topic retention), 044 (cloud provider): specified, not
  implemented; FR-040 gates real money on them.
- ankka-port and ankka-inspect (the `ankka@ankka` plugin) for FR-029 to FR-032.
- The original, `eitheror-platform`, runnable locally with `fake-st8` and `uf-stub`.

Requirements the first wave of service specs (002–007, 020) placed on 008–010 are met: `players`
opens game sessions (008 FR-017) and answers account status (008 FR-018); `kyc` answers the play and
deposit gates as queries (009 FR-019, FR-020) and produces its families; `responsible-gambling`
answers `may-play`, `may-deposit`, `may-bet` and `may-log-in` (010 FR-007 to FR-010). Requirements
the second wave (008–011) placed on the third (014–019) are met as listed, and the third wave's
requirements on earlier specs were taken at reconciliation 3: the reporting zone lives in `players`'
brand configuration (008 FR-041, read by `reporting` and `attribution`); `casino.rounds`,
`casino.rewards`, `payments.payments` and `money.transactions` carry the fields the table names;
`players` serves `engagement` a message route and produces the message families (008 FR-030);
`rewards` serves the award, target and active-grants routes (011 FR-022, FR-022a); the player site's
proxy forwards the realm token instead of minting a hand-off token (017, a frontend change spec 019
carries).

- Spec 014 (`engagement`): the holding-level Customer.io configuration moves here from
  `merchant-service` (spec 004); operator invitations from `merchant` are sent through it; the
  Customer.io webhook and the `grant_bonus` and `target_offer` actions call `rewards`' service routes
  (spec 011 FR-022); every CRM event the original sent for players, limits, breaks, KYC and rewards
  is derived here from the families FR-011 lists (specs 008, 009, 010 FR-030, 011 FR-029), once each;
  the verification and password emails are sent through this service (spec 008 FR-004).
- Spec 015 (`reporting`): exclusions, limits and the regulator's case export from `rg_case_opened`
  and `rg_case_closed` (spec 010 FR-031); KYC queue counts from `kyc`'s families.
- Spec 019 (migration): the opening events for players, sessions, the account trail, KYC records and
  documents (specs 008, 009), responsible-gambling profiles and cases (010), offers and grants in the
  new shape (011); and the defect list below, so the cutover runbook says which behaviours change.

## Known defects of the original fixed by the port

Each is a behaviour the original's code has today that its own specs or incidents say it should not;
each service spec lists it under *Changes from the original*, and spec 019's runbook cites this list.

- A credit settles only the first debit of a multi-debit round, leaving the rest open in the wallet
  — the open-rounds leak of decision 0032 and the 2026-08-19 incident (spec 003).
- No deposit is ever refused for a limit, a break or an exclusion: the check runs after the credit
  and its refusal is a warning in a log; the bet-time check fails open on any transport error (spec
  010).
- A staged limit loosening never applies (the function is not registered); a cooling-off never ends
  by itself (the function is disabled, matches no case and clears no flag); reinstating a player
  leaves `rg_is_cooling_off` set so they stay blocked at login; a period exclusion's period is
  written and never read (spec 010).
- The KYC verification window is set at registration and never enforced (the expiry function's body
  is empty); the source-of-funds gate can never be cleared, so a player over the threshold is
  refused every deposit for good; only the latest document view is audited (spec 009).
- The player id is derived from brand and email in three paths, so an erased player who registers
  again gets the same id; the login-failure lockout function has an empty body; a registration whose
  player write fails leaves an orphan realm user (spec 008).
- A player who registers with an affiliate's bonus code is never attributed for later deposits and
  play, and the click id is never forwarded to registration postbacks (spec 012).
- Nothing enforces marketing suppression outbound: a self-excluded or cooling-off player can be sent
  a campaign, and a duplicate or stale lifecycle event can fire one, because the triggers are state
  predicates with a process-local dedup (spec 014).
- No money command carries a caller's idempotency key: the wallet dispatcher mints a random UUID per
  dispatch, so a retried bet, win or adjustment can move money twice; only deposits are deduplicated
  by payment reference. FR-027 is new behaviour against the original (spec 002).
- Every money figure on the backoffice dashboard and the revenue report is a sum over the twenty most
  recently registered players presented as the brand's, the payment report answers constants, and
  the ledger truncates a player at two hundred rows (spec 015). This is a reporting defect, not a
  data one: no stored balance is wrong, but no historic dashboard figure is reproducible.

## Out of scope

- Any service's behaviour: each is its own spec (002–018).
- The Spinvibe migration (spec 019).
- Frontend changes beyond base URLs; custom hostnames per brand.
- Hosted ankka (ankka-cloud) — the installation is eitheror's own.
- Two installations, or a brand in a second cloud account: a brand licensed in another jurisdiction is
  a second installation (ankka 044).
