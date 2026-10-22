# Feature Specification: Core and Modules — A Brand Runs What Its Entitlement Names

**Feature Branch**: `021-core-and-modules`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "Amends spec 001 to the module model of decision 0055: a brand
project holds a core every brand runs and the services of the modules its entitlement names, no
more. Modules are described by manifests in `modules/<module>/module.json`, released by one tag on
the vendor's channels (ankka-vendor spec 004), and applied, upgraded and removed in a brand by the
vendor's agent through the brand's own control plane. The brand manifest becomes the brand's
values. The brands tool becomes the modules tool. Grants are declared per module and resolved at
apply. Operators see their brand's modules in the backoffice; eitheror staff enable them from
ankka-vendor and never see a player."

## Context

Spec 001 lays a brand out as twelve fixed services (FR-002), each brand pinning each service's
version in one manifest under `brands/<slug>/` (FR-007, FR-008), with exactly five grants made and
two received (FR-016), applied by `tools/brands`. That was right for one product. Decision 0055
makes the repository a core and product modules: a brand may run the casino, the prediction
market, both, or something not yet written, and eitheror decides which from one place
(ankka-vendor) rather than by editing a brand's manifest. This spec restates the parts of 001 that
change and leaves the rest standing: the topic contract, route classes, realms, personal data,
money rules, recordings, the installation's fitness for real money.

What becomes a module, and what stays core, follows one question: does every brand need it
whatever it sells? Wallet, players, kyc, responsible-gambling and payouts do, and so do
engagement, reporting and attribution, which know nothing of a game or a market. Game-provider,
rewards and partner-api are the casino's. The support agent is a module of its own because spec
017 already makes it off by default. The prediction market is spec 023; the liquidity pool, a
shared module, is spec 024. Rewards' home is the one placement argued below.

The vendor's agent is ankka-vendor's service, deployed into every brand project at provisioning
with a machine identity scoped to that project (ankka 040); this repository does not build it. It
builds the manifests the agent reads, the values a brand supplies, the tool that validates and
renders them, and the backoffice view of what is enabled.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A brand runs its core and the modules it is entitled to, no more (Priority: P1)

A brand is provisioned by ankka-vendor: one project, the agent, an entitlement to `core`. The
agent applies the core: `wallet`, `players`, `kyc`, `responsible-gambling`, `payouts`,
`engagement`, `reporting`, `attribution`, in the order the core's manifest gives, with the grants
the core makes to and receives from the shared `payments`, `programme` and `reasoner` projects.
Players can register, deposit and withdraw; nothing can be played or traded. When eitheror
enables `casino`, the agent adds `game-provider`, `rewards` and, if named, `partner-api`, and the
grants between them and the core; now the brand is a casino. A brand entitled to `predictions` and
not `casino` has markets and no games.

**Why this priority**: The whole point: what a brand runs is a choice made per brand, after it
exists.

**Independent Test**: Apply the core manifest to a project with the modules tool in `--local`
mode; register and deposit; confirm no game route exists; apply `casino`; launch a game.

**Acceptance Scenarios**:

- added `features/brands/modules.feature`: a brand runs the core alone until a module is enabled
- added `features/brands/modules.feature`: enabling a module adds its services and grants to the brand and nothing to any other brand
- added `features/brands/modules.feature`: a brand entitled to the prediction market and not the casino serves markets and no games
- added `features/brands/modules.feature`: disabling a module removes its services and keeps its data for the module's retention

---

### User Story 2 - A module is described by one manifest and released by one tag (Priority: P1)

An engineer writes `modules/casino/module.json`: the module's services with their descriptors,
the grants and topics it declares, its dependency on `core` at a version range, the configuration
keys a brand must supply by name, its data retention after disable, and the condition on ending
it (none for the casino). `just modules validate` checks it against the vendor's schema and this
repository's contract: every service named is one the contract lists, every topic is the
contract's, every grant names a route class the contract defines. A `v*` tag builds every module's
images and publishes every manifest at the tag's version to ankka-vendor; the channel does not
move until someone moves it.

**Why this priority**: The manifest is the contract with the vendor's product; nothing deploys
without it.

**Independent Test**: Validate the four manifests; break one (a service the contract does not
list, a literal secret, a dependency on a module that does not exist) and confirm the refusal
names the entry.

**Acceptance Scenarios**:

- added `features/brands/modules.feature`: a module manifest naming a service, a topic or a grant the contract does not list is refused naming the entry
- added `features/brands/modules.feature`: a module manifest carrying a value where a key is expected is refused naming the key
- added `features/brands/modules.feature`: one tag publishes every module at the tag's version and moves no channel

---

### User Story 3 - A brand's values are the only thing a brand supplies (Priority: P2)

`brands/<slug>/values.json` replaces the brand manifest: the realm's settings, whether the brand
takes real money, the names of the secrets the brand's services read, the hostnames of its sites,
and a value for every configuration key the enabled modules name. It holds no version, no service
list and no grant: those are the modules' and the entitlement's. `just modules render <slug>`
shows what the agent would apply from the brand's values and its entitlements, for a person to
read before anything runs. A real-money brand is still refused on an installation that fails spec
001 FR-040, by the agent now, before the core is applied.

**Why this priority**: Separating what a brand is from what a brand runs is what lets eitheror
change the second without touching the first.

**Independent Test**: Render goldvibe's values against core and casino; compare with the
descriptors spec 001's tool produced; the services, env and grants are the same, the versions come
from the channel.

**Acceptance Scenarios**:

- added `features/brands/modules.feature`: a brand's values carry no version, no service and no grant
- added `features/brands/modules.feature`: a brand whose values lack a key an enabled module names runs nothing of that module until the key is set
- added `features/brands/modules.feature`: a real-money brand is refused by the agent on an installation that is not fit for it

---

### User Story 4 - Operators see their modules; eitheror enables them (Priority: P2)

A brand operator with the `brand_<slug>` role opens the backoffice's modules page and sees what
is enabled, at which version, on which channel, with each service's state, read from the agent's
module entity in their own project; they may ask eitheror for a module and may not enable one. A
member of eitheror staff signs in to ankka-vendor's console, which shows every brand's modules
from the mirrored entities and lets them enable, disable, pin or move a brand; the console never
shows a player, a wallet or a value. The `platform_admin` role of spec 001 stays what it was on
the installation and gains nothing here: enabling is the vendor's act, not the installation's.

**Why this priority**: The backoffice must tell an operator what their brand is, and the
separation of who may enable from who may see players is a regulatory line.

**Independent Test**: As `brand_goldvibe`, read the modules page and attempt an enable: shown and
refused. As eitheror staff in the vendor console, enable casino for goldvibe and confirm the
operator's page changes and no player data was readable from the console.

**Acceptance Scenarios**:

- added `features/brands/modules.feature`: a brand operator sees their brand's modules and cannot enable one
- added `features/brands/modules.feature`: eitheror staff enable a module from the vendor's console and see no player of any brand
- added `features/brands/modules.feature`: a module's every change is a fact on the brand's platform topic

---

### Edge Cases

- A module whose dependency is disabled: disabling `core` is refused while any module is enabled;
  disabling a product module another module depends on is refused naming the dependent.
- `partner-api` and `support-agent`: `partner-api` is a service of `casino` the manifest marks
  optional, named in a brand's values when a partner is contracted (spec 016); `support-agent` is
  its own module, `support`, so that spec 017 FR-001's off-by-default is an entitlement, not a
  switch.
- A brand migrated from the original (spec 019): its values are written from the original's
  configuration and its entitlement names `core` and `casino` from the first apply; nothing in
  the migration changes.
- The `tools` shared project and the simulator (spec 020): not a module; applied by the modules
  tool in local mode, never to a real-money installation, as before.
- Rewards' placement: rewards are bonuses on bets and are the casino's; a module that wants
  rewards of its own declares its own. [NEEDS CLARIFICATION: whether `engagement` and `reporting`
  are core, as assumed, or should each be a module so a brand can be billed for CRM separately]

## Requirements *(mandatory)*

### Functional Requirements

**Layout (replaces spec 001 FR-002, FR-007, FR-008)**

- **FR-001**: A brand project MUST hold the services of `core` and of the modules the brand's
  entitlements name, and no others. `core` is `wallet`, `players`, `kyc`, `responsible-gambling`,
  `payouts`, `engagement`, `reporting`, `attribution`, and the vendor's agent. `casino` is
  `game-provider`, `rewards` and optionally `partner-api`. `support` is `support-agent`.
  `predictions` is spec 023's; `pool` is spec 024's and shared.
- **FR-002**: Every module MUST be described by one manifest at `modules/<module>/module.json` in
  ankka-vendor's manifest format: services with descriptors, grants and topics declared by route
  class and topic name, dependencies with version ranges, configuration keys by name and type,
  retention after disable, and any condition on ending; a product module MUST depend on `core`
  and on no other product module.
- **FR-003**: A brand MUST supply its values at `brands/<slug>/values.json`: realm settings, real
  money, secret names, hostnames, and a value for every key its enabled modules name; a value
  file MUST hold no version, service, grant or secret value.
- **FR-004**: One `v*` tag MUST publish every module's images and manifests at the tag's version
  to ankka-vendor and MUST move no channel; which release a brand runs is its entitlement's channel
  or pin at the vendor. Spec 001 FR-008's rule, a release reaches one brand first, holds through
  channels: a channel a single brand follows is how.
- **FR-005**: `tools/brands` MUST become `tools/modules`: `validate` a manifest or a values file
  against the vendor's schema and this repository's contract; `render <slug>` what the agent would
  apply; `apply` in local mode only, to an installation with no vendor, for development, parity
  and the simulator; `status <slug>` from the agent's module entity. It MUST be reachable as
  `ankka casino` under ankka 056.

**Grants (replaces spec 001 FR-016's fixed list)**

- **FR-006**: Grants MUST be declared in the manifest of the module that makes or receives them,
  by route class and the principal's module and service, and resolved to projects at apply:
  `core` makes to `payments`/`merchant` the wallet's deposit and payment-outcome routes, to
  `payments`/`psp-gateway` the payouts outcome route, to `programme`/`affiliate-programme` the
  `affiliates.attribution` topic, to `reasoner`/`audit` the `platform.audit` topic, and receives
  `psp-gateway`'s payout route for `payouts`; a product module declares what it makes to and
  receives from `core` and from shared instances it may be bound to; no grant MUST exist between
  two brands.
- **FR-007**: A grant within one brand project (game-provider to wallet, exchange to wallet) is
  not a grant and MUST NOT be declared; spec 001's choice of one project per brand stands.

**Lifecycle in a brand**

- **FR-008**: The agent MUST apply `core` before any module, a module's dependencies before the
  module, and a module's services in its manifest's order; MUST remove a module in reverse and
  keep its data for its retention; and MUST refuse to disable `core` while a module is enabled or
  a module while another depends on it, naming the dependent.
- **FR-009**: A brand whose values lack a key an enabled module names MUST run nothing of that
  module until the key is set, and the module entity MUST name the key.
- **FR-010**: A brand marked for real money MUST be refused by the agent, before `core` is
  applied, on an installation that fails spec 001 FR-040; the agent reads the same conditions
  the brands tool read.
- **FR-011**: Every change to a brand's module entity MUST be a fact on the brand's
  `platform.modules` topic, a new topic of the contract: module, change, version, channel, actor
  as the vendor's principal or the agent, time; `reporting` and the shared `audit` read it as they
  read `platform.audit`.

**Who sees and who enables (extends spec 001 FR-020)**

- **FR-012**: The backoffice MUST show a brand's modules, versions, channels and service states to
  the `brand_<slug>` role from the brand's own module entity, and MUST offer a request to the
  vendor and no enable.
- **FR-013**: Enabling, disabling, pinning and moving a brand's modules MUST be writes by eitheror
  staff through ankka-vendor, admitted by the agent as the vendor's principal; no route on the
  installation MUST let any role do them.
- **FR-014**: eitheror staff's view in ankka-vendor MUST hold no player, wallet, account or
  configured value of any brand; the module entity it mirrors carries names, versions, states and
  times only.

### Key Entities

- **Module**: a named set of services, grants, topics, dependencies, keys and retention, described
  by one manifest; `core`, `casino`, `support`, `predictions`, `pool`.
- **Values**: what one brand supplies: realm, real money, secret names, hostnames, configured
  values; `brands/<slug>/values.json`.
- **Entitlement**: ankka-vendor's record that a brand may run a module on a channel or pin; read,
  never written, here.
- **Module entity**: the agent's record in the brand project of what is enabled, running and
  failed; the source of the backoffice page and the `platform.modules` facts.
- **Modules tool**: `tools/modules`, `ankka casino`: validate, render, local apply, status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The four manifests (`core`, `casino`, `support`, `predictions`) validate, and
  rendering goldvibe's values against `core` and `casino` yields the same services, env and grants
  spec 001's tool produced for goldvibe, with versions from the channel.
- **SC-002**: A brand with `core` alone answers every scenario of `features/players/`,
  `features/wallet/deposits.feature` and `features/payouts/` and serves no game route; enabling
  `casino` makes every `features/game-provider/` scenario pass without touching the core's data.
- **SC-003**: No role on the installation can enable a module: every route the backoffice serves
  for modules is read-only under test.
- **SC-004**: One tag publishes every module within the release job's run and moves no channel.

## Assumptions

- ankka-vendor spec 004 defines the manifest format, the agent, entitlements, channels and the
  console; this spec consumes them and asks for nothing beyond them.
- The agent runs in each brand project with a machine identity that may apply and delete services
  in that project only; its image is ankka-vendor's.
- The shared projects `payments`, `programme` and `reasoner` stay as spec 001 FR-003 lays them
  out, applied by the modules tool in local mode or by ankka-vendor as shared instances later.
- Spec 001's scenarios in `features/brands/manifest.feature` stand as written for the local mode
  and are superseded for a vendor-run installation by this spec's; they are not renamed so that
  spec 001's references hold.

## Dependencies

- ankka-vendor spec 004; ankka 040, 051 with ankka-replication, 056.
- Spec 001 for everything this does not restate; spec 022 for the wallet primitives
  `predictions` and `pool` need; spec 023 for `predictions`.

## Out of scope

- The spoke template and eitheror's own cluster values: eitheror's deployments repository.
- The pool (spec 024) and the prediction market's services (spec 023).
- Billing terms: ankka-vendor's.
