# Feature Specification: games — eitheror's own game provider, beside ST8

**Feature Branch**: `026-games-provider`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "026 first-party game provider: a shared project `games` with a
`provider` edge service, speaking ST8's protocol to brands. eitheror becomes its own game provider,
sitting beside ST8: one deployment of a shared project serves every brand on the installation and
supplies first-party games, the first a Loteca-style football pools game (spec 027), each a game
service behind one edge, so a new game never needs a new brand integration. The edge speaks ST8's
operator API and seamless-wallet protocol exactly, is built from the simulator's core (spec 020),
and defines the contract a game service implements. Game clients are TypeScript, owned by their
game service. The brands run under an Anjouan licence into South American markets."

## Context

A brand's `game-provider` (spec 003) speaks to one provider today, ST8, which aggregates every game
the brand offers. It holds one provider configuration per provider it runs (003 FR-026), serves a
second provider's callbacks under that provider's own prefix (003 FR-002), names a game by provider
and code, and refreshes each provider's catalogue nightly. Everything is in place for a second
provider except the second provider itself: the service picks its protocol adapter by the
provider's name, so a second provider that speaks ST8's protocol still needs a line of code.

Spec 020 built the simulator: a provider core (sessions per launch ticket, rounds, scheduled and
signed callbacks with retries, a key pair and a published key document with rotation, a brand table
and an outbound sender that reaches a brand from outside through the gateway) under an ST8 protocol
adapter (the operator API: launch, games and free rounds; the game page that performs `check`; the
eight callbacks with their bodies and signatures), serving many brands from one deployment in the
`tools` project. Its clarifications say what it is not — "the hard part of a real provider is the
games" — and where it would go if that changed: "extract it if it ever becomes a product".

This spec is that extraction. eitheror becomes a game provider for its own brands: a shared
project `games` holding one `provider` service, the edge, and one service per game behind it. The
edge is the simulator's core and ST8 adapter without the scenarios, speaking ST8's protocol so that
a brand configures it as it would configure ST8 and cannot tell the difference. A game service
holds a game's rules and state and its browser client; it reaches the brand's wallet only through
the edge, which signs, sends, retries and records every callback. The first game is spec 027's
football pools; this spec defines the contract it and every later game implements, and nothing of
any game.

Three decisions from the discussion of 2026-11-07 bind it. The brands run under an Anjouan licence
into South American markets, so UKGC and MGA supplier licensing is not a concern of this spec and
the KYC service gains an Anjouan licence profile. Game clients are needed now, so each game owns a
TypeScript client built into its image. The simulator and the product share one core as a library
and are two services: scripted misbehaviour must not be deployable in a real-money provider.

## Clarifications

### Session 2026-11-07

- Q: What are the Anjouan licence profile's values? → A: Curaçao's defaults under the new name,
  confirmed by the compliance officer before a brand under it takes real money (FR-027).
- Q: Do the 13 proposed glossary terms stand (house provider, game service, game page, brand table,
  brand entry, pending debit, unanswered call, retry schedule, key rotation, demo round, claim
  deadline, day export, test game)? → A: All accepted as defined; `key document` and `licence
  profile` widened.
- Q: Who may write the brand table, rotate the key and abandon unanswered calls? → A: Operators of
  the installation holding a `games` permission in the staff realm, as `markets` is a permission for
  prediction markets; a brand's own operators read only their brand (FR-024).
- Q: How does a game leave the list of games? → A: Explicitly only: a game stays registered until
  its game service deregisters it or an operator removes it; a game service that is down leaves its
  games listed and a launch of one is answered unavailable (FR-007, FR-008).
- Q: What decides which house games a brand may run? → A: The brand entry's games, for now;
  eitheror operates both sides, and a vendor entitlement that writes the brand entry is a later spec
  (FR-017).
- Q: How long are the provider's records kept? → A: 90 days, an installation setting (FR-023); the
  brand's rounds remain the books of record.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A brand plays a first-party game exactly as it plays an ST8 game (Priority: P1)

An operator of a brand adds a second provider configuration, named `eitheror` with the protocol
`st8`, pointing at the provider's address and holding the provider's public key. The nightly
refresh lists the provider's games under that provider. A player launches one: the brand issues a
launch ticket, calls the provider's launch signed with the brand's operator key, and shows the game
page the provider answered. The page loads, the provider checks the ticket with the brand, the game
opens a round with a signed debit, settles it with a signed credit (or a credit of zero for a loss),
and the brand's wallet, rounds, `casino.rounds` facts, reporting and rewards see what they would see
from ST8. The brand's game-provider needs no new adapter, only the configuration.

**Why this priority**: the whole proposition is that a first-party game costs a brand nothing but
a configuration. If the brand side needs code, the provider is a second integration, not a second
provider.

**Independent Test**: configure a brand with the provider beside the simulator, register the test
kit's game, launch it, play a round; replay the brand's parity suites of spec 003 with the provider
in the simulator's place and the simulator's operator API recordings against the provider.

**Acceptance Scenarios**:

- added `features/games/brand.feature`: a brand configures the house provider as a second game provider of the same protocol and lists its games after the refresh
- added `features/games/brand.feature`: a launch at the brand reaches the house provider signed with the brand's credential and answers the game page's address
- added `features/games/brand.feature`: the game page's load checks the launch ticket with the brand and the game learns the player, brand and currency
- added `features/games/brand.feature`: a round opened and settled by a game moves the brand's wallet as any game provider's round does and produces one round settled fact
- added `features/games/brand.feature`: a launch signed with a key that is not the brand's is refused and nothing is stored
- added `features/game-provider/launch.feature`: a second game provider of the same protocol under its own name has its provider calls served under its own name and never under the first's

---

### User Story 2 - A game service joins the provider through one contract (Priority: P1)

A game service starts in the `games` project and registers its games with the edge: code, name,
category, the fields a brand's catalogue filters on, whether the game supports demo play, and the
address of its page. From then on the edge answers launches for those codes with the game's page
address carrying the launch ticket; the game asks the edge to check a ticket and receives the
session (brand, player id, currency, country, demo or not, language); the game opens a round by
asking the edge for a debit and gets the brand's answer or `pending`; the game settles a round by
asking for a credit, which the edge sends and retries until the brand answers; a game cancels a
debit the same way. The game never holds a key, never knows a brand's address, and never sends a
callback itself.

**Why this priority**: the contract is what makes the second and every later game a service in
the project and nothing else. Spec 027 cannot be written without it.

**Independent Test**: the test kit's game (a fixture that exists only in the tests) registers,
is launched, opens, settles and cancels rounds against a fake brand, including a brand that answers
late, answers `error`, and never answers.

**Acceptance Scenarios**:

- added `features/games/contract.feature`: a game registers its games and they appear in the games list a brand fetches with the game's own developer and category
- added `features/games/contract.feature`: a launch for a registered code answers that game's page address carrying the launch ticket; an unregistered code is unknown game
- added `features/games/contract.feature`: a game's check of a launch ticket sends one signed check to the brand and answers the session; a ticket the brand refuses is answered as refused and the game shows the session expired page
- added `features/games/contract.feature`: a debit asked by a game is sent once, signed, under a new transaction id, and the brand's status and balance are answered to the game
- added `features/games/contract.feature`: a debit the brand does not answer within the wait is answered to the game as pending and retried under the same transaction id until the brand answers or the schedule is exhausted
- added `features/games/contract.feature`: a credit asked by a game is accepted at once and sent, retried on error or no answer, and the game can read whether it was acknowledged
- added `features/games/contract.feature`: a credit of zero asked by a game is sent as the brand expects a loss
- added `features/games/contract.feature`: a cancel asked by a game references the debit's transaction id and the round is closed as cancelled on the brand's acknowledgement
- added `features/games/contract.feature`: a round asked on a demo session sends no money callback and the game is told so
- added `features/games/contract.feature`: a game asking for a round on a session of another game is refused
- added `features/games/contract.feature`: a game stays registered until its game service deregisters it or an operator removes it, and a game service that is down leaves its games listed
- added `features/games/contract.feature`: a launch in fun mode of a game that does not support demo play is answered unknown game

---

### User Story 3 - One provider serves many brands, each held apart (Priority: P1)

An operator of the installation adds a brand to the provider's brand table: its slug, the address
of its game-provider, its site id, its operator public key, its language, whether it is a test
brand, and which games it may run. A launch from that brand is admitted only when signed with that
brand's key; a session, a round and a game's state belong to one brand; a game's list answered to a
brand holds only the games the brand may run. Two brands playing the same game share nothing but
the code.

**Why this priority**: the provider is one deployment for every brand on the installation, and a
brand is a licensing boundary (constitution III). A leak between brands is a regulatory failure.

**Independent Test**: two fake brands against one provider; play the same game at both; read each
brand's sessions, rounds and games list.

**Acceptance Scenarios**:

- added `features/games/brands.feature`: an operator adds a brand with its address, site, key, language and games, and the provider admits its launches from then on
- added `features/games/brands.feature`: a launch for a brand the table does not hold is refused
- added `features/games/brands.feature`: a brand's games list holds only the games the brand may run
- added `features/games/brands.feature`: a session of one brand is never answered to another brand's game page or callback
- added `features/games/brands.feature`: an operator's reads of sessions and rounds are per brand
- added `features/games/brands.feature`: only an operator holding the games permission writes a brand entry, and a brand's own operator reads only that brand

---

### User Story 4 - Keys are the provider's, rotation is an operator's act (Priority: P2)

The provider signs every callback with one key pair held in the secret store and publishes a key
document naming its current key and, during a rotation, its next. A real-money brand holds the
provider's public key itself in its provider configuration (spec 003 FR-026); a test brand may name
the key document. An operator starts a rotation: the next key is published, the operator copies it
into each real-money brand's configuration, and only then tells the provider to switch; the old
key is withdrawn from the document after the switch. No timer switches a key.

**Why this priority**: a key switched before a brand holds it fails every callback of every round
at that brand.

**Independent Test**: rotate against two fake brands, one holding the key, one naming the document.

**Acceptance Scenarios**:

- added `features/games/keys.feature`: the key document names the current key and, during a rotation, the next key with their ids
- added `features/games/keys.feature`: starting a rotation publishes the next key and changes nothing about how callbacks are signed
- added `features/games/keys.feature`: switching signs every later callback with the next key and withdraws the old one from the document
- added `features/games/keys.feature`: a brand naming the key document admits a callback signed with the new key within its refresh bound

---

### User Story 5 - Free rounds reach a game as they reach an ST8 game (Priority: P2)

A brand's `rewards` creates a provider bonus for a player on one of the provider's games through
the brand's game-provider (spec 003 FR-025): count, spin value, games, grant id. The edge hands it
to the game, which holds the instance and answers whether it is active; the brand attaches the grant
at launch; the game's free round is a debit of zero of kind `free_debit` followed by a credit, which
the brand routes to the grant's restricted funds (spec 003 FR-013, spec 011). A game whose rounds
stay open for days keeps the instance claimable until its own deadline; the brand's grant expiry
decides what the winnings are worth (spec 011).

**Why this priority**: a free entry is the retention product's whole point (spec 027), and it
costs nothing here if the free-rounds protocol is honoured exactly.

**Independent Test**: the simulator's `OperatorApi_BonusLifecycle` recording against the provider
with the test kit's game; a free round played at a fake brand.

**Acceptance Scenarios**:

- added `features/games/free-rounds.feature`: a bonus created for a registered game is handed to the game and answered active with the count the brand asked
- added `features/games/free-rounds.feature`: a bonus for an unknown game is answered unknown game
- added `features/games/free-rounds.feature`: a cancelled bonus is cancelled at the game and answered cancelled
- added `features/games/free-rounds.feature`: a free round is a debit of zero of kind free debit followed by a credit on the same round
- added `features/games/free-rounds.feature`: a game's free-round instance stays claimable until the game's own deadline

---

### User Story 6 - Operators see what the provider sent and what the brands answered (Priority: P2)

An operator of the installation reads, per brand, the sessions, rounds and callbacks: what was
sent, when, with which key, how many attempts, and what the brand answered. Callbacks the brand has
not acknowledged are listed with their retry state and can be abandoned with a reason. Finance
exports a brand's settled rounds for a day to compare with the brand's own figures. Every operator
act (a brand added or changed, a key rotation, an abandoned callback) is a record on
`platform.audit`.

**Why this priority**: the provider's record is the other side of the brand's books. Without it a
dispute has one witness.

**Independent Test**: play rounds at a fake brand that answers `error` to one credit; read the
unacknowledged list; abandon it; export the day.

**Acceptance Scenarios**:

- added `features/games/operators.feature`: an operator reads a brand's rounds and the callbacks of one round with their attempts and answers
- added `features/games/operators.feature`: a callback the brand has not acknowledged is listed with its attempts and next retry
- added `features/games/operators.feature`: an operator abandons an unacknowledged callback with a reason and no further attempt is made
- added `features/games/operators.feature`: finance exports a brand's settled rounds for a day
- added `features/games/operators.feature`: every operator act on the provider is a record on the shared audit
- added `features/games/operators.feature`: records are swept after the installation's retention for the provider

---

### User Story 7 - The KYC service knows the Anjouan licence (Priority: P3)

An operator applies the `Anjouan` licence profile to a brand (spec 009) and the policy's fields are
set as the profile says, marked custom once any field is changed by hand, as for UKGC, MGA and
Curaçao.

**Why this priority**: the brands this provider serves run under Anjouan, and today the KYC service
knows only three regulators. It is a small amendment and rides here so the first brand on the
provider is not a Curaçao brand by default.

**Independent Test**: apply the profile; read the policy.

**Acceptance Scenarios**:

- added `features/kyc/policy.feature`: applying the Anjouan profile sets its gates, window, thresholds and categories

---

### Edge Cases

- A brand's game-provider is unreachable when a game asks for a debit: the game is answered
  `pending`, the debit is retried on the schedule under the same transaction id, and the game
  treats the round as not open until the brand answers.
- The brand answers a retried debit differently from the first attempt (the brand's workflow
  answers from its stored outcome, spec 003 FR-011, so it should not): the first answer the edge
  received stands, the difference is recorded on the round, and the operator sees it.
- A brand answers `error` to every attempt of a credit until the schedule is exhausted: the credit
  is left unacknowledged for the operator, the round stays open at the provider, and the game is
  told the settlement is not acknowledged.
- A currency with other than two decimals (Chilean peso, none; some with three): the wire carries
  decimal strings and the brand converts by spec 001 FR-026; the edge and a game hold minor units and
  render by the currency's exponent, never assuming two.
- A launch names a code two games registered: the second registration is refused naming the first;
  codes are unique across the provider.
- A game service is down when a launch for its code arrives: the launch is answered as the game
  unavailable and nothing is stored; a check, debit or credit for a session of that game waits for
  the game as the brand's answer waits for the brand.
- A game asks for a credit on a round it did not open, or a cancel of a transaction it did not
  send: refused, nothing sent.
- A player's two launches of the same game: two sessions, two launch tickets; a round belongs to
  the session that opened it.
- The provider's key is switched while a brand still holds the old one: every callback at that
  brand fails signature until the operator fixes the configuration; the switch is the operator's
  act and the unacknowledged list shows the failures at once.
- A brand launches in fun mode a game that does not support demo play: refused as `unknown_game`;
  the list of games says per game whether demo play is supported, so the brand's catalogue does not
  offer it.
- A game service deregisters a game while sessions of it are open: no new launch is answered for
  the code; open sessions and their rounds are served until settled.
- A test brand is marked real money at the brand (its manifest's `realMoney`): the provider
  refuses to serve it while its entry names the key document instead of holding a key.
- The brand's launch carries a `player_profile` with a birth date and a registration country: the
  edge reads the country for the session and keeps nothing else of the profile.

## Requirements *(mandatory)*

### Functional Requirements

**The project and its services**

- **FR-001**: `games` MUST be a shared project of the installation (constitution III), beside
  `payments`, `programme`, `reasoner` and `tools`, deployed from `shared/games/manifest.json` by
  the modules tool, holding the `provider` service and one service per game; it MUST hold no
  player data beyond the player id, the currency and the country code of a session, and it MUST be
  deployable on an installation that takes real money (unlike `tools`).
- **FR-002**: The provider MUST reach a brand only as an outside caller through the installation's
  gateway, admitted by the brand's signature verification (spec 003 FR-003), with no grant under
  ankka 040 and no platform identity; a brand MUST reach the provider's operator API at the
  provider's exposed address as it reaches ST8's.
- **FR-003**: The provider MUST speak ST8's protocol as spec 020's ST8 adapter speaks it and
  nothing else: the operator API (launch, games, bonus create, get and cancel, spin values), the
  game page handoff and `check`, and the callbacks `check`, `balance`, `player_profile`, `debit`,
  `credit` and `cancel` with the bodies of 020 FR-009, the signature of 020 FR-010, the answers of
  020 FR-012, a loss as a credit of "0.00" and a free round as a zero debit of kind `free_debit`
  followed by a credit (020 FR-013). `buyin` and `payout` MUST NOT be sent: no game of this
  provider plays outside a round.
- **FR-004**: The provider MUST verify a brand's operator API calls (launch, games, bonus) against
  the operator public key the brand table holds for that brand, refusing a call whose signature
  does not verify, whose brand the table does not hold, or whose `site` is not that brand's; this is
  tighter than `fake-st8`, which verifies nothing (*Changes*).

**The core as a library**

- **FR-005**: The simulator's core (sessions, rounds, callbacks, the scheduler and retry schedule,
  the key pair and key document, the brand table and the sender) and its ST8 protocol adapter MUST
  become a library in the root build on which `provider` and `game-provider-simulator` both depend
  (amending spec 020); the scenarios, instruction routes, load profile, run export and seeded
  generator MUST stay in the simulator and MUST NOT be on the provider's classpath, so that no
  scripted misbehaviour is deployable in the product.
- **FR-006**: A brand talking to the provider MUST see what it sees talking to the simulator in its
  default scenario: the simulator's operator API recordings (`tests/port/simulator/OperatorApi_*`)
  MUST replay against the provider with the test kit's game registered, and spec 003's brand-side
  parity suites MUST pass with the provider in the simulator's place.

**The game contract**

- **FR-007**: A game service MUST register its games with the provider at start and on change: for
  each, a code unique across the provider, a name, a category, a developer (`eitheror`), the
  catalogue fields spec 003 FR-021 lists (bonus types, features, themes, restricted and prohibited
  territories, deprecation date), whether it supports demo play, and the address of its game page;
  a code another game holds MUST be refused naming it. A game MUST stay registered until its game
  service deregisters it or an operator removes it; nothing else removes a game, and a game service
  that is down leaves its games listed.
- **FR-008**: Launch MUST store the session (brand, player id, currency, game code, demo or not,
  the brand's language, the country from the profile when given) and answer the registered game's
  page address carrying the launch ticket; a code no game registered MUST be answered
  `unknown_game` as 020 FR-007 answers it; a game the brand may not run MUST be answered the same; a
  game service that does not answer MUST be answered as unavailable and nothing MUST be stored; a
  launch in fun mode of a game that does not support demo play MUST be refused the same way, and
  the list of games MUST say per game whether it supports demo play (the provider's `fun_mode`
  field).
- **FR-009**: The provider MUST serve a game service a check of a launch ticket: it sends the signed
  `check` to the brand once and answers the session (brand, player id, currency, country, demo,
  language, the session ticket) on "ok" and the brand's refusal otherwise; a game MUST only be
  answered sessions of its own codes.
- **FR-010**: A game MUST open a round by asking the provider for a debit on a session (amount in
  minor units, the round id the game chooses, the kind `debit` or `free_debit`): the provider
  assigns a new transaction id, converts the amount to the wire's decimal string by the currency's
  exponent (spec 001 FR-026), sends the signed debit, and answers the brand's status and balance
  within the brand's wait; past the wait it answers `pending`, retries under the same transaction id
  on the schedule, and the game MUST be able to read the outcome when it arrives. A round on a demo
  session MUST send nothing and MUST be answered as a demo round.
- **FR-011**: A game MUST settle a round by asking for a credit (an amount, zero for a loss): the
  provider accepts it at once, sends it signed, retries on `error` or no answer on the schedule, and
  the game MUST be able to read whether the brand acknowledged it; a credit on a round the game did
  not open, or a second settlement of a settled round, MUST be refused.
- **FR-012**: A game MUST cancel a debit by asking for a cancel naming the debit's transaction id;
  the provider sends it with a new cancel id and the round MUST be closed as cancelled on the
  brand's acknowledgement; a cancel of a transaction the game did not send MUST be refused.
- **FR-013**: The retry schedule MUST be spec 020's shipped default (every 30 seconds for ten
  minutes, then every ten minutes, capped at eight hours) as one setting of the provider, not per
  scenario; a callback whose schedule is exhausted MUST be left unacknowledged (FR-021).
- **FR-014**: The provider MUST hand a bonus (free rounds) for a registered game to that game and
  answer the game's status in the words of 020 FR-007 (`active`, `processing`, `rejected`,
  `failed`, `cancelled`, `unknown_game`); a game MUST hold its instances and decide its own claim
  deadline, which MUST NOT be earlier than the brand asked; spin values MUST be the game's.

**Game clients**

- **FR-015**: A game's page MUST be served by its game service from a client under
  `services/<game>/client/` built into the service's image, in TypeScript (constitution VI: browser
  code, not the money path); the page MUST ask the game service to check the launch ticket on load
  (FR-009) and MUST show the session-expired page on a refusal, as 020 FR-008 does; the page MUST
  render in the session's language (Spanish or Portuguese at least) and amounts in the session's
  currency by its exponent.
- **FR-016**: The provider's own game page (the simulator's, 020 FR-008) MUST NOT be served by the
  provider: the page is the game's.

**Brands**

- **FR-017**: The brand table MUST be written and read by an operator over operator routes, one
  entry per brand: slug, the address of its game-provider, its site id, its operator public key,
  its language, whether it is a test brand, the games it may run (every registered game by default),
  and, for a test brand only, whether it names the key document; a change MUST apply without a
  redeploy; a brand absent from the table MUST be refused at launch. The entry's games are the
  brand's entitlement to house games for now (Clarifications); a vendor entitlement that writes the
  entry is a later spec.
- **FR-018**: A session, a round, a callback and a game's state MUST belong to one brand; no read,
  page or callback MUST answer one brand's data to another; a game service MUST key its state by
  brand and MUST be given the brand with every session.

**Keys**

- **FR-019**: The provider MUST sign with one P-256 key pair held in the secret store (ankka 038),
  never in configuration, and MUST publish a key document naming its current key and, during a
  rotation, its next, each with a key id (020 FR-011).
- **FR-020**: Rotation MUST be three operator acts: start (publish the next key; nothing changes in
  signing), switch (sign every later callback with the next key), withdraw (remove the old key from
  the document); no timer MUST switch a key; a real-money brand MUST hold the key itself and the
  provider MUST refuse to serve a brand marked real money whose entry names the document.

**Records, operators and audit**

- **FR-021**: Every callback sent MUST be recorded with the route, the body, the key id, the time,
  the attempt, the answer's status and balance and the time taken (020 FR-020); an operator MUST
  read a brand's sessions, rounds and one round's callbacks, MUST list the unacknowledged callbacks
  with their attempts and next retry, and MUST be able to abandon one with a reason, after which no
  attempt is made; finance MUST export a brand's settled rounds for a calendar day.
- **FR-022**: Every operator act (a brand entry written, a rotation act, an abandonment) MUST be a
  record on `platform.audit` carrying the actor from the staff token, as the shared `payments`
  records its own.
- **FR-023**: Records MUST be kept for the installation's retention for the provider (an
  installation setting, shipping default 90 days; the brand's rounds remain the books of record) and
  swept after it; the `player_profile` answer MUST be recorded with its personal
  fields redacted (020 FR-023), and the profile a launch carries MUST NOT be kept beyond the
  session's country.
- **FR-024**: The provider's routes MUST fall in spec 001's route classes and no other: the
  operator API, the key document and the game contract's launch handoff are public (the operator
  API admitted by the brand's signature, FR-004; the contract's routes admitted in-project to the
  game services, service class); the brand table, reads, abandon and export are operator routes
  admitted through the gateway with a staff token of the installation holding the `games`
  permission (writes, rotation, abandon) or, for the reads of one brand, that brand's operator role; a game's page is public
  and admitted by its launch ticket.
- **FR-025**: Metrics MUST count callbacks sent per brand and kind, callbacks unacknowledged per
  brand, and launches per brand and game.

**Amendments to neighbouring specs**

- **FR-026**: Spec 003 is amended: a provider configuration (003 FR-026) MUST carry a protocol
  (`st8`, the only one, the default), so the ST8 adapter is instantiated under any provider name; the
  callbacks of a provider other than `st8` MUST be served under its own prefix and never at the root
  (003 FR-002, already stated); the not-configured refusal's word MUST name the provider (003 FR-007,
  already stated).
- **FR-027**: Spec 009 is amended: the licence profiles MUST include `Anjouan`, whose values are
  Curaçao's defaults under its own name (no deposit threshold, no KYC currency, the same gates,
  window, categories and document retention), marked custom once any field is changed by hand, and
  confirmed by the compliance officer before a brand under it takes real money, as the Curaçao
  default is. *Decided 2026-11-07 (Trevor): copy Curaçao's defaults.*
- **FR-028**: Spec 001's shared-manifest schema MUST gain the project `games` and the services
  `provider` and the games (027 adds `pools`); the modules tool MUST apply `shared/games/manifest.json`
  on a real-money installation.

### Key Entities

- **Provider**: the edge: one deployment, one key pair, one brand table, many games, many brands.
- **Game**: a registered first-party game: code, name, category, developer, catalogue fields,
  demo support, page address, the service that owns it.
- **Game service**: a service of the `games` project holding one or more games' rules, state and
  client; a party to the game contract.
- **Brand entry**: a row of the brand table: slug, address, site, operator public key, language,
  test flag, games it may run, key document flag.
- **Session**: one launch: brand, player id, currency, game code, demo, language, country, launch
  ticket, session ticket.
- **Round**: one round in a session: round id, state (open, pending, won, lost, cancelled), the
  transaction ids sent, the acknowledgement of each.
- **Callback**: one request to a brand with its attempts and answers.
- **Key document**: the current and next key with their ids.
- **Free-round instance**: a bonus's allocation to a player at one game: status, count remaining,
  claim deadline.
- **Licence profile (Anjouan)**: the KYC policy defaults applied by name.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Spec 003's brand-side parity suites pass with the provider in the simulator's place,
  and the simulator's operator API recordings replay against the provider, with zero differences.
- **SC-002**: A new game reaches a brand with no change to the brand beyond its next nightly
  refresh: zero brand-side writes per game added.
- **SC-003**: A launch is answered and the game page's check completes within 2 seconds at the
  99th percentile against a local brand.
- **SC-004**: A brand that stops answering for 15 minutes loses no round: every debit and credit
  sent in that window is acknowledged after it answers, with the balance moved once per callback.
- **SC-005**: A key rotation across two brands completes with zero callbacks failing signature at a
  brand that holds the new key before the switch.
- **SC-006**: Two brands playing the same game for 1,000 rounds each share no session, round or
  record in any read.
- **SC-007**: The simulator's own suites pass unchanged after the core moves to the library.

## Changes from the original

The original (`eitheror-platform`) has no first-party game provider, no game service and no game
client; everything here is new under constitution I. What is not new is the protocol: the edge's
behaviour on the wire is established by the simulator's recordings of `fake-st8` and by spec 003's
parity suites, and the edge is held to them (FR-006).

Changes from `fake-st8` and the simulator, beyond spec 020's own list:

- Tightened: the brand's operator API calls are verified against the brand's operator public key
  (FR-004); `fake-st8` and the simulator verify nothing.
- Dropped: scenarios, instruction routes, the load profile, the run export, the seeded generator,
  the provider's own game page, `buyin` and `payout`.
- Added: the game contract (FR-007 to FR-014), game services and their clients, the brand's games
  entitlement and language, the three-act rotation, the audit records, the day export.
- Changed: the retry schedule is one setting of the provider, not per scenario.

## Recordings

No recording of the original drives this spec. The edge's parity suite replays spec 020's
`tests/port/simulator/OperatorApi_Launch.json`, `OperatorApi_Games.json`,
`OperatorApi_BonusCreateUnknownGame.json` and `OperatorApi_BonusLifecycle.json` against the
provider with the test kit's game registered; and spec 003's parity suites (`tests/port/game-provider/`)
run against a brand configured with the provider under the name `eitheror`, protocol `st8`, with
the test kit's game playing the rounds the recordings' instructions name.

## Assumptions

- The provider's name at a brand is `eitheror`, its developer in the catalogue `eitheror`; a game's
  id at a brand is `eitheror/<code>`.
- The brand's launch carries no language (spec 020's wire: `game_code`, `currency`, `token`,
  `player`, `fun_mode`, `site`, `player_profile`), so a session's language is the brand entry's;
  a game page MAY take a `lang` query parameter the brand's site appends, overriding it. Carrying the
  player's language in the launch is a later amendment to spec 003 and ST8's wire, not this spec's.
- Spanish and Portuguese are the first languages; a game ships at least those two; the brand entry
  names one of them as its default.
- The game contract is in-project: a game service calls the provider over HTTP within `games`
  (service class routes), and the provider calls a game service's registration and bonus routes the
  same way. Nothing of the contract is reachable from outside the project.
- A game's state (an entry, a coupon, an instance) is the game service's own, event sourced in its
  own database, keyed by brand (constitution VI: every service owns its database). The provider
  holds sessions, rounds and callbacks only.
- Revenue share between the provider and a brand is not computed here: eitheror operates both
  sides today. The day export (FR-021) is what a later settlement would read.
- Amounts: minor units as `Long` inside the project (constitution II); decimal strings on the wire
  by the currency's exponent (spec 001 FR-026); two places for every currency the original ran, and
  the exponent's places otherwise.
- The provider is Scala, as the simulator is; the clients are TypeScript, built in the service's
  image (FR-015); a game's rules are Scala (the money path).
- Regulatory scope: the brands run under an Anjouan licence into South American markets. UKGC and
  MGA supplier licensing, and RNG and RTP certification, are out of scope; the first games have no
  random element, and a note in this spec names certification as a precondition for any game that
  does. The brain has no mention of Anjouan or South America and gains one with this spec.
- Cross-brand: a game's coupon or pool is per brand unless a later decision says otherwise
  (decision 0055's regulatory question about pooling across licensees). The provider makes a shared
  coupon technically easy; this spec does not allow it.
- The Anjouan profile's values are confirmed by the compliance officer before a brand under it takes
  real money, as the Curaçao default is (spec 009).

## Dependencies

- Spec 020 (the simulator, built: its core becomes the library), spec 003 (the brand's adapter,
  built: the protocol on configuration), spec 001 (shared projects, the manifest schema, route
  classes, FR-026 conversion), spec 021 (the modules tool applying shared manifests), spec 011 (free
  rounds), spec 009 (licence profiles), spec 018 (the shared audit).
- ankka 038 (the secret store, for the key pair), ankka's gateway for the public routes.
- Spec 027 (the pools game) is the first implementation of the game contract and depends on this.

## Out of scope

- Any game: spec 027 and its successors.
- A protocol of our own, or a second protocol adapter at the brand.
- Becoming a content supplier to ST8 or any aggregator; serving brands not on this installation.
- Revenue share, invoicing, or a provider report beyond the day export.
- Carrying the player's language in the launch (an amendment to spec 003 and the wire).
- RNG and RTP certification; UKGC and MGA supplier licensing.
- Scenarios, load profiles and run exports: the simulator keeps them.
