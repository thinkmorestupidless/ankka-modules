# Feature Specification: Outside Products — A Brand Consumes eitheror's Game Provider and Affiliate Network as Any Operator Would

**Feature Branch**: `028-outside-products`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "eitheror's own game provider and its affiliate network are products
of their own, not parts of the casino: each in its own repository (`ankka-games`,
`ankka-affiliates`) and, on an installation, in its own ankka organisation. A brand consumes each
exactly as it consumes ST8 or a third-party network: credentials exchanged at onboarding, every
call through the public gateway, no grant under ankka 040 and no shared realm. `programme` stops
being a shared project. `attribution` delivers facts to every network, eitheror's included, by the
protocol's signed feed and webhooks only. `game-provider` runs eitheror's provider as a second
provider beside ST8. The first games are a pick 14 football pool and card rips, so a round may
stay open for a week. A check holds the boundary. Amends specs 001, 003, 010, 012, 013, 015 and,
where it has merged, 026."

## Context

Spec 001 gives an installation four shared projects (FR-003): `payments`, `programme`, `reasoner`
and `tools`. A brand grants the `programme` project its `affiliates.attribution` topic to consume
(001 FR-016, 012 FR-019), affiliates and programme managers sign in to the casino's own `eitheror`
realm (001 FR-019, 013 FR-004), and the constitution's principle III names `programme` among the
shared projects. Spec 013 built the programme that way, and gave it a second way in for a brand it
cannot reach by a grant: a hosted brand, or a brand of eitheror's on another installation, posts
the same facts to the programme's network webhook (013 FR-008). Spec 012 already describes a
network as "the programme, another installation's programme, or a third party", and gives every
network two carriers that need nothing of the installation: the signed feed and the network
webhook (012 FR-020, FR-021).

Spec 026, in flight on its own branch and not merged when this was written, makes eitheror a game
provider: a `provider` edge that speaks ST8's protocol exactly, built from the simulator's core
(spec 020), reaching a brand only as an outside caller through the gateway with no grant (026
FR-002), and configured at a brand as a second provider of the protocol `st8` under the name
`eitheror` (026 FR-026). It places that provider in a fifth shared project, `games`, deployed from
`shared/games/manifest.json`, with its operators admitted by a `games` permission in the casino's
staff realm (026 FR-001, FR-024, FR-028).

This spec moves the line. The game provider and the affiliate network are **outside products**:
each is built, released and operated apart from the casino, serves eitheror's brands first and
other companies' brands on the same terms, and is consumed by a brand of eitheror's exactly as a
brand consumes ST8 or a third-party network. Three things follow, and they are the whole of this
spec:

1. **Where they live.** Neither is a shared project of the installation and neither is built in
   this repository. The affiliate network's home is `thinkmorestupidless/ankka-affiliates`; the
   game provider's is `thinkmorestupidless/ankka-games`. Where one runs on the same cluster as the
   brands it serves, it runs in an ankka organisation of its own.
2. **How a brand reaches them, and they a brand.** Through the public gateway, admitted by a
   signature or a credential exchanged at onboarding, as the internet reaches a brand's public
   routes (constitution III). No grant under ankka 040 exists between a brand and either product in
   either direction, and neither shares a realm with the casino.
3. **Who owns the protocol.** Each product publishes the protocol it speaks from its own
   repository; this repository vendors a pinned copy and is held to it.

Nothing on the wire changes for the game provider: it already speaks ST8's protocol and already
reaches a brand from outside. For the affiliate network one carrier goes: the granted topic. A
brand of eitheror's delivers to the programme as a hosted brand does today.

The first games (specs of `ankka-games`, not of this repository) are a pick 14 football pool and
card rips. Neither is a slot. A card rip is a round opened and settled in one sitting. A pool
entry is a bet placed when the coupon is entered and a win credited after the last match, days
later; spec 003 gives a round no limit of time (neither ticket expires, 003 FR-008; a credit finds
its round by the round id, 003 FR-014), but nothing downstream of it has met a round that stays
open for a week. This spec says what such a round means for the brand's operators, its loss
limits and its day-end.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A brand delivers its facts to eitheror's network as it would to anyone's (Priority: P1)

A brand's operator adds eitheror's affiliate network to the brand as they would add a third
party's: the network's id, the public addresses of its key document and revocation list, the
pseudonym key the brand generates for it, and a network webhook with the address and shared
secret the network's operators gave them. From then on the brand verifies tags against the
network's published keys, binds players, and posts every fact to the webhook, retrying for a day.
A network that prefers to pull reads the brand's feed with the machine sign-in the brand's
operators registered for it. Nothing in the installation is allowed to read the brand's
attribution facts where they are kept.

**Why this priority**: it is the whole of the affiliate side of the change, and a brand cannot
launch with affiliates until its facts reach the network this way.

**Independent Test**: apply a brand with the programme added as a network by operator acts alone;
emit facts; observe them arrive at the network's webhook signed; list what the brand allows and
find nothing allowed to the network; ask for the brand's attribution facts as the programme's
service from inside the installation and be refused.

**Acceptance Scenarios**:

- changed `features/attribution/carriers.feature`: the programme receives a brand's facts by its network webhook as any network does, signed, within a minute
- added `features/attribution/carriers.feature`: nothing in the installation is allowed to read a brand's attribution facts where they are kept
- added `features/attribution/networks.feature`: the programme is added to a brand by an operator as any network is, and a brand it was not added to binds nothing to it
- added `features/attribution/networks.feature`: a brand fetches the programme's key document, revocation list and legacy tag document from the public addresses the operator gave

---

### User Story 2 - A brand allows nothing to an outside product, and a check says so (Priority: P1)

An operator of the installation asks what a brand allows. The answer is what spec 001 FR-016 lists, the shared payments' routes and the shared
audit's facts, and nothing else: no
service of the game provider's or the affiliate network's holds an allowance at any brand, no
brand's service holds one at either product, and none is waiting to be accepted. A module
manifest that names either product as the maker or receiver of a grant is refused before anything
is applied. On a running installation the same check runs against what the control plane holds,
and an allowance the contract does not list is reported naming the brand, the grantee and the
thing it opens.

**Why this priority**: sharing a cluster is what erodes a boundary. The products can be moved to a
cluster of their own, or sold to another operator, only while nothing depends on them standing
beside a brand.

**Independent Test**: validate the modules' manifests and find no grant naming either product; on
an installation holding a brand and both products, make a grant from the brand to a service of
the network's by hand and see the check report it; end the grant and see the check pass.

**Acceptance Scenarios**:

- added `features/brands/access.feature`: a brand allows nothing to the programme or to a game provider, and neither allows anything to a brand
- added `features/brands/access.feature`: an allowance the contract does not list is reported naming the brand, who was allowed and what
- added `features/brands/modules.feature`: a module manifest allowing anything to the programme or to a game provider is refused naming the entry

---

### User Story 3 - A brand runs eitheror's game provider beside ST8, by configuration and credentials alone (Priority: P1)

An operator of a brand adds a second provider configuration: the provider's name, its protocol,
its public address and its public key, and the brand's own operator key for it, exactly as the
brand holds ST8's. The provider's operators add the brand at their side with the brand's address
and public key. The nightly refresh lists the provider's games; a player launches one; the
provider's signed callbacks arrive at the brand's public provider routes under the provider's own
name and move the wallet as ST8's do. No setting of the installation names the provider, no
manifest of this repository deploys it, and no one signs in to it through the casino's realms.

**Why this priority**: it is what lets the brands launch with eitheror's own games, and what lets
another company's brand integrate with them the same way.

**Independent Test**: configure a brand with the provider at an address outside the installation
(the simulator standing in for it on a second cluster or a second gateway host), launch, play a
round, and replay spec 003's parity suites under the provider's name.

**Acceptance Scenarios**:

- added `features/game-provider/launch.feature`: a game provider is known to a brand only by the name, address and keys its operator configured, wherever the game provider runs

---

### User Story 4 - A round that stays open for a week is an open round, not a fault (Priority: P2)

A player enters a football pool on Tuesday. The game provider debits the stake; the brand's
wallet places the bet and the round is open. On Sunday night the last match ends and the provider
credits the win, or a zero credit for a loss; the round settles, whether or not the player has a
game session open, and one round settled fact is published then. In between, an operator listing
open rounds sees the round with its game, its stake and its age, and nothing closes it for being
old. The player's loss limit counts the stake from Tuesday. Finance's day-end for Tuesday to
Saturday lists the round as open with its stake, apart from the differences it must chase.

**Why this priority**: the brands can launch on card rips and ST8 alone; pools need this before
their first coupon closes.

**Independent Test**: with the simulator, debit a round, move the clock six days, credit it;
read the operator's open rounds, the loss sums a bet is checked against, and each day's day-end
in between.

**Acceptance Scenarios**:

- added `features/game-provider/rounds.feature`: a round stays open for days until the game provider settles it, and the win settles it whether or not the player's game session has ended
- added `features/game-provider/reads.feature`: an operator lists a brand's open rounds with each round's game, stake and age, and none is closed for its age
- added `features/responsible-gambling/limits.feature`: the stake of a bet in an open round counts towards the loss limit from the moment it is placed until the round settles
- added `features/reporting/day-end.feature`: a round still open when the day closes is listed as open with its stake and not under differences

---

### Edge Cases

- A brand on the same cluster as the network posts to the network's public address and back
  through the gateway; it never uses an address inside the cluster, and a network webhook whose
  address is not public is refused when the operator creates it on a real-money brand.
- The network is unreachable for longer than a day: facts are kept as undelivered facts the
  operator can resend (012 FR-021); nothing is lost and nothing else of the brand waits on it.
- A grant to either product made by hand on a running installation: the check reports it and the
  brand's status says the brand is outside its contract; nothing ends the grant but an owner.
- A brand removed from the game provider's side while it holds open rounds: the provider's later
  credits never arrive; the rounds stay open and listed, and an operator resolves each with a
  reason, as spec 003 FR-020 resolves an unacknowledged call. A stake resolved this way stops
  counting towards the loss limit when it is resolved.
- A credit for a week-old round that the wallet refuses: recorded as unacknowledged and replayed
  or resolved by an operator as any other (003 FR-020); the provider's own retries stop at its
  schedule's cap, so the brand's unacknowledged list, not the provider's retry, is what closes it.
- A cancel of a pool entry after the coupon is voided: a cancel of the debit, days later, rolls
  the bet back as 003 FR-015 says and the stake stops counting towards the loss limit.
- A player self-excludes or is closed while holding an open round: the credit is still paid (003
  FR-014 settles by round id and asks no gate); no new debit is admitted.
- A player erased while holding an open round: the round and its wallet facts keep the player id
  as they do today; the credit settles it.
- The vendored copy of a protocol differs from the pinned digest: the build fails naming the file.
- Spec 026 merges before this is built: its `shared/games`, `services/provider`, the `games`
  realm permission and its features move with it to `ankka-games`; its amendment to spec 003 (a
  provider's protocol is configuration) stays here.

## Requirements *(mandatory)*

### Functional Requirements

Each requirement names what established it. Nothing here was recorded from the original
(`eitheror-platform`), which has neither an outside game provider of its own nor a network outside
its brand; every requirement is a decision of 2026-11-10 unless it restates a spec named beside it.

**Where the products live**

- **FR-001**: The installation MUST hold three shared projects: `payments`, `reasoner` and
  `tools`. `programme` MUST NOT be a shared project, and no `games` project MUST be one. *Amends
  spec 001 FR-003, the constitution's principle III (amendment 1.3.0) and, where it has merged,
  spec 026 FR-001 and FR-028.* The slugs `programme` and `games` MUST stay refused as a brand's
  slug (001 FR-001), so no brand is mistaken for either product.
- **FR-002**: This repository MUST hold nothing of the affiliate network but what a brand needs to
  speak to one: no service, shared manifest, realm role, recording, living feature or build job of
  the programme. Spec 013 and everything built from it (`services/affiliate-programme`,
  `shared/programme`, `features/programme`, `tests/port/programme`, the `programme-*` recipes and
  the build's job) MUST move to `ankka-affiliates`; `specs/013-affiliate-programme/` here MUST
  hold one file naming where the spec went and nothing else. *Amends spec 013 (its home) and the
  spec list in `CLAUDE.md`.*
- **FR-003**: This repository MUST hold nothing of the game provider but what a brand needs to
  speak to one. Where spec 026 has been built here, its provider (`services/provider`,
  `shared/games`, `features/games`, the `games` realm permission) MUST move to `ankka-games` the
  same way; its amendments to the brand's side (026 FR-026: a provider's protocol is configuration;
  026 FR-027: the Anjouan licence profile) MUST stay. *Amends spec 026.*
- **FR-004**: On an installation that holds a brand and an outside product, the product MUST run
  in an ankka organisation that holds no brand and no shared project. *Decision 2026-11-10.*

**The boundary**

- **FR-005**: No grant under ankka 040 MUST exist whose maker is a brand's project or a shared
  project and whose grantee is a service of an outside product, nor the reverse, nor one pending
  acceptance. A brand MUST grant exactly what spec 001 FR-016 lists once this spec has removed the
  `affiliates.attribution` grant to `programme`/`affiliate-programme` from it. *Amends spec 001
  FR-016 and the `core` module's manifest.*
- **FR-006**: The modules tool MUST refuse, before any call, a module manifest or a shared
  manifest that names a project other than a brand's own or a shared project of FR-001 as the
  maker or receiver of a grant, naming the entry. *Amends spec 021 FR-006.*
- **FR-007**: The modules tool's `status` MUST list every grant the control plane holds for a
  brand's project and for each shared project, made and received, accepted and pending, and MUST
  report each one the contract does not list, naming the project, the grantee and the route or
  topic; with any such finding `status` MUST say the brand is outside its contract and exit
  refused. The same check MUST run in the integration suite against an installation holding a
  brand and a stand-in for each product in another organisation. *Decision 2026-11-10.*
- **FR-008**: The casino's realms MUST hold no affiliate, no programme manager and no operator of
  an outside product: the realm `eitheror` is for the casino's operators and partners. The roles
  `affiliate` and `affiliate_manager`, and any `games` permission, MUST NOT be in
  `brands/_realm/eitheror-operators.json`. An outside product keeps its own sign-in, in an identity
  provider instance of its own (one per product, decided 2026-11-10). *Amends spec
  001 FR-019; restates the destination of spec 013 FR-004 and spec 026 FR-024, which move.*
- **FR-009**: No setting of an installation (`settings.env`, 001 FR-034 to FR-039) and no manifest
  of this repository MUST name an outside product's address, key or image. A brand's knowledge of
  a product is the brand's own: its network settings (012 FR-025), its provider configuration
  (003 FR-026), and its values. *Decision 2026-11-10.*

**Attribution: one way in for every network**

- **FR-010**: A brand MUST deliver its attribution facts to every network, eitheror's included,
  by the network webhook (012 FR-021) or the feed (012 FR-020), whichever the network uses, and by
  nothing else. The requirement that a brand be able to grant its topic to a programme project
  (012 FR-019) is withdrawn. *Amends spec 012 FR-019 and spec 013 FR-008.*
- **FR-011**: `affiliates.attribution` MUST remain the brand's own record of the facts it emitted,
  declared as spec 012 FR-018 declares it, from which the feed and the webhooks are served, and
  MUST be granted to no project. [NEEDS CLARIFICATION: 012 FR-019 also lets a brand grant the
  topic to a registered machine, so a third-party network may read it with its own reader through
  the installation's broker; the scenario "a network registered as a machine reads the same facts
  with its own reader" is built. Is that carrier withdrawn too, so that the feed and the webhook
  are the only two for everyone, or kept for a third party that asks for it? It crosses no
  organisation: the machine is registered in the brand's own.]
- **FR-012**: The feed MUST admit a network as spec 012 FR-020 says: a machine registered in the
  brand's organisation for that network, and no service identity of any project. *Restates spec
  012 FR-020.*
- **FR-013**: A brand MUST fetch a network's key document, revocation list, legacy tag document
  and code document from the public addresses its operator gave when adding the network (012
  FR-023, FR-025; `rewards-code-document-url`, spec 011), over the public gateway, and MUST NOT
  derive any of them from the installation it runs on. On a real-money brand an address that is
  not `https` on a public name MUST be refused when it is set. *Amends spec 001 FR-016's sentence
  on the programme's documents; decision 2026-11-10 for the refusal.*
- **FR-014**: No network MUST be added to a brand by applying the brand: eitheror's network is
  added by an operator's act with the credentials its operators supplied, as a third party's is
  (012 FR-025). The modules tool MUST seed none. *Decision 2026-11-10.*

**The protocols**

- **FR-015**: The attribution protocol (the tag, the fact kinds and fields, the feed, the webhook,
  the documents a network publishes: `specs/012-affiliate-protocol/contracts/protocol.md` and
  `facts.md`) MUST be published by `ankka-affiliates` under its `contract/`, versioned. This
  repository MUST hold a vendored copy at a named version with its digest, and the build MUST fail
  when the copy and the digest differ. Spec 012 remains the specification of the brand's side;
  where it and the vendored protocol disagree the protocol wins and spec 012 is corrected.
  *Amends spec 012 (ownership of its contracts).*
- **FR-016**: The operator protocol of eitheror's game provider MUST be published by `ankka-games`
  under its `contract/` and vendored here the same way. Its first version is ST8's protocol as
  spec 020's ST8 adapter and spec 003's parity suites establish it, so a brand runs the provider
  under the protocol `st8` and this repository builds no second adapter. A version that departs
  from ST8's wire MUST be a protocol of its own name with an adapter of its own, by a later spec.
  *Restates spec 026 FR-003 and FR-026; decision 2026-11-10 for the ownership.*
- **FR-017**: A brand MUST hold, per provider it runs, that provider's configuration as spec 003
  FR-026 holds ST8's: in the secret store, per brand, never in a manifest or a setting; and its
  values MUST name the providers it runs (026 FR-026). *Restates spec 003 FR-026.*
- **FR-018**: The game provider simulator (spec 020) MUST stay a test tool in `tools`, refused on
  an installation that takes real money, speaking ST8's protocol. It needs no adapter for
  eitheror's provider while FR-016's first version holds; the simulator MUST be able to stand in
  for the provider under the provider's name in the brand's suites. [NEEDS CLARIFICATION: spec 026
  FR-005 makes the simulator's core a library shared by the simulator and the provider. With the
  provider in `ankka-games`, which repository owns and publishes that library, and does the
  simulator move with it or stay here consuming it?]

**A round that stays open**

- **FR-019**: A round MUST stay open, with its bets held by the wallet, until the game provider
  credits or cancels it or an operator resolves it, for any length of time; nothing MUST close,
  settle or flag a round for its age alone. A credit MUST settle its round by the round id
  whether or not the session ticket's game session is still open. *Restates spec 003 FR-008 and
  FR-014; established from their text, not from a recording: no recording holds a round open past
  one sitting, and the plan MUST add one with the simulator.*
- **FR-020**: An operator MUST be able to list a brand's open rounds with, for each, the provider,
  the game, the player, the total stake and when it was opened, filtered by provider and game and
  ordered by age. *New; amends spec 003 (its operator reads).*
- **FR-021**: An operator MUST be able to resolve an open round whose provider will never settle
  it, giving a reason: the bet is rolled back or settled as a loss as the operator states, the act
  is recorded on the round with the operator and the time, and a later credit for it from the
  provider is a provider credit under spec 003 FR-014's rule for a round already settled. *New;
  amends spec 003 FR-020, which resolves only an unacknowledged call.*
- **FR-022**: The stake of every bet in an open round MUST count towards the player's loss sums
  (spec 010 FR-010, carried by the wallet in `may-bet`) from the moment the bet is placed, and
  MUST be replaced by the round's actual result when it settles or removed when it is rolled back.
  This changes what spec 010 enforces for every game, slots included, where a round is open for
  seconds: until now a loss was a stake settled as a loss, so a player could enter any number of
  pools in a week without nearing a loss limit. Counting at entry was decided on 2026-11-10 and
  confirmed the same day, for every profile. *Amends spec 010 FR-010 and the wallet's loss sums (spec 002).*
- **FR-023**: A round settled fact (`round_settled` on `casino.rounds`, 001 FR-011) MUST be
  published when the round settles and never before, carrying when the round was opened as well as
  when it settled. Wallet facts fall on the day they happen: the bet on the day of entry, the win
  on the day of settlement. Revenue, GGR and the affiliate's `ggr-daily` fact (012 FR-016) MUST
  follow the wallet's facts with no spreading across days. *Restates spec 003 FR-019 and spec 015;
  the opened time is new.*
- **FR-024**: The day-end (spec 015) MUST list, per provider and game, the rounds open at the
  close with their count and total stake, apart from differences; a round open at the close MUST
  NOT be listed as a round the wallet holds and the provider's record lacks. *Amends spec 015
  FR-017 and FR-018.*

### Key Entities

- **Outside product**: a product eitheror builds and operates apart from the casino, in its own
  repository and its own organisation, that a brand consumes over public addresses with exchanged
  credentials: the affiliate network (`ankka-affiliates`) and the game provider (`ankka-games`).
- **Network** (spec 012): unchanged; the programme is one network among any.
- **Provider configuration** (spec 003 FR-026): unchanged; one per provider a brand runs.
- **Vendored protocol**: a copy of a product's published protocol at a named version, with its
  digest, that this repository's build is held to.
- **Open round**: a round with a bet the wallet holds and no settlement; it has an opened time, a
  game, a provider and a total stake, and no limit of age.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On an installation holding a brand and both products, the grant check finds no
  grant between the brand's organisation and either product's, and reports within one run a grant
  made by hand between them.
- **SC-002**: A brand with the programme added by operator acts alone delivers every one of
  10,000 emitted facts to the programme's webhook, each within a minute of emission while the
  network answers, with none delivered by any other carrier.
- **SC-003**: With either product stopped, a brand's players register, deposit, play ST8's games
  and withdraw with no error; with the network stopped for an hour, every fact emitted in that
  hour is delivered within a day of its return.
- **SC-004**: A brand is pointed at the same product running on a second cluster by changing the
  brand's own settings and credentials only: no manifest, no installation setting and no grant
  changes.
- **SC-005**: A round opened and settled six days later appears in the operator's open rounds on
  each of the days between, in each day's day-end as open and never under differences, counts its
  stake towards the loss limit on each of those days, and publishes exactly one round settled
  fact.
- **SC-006**: After the move, a search of this repository finds no service, manifest, realm role,
  recipe or build job of the programme or of the game provider.

## Changes from the original

The original has no affiliate network outside its brand and no game provider of its own; what
this spec changes is the shape specs 001, 012, 013 and 026 gave them here.

1. **The programme is not a shared project.** It is an outside product in an organisation of its
   own, and a brand of eitheror's reaches it as a hosted brand does.
2. **The granted topic is not a carrier.** Every network receives by webhook or reads the feed.
3. **Affiliates and programme managers leave the casino's realm.**
4. **The game provider is not a shared project** and its operators hold no permission in the
   casino's staff realm.
5. **Each product owns its protocol**; this repository vendors a pinned copy.
6. **Open stakes count towards a loss limit**, and an open round is listed as open by the
   operator's reads and the day-end. New behaviour, named here under constitution I.

## Assumptions

- The shared projects that remain (`payments`, `reasoner`, `tools`) and their grants with a brand
  are unchanged; whether they share the brands' organisation is as it is today.
- A brand of eitheror's joins the programme as spec 013's hosted brand joins it: added at the
  network's side by its operators, facts by webhook. What the network calls such a brand is the
  network's word, in its own glossary.
- The products' addresses are public names under the installation's base domain or their own
  custom hostnames; a brand does not care which.
- Commission, plans, payouts, the affiliate portal, the games, the game contract and the
  provider's operators are the products' own specs and are not restated here.
- Card rips are rounds opened and settled in one sitting and need nothing of this spec beyond
  FR-016 and FR-017.
- A pool's coupon is per brand (026's assumption); a pool shared across brands is the provider's
  later decision and changes nothing at a brand.
- Free rounds reach a game of eitheror's provider as they reach ST8's (026 FR-014).
- The move of files between repositories keeps their history where the tooling allows; it is the
  plan's concern.

## Dependencies

- Spec 001 (shared projects, grants, realms), 003 (the brand's provider service), 010 (loss
  limits), 012 (the protocol and `attribution`), 013 (the programme, built), 015 (the day-end, in
  flight), 020 (the simulator), 021 (the modules tool), 026 (the provider, in flight).
- `ankka-affiliates` and `ankka-games` existing as repositories that can receive what moves and
  publish a versioned `contract/`.
- ankka 040: machines for the feed, and the control plane's listing of a project's grants made,
  received and pending, which FR-007 reads.

## Out of scope

- Anything the products do inside themselves.
- A second protocol adapter at the brand.
- Moving a product to a cluster of its own: this spec makes it a change of a brand's settings and
  does not do it.
- Revenue share or invoicing between a brand and a product.
- Certification of any game with a random element (026's assumption stands: a precondition named
  by the game's own spec).

## Open Questions

- The two marked above: the machine's topic carrier (FR-011), and the home of the provider's core
  library and of the simulator (FR-018).
- Spec 026 is finished and merged here first, then moves to `ankka-games`, and further amendments
  follow the move (decided 2026-11-10).
- Spec 015 is in a draft pull request; FR-024 amends it there or after it merges.
