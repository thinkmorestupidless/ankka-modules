# Feature Specification: An Installation's Values Live with the Installation

**Feature Branch**: `029-installation-values`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "What differs between installations leaves this repository. A real
installation's casino overlay and its `settings.env`, and each brand's values for that
installation, are read from the installation's own directory (in practice
`ankka-deployments/clusters/<installation>/casino/` and `clusters/<installation>/tenants/<slug>/`).
This repository keeps the components, `dev-kind`, and goldvibe as the local example. Beside a
tenant's values sits a version lock: which modules at which versions it runs, applied by the
modules tool where no vendor serves the installation. An installation's facts are stated once:
`settings.env` does not repeat what the platform's overlay or `GET /installation` already says.
Amends specs 001 and 021."

## Context

Three kinds of thing decide what runs for a brand, and today all three are in this repository.

**What a module is** — its services, topics, grants, keys and requirements — is
`modules/<module>/module.json`, released by one tag (spec 021 FR-002, FR-004). That belongs here
and stays.

**What an installation is** is `installation/overlays/<name>/`: a kustomize overlay over
`installation/base` (the casino's Keycloak) and a `settings.env` the modules tool reads for its
readiness check and for `setting:<KEY>` in a shared manifest (spec 001 FR-034 to FR-039, spec 021
research R6). There are two: `dev-kind`, the local cluster, and `gke`, which names eitheror's cloud
account, addresses and payment providers' production addresses. `gke`'s own header says that
"every setting below is what ankka must be installed with; the platform's own overlay carries the
same values": nine of its lines (backups, erasure, the broker, topic retention) restate facts the
platform's overlay sets and the control plane reports at `GET /installation`, and six more (cloud
provider, account, location, secret backend, object store, telemetry) restate the platform's
overlay and are read by nothing here.

**What a brand is** is `brands/<slug>/values.json` (spec 021 FR-003): its name, whether it takes
real money, where its realms are, its hostnames, the names of its secrets and a value for each
non-secret key. One file per brand; but `keycloak.issuerBase`, the hostnames and several values
are the installation's (goldvibe's issuer is the local cluster's address), so one file cannot
describe one brand on two installations, and a real brand's file would put an installation's
addresses in a repository that every licensee's developers read.

**Which version a brand runs** is nowhere in a repository. With a vendor it is the vendor's:
an entitlement following a channel (spec 021 FR-004, FR-013). Without one, the modules tool plays
the agent and takes the versions from `--version` on the command line, or none; what a brand ran
yesterday is known only to the control plane. Spec 001 FR-008's rule, that a release reaches one
brand first and another moves only when its own manifest changes, has no file to live in.

The installations themselves — the cloud project, the cluster, the platform's overlay, the pinned
ankka release — are in another repository, `ankka-deployments`, one directory per cluster. This
spec puts each fact beside the thing it is a fact about: a module in this repository; an
installation's settings and a tenant's values and versions in the installation's directory; a
platform fact where the platform states it and nowhere else. The tool finds the installation's
directory by one new argument.

Where a vendor serves an installation, nothing here changes how versions move: the entitlement
and the channel stay the statement, and the tool refuses to apply as it does today.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A real brand is applied from the installation's directory (Priority: P1)

An operator checks out this repository at a release and the installation's directory beside it.
The directory holds `casino/settings.env` and, for the brand, `tenants/<slug>/values.json` and
`tenants/<slug>/modules.lock`. They run the modules tool naming the directory: it validates the
values against the modules the lock names, checks the installation is fit if the brand takes real
money, renders what would run, and applies it through the installation's control plane. This
repository holds nothing of that brand or that installation, and the same brand's slug may appear
under another installation's directory with other values.

**Why this priority**: the first real brand cannot be applied without committing an
installation's addresses here, or without a hand-typed list of versions, until this exists.

**Independent Test**: move goldvibe's values and a lock into a directory outside the checkout
shaped like an installation's, with `dev-kind`'s settings; `validate`, `render`, `apply` and
`status` naming the directory give what they give today for the files in the repository at the
same versions.

**Acceptance Scenarios**:

- added `features/brands/installations.feature`: a brand is applied from the values and the version lock kept in its installation's directory
- added `features/brands/installations.feature`: a brand kept in an installation's directory is unknown to the modules tool when the installation's directory is not named
- added `features/brands/installations.feature`: one brand has different values on two installations and each installation applies its own
- added `features/brands/installations.feature`: a local installation and the example brand are still applied from beside the modules with no installation's directory named

---

### User Story 2 - A version lock says what a tenant runs, and a reviewed change moves it (Priority: P1)

Beside a brand's values is its version lock: each module the brand runs and the released version
it runs it at. Applying the brand brings its project to the lock: `core` first, each module after
the modules it depends on. To upgrade one brand, a person changes that brand's lock in a reviewed
change and applies it; no other brand's lock changed, so no other brand moved. `status` compares
what the project runs with the lock and says where they differ. A lock whose versions break a
module's dependency range is refused before anything is called. The shared projects have a lock
of their own, the same way.

**Why this priority**: it is the record of what runs, and the one-brand-first rule, for every
installation eitheror operates without a vendor.

**Independent Test**: apply a brand from a lock; change one module's version in the lock; `status`
reports the difference and exits refused; `apply` moves that module and no other; a second brand's
project is untouched; a lock naming `casino` at a version outside its range of `core` is refused
with no call made.

**Acceptance Scenarios**:

- added `features/brands/installations.feature`: applying a brand brings it to the modules and versions its version lock names
- added `features/brands/installations.feature`: changing one brand's version lock moves that brand and leaves another brand untouched
- added `features/brands/installations.feature`: a brand running a version its version lock does not name is reported as differing, naming the module and both versions
- added `features/brands/installations.feature`: a version lock whose versions break a module's dependency range is refused before anything is applied
- added `features/brands/installations.feature`: a module running in a brand and absent from its version lock is reported and is not removed by applying
- added `features/brands/installations.feature`: a version lock on an installation the vendor serves is refused naming the vendor
- added `features/brands/installations.feature`: a shared project is applied at the version its own version lock names

---

### User Story 3 - An installation's facts are stated once (Priority: P2)

Whether an installation is fit for real money is read from what the platform reports about
itself. The installation's `settings.env` holds the casino's own settings — where its Keycloak
is, the payment providers' addresses, its edge protection — and none of the platform's. A settings
file that restates a platform fact is refused naming the line and where that fact is read. A fact
the platform does not report is unmet, as it is today.

**Why this priority**: two statements of one fact drift, and the one that decides whether a brand
takes real money must be the one the cluster actually runs with.

**Independent Test**: validate a real-money brand against an installation whose control plane
reports every required fact and whose settings file holds none: fit. Add `BROKER_REPLICAS=3` to
the settings file: refused naming it. Remove a fact from the control plane's answer: unmet, naming
the fact and the control plane as its source.

**Acceptance Scenarios**:

- added `features/brands/installations.feature`: whether an installation is fit for real money is read from what the installation reports and never from its settings
- added `features/brands/installations.feature`: an installation's settings restating a fact the installation reports are refused naming the entry
- changed `features/brands/manifest.feature`: a brand marked for real money is refused on an installation that is not fit for it

---

### Edge Cases

- The directory names an installation whose `settings.env` says another name: refused naming both.
- The directory and `--installation dev-kind` together: refused; an installation is local (in this
  repository) or it has a directory, never both.
- A tenant directory with values and no lock: `validate` and `render` work when versions are given
  on the command line; `apply` on a directory installation is refused naming the missing lock.
- A lock and `--version`, `--channel` or `--modules` on the same command: refused; the lock is the
  statement.
- A lock naming a module no manifest describes, or a version that is not a release: refused naming
  it, before any call.
- A lock that leaves `core` out, or names a module whose dependency it leaves out: refused.
- A values file under `tenants/<slug>/` whose `brand.slug` is not `<slug>`: refused, as spec 021's
  values contract refuses it under `brands/`.
- The same slug under `brands/` in this repository and under the directory: the directory's is
  read and the repository's is not consulted; `validate --all` in the repository still validates
  the example.
- A secret: never in the directory. `secrets[]` holds names; values go from a file outside every
  repository straight to the project, as today.
- A downgrade: the lock moves to an earlier version and `apply` applies it; whether a module's
  stored data allows it is the module's compatibility promise, not this tool's check.
- The control plane cannot be reached during `validate --real-money`: the facts are not reported,
  so every one is unmet and the brand is refused, naming the control plane.

## Requirements *(mandatory)*

### Functional Requirements

Each requirement names what established it: the tool's present behaviour read from
`tools/modules` on 2026-11-10, a spec it amends, or a decision of 2026-11-10. Nothing here touches
the original.

**Where things are kept**

- **FR-001**: An installation that is not local MUST keep, in a directory of its own outside this
  repository (the *installation's directory*): `casino/`, holding the installation's kustomize
  overlay over this repository's components and its `settings.env`; and `tenants/<name>/` for each
  brand and each shared project it holds, with that tenant's `values.json` (brands) and
  `modules.lock`. *Decision 2026-11-10; in practice `ankka-deployments/clusters/<installation>/`.
  Amends spec 001 FR-007 and FR-039 and spec 021 FR-003, which keep them here.*
- **FR-002**: This repository MUST keep: the components an installation's overlay is built from
  (`installation/base`, and a component for each thing every real installation needs alike, such
  as the casino's Keycloak in production mode and its route, with the hostname supplied by the
  overlay); the local installation `installation/overlays/dev-kind`; one example brand,
  `brands/goldvibe`; and the realm templates `brands/_realm`. It MUST hold no other installation's
  overlay or settings and no other brand's values: `installation/overlays/gke` leaves. *Decision
  2026-11-10.*
- **FR-003**: The components MUST be consumable by an overlay in another repository at a release
  of this one, and a release MUST NOT change a component's meaning without saying so in its notes;
  an installation's overlay names the release it was written against. *Decision 2026-11-10.*
- **FR-004**: No file in this repository MUST name a real installation's cloud account, address,
  hostname or payment provider address, and no file in an installation's directory MUST hold a
  secret's value. *Restates spec 021's values contract for secrets; decision 2026-11-10 for the
  rest.*

**How the tool finds them**

- **FR-005**: The modules tool MUST take the installation's directory as one argument
  (`--installation-dir <path>`, or the environment's `CASINO_INSTALLATION_DIR`). With it, the
  installation's settings are read from `<dir>/casino/settings.env`, a brand's values from
  `<dir>/tenants/<slug>/values.json` and its lock from `<dir>/tenants/<slug>/modules.lock`;
  `--installation <name>`, when also given, MUST equal the `INSTALLATION` the settings name.
  Without it the tool MUST behave as it does today: settings from
  `installation/overlays/<name>/settings.env`, values from `brands/<slug>/values.json`. Module
  manifests, shared manifests, schemas, the contract and the realm templates are always this
  repository's. *Established from the tool: `Installation.load` and `Main.values` resolve both
  under one root (`CASINO_ROOT`, else the working directory); this adds a second root for the two
  and changes no other resolution.*
- **FR-006**: Every command that names a brand (`validate`, `render`, `values push`, `secrets`,
  `hostnames`, `realm`, `apply`, `status`) MUST accept the directory and MUST refuse, naming the
  path it looked at, a brand the directory does not hold. `validate --all` with the directory MUST
  validate every tenant in it against this repository's manifests and schemas, so the
  installation's repository can run it as its own check. *Decision 2026-11-10.*

**The version lock**

- **FR-007**: A tenant's version lock MUST name each module the tenant runs and, for each, one
  released version of this repository; nothing else. A brand's lock MUST name `core`. A shared
  project's lock MUST name the version its services run at. *Decision 2026-11-10; restores spec
  001 FR-008 (each brand pins its own versions), which spec 021 moved to the vendor's channels.*
- **FR-008**: On an installation with a directory and no vendor, `apply <slug>` MUST apply exactly
  the modules the lock names at the versions it names, in the order spec 021 FR-008 gives, and
  MUST refuse before any call: a missing lock; a module no manifest describes; a version that is
  not a release; versions that break a module's dependency range; and `--modules`, `--version` or
  `--channel` given beside a lock. *Amends spec 021 FR-005 (the tool's local mode).*
- **FR-009**: `apply` MUST NOT remove a module or a service that runs in the project and is absent
  from the lock; it MUST report it. Removing a module stays the act spec 021 FR-001 describes
  (services removed, data kept for the module's retention), asked for by name. *Decision
  2026-11-10: a line deleted from a file must not delete a service.*
- **FR-010**: `status <slug>` with a lock MUST compare what the project runs with the lock and
  report each difference — a module at another version, a module the lock names that is not
  running, a module running that the lock does not name — and MUST exit refused when there is
  any, so a check can gate on it. *Decision 2026-11-10.*
- **FR-011**: `render <slug>` with a lock MUST show what `apply` would run from it and what would
  change in the project, with no call but the reads `status` makes. *Restates spec 021 FR-005 for
  the lock.*
- **FR-012**: Every module applied or moved from a lock MUST be a fact on the brand's
  `platform.modules` topic naming the version and the actor, as the agent's are (spec 021 FR-011),
  so the audit holds what ran when whether or not a vendor decided it. *Restates spec 021 FR-011.*
- **FR-013**: On an installation a vendor serves (`VENDOR_URL` in its settings, or
  `ANKKA_VENDOR_URL`), nothing changes: the entitlement and its channel or pin decide versions,
  the agent applies, `apply` is refused naming the vendor, and a `modules.lock` in that tenant's
  directory MUST be refused by `validate` naming the vendor as where versions are decided.
  *Restates spec 021 FR-005 and FR-013.*
- **FR-014**: A shared project on an installation with a directory MUST take its services' version
  from its lock and the casino's realm address from the installation's settings
  (`KEYCLOAK_ISSUER_BASE`), not from the version and `keycloak.issuerBase` written in
  `shared/<project>/manifest.json`, which describe the local installation. *Established from the
  shared manifests, which carry `:0.1.0` and the local cluster's issuer; amends spec 001's shared
  manifest.*

**Facts stated once**

- **FR-015**: Whether an installation is fit for real money (spec 001 FR-035 to FR-038, FR-040;
  the core manifest's `requires[]`, spec 021 R6) MUST be decided from what the control plane
  reports at `GET /installation` and from nothing else; a fact it does not report is unmet, naming
  the control plane as the source. The mapping of `settings.env` lines to those facts is
  withdrawn. *Amends spec 021 R6 and FR-010; established from `Readiness.fromOverlay`, which maps
  `BACKUP_POINT_IN_TIME`, `BACKUP_OFF_CLUSTER_COPY_REQUIRED`, `ERASURE_ENABLED`,
  `ERASURE_KEYRING_REPLICAS`, `BROKER_REPLICAS`, `TOPIC_COPIES`, `BROKER_MIN_IN_SYNC` and
  `TOPIC_LONGEST_RETENTION`, and from ankka's control plane reference, which reports backups,
  erasure, the broker and retention.*
- **FR-016**: An installation's `settings.env` MUST hold only settings that are the casino's own:
  the installation's name, the casino's Keycloak addresses, the player site's base, the payment
  providers' addresses and the gateway's public address for them, the edge protection in front of
  the gateway, and `VENDOR_URL`. A line that states a fact the control plane reports (FR-015's
  eight) or one the platform's own overlay sets and nothing here reads (`CLOUD_PROVIDER`,
  `CLOUD_ACCOUNT`, `CLOUD_LOCATION`, `SECRET_BACKEND`, `OBJECT_STORE`, `OTLP_ENDPOINT`) MUST be
  refused by `validate`, naming the line and where the fact is stated. *Decision 2026-11-10.*
- **FR-017**: `validate <values> --real-money` MUST say, for each requirement, the fact, what was
  wanted, what was reported and that the control plane reported it; it needs the control plane and
  MUST say so when it cannot reach it. *Amends spec 021's modules-tool contract, where the check
  could run from the overlay with no call.*
- **FR-018**: The local installation `dev-kind` never takes real money (its overlay says so) and
  its `settings.env` is held to FR-016 like any other. *Decision 2026-11-10.*

### Key Entities

- **Installation's directory**: a directory outside this repository, one per installation, holding
  the installation's casino overlay and settings and its tenants.
- **Tenant**: a brand or a shared project on one installation; a directory under `tenants/` named
  by the brand's slug or the shared project's name.
- **Values** (spec 021 FR-003): unchanged in shape; kept per installation.
- **Version lock**: per tenant, the modules it runs and the released version of each.
- **Settings**: the casino's own settings of one installation; no platform fact.
- **Component**: a piece of kustomize configuration in this repository that an installation's
  overlay composes, the same for every installation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new brand is applied to a real installation by adding one directory to the
  installation's repository and running the tool; no file in this repository changes.
- **SC-002**: For any tenant on an installation with no vendor, the versions it runs are answered
  from the installation's repository alone, and `status` agrees with the lock or names every
  difference.
- **SC-003**: Upgrading one brand changes one file and moves that brand's modules and no other
  tenant's: a second brand's project shows no change after the first is applied.
- **SC-004**: A search of this repository finds no real installation's account, address or
  hostname; a search of an installation's directory finds no line restating a platform fact and no
  secret value.
- **SC-005**: With the same values, lock and settings, the tool's `render` naming the directory is
  identical to its `render` of the same files placed in this repository at the same versions.
- **SC-006**: An installation whose control plane reports a required fact as unmet refuses a
  real-money brand whatever its settings file says.

## Assumptions

- The installation's directory is in practice `ankka-deployments/clusters/<installation>/`, with
  `casino/` applied by Flux over this repository's components at a tag and `tenants/` read by the
  tool; this spec asks only for the shape under the directory, not for that repository.
- A released version's manifest is what `release render <tag>` writes. The tool today takes a
  module's manifest from the checkout and its version from the command line; whether it reads each
  locked version's manifest from the checkout at that tag or from the release's published render
  is the plan's choice. Because one tag releases every module (decision 0055), a lock whose
  modules are all at one version needs one checkout.
- The lock is a small JSON file written by a person; no command writes it.
- ankka's `GET /installation` reports backups, erasure, the broker and retention at the release
  the installations pin (0.16). The tool's names for the retention fact differ from ankka's
  (`topics.longestRetention` against `retention.longest` and `retention.unbounded`); the plan
  reconciles them.
- Edge protection (spec 001 FR-034) is not a fact the control plane reports; it stays a setting of
  the installation until the platform reports it.
- The vendor's path is untouched: `values push` through a vendor still sends the tenant's values
  to it, read from the directory when one is named.
- Applying from the lock is a person's act today and may become a workflow of the installation's
  repository; the tool's exit codes (FR-010) are what that workflow would gate on.

## Dependencies

- Spec 001 (installations, FR-034 to FR-040; FR-007, FR-008), spec 021 (the values, the modules
  tool, local mode, R6).
- `ankka-deployments`: a directory per installation that can hold `casino/` and `tenants/`.
- ankka's control plane answering `GET /installation` with the facts FR-015 names.

## Out of scope

- The installation's cloud project, cluster and platform overlay: `ankka-deployments`' own.
- Anything the vendor does: entitlements, channels, the agent.
- A command that writes or bumps a lock, and automation that applies on merge.
- Removing a module (spec 021 FR-001's act) beyond saying that `apply` does not.
- Secrets' values and where they are kept.

## Open Questions

- [NEEDS CLARIFICATION: the tenant project id. With a vendor it is `<organization>-vendor`;
  locally it is the slug (spec 021's values contract). On a real installation with no vendor, is a
  brand's project its slug in one organisation shared by the brands, or one organisation per
  brand? The lock and the values do not depend on the answer, but `apply` creates the project and
  `--organization` names where.]
- Whether `installation/overlays/gke`'s Cloud Armor attachment becomes a component here or stays
  the installation's own: it attaches to a GKE Ingress backend, and the gateway's service on the
  first real installation is not one.
