# ankka-casino Constitution

eitheror's casino and payments, rebuilt as services on ankka. These are the rules every spec, plan
and implementation in this repository is held to.

## Core Principles

### I. The running original is the specification
`eitheror-platform` runs real money today. Where a route, a state machine, a timer or a message
exists there, it is kept; its recorded behaviour (ankka-port recordings, the Go end-to-end suite,
the Playwright suite) is the acceptance test. Where a documented feature is a stub, it is dropped,
and the spec says so. A spec never invents behaviour the original does not have without naming it as
new under *Changes from the original*.

### II. Money is exact and the wallet is the ledger
Every amount is a whole number of minor units (a `Long`), never a decimal. The wallet is the one
ledger: every other service either calls it or listens to it, and no service keeps a balance of its
own. Every money command is idempotent under a key the caller supplies (a provider's transaction id,
a PSP's reference, a workflow's step id), and a repeat returns the first outcome.

### III. A brand is a project
Each brand is its own ankka project with its own services, databases, topics and versions. Keys
inside a brand carry no brand; a wallet is `player/currency`. Routes keep their `{brand}` segment and
a brand project refuses any brand but its own. Shared projects (`payments`, `programme`, `reasoner`)
hold no brand's player data beyond a pseudonymous subject id, and reach a brand only through a grant
the brand made (ankka 040). Nothing is shared by convention.

### IV. Commands over HTTP, facts over topics
A command that needs an answer in the request path is an HTTP call to the owning service. A fact is
a message on a topic, shaped by a consumer into a stable, versioned message, so no service's journal
leaks. A reader builds its own view; it never queries another service's store. Shared services
accept every message and API version still deployed in any brand.

### V. Personal data is marked, and erasure is honoured
Every field that identifies a person is a `Personal` value (ankka 042) under the player's subject.
A service that holds a subject's objects names them under the subject's prefix and implements the
erasure handler. Regulatory retention is a hold on the erasure request, never an excuse to keep a
field unmarked.

### VI. Scala by default, with a reason for anything else
The money path and anything that must be atomic is Scala. An adapter that mostly speaks to an
external HTTP API may be TypeScript or Python when the spec gives the reason. Every service owns its
own provisioned database and shares nothing with another.

### VII. Specs in the domain's words
A spec describes what a player, an operator, an affiliate, a PSP or a regulator observes, in the
glossary's words. Acceptance scenarios live once, in the living features, and the spec names them.
A requirement is testable or it is not a requirement.

## Scope and dependencies

- The platform is ankka at a version this repository pins; features the casino needs and ankka
  lacks are specs in ankka (038–044 at the time of writing), not workarounds here.
- Frontends (`casino-player-frontend-next`, the affiliate portal, the operator console) are out of
  scope: they change only base URLs.
- Chatwoot, Strapi, Keycloak and Unleash stay external; this repository integrates with them and
  replaces none of them.

## Workflow

- `/speckit-specify` → `/speckit-bdd-features` → `/speckit-clarify` → `/speckit-plan` →
  `/speckit-tasks` → `/speckit-implement`, in that order, one feature per branch named after its
  spec directory.
- `just features` must pass before a spec is planned.
- A plan names the ankka-port recordings it will replay and the parity tests it will leave behind.

## Governance

This constitution outranks any other practice in the repository. An amendment is a pull request
that states what changes and why, and every open spec is checked against it before merge. Where a
spec and the constitution disagree, the spec is wrong until the constitution is amended.

**Version**: 1.0.0 | **Ratified**: 2026-10-08 | **Last Amended**: 2026-10-08
