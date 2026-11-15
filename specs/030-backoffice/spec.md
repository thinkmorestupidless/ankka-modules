# Feature Specification: The Backoffice — Every Operator Capability of the Original, Reworked From How It Is Used

**Feature Branch**: `030-backoffice`

**Created**: 2026-11-15

**Status**: Draft

**Input**: User description: "A backoffice web UI for ankka-modules that rewrites the eitheror
casino backoffice: every capability the original's operators have, with the UX reviewed in the
light of how the original is actually used. It lives in this repository as an ankka web-hosted
service with a backend-for-frontend, in a shared project of its own; the browser never calls a
brand's services directly. Content editing (Strapi) is not part of it. Its look follows the
dashboard inspiration supplied for it, not the eitheror brand."

## Context

The original's backoffice is `eitheror-platform/casino/frontend` with `payments/frontend` folded
in and a small backend (`casino/backoffice-bff`): about sixty screens across dashboard and
reports, players, sessions, wallets, transactions, withdrawals and payouts, KYC, responsible
gambling, fraud, rewards and offers, games and providers, brand configuration, payments
administration, the operator team, the affiliate programme and casino content. Every spec in this
repository so far keeps the routes that frontend calls so that it would change only its base URLs
(constitution, *Scope*; spec 001 FR-033). This spec replaces that frontend rather than re-pointing
it, because the evidence of its use says re-pointing it would carry its faults onto ankka.

What the original's use shows, from its specs, incidents, commits and the work done around it
(sources in [research.md](research.md)):

1. **"Why did this happen?" cannot be answered in it.** Every non-trivial player question ends
   with an engineer reading histories, ledgers and logs by hand (original spec 258); withdrawal
   investigations need port-forwards and graph queries. The operator assistant was built because
   the backoffice could not explain anything.
2. **Lists silently lost rows.** Fetches capped at 500 or 20, oldest first, filtered in the
   browser; fixed page by page across eight commits and still open in places. A row missing from
   a list while its detail page loads is a known, written-down symptom.
3. **Money on screen could not be trusted.** Minor units shown as major, a refund a hundred times
   too large, €0.05 shown for €5, requested and voided amounts hidden behind one `amount`.
4. **Reward states were opaque and actions confused.** Operators emptied a reward with an
   adjustment, which left it active; revoke buttons called routes that did not exist; refusals came
   back as a generic error.
5. **A withdrawal's story was scattered.** Reviewing withdrawals is the most common review action
   (original spec 250), yet a player's tab showed only the pending request, with no balance
   context, no approver and, for one state, buttons that never rendered.
6. **Corrections happened outside it.** Compensation went through a generic adjust button with no
   link to its cause; repairs went through scripts; a manual win paid by hand was later paid again
   by the provider's retry.
7. **Roles and brand selection broke pages.** 403s and blank pages from the holding, company and
   sub-brand picker; the screen's role checks and the backend's drifted apart (roles that confer
   nothing; buttons shown to everyone and refused by the server).
8. **Daily friction.** Lost filters and scroll on every return to a list, review queues that were
   not first-in first-out, a birth-date picker clicked month by month, polling that blanked lists,
   no sign that the figures were minutes old.

So the backoffice keeps every capability and changes the shape around three things the original
does not have: a **worklist** per operator, an **investigation** view that tells a player's or a
withdrawal's whole story with its gaps marked, and **actions that say what they will do and why
they are refused**. Everything else follows from the eight findings above.

### Where it runs

A shared project, `backoffice`, beside `payments`, `programme`, `reasoner`, `games` and `tools`.
It holds no brand's player data (constitution III): it stores nothing about a player, and what it
shows it reads, as the signed-in operator, from the brand's and the shared projects' own operator
routes. It serves the operator's browser one address; the browser never calls a brand service.
Every call it makes for an operator carries that operator's own sign-in, so each owning service
decides, as it does today, whether this person may do this to this brand. The backoffice decides
nothing a service would refuse, and adds no rule a service does not hold.

### Constitution amendment

The constitution's *Scope* says the operator console is out of scope and changes only base URLs.
This spec needs amendment 1.3.0, made on this branch: the operator console is in scope as this
spec; the player site and the affiliate portal stay out of scope. Principle III gains how the
backoffice reaches a brand: as the signed-in operator, never by a grant or its own identity.
Principle VI is met as written: the program that serves the
browser speaks HTTP to other services and to the browser and holds no money path and no state that
must be atomic, so TypeScript is its reason-given language; anything that keeps state (an
operator's preferences) is a Scala service.

## Clarifications

### Session 2026-11-15

- Q: Does the backoffice follow the services' roles as they are (any operator of a brand may adjust,
  set limits, suspend, self-exclude and close), or carry original spec 135's matrix (a read-only
  support role; money and standing restricted) into the owning services? → A: Carry spec 135 in, as
  amendments to the owning services (FR-008, FR-008a).
- Q: Which of the six phases of FR-045 does this spec's task list, and the one build that follows,
  cover? → A: All six, in one task list and one build, in phase order (FR-045).
- Q: What language is the backoffice's own interface in for operators? → A: English at delivery,
  with every string in a message catalogue so that another language is a translation (FR-040a).
- Q: Which of parity.md's *decide* rows (the CRM configuration page, affiliate adjustments) does
  the backoffice include? → A: Both (FR-030; [parity.md](parity.md)).
- Q: Do the proposed glossary terms worklist, timeline, adjustment batch and merge tag stand? → A:
  All four, as defined.

## User Scenarios & Testing *(mandatory)*

Priorities follow the phases in FR-045. Each story names the original screens it replaces in
[parity.md](parity.md).

### User Story 1 - An operator signs in and works in a brand, seeing only what they may do (Priority: P1)

An operator signs in once at the `eitheror` realm. The backoffice lists the brands their roles name
(`brand_<slug>`, or every brand for `platform_admin`) and opens the last one they used, or the only
one. The brand they are working in is always visible and changed in one step; nothing they see or
do crosses into another brand. Every action they are not permitted is either absent or shown
disabled with the role it needs, decided per action from the same roles the owning service checks;
nothing is offered and then refused for a role the screen already knew about. Every list and figure
says how fresh it is.

**Why this priority**: Nothing else works without it, and the original's brand picker and drifted
role checks broke pages for whole roles.

**Independent Test**: Sign in as operators holding one brand, two brands, `platform_admin`, and a
role with no brand; check the brand list, the switch, and that each action's availability matches
what the owning service admits for that role.

**Acceptance Scenarios**:

- added `features/backoffice/access.feature`: an operator sees only the brands their roles name and works in one at a time
- added `features/backoffice/access.feature`: an operator with no brand role is told which role they need and sees no brand
- added `features/backoffice/access.feature`: an action the operator's roles do not admit is shown with the role it needs and cannot be started
- added `features/backoffice/access.feature`: a refusal the owning service gives is shown with its reason and the next step, never as a generic error
- added `features/backoffice/access.feature`: the operator's sign-in never reaches the browser's scripts
- added `features/backoffice/access.feature`: a support operator reads a player's account and can write only notes and inbox items
- added `features/backoffice/access.feature`: an adjustment is admitted to finance and administrators only, and a limit to compliance and administrators only
- added `features/backoffice/freshness.feature`: a list read from a brand's views shows their freshness and says when it is provisional

---

### User Story 2 - An operator starts from their worklist (Priority: P1)

An operator's first screen is their worklist: the open work their roles let them act on in the
current brand, one row per item, oldest first, with each kind's count. A finance operator sees
withdrawal requests pending review and withdrawal payments that need review; a compliance operator
sees identity documents awaiting review, players flagged for review, open cases and fraud cases; an
administrator sees undelivered facts, unacknowledged provider callbacks and parked sends. Opening
an item opens its investigation; acting on it returns the operator to the worklist at the same
place, with the item gone or moved.

**Why this priority**: The original opens on a dashboard of figures, and every queue is a separate
page with its own filters; the most frequent work (withdrawals, documents) is a list the operator
must find and re-filter.

**Independent Test**: Seed a brand with one item of each kind; sign in as each role; confirm the
worklist holds exactly the kinds that role may act on, oldest first, and that acting on one removes
it and keeps the operator's place.

**Acceptance Scenarios**:

- added `features/backoffice/worklist.feature`: an operator's worklist holds the open items their roles may act on, oldest first
- added `features/backoffice/worklist.feature`: acting on an item returns the operator to the worklist at the same place
- added `features/backoffice/worklist.feature`: an item another operator has just acted on shows who did what and offers no action
- added `features/backoffice/worklist.feature`: a kind whose source cannot be read is shown as unavailable, not as empty

---

### User Story 3 - An operator investigates a player (Priority: P1)

An operator pastes whatever they were given (a player id, an email, a name, a provider's round or
transaction id, a payment reference, a withdrawal request id, a reference number) into one search,
and lands on what it names. A player's page shows, without scrolling, who the player is, their
account status, identity status, limits and breaks, balances by currency with restricted funds,
lifetime figures, pinned notes and whether they are online. Below it, one timeline holds everything
that happened to the player in order: deposits, withdrawals, rounds, rewards, adjustments, status
changes, identity decisions, limits, notes and messages, each with who or what caused it. When one
source of the timeline cannot be read, the timeline says which and what is missing; it never drops
it silently. From a round, the operator drills into its bets and wins and its totals; from a
deposit, its payment; from a reward, its record.

**Why this priority**: Investigation is where the original falls shortest and where engineers are
called in.

**Independent Test**: For a seeded player with one of each event, find the player by each kind of
reference; read the timeline end to end; make one source unavailable and confirm the timeline names
it.

**Acceptance Scenarios**:

- added `features/backoffice/investigation.feature`: any reference an operator pastes finds what it names, or says it names nothing in this brand
- added `features/backoffice/investigation.feature`: a player's page shows the player's standing without scrolling
- added `features/backoffice/investigation.feature`: a player's timeline holds every kind of event in order, each with its cause
- added `features/backoffice/investigation.feature`: a timeline whose source cannot be read names the source and the gap
- added `features/backoffice/investigation.feature`: a round shows its bets and wins with the total staked, won and the net, and is marked when unsettled

---

### User Story 4 - Finance reviews a withdrawal from request to payment (Priority: P1)

A finance operator opens a withdrawal request and sees its whole story on one page: the amount
requested, held and paid, any part voided and why, the player's balances and restricted funds when
it was made and now, the player's identity status and recent deposits, the terminal and
destination, every state it passed through with who moved it, and the withdrawal payment's
attempts. The actions offered are exactly those its state allows (approve, reject with a reason,
confirm a manual payment, mark a payment failed with a reason), and the page says what each will
do before it is done. A player's withdrawals tab lists every request in every state.

**Why this priority**: The most common review action, and the one whose missing context caused
voided deposits to be refunded by hand.

**Independent Test**: Take one request through each path (approved and paid; rejected; approved,
needs review, confirmed by hand; capped with a voided part); confirm the page shows each state,
amount and actor, and offers only that state's actions.

**Acceptance Scenarios**:

- added `features/backoffice/withdrawals.feature`: a withdrawal request's page shows the amounts requested, held, paid and voided, and why
- added `features/backoffice/withdrawals.feature`: a withdrawal request offers only the actions its state allows and says what each does
- added `features/backoffice/withdrawals.feature`: every state a withdrawal request passed through is shown with who moved it
- added `features/backoffice/withdrawals.feature`: a player's withdrawals list holds requests in every state

---

### User Story 5 - An operator corrects money and says why (Priority: P2)

An operator corrects a player's balance with an adjustment: credit or debit, real balance or a
restricted funds record, amount, a reason from the brand's list with free text, and optionally the
cause it answers (a round, a provider transaction, a payment, an incident's reference). The
backoffice says the balances before and after, and refuses a debit larger than the balance rather
than clamping it. For an incident affecting many players, the operator enters one reason and cause
and a list of players and amounts; each player's adjustment is made separately under its own key,
and the result lists each one as made, refused (with the reason) or already made.

**Why this priority**: Corrections went through the deposit route, then a generic button, then
scripts; one paid a win twice.

**Independent Test**: Adjust one player with a cause; adjust a list of five, one of which is
refused; repeat the list and confirm nothing is made twice.

**Acceptance Scenarios**:

- added `features/backoffice/corrections.feature`: an adjustment shows the balance before and after and records its reason and cause
- added `features/backoffice/corrections.feature`: a debit larger than the balance is refused, not reduced
- added `features/backoffice/corrections.feature`: an adjustment to many players lists each as made, refused or already made, and a repeat makes none twice

---

### User Story 6 - An operator manages a player's rewards (Priority: P2)

An operator sees each of a player's rewards with its state in the glossary's words (active,
awaiting its cap, forfeited, closed), its wagering progress, its open rounds and, for free rounds,
rounds used of rounds given as the player sees them. The actions offered are those the state
allows, each explaining its effect: forfeit (the record is zeroed and closed), reclaim, or grant a
reward from an offer or by hand. A grant that is refused says why (a suspected duplicate, a claim
limit, a missing deposit) and what would allow it. Offers are created, edited, ordered and
deactivated, with the offers that depend on one named before it changes.

**Why this priority**: The second-largest source of fix commits in the original and the cause of
an incident.

**Independent Test**: For a player with one record in each state, confirm the offered actions and
their explanations; grant to a suspected duplicate and read the refusal; forfeit and confirm the
record closes.

**Acceptance Scenarios**:

- added `features/backoffice/rewards.feature`: a restricted funds record shows its state, wagering progress and open rounds, and only its state's actions
- added `features/backoffice/rewards.feature`: a refused reward shows the reason and what would allow it
- added `features/backoffice/rewards.feature`: changing an offer other offers depend on names them before it is changed

---

### User Story 7 - Compliance reviews identity and responsible gambling (Priority: P2)

A compliance operator works the identity queue first-in first-out, keeping their place: opens a
player, views each document, approves or rejects it with a reason, sets the player's verification
mode and required documents, and records the final decision. They set and remove limits, start
and lift breaks, record a self-exclusion, close cases with a note, and review or dismiss fraud
cases. Each action shows what it changes for the player (deposits blocked, play blocked, a limit
that takes effect later) before it is done.

**Why this priority**: Regulated work that the original spread over a queue page, a player tab and
three other pages.

**Independent Test**: Take a player from documents awaiting review to verified; set a limit and
see its pending change; start and lift a break; dismiss a fraud case and confirm its flags clear.

**Acceptance Scenarios**:

- added `features/backoffice/compliance.feature`: the identity queue is first-in first-out and keeps the operator's place
- added `features/backoffice/compliance.feature`: an identity document is viewed only by an operator the brand admits
- added `features/backoffice/compliance.feature`: a limit raised takes effect later and the page shows when
- added `features/backoffice/compliance.feature`: dismissing a fraud case clears the flags it set

---

### User Story 8 - An operator reads the dashboard and the reports (Priority: P2)

The dashboard shows the brand's figures per currency (never summed across currencies), their trend,
where players are on a map of the world, top games and players, rewards and payment health, each
with its freshness. Reports (revenue, players, transactions, rewards, payment providers, day-end,
compliance exports) are run for a period and exported from the server with their reference number.

**Why this priority**: Read-only and already served by spec 015; it carries the visual direction.

**Independent Test**: Load the dashboard for a brand with two currencies and players in five
countries; confirm two sets of figures and five countries on the map; export each report.

**Acceptance Scenarios**:

- added `features/backoffice/dashboard.feature`: the dashboard shows each currency's figures apart
- added `features/backoffice/dashboard.feature`: the map shows players by country for the chosen period
- added `features/backoffice/dashboard.feature`: a report exported from the backoffice carries its reference number

---

### User Story 9 - Administrators configure the brand, payments and the team (Priority: P3)

A brand administrator sets the brand's configuration (sites, countries, currencies, withdrawal,
deposit and wagering settings, the game catalogue, the support agent, the modules page of spec
021) and sees which values differ from the brand's defaults. A payments administrator, at their
operator level, manages holdings, companies and sub-brands, payment provider configurations and
terminals, routing rules (draft, simulate, deploy, roll back) and risk settings. Administrators
invite operators with a role at or below their own, resend, reset and revoke.

**Why this priority**: Infrequent, high-consequence work; parity matters more than redesign.

**Independent Test**: Change one value in each configuration area and see it differ from the
default; simulate and deploy a routing rule; invite an operator at a role above the inviter's and
see it refused.

**Acceptance Scenarios**:

- added `features/backoffice/configuration.feature`: a configuration value that differs from the brand's default is marked
- added `features/backoffice/configuration.feature`: a routing rule is simulated before it is deployed and can be rolled back
- added `features/backoffice/configuration.feature`: an operator cannot invite a role above their own

---

### User Story 10 - Managers run the affiliate programme (Priority: P3)

A manager (or an administrator) manages affiliate accounts, plans, tracking links, creatives,
payouts by period, fraud flags, messages, the programme's settings and the managers, and sees the
portal as an affiliate would, from the programme wherever it is deployed.

**Why this priority**: The largest area by screens and the least changed by the findings.

**Independent Test**: Approve an account, attach a plan, close a payout period, dismiss a fraud flag
with a reason.

**Acceptance Scenarios**:

- added `features/backoffice/affiliates.feature`: a manager sees only the programme, and only the brands their roles name
- added `features/backoffice/affiliates.feature`: dismissing a fraud flag records the reason

---

### User Story 11 - An operator reaches out to players who are online (Priority: P3)

An operator who has asked for it is told when a player of their brand comes online, and from the
notice opens the player and sends a message with the brand's merge tags, previewed before sending.
Notes are written, pinned and unpinned on the player.

**Why this priority**: Retention work that exists today and is used, but is not the core of the
findings.

**Independent Test**: Turn the notice on, bring a player online, open them from the notice, send a
previewed message.

**Acceptance Scenarios**:

- added `features/backoffice/outreach.feature`: an operator who asked is told when a player of their brand comes online
- added `features/backoffice/outreach.feature`: an inbox item is previewed with its merge tags filled before it is sent

### Edge Cases

- An operator's roles change while they are signed in: the next call is decided on the new roles;
  a brand they lost is closed and they are told why.
- A brand's views are rebuilding: lists show what they have, marked provisional, with the
  freshness; nothing is shown as empty that is only late.
- Two operators act on the same item: the second is refused by the owning service on its state,
  and the backoffice shows who acted first and what they did.
- A reference matches more than one thing (a number that is both a round id and a payment
  reference): every match is listed by kind; none is chosen silently.
- A player is erased: the player's page says so and shows no personal data; the records the brand
  keeps for regulation are shown under the pseudonym only.
- An amount in a currency with no minor unit, or three: every amount is shown by the currency's
  own minor-unit count.
- An adjustment list holds a player twice: both rows are made under their own keys, and the result
  says so.
- A service that the brand does not run (a module not enabled): its screens are absent, and the
  modules page says why, rather than every call failing.
- A long-running export: it runs as a job and the operator is told when it is ready, whatever
  page they are on; the backoffice never waits more than its gateway's bound for an answer.

## Requirements *(mandatory)*

### Functional Requirements

**Placement and access**

- **FR-001**: The backoffice MUST run in a shared project, `backoffice`, as a web-hosted service
  that serves the operator's browser one address, and MUST hold no player's data at rest.
- **FR-002**: The browser MUST call only the backoffice; the backoffice MUST make every call to a
  brand's or a shared project's service on the operator's behalf, carrying the operator's own
  sign-in, so that the owning service admits or refuses the operator as it would any caller.
- **FR-003**: An operator's sign-in MUST be held by the backoffice's server and MUST NOT be
  readable by the page's scripts; the browser holds only a session that expires when the sign-in
  does, and signing out ends both.
- **FR-004**: The backoffice MUST list the brands the operator's realm roles name
  (`brand_<slug>`, or every brand for `platform_admin`), MUST work in exactly one brand at a time,
  and MUST show which. It MUST NOT offer a holding, company or sub-brand picker for casino work;
  payments administration acts at the operator's level as spec 004 defines it.
- **FR-005**: The backoffice MUST decide which actions to offer from the same role rules the
  owning services check, read from this repository's contract rather than written again; an action
  the operator may not take MUST be shown with the role it needs, or not at all, never offered and
  then refused for a reason the backoffice already knew.
- **FR-006**: Every refusal from an owning service MUST be shown with its reason in the domain's
  words and, where one exists, the next step; a refusal code the backoffice does not know MUST be
  shown as given.
- **FR-007**: The backoffice MUST add no rule a service does not hold and MUST NOT work around a
  missing route; a capability with no route in the owning service is a change to that service's
  spec (see [parity.md](parity.md)).
- **FR-008**: The roles that admit each action MUST be original spec 135's matrix, carried into the
  owning services by this spec as amendments to them: a realm role `brand_support` that reads a
  brand's players, wallets, rounds, withdrawals and rewards and writes notes and messages only;
  adjustments admitted to `brand_finance` and `brand_admin`; limits, breaks, self-exclusion,
  suspension, reinstatement, closing and reopening an account admitted to `brand_compliance` and
  `brand_admin`; withdrawal review admitted to `brand_finance` and `brand_admin`; configuration
  admitted to `brand_admin`; `platform_admin` admitted everywhere. `brand_finance` and
  `brand_compliance` MUST be realm roles. Each owning service's acceptance scenarios MUST state the
  roles it admits per route, and the contract MUST hold the matrix once (FR-005).
- **FR-008a**: At spec 019's cutover every operator of the original MUST be given the roles their
  original role maps to, by a mapping the migration publishes for review before it runs; an
  operator whose mapping is ambiguous MUST be given `brand_support` and listed.

**Lists, figures and money**

- **FR-009**: Every list MUST be paged by the owning service, newest first unless it is a review
  queue (oldest first), and MUST show its total and whether it is complete.
- **FR-010**: A list MUST keep the operator's filters, sort, page and position when the operator
  opens an item and returns.
- **FR-011**: Every figure read from a brand's views MUST show its freshness, and MUST be marked
  provisional while the views are degraded, rebuilding or catching up.
- **FR-012**: Refreshing a list MUST NOT blank or dim the rows already shown; changed rows MUST be
  marked.
- **FR-013**: Every amount MUST be shown from minor units by one rule: the currency's own minor
  unit count and symbol, never converted, never summed across currencies.
- **FR-014**: Wherever a withdrawal or a deposit is shown, the amounts requested, held, paid and
  voided MUST each be shown when they differ.
- **FR-015**: Dates MUST be shown in the brand's zone with the zone named; a date filter's end MUST
  include the whole of its last day; a birth date MUST be enterable by typing.

**Worklist**

- **FR-016**: The backoffice MUST give each operator a worklist of the open items their roles may
  act on in the current brand: withdrawal requests pending review, withdrawal payments needing
  review, identity documents awaiting review, players flagged for review, open cases, open fraud
  cases, unacknowledged provider callbacks, undelivered facts and parked sends.
- **FR-017**: Worklist items MUST be ordered oldest first with each kind's count; a kind whose
  source cannot be read MUST be shown as unavailable.
- **FR-018**: Acting on an item MUST return the operator to the worklist at their place; an item
  acted on by someone else MUST show who and what, and offer nothing.

**Investigation**

- **FR-019**: One search MUST accept a player id, email, name, provider round id, provider
  transaction id, payment reference, withdrawal request id, reward id and reference number, and
  MUST list every match by kind; free text MUST never be sent to a service as an id.
- **FR-020**: A player's page MUST show, without scrolling on a 1440 by 900 screen: identity, account
  status, identity status, limits and breaks, balances per currency with restricted funds, lifetime
  figures, pinned notes, flags and presence.
- **FR-021**: A player's timeline MUST merge deposits, withdrawals, rounds, rewards, adjustments,
  account status changes, identity decisions, limits and breaks, notes and messages in time order,
  each with its actor (player, operator by name, system or provider) and, where the fact carries
  one, its cause; it MUST be filterable by kind and period and exportable.
- **FR-022**: When a timeline source cannot be read the timeline MUST name it and the period it
  is missing, and MUST NOT present the rest as complete.
- **FR-023**: A round MUST show its bets and wins with the total staked, won and net, and MUST be
  marked when unsettled; a game session MUST show its rounds; a player session MUST show its game
  sessions and any gap in the data.
- **FR-024**: Every record shown MUST link to the records it names (a deposit to its payment, a
  win to its round, an adjustment to its cause, a withdrawal to its payment attempts).

**Actions**

- **FR-025**: Every action that changes money, a player's standing or a configuration MUST state its
  effect before it is confirmed, in the domain's words, and MUST require a reason where the owning
  service records one.
- **FR-026**: The actions offered on a withdrawal request, a restricted funds record, a case, a
  fraud case or an identity document MUST be exactly those its state allows.
- **FR-027**: An adjustment MUST show the balance before and after, MUST take a reason from the
  brand's list with free text and an optional cause reference, and MUST be refused, not reduced,
  when a debit exceeds the balance.
- **FR-028**: An adjustment to many players MUST be made as one adjustment per row under its own
  idempotency key derived from the batch and the row, MUST report each row as made, refused with
  its reason, or already made, and MUST make nothing twice when repeated.
- **FR-029**: An action that takes longer than an answer may (exports, reports, batches) MUST run
  as a job whose progress and result the operator can return to, and MUST tell the operator when it
  ends wherever they are in the backoffice.

**Parity**

- **FR-030**: Every capability listed in [parity.md](parity.md) MUST be reachable in the
  backoffice for the roles the owning service admits, except those the list marks dropped or
  moved. The CRM configuration page and affiliate adjustments, unreachable in the original, are
  kept.
- **FR-031**: Identity documents MUST be shown only through the backoffice to a signed-in operator
  the brand's kyc service admits for that document; the backoffice MUST NOT fetch any address it
  is given.
- **FR-032**: The dashboard MUST show the brand's figures per currency, their trend, players by
  country on a world map, top games and players, rewards and payment health (spec 015).
- **FR-033**: Reports MUST be run for a period and exported by the reporting service, carrying
  their reference number (spec 015); the backoffice MUST NOT build an export itself.
- **FR-034**: Configuration screens MUST mark each value that differs from the brand's default or
  its licence profile.
- **FR-035**: The routing rules screen MUST let a payments administrator draft, simulate, deploy,
  retire and roll back rules (spec 004), showing the tree as the original does.
- **FR-036**: An operator MUST be able to ask to be told when a player of their brand comes online,
  and the notice MUST open the player; messages MUST be previewed with merge tags filled before
  they are sent.
- **FR-037**: The affiliate programme's management screens MUST call the programme wherever it is
  deployed (a shared project today; ankka-affiliates if spec 028 moves it) and MUST admit managers
  to the programme only.

**Operator preferences**

- **FR-038**: An operator's preferences (theme, last brand, worklist kinds shown, online notices)
  MUST be kept per operator by a service of the `backoffice` project and MUST hold nothing about
  any player.

**Look and use**

- **FR-039**: The backoffice MUST follow the visual direction in this spec's *Visual direction*
  and MUST NOT use the eitheror brand.
- **FR-040**: Every screen MUST meet WCAG 2.2 AA for contrast, focus and keyboard use; colour MUST
  never be the only sign of a state.
- **FR-040a**: The backoffice's interface MUST be in English at delivery, and every string an
  operator reads that the backoffice itself writes (labels, effect statements, the reasons it gives
  for known refusal codes) MUST come from one message catalogue keyed by message, so that another
  language is added by translating the catalogue and changes no screen. What an owning service
  returns as data is shown as given.
- **FR-041**: The backoffice MUST be usable at 1280 pixels wide and above; below that it MUST stay
  readable for looking a player up, and need not support editing.
- **FR-042**: Built files MUST be named by their content and the index served uncached, so that a
  rollout never breaks an open page (ankka web hosting).

**Delivery**

- **FR-043**: Content editing (the original's casino content screens) MUST NOT be part of the
  backoffice; where an operator needs it, the backoffice MUST link to the content system's own
  editor.
- **FR-044**: The original backoffice MUST keep working for Spinvibe until spec 019's cutover; this
  backoffice serves brands on ankka and MUST NOT be required by any route of another spec.
- **FR-045**: The backoffice MUST be built in one task list and one build that covers all six
  phases, in phase order, each phase usable on its own when its tasks are done: (1) access,
  worklist and investigation (stories 1 to 3); (2) withdrawals and corrections (4, 5); (3) rewards
  and compliance (6, 7); (4) dashboard and reports (8); (5) configuration, payments and team (9);
  (6) the affiliate programme and outreach (10, 11).

### Visual direction

Taken from the dashboard inspiration supplied with this spec (four shots of dark wallet and trading
dashboards), and stated here so that the plan's design tokens can be checked against it:

- Dark first: near-black surfaces with a faint green cast; cards a step lighter, with large radii
  and no borders, separated by space.
- One saturated green as the primary and positive colour; magenta and yellow as the second and
  third series colours; red only for loss, failure and danger.
- Pill-shaped tabs and range toggles; the active one filled with the primary colour.
- A narrow icon rail for navigation, the current area a filled circle.
- Large figures for balances and totals, with a small chip beside each showing the change and its
  direction.
- Charts as the inspiration draws them: line and candle series on a faint grid, donut breakdowns
  with floating labels, a dot grid for activity by hour; and a world map in the same palette.
- A light theme is offered and kept equal in contrast; dark is the default.

The density of an operator's screen outranks the inspiration's spaciousness: tables, timelines and
forms keep the inspiration's colours, radii and type, at the density FR-020 needs.

### Key Entities

- **Worklist**: an operator's open items across the kinds FR-016 names, in one brand, oldest first;
  read from the owning services' queues, never stored by the backoffice.
- **Worklist item**: one item to act on: its kind, its subject, its age and, once acted on, who did
  what.
- **Timeline**: a player's events from every owning service in time order, each with its actor and
  cause, and the sources that could not be read.
- **Reference**: anything an operator pastes to find a record; resolved by kind to every match.
- **Adjustment batch**: one reason and cause applied as one adjustment per row; its rows' outcomes.
- **Operator preferences**: theme, last brand, worklist kinds, online notices; per operator, in the
  `backoffice` project.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every capability in [parity.md](parity.md) not marked dropped or moved is
  reachable, by the roles the owning service admits, before the phase that lists it ends.
- **SC-002**: An operator finds a player from any of the reference kinds of FR-019 in under ten
  seconds and one search, in nine of ten tries in a moderated test with three operators.
- **SC-003**: A finance operator reviews and approves a withdrawal request in under one minute
  without leaving its page, using only what the page shows.
- **SC-004**: The three investigations original spec 258 names ("why was this withdrawal capped",
  "where did this reward go", "is this a bug") are answered from the backoffice alone, without an
  engineer, in a test on seeded players.
- **SC-005**: No list drops a row: for a brand seeded with 10,000 players, 1,000 rewards and 5,000
  withdrawal requests, every list's total equals its source's count and every row is reachable.
- **SC-006**: No action is offered and then refused for a role: for each role, every action the
  backoffice offers is admitted by the owning service under test.
- **SC-007**: Every amount on every screen matches its record's minor units in a sweep of seeded
  amounts in currencies with zero, two and three minor units.
- **SC-008**: Every screen passes an automated WCAG 2.2 AA check with no serious findings.

## Changes from the original

- **Worklist first**: the first screen is the operator's open work, not the dashboard; the
  dashboard is one step away.
- **Investigation as one view**: one search for any reference; one timeline from every source, with
  causes and gaps; drill-down from round to bet, deposit to payment, reward to record.
- **Brand, not hierarchy, for casino work**: the brand comes from the operator's roles; the
  holding, company and sub-brand picker and its impersonation banner are gone from casino work and
  stay only in payments administration, where operator levels are spec 004's.
- **Role checks from the contract**: one source for what each role may do, so screen and service
  cannot drift; `casino_admin` is gone, and `brand_finance` becomes a realm role that confers what
  it is checked for.
- **Spec 135's roles in the services** (FR-008): a read-only `brand_support`; adjustments,
  standing and withdrawal review restricted, where the original's backend admitted any operator of
  the brand. This amends specs 001 (realm roles), 002 (adjustments), 007 (withdrawal review), 008
  (account standing), 009 and 010 (identity and responsible gambling) and 019 (role mapping at
  cutover).
- **The sign-in stays on the server**: the original kept the token in the page.
- **Lists paged by the services, newest first, with totals and freshness**: the capped, oldest
  first, browser-filtered lists are gone.
- **One money rule**: amounts from minor units by the currency, requested, held, paid and voided
  shown apart.
- **Actions by state, with effects and reasons**: forfeit and reclaim explained, refusals with
  their reasons, debits refused rather than clamped.
- **Adjustments carry a cause and can be batched**: one per row, under its own key.
- **Withdrawal request page with its whole story**, and every state in a player's list.
- **Identity documents served only to admitted operators**: the original's document viewer fetched
  any address it was given, unauthenticated.
- **Review queues first-in first-out, keeping the operator's place.**
- **Jobs for long work**, with a notice wherever the operator is.
- **A new look** from the inspiration, light and dark.
- **Kept though unreachable in the original**: CRM configuration and affiliate adjustments, each
  now a screen.
- **Strings in one catalogue**: English at delivery, translatable without changing a screen.
- **Dropped**: the casino content screens (FR-043); the code that had no route in the original
  (the operators twin page, the affiliate portal calls); the build-time content flag and the
  reporting tab's feature flag.
- **Not carried over**: the original's generic twin reads for lists; the browser-side CSV; the
  theme store in its own database.

## Recordings

The original's Playwright suite is the acceptance test of the original frontend against a rebuilt
brand (spec 001 FR-033) and is not this spec's: this backoffice replaces that frontend. Parity here
is [parity.md](parity.md): each capability becomes at least one scenario in `features/backoffice/`,
run against a brand on a local installation, and the original's screens are recorded (one capture
per screen, per role) as the *before* that the plan's design is compared with.

## Assumptions

- The owning services keep their operator routes as their specs define them; this spec adds
  routes to them only where [parity.md](parity.md) marks a gap, each as an amendment to that
  service's spec.
- ankka's web hosting (ankka 021, in every release from 0.11.0) passes the operator's
  `Authorization` header unchanged on calls the backoffice makes to services of other projects, and
  the services' `Oidc.authenticate()` admits such a call by the token whoever the calling service is.
  The first task of phase 1 proves this on a local installation; if it does not hold, it is an
  ankka request, not a workaround.
- Operators keep signing in at the `eitheror` realm; the backoffice is a new client of that realm.
- The brand list of the original's operators maps to `brand_<slug>` roles as spec 001 lays them out.
- Long work runs as jobs in the owning services where they have them (exports, reports); a batch of
  adjustments is the backoffice's own sequence of single adjustments and holds no state beyond the
  operator's session; if it is interrupted, repeating it makes nothing twice (FR-028).

## Dependencies

- Constitution amendment 1.3.0 (*Context*).
- Amendments to specs 001, 002, 007, 008, 009, 010 and 019 for the role matrix (FR-008, FR-008a).
- Specs 001, 002, 004, 007, 008, 009, 010, 011, 013, 014, 015, 018, 021 and 026 for the routes
  behind each screen; spec 028 for where the programme lives.
- ankka web hosting (021) and OIDC sign-in (022) at the pinned version.

## Out of scope

- Content editing and the choice of content system (decided after this spec is drafted).
- The player site, the affiliate portal and the vendor's console.
- An operator assistant inside the backoffice (the original's spec 258 phase 2): a later spec, once
  the investigation view shows what it still cannot answer.
- Retiring the original backoffice, which ends with spec 019.

## Open Questions

- None open. The *decide* rows of [parity.md](parity.md) were settled on 2026-11-15: both kept.
