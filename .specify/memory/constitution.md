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
a brand project refuses any brand but its own. Shared projects (`payments`, `programme`, `reasoner`,
`tools`) hold no brand's player data beyond the player id, and in `payments` the fields a payment
provider requires for a deposit, held as `Personal` values under the project's own subject;
`programme` holds no player id at all, only a per-network pseudonym. A shared project reaches a
brand only through a grant the brand made (ankka 040); a brand's public routes are the exception,
reached by any caller as the internet reaches them, admitted by the route's own signature or key
and never by a platform identity. A brand's `backoffice` is a module of the brand's own project; it
holds no player data, and it reaches the brand's and a shared project's operator routes only on
behalf of a signed-in operator, carrying that operator's own sign-in, so the owner admits the person
and never the backoffice. Nothing is shared by convention.

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
external HTTP API may be TypeScript or Python when the spec gives the reason. Code that runs in a
player's browser, and the server that serves it, are TypeScript: they speak to a browser and to
services over HTTP, hold no state that outlives an instance, and move no money themselves. Every service owns its
own provisioned database and shares nothing with another.

### VII. Specs in the domain's words
A spec describes what a player, an operator, an affiliate, a PSP or a regulator observes, in the
glossary's words. Acceptance scenarios live once, in the living features, and the spec names them.
A requirement is testable or it is not a requirement.

## Scope and dependencies

- The platform is ankka at a version this repository pins; features the casino needs and ankka
  lacks are specs in ankka (038–044 at the time of writing), not workarounds here.
- The player site is in scope as spec 027: the player SDK and a reference site, a module of each
  brand's own project. A site built on the SDK holds no rule of a service and reaches a service
  only as the signed-in player. The affiliate portal is out of scope: it changes only base URLs.
  The operator console is in scope as spec 030, the backoffice, a module of each brand's own
  project; it calls the owning services as the signed-in operator and holds no rule of theirs.
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

**Version**: 1.4.0 | **Ratified**: 2026-10-08 | **Last Amended**: 2026-11-19

Amendment 1.1.0 (2026-10-09): principle III said shared projects hold "no brand's player data beyond
a pseudonymous subject id". Spec 001 FR-018, settled after the cashier was read, has `payments` hold
the player id and the customer fields a payment provider requires, and `reasoner` the player id; only
`programme` is pseudonymous. The principle now says what the specs do.

Amendment 1.2.0 (2026-10-09): principle III named three shared projects and said a shared project
reaches a brand only through a grant. Spec 020 adds `tools` (the game provider simulator), which
reaches a brand's public provider routes as the real provider does: from outside, signed, with no
platform identity and nothing to grant. The principle now names `tools` and says that a public route
is reached as the internet reaches it; the grant rule governs every other route.

Amendment 1.3.0 (2026-11-18): *Scope* put every frontend out of scope, changing only base URLs.
Spec 027 builds the player site's behaviours (session, deposit, play, inbox, chat) as an SDK and a
reference site on it, because a brand on these modules has no player site otherwise and the
original's site holds rules the services own (it opened the cashier session itself). The player
site is now in scope, holding no rule of a service and calling a service only as the signed-in
player; principle VI gains the reason a browser's code is TypeScript. Spec 030 amends the same
bullet for the operator console: whichever merges second keeps both sentences and takes 1.4.0.

Amendment 1.4.0 (2026-11-15, numbered when spec 027 merged first): *Scope* kept the operator
console out of scope, to change only its base URLs. Spec 030 replaces it instead, because the
original's use shows its faults would carry over (spec 030 research). The operator console is now
in scope as spec 030, a module of each brand's own project, one backoffice per brand; principle III
says how it reaches a shared project (as the signed-in operator, never by a grant or its own
identity), and principle VI is met by the reason spec 030 gives for TypeScript in the program that
serves the browser.
