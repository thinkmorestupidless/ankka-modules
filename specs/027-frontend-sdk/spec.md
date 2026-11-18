# Feature Specification: the player SDK — a site server, behaviours and a reference player site

**Feature Branch**: `027-frontend-sdk`

**Created**: 2026-11-18

**Status**: Draft

**Input**: User description: "Spec 027: the frontend SDK, first slice. A TypeScript SDK in
ankka-modules that lets someone build a player-facing UI on the modules by composing behaviours and
supplying only look and feel (the shape of ui-sdk.dev, one layer up: with data, auth and wiring).
Layers: a client interface per module, hand-written first and ported from the original Spinvibe
player site, later replaced by a client generated from each service's OpenAPI document (ankka 067);
a runtime (sign-in at the brand's realm, refresh, idempotency keys, the notification stream, money
in minor units, languages); framework-free behaviours as flow state machines with the mandatory
responsible-gambling and KYC steps locked in the machine so a UI cannot skip them (Sheldon agreed),
the first two being deposit and guarded play; a React-only adapter; a reference player site run as
an ankka web-hosted service for the new brand `guyanabet` (modules casino, engagement, support).
Players first; backoffice later."

## Context

Everything a player does at a brand is served by the brand's services (specs 002 to 017), and no
part of this repository serves a browser: the constitution put the player site out of scope, to
"change only base URLs". That held while the only site was the original's. It does not hold for a
second brand, or for anyone outside eitheror building on the modules: the original site is one
brand's appearance welded to the logic of depositing, playing and staying signed in, and that logic
is where a site goes wrong. A site that forgets the deposit gate, reads a balance as a decimal, or
lets a player launch during a break is a regulatory failure, however it looks.

This spec separates the two. The **player SDK** holds the logic and none of the appearance:

- a **module client** per module: the calls a player's page makes of that module's services, typed,
  one function per route, written by hand in this spec from the routes the services serve;
- a **site server**: the part of a player site that runs beside the brand's services, holds each
  player's sign-in where the page's scripts cannot read it, and makes every call for the page;
- **behaviours**: a player's task as steps and the actions each step allows — the session, the
  deposit, play, the inbox signal — with no appearance, bound to no interface library;
- an **adapter** that binds a behaviour to React;
- the rules every behaviour shares: amounts as whole minor units, one form of refusal, the page's
  language.

A site builder composes behaviours and supplies the appearance. What must not be skipped cannot be:
a deposit exists only after the brand's own checks (the deposit gate, the deposit limit, a break),
a launch is the brand's to refuse (the play gate, a break, the session limit), and a behaviour has
no action that goes round a refusal.

The spec proves the SDK with a **reference site**: a small player site of eitheror's own, run as a
web-hosted service in a new module `player-site`, for a new brand, `guyanabet`, serving French
first and English second.

What was read, 2026-11-18: the original site's deposit (`DepositModal.tsx`, twelve steps, completion
by polling), its guard (`useGuardedPlay`: signed in, then funds), its launch and timers
(`GameManagerContext`), its session routes and cookies, its notification stream, and its tests; and
every player route of `players`, `wallet`, `game-provider`, `kyc`, `responsible-gambling`,
`engagement` and `support-agent` here. `packages/cashier/CashierWidget.tsx` is not used by the
original site and is no source for this spec.

Four decisions of 2026-11-18 bind it. The sign-in lives on the site server, as on the original site
and in the backoffice (spec 030). A deposit starts at `players`' deposit route, amended to answer
what the cashier session offers, and never at the cashier directly. `guyanabet` is for French
Guiana: French first, English second, euro. The descriptions of the services' routes (ankka 067)
and the client generated from them are a later spec; the module client's interface is the seam.

## Clarifications

### Session 2026-11-18

- Q: Where does a signed-in player's sign-in live? → A: On the site server, in cookies the page's
  scripts cannot read, as on the original site; the SDK ships the site server and a site must run
  it (FR-001 to FR-008).
- Q: Which route starts a deposit? → A: `players`' deposit route, amended to answer the cashier
  ticket and the terminals (FR-018, *Amendments*), so every deposit passes the deposit gate and the
  responsible-gambling check before a cashier session exists.
- Q: What market is `guyanabet` for? → A: French Guiana: French first, English second (FR-043).
- Q: Where do the services' route descriptions and the generated client go? → A: A later spec, once
  an ankka release holds spec 067; this spec's module clients are written by hand behind the
  interface a generated one will meet (FR-034).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player's sign-in is held by the site server (Priority: P1)

A player registers, verifies their email address, and signs in at a player site. The site server
passes the sign-in to the brand, keeps what the realm issued where no script in the page can read
it, and from then on makes every call for the page under it. When the sign-in lapses the site
server refreshes it once, however many calls were waiting, and makes them again; when it cannot be
refreshed the player is signed out and the page is told. A page loaded by a signed-in player knows
who they are, their currency and their country before it asks anything. A player idle for thirty
minutes is warned and then signed out; a player placing bets is not idle.

**Why this priority**: every other behaviour runs under the sign-in. A sign-in a script can read is
the one fault that exposes every player of a site at once.

**Independent Test**: run the site server against doubles of `players`; register, sign in, let the
sign-in lapse, refresh, sign out; read every response and the page's storage for the sign-in.

**Acceptance Scenarios**:

- added `features/player-site/session.feature`: a player who signs in at the player site is signed in at the brand and the page is never given the sign-in
- added `features/player-site/session.feature`: a sign-in the brand refuses puts the session behaviour at refused with the brand's code and fields
- added `features/player-site/session.feature`: calls refused because the sign-in lapsed refresh it once and are each made once more
- added `features/player-site/session.feature`: a sign-in the realm will not refresh signs the player out and the page is told why
- added `features/player-site/session.feature`: a refresh that fails for another cause keeps the sign-in
- added `features/player-site/session.feature`: a player who signs out ends the player session and the site server keeps nothing of the sign-in
- added `features/player-site/session.feature`: an idle player is warned and then signed out
- added `features/player-site/session.feature`: a warned player who chooses to stay has their sign-in refreshed
- added `features/player-site/session.feature`: a player who placed a bet in the last six minutes is not idle
- added `features/player-site/session.feature`: a visitor who registers is told to verify their email address and is not signed in
- added `features/player-site/session.feature`: a page loaded by a signed-in player knows the player before it asks anything
- added `features/player-site/session.feature`: a request for anything the player SDK does not name is refused by the site server
- added `features/player-site/session.feature`: a site server refuses a request naming another brand

---

### User Story 2 - A player deposits through the deposit behaviour (Priority: P1)

A signed-in player opens the deposit. The behaviour stands at the amount, offering the site's
preset amounts in the player's currency. Confirming an amount asks the brand to start the deposit:
the brand asks the deposit gate, the deposit limit and whether the player is cooling off, and only
then opens a cashier session. A refusal is a step of its own that names the reason and what
remains; nothing in the behaviour goes past it. Otherwise the player picks one of the terminals the
cashier session offers, supplies any details the terminal needs and their profile lacks, and the
session is submitted. The payment then waits on the player at the payment provider, or shows a
crypto address, until it is captured, fails, expires, or twenty minutes pass. A captured payment
ends the deposit and the balance is read again.

**Why this priority**: a deposit is the first money a brand sees, and the original site reached the
cashier without the brand's checks. Moving it behind them is the point of the SDK.

**Independent Test**: run the deposit behaviour with no interface against doubles of `players` and
the cashier, driving each step and each refusal; then against the running brand with the payment
provider stub.

**Acceptance Scenarios**:

- added `features/player-site/deposit.feature`: a deposit opens at the amount with the site's preset amounts in the player's currency
- added `features/player-site/deposit.feature`: confirming an amount has the brand start the deposit and offers the cashier session's terminals
- added `features/player-site/deposit.feature`: a deposit the brand refuses stands at refused naming the reason and no cashier session is opened
- added `features/player-site/deposit.feature`: a deposit the cashier refuses stands at refused with the cashier's code
- added `features/player-site/deposit.feature`: going back from a refused deposit returns to the amount
- added `features/player-site/deposit.feature`: a cashier session offering no terminal ends the deposit as failed
- added `features/player-site/deposit.feature`: choosing a terminal whose details the player profile holds submits the cashier session at once
- added `features/player-site/deposit.feature`: choosing a terminal whose scheme needs details the player profile lacks asks for them, saves them and submits with them
- added `features/player-site/deposit.feature`: a terminal of a scheme that needs no details submits at once
- added `features/player-site/deposit.feature`: a crypto terminal asks for the coin and then shows where to pay
- added `features/player-site/deposit.feature`: a payment awaiting the player at the payment provider gives the page the provider's address and waits
- added `features/player-site/deposit.feature`: a captured payment ends the deposit as succeeded and the balance is read again
- added `features/player-site/deposit.feature`: a payment that fails or expires ends the deposit as failed with the payment's reason
- added `features/player-site/deposit.feature`: a payment with no outcome in twenty minutes ends the deposit as timed out
- added `features/player-site/deposit.feature`: a payment read that fails while waiting is ignored
- added `features/player-site/deposit.feature`: closing a waiting deposit abandons the cashier session
- added `features/player-site/deposit.feature`: an action the step does not allow sends nothing and is answered as not allowed
- added `features/player-site/deposit.feature`: an amount confirmed twice starts one deposit
- added `features/player-site/deposit.feature`: a visitor arriving at the site's deposit address is asked to sign in and the deposit opens once they have
- added `features/players/deposit-start.feature`: a started deposit answers the cashier ticket and the terminals of the cashier session it opened
- added `features/players/deposit-start.feature`: a deposit the deposit gate refuses opens no cashier session and answers no cashier ticket

---

### User Story 3 - A player launches a game only when the brand allows it (Priority: P1)

A player picks a game. A visitor is asked to register; a signed-in player with no playable funds is
taken to the deposit; otherwise the game is launched. The brand may refuse the launch — the play
gate, a break, the session limit, a restricted player, the rate limit — and each refusal is a step
that names the reason. A launched game is shown from the address the brand answered, under a game
session that ends when the player leaves. Where the player has set a reality check, the game is
paused at each interval until they acknowledge it or stop. A launch in fun mode needs a sign-in and
no funds.

**Why this priority**: play is the product, and every one of its refusals is a protection the brand
is licensed to enforce.

**Independent Test**: run the play behaviour with no interface against doubles of the wallet,
`game-provider` and `responsible-gambling`; then launch a house provider game in fun mode and for
money at the running brand.

**Acceptance Scenarios**:

- added `features/player-site/play.feature`: a visitor who picks a game is asked to register
- added `features/player-site/play.feature`: a signed-in player with no playable funds is taken to the deposit
- added `features/player-site/play.feature`: playable funds are a real balance, a restricted balance or free rounds for the game
- added `features/player-site/play.feature`: balances not yet read count as playable funds
- added `features/player-site/play.feature`: a launch the brand answers stands at playing with the game's address and the game session
- added `features/player-site/play.feature`: a launch the brand refuses stands at refused naming the reason and the page is given no game address
- added `features/player-site/play.feature`: a launch in fun mode needs a sign-in and no funds
- added `features/player-site/play.feature`: a visitor who launches a game in fun mode is asked to sign in
- added `features/player-site/play.feature`: a game not offered in the player's country is not shown and its game session is ended
- added `features/player-site/play.feature`: a player of unknown country is not blocked by territory
- added `features/player-site/play.feature`: leaving a game ends its game session
- added `features/player-site/play.feature`: a reality check falls due after its interval of the game being shown
- added `features/player-site/play.feature`: keeping on at a reality check records the acknowledgement and schedules the next
- added `features/player-site/play.feature`: stopping at a reality check ends the game session
- added `features/player-site/play.feature`: no reality check fires when none is set or it could not be read
- added `features/player-site/play.feature`: a reality check that falls due while the game is hidden fires when the game is shown again

---

### User Story 4 - A site builder composes behaviours and supplies only the appearance (Priority: P2)

Someone building a player site installs the player SDK, runs the site server with the brand's name
and the addresses it is given, and binds each behaviour to their own components through the
adapter: the component is told the step the behaviour stands at and the actions that step allows,
and calls them. Two sites that look nothing alike run the same deposit. A behaviour reaches the
brand only through the module clients, so a client generated later from a service's own
description replaces a hand-written one without a behaviour changing.

**Why this priority**: the saving is the next site, and the safety is that the next site cannot
get the logic wrong.

**Independent Test**: bind the deposit to two different sets of components and to none; replace a
module client with a double and with a second implementation of its interface.

**Acceptance Scenarios**:

- added `features/player-site/composing.feature`: a behaviour run with no appearance takes every step it takes at a player site
- added `features/player-site/composing.feature`: two sites of different appearance run the same deposit
- added `features/player-site/composing.feature`: a component bound to a behaviour is told each step and the actions it allows
- added `features/player-site/composing.feature`: a site builder configures the brand, its languages and its preset amounts and no address of a service
- added `features/player-site/composing.feature`: replacing a module client with another of the same interface changes no behaviour
- added `features/player-site/composing.feature`: a behaviour makes no call of the brand except through a module client
- added `features/player-site/composing.feature`: a site offers only the behaviours of the modules its brand runs

---

### User Story 5 - Amounts, refusals and language are one rule each (Priority: P2)

Every amount a behaviour gives the page is a whole number of minor units with its currency, whatever
the service wrote: the wallet's balance, written in major units as text, is read exactly, never
through a decimal. The page formats an amount by its language and the currency's own exponent.
Every refusal reaches the page in one form — a code, the status, and the named fields — whichever
of the services' three forms it was written in, and the page maps the code to its own words. The
page's language is the visitor's choice, else the first of the browser's languages the site has,
else the site's first.

**Why this priority**: these are the three places the original site was inconsistent (major units
with ad hoc formatting, a parser per error shape, hard-coded fallbacks), and each is a way to show
a player a wrong number or no reason.

**Independent Test**: tables of amounts, currencies, languages and recorded refusals through the
rules alone.

**Acceptance Scenarios**:

- added `features/player-site/amounts-and-refusals.feature`: a balance the wallet writes in major units is read exactly as minor units
- added `features/player-site/amounts-and-refusals.feature`: a balance that does not fit its currency is refused as malformed and never rounded
- added `features/player-site/amounts-and-refusals.feature`: an amount is shown by the page's language and the currency's own decimals
- added `features/player-site/amounts-and-refusals.feature`: a refusal reaches the page in one form whichever service wrote it
- added `features/player-site/amounts-and-refusals.feature`: a refusal the platform writes as a code and an explanation is read as its code
- added `features/player-site/amounts-and-refusals.feature`: a refusal with a code the player SDK does not know is the unknown refusal and the service's text is not the player's
- added `features/player-site/amounts-and-refusals.feature`: every refusal code a behaviour can meet has words in each language the reference site offers
- added `features/player-site/amounts-and-refusals.feature`: the page's language is the visitor's choice, else the browser's, else the site's first
- added `features/player-site/amounts-and-refusals.feature`: a text missing in a language is shown in the site's first language

---

### User Story 6 - The page is told when the inbox changes (Priority: P2)

A signed-in page holds a push connection through the site server. A push makes the page read the
unread count again. A connection the brand closes is reopened after a wait that doubles to thirty
seconds; a page that becomes visible again reads without waiting for a push; signing out closes the
connection.

**Why this priority**: the brand runs the engagement module, and a push connection that carries the
sign-in is the one stream a page cannot open by itself once the sign-in is on the site server.

**Independent Test**: the inbox behaviour against a double that sends pushes, heartbeats and each
closing reason.

**Acceptance Scenarios**:

- added `features/player-site/inbox.feature`: a signed-in page holds a push connection through the site server
- added `features/player-site/inbox.feature`: a push makes the page read the unread count again
- added `features/player-site/inbox.feature`: a push connection the brand closes is reopened after a wait that doubles to thirty seconds
- added `features/player-site/inbox.feature`: a push connection that opens resets the wait
- added `features/player-site/inbox.feature`: a page that becomes visible reads the unread count without waiting for a push
- added `features/player-site/inbox.feature`: signing out closes the push connection and it is not reopened

---

### User Story 7 - Brand guyanabet runs a reference site built on the SDK (Priority: P2)

eitheror adds a brand, `guyanabet`, with a values file naming the modules core, casino, engagement,
support and the new module `player-site`, whose one service is the reference site: the SDK's site
server and a small set of pages with eitheror's own appearance. The site is served at the brand's
player site address, in French first and English second. A visitor registers, verifies, signs in,
deposits through the payment provider stub, launches a house provider game for money and in fun
mode, sees a reality check, and signs out, on the development installation.

**Why this priority**: an SDK with no site built on it is unproven, and the brand is the first
that is not the original's.

**Independent Test**: `just modules render guyanabet`; apply it to the development installation;
run the reference site's features against it.

**Acceptance Scenarios**:

- added `features/player-site/reference-site.feature`: brand guyanabet runs the core, the casino, engagement, support and the player site
- added `features/player-site/reference-site.feature`: the reference site answers at the brand's player site address in French and in English
- added `features/player-site/reference-site.feature`: a visitor registers, signs in, deposits and plays at the reference site
- added `features/player-site/reference-site.feature`: the reference site's pages call no service and hold no rule of a deposit or a launch
- added `features/player-site/reference-site.feature`: a page of an old build finds its files during a rollout
- added `features/player-site/reference-site.feature`: a reference site without the casino module is served without play

---

### Edge Cases

- A service that does not answer the site server within the bound: the behaviour's step is refused
  as unavailable naming the service, and the player may try again; no call that moves money is made
  again by itself.
- The confirm of an amount pressed twice: one start is sent; the second press is an action the
  step no longer allows.
- A deposit's provider page blocked from opening by the browser: the behaviour still stands at
  waiting with the address, and the page may offer it as a link.
- The player returns from the payment provider in a second tab: the return page tells the first tab,
  which closes the provider's tab; the outcome still comes only from reading the payment.
- A payment read that fails while waiting: ignored until the twenty minutes pass.
- A crypto address past its expiry with no outcome: shown as expired; the step does not change
  until the payment says so.
- Two tabs of one player refreshing at once: each tab refreshes once; the realm's rotation may
  refuse the second, which then reads the session again before signing the player out.
- The sign-in refreshed while a push connection is open: the connection is closed by the brand's
  re-check or kept; either way the behaviour reopens it under the new sign-in.
- A launch whose game session cannot be ended because the page is closing: the end is sent so that
  it outlives the page.
- A reality check that falls due while the game is hidden: it fires when the game is shown again.
- A wallet balance with more decimals than the currency's exponent, or not a number: refused as a
  malformed amount; the page shows no balance, never a rounded one.
- A language catalogue missing a key: the site's first language's text is shown.
- A brand whose values name `player-site` and not `casino`: the site is served without play.

## Requirements *(mandatory)*

### Functional Requirements

**The site server and the sign-in**

- **FR-001**: The player SDK MUST include a site server: a program part a player site runs beside
  the brand's services, through which the page makes every call. A page MUST NOT be given the
  address of any service.
- **FR-002**: On sign-in the site server MUST pass the email address, password and device
  fingerprint to `players`' sign-in route with the visitor's network address and user agent, and
  keep what is issued only in cookies that the page's scripts cannot read, are sent only over a
  secure connection, and are never sent to another site. *Established by:* the original's
  `api/auth/login/route.ts` and its `eo_session`, `eo_refresh` cookies.
- **FR-003**: The sign-in MUST NOT appear in any response to the page, in page storage, in a log or
  in a trace.
- **FR-004**: The site server MUST add the sign-in to each call it makes for the page, and MUST
  serve only the routes the module clients name, each with its method; any other request is
  refused without a service being asked.
- **FR-005**: When a call is refused because the sign-in lapsed, the SDK MUST refresh the sign-in
  once, shared by every call waiting in that page, and make each call once more; a refresh the realm
  refuses MUST sign the player out and tell the page the reason; a refresh that failed for another
  cause MUST keep the sign-in and report the service unavailable. *Established by:*
  `fetchWithRefresh.ts`, `api/auth/refresh/route.ts`.
- **FR-006**: On sign-out the site server MUST ask `players` to end the player session and MUST
  remove every cookie it set.
- **FR-007**: A page loaded under a sign-in MUST be told the player id, the brand, the currency and
  the country without a call to a service. The site server MUST NOT trust these for anything a
  service decides.
- **FR-008**: The site server MUST serve one brand, named in its configuration, and refuse a
  request naming another.
- **FR-009**: The session behaviour MUST offer registration (answering that the email address must
  be verified, never a sign-in), email verification, resending the verification, sign-in, sign-out,
  and the password reset request; each refusal of `players` is a refused step with the brand's code
  and fields (spec 008).
- **FR-010**: The session behaviour MUST warn a signed-in player after thirty minutes less thirty
  seconds without a key press, a click or a scroll, and sign them out thirty seconds later unless
  they choose to stay, which refreshes the sign-in. Both times are the site's configuration, with
  these defaults. *Established by:* `ClientShell.tsx`.
- **FR-011**: While a game is shown, every five minutes the play behaviour MUST read the game
  session and, where its last bet was within six minutes, refresh the sign-in and count the player
  as not idle. *Established by:* `GameManagerContext.tsx`.

**The deposit behaviour**

- **FR-012**: The deposit behaviour's steps MUST be: amount; starting; refused; choosing a
  terminal; choosing a coin; details; submitting; waiting at the payment provider; waiting on a
  crypto payment; waiting; succeeded; failed. Each step names the actions it allows, and an action
  it does not allow MUST send nothing and be answered as not allowed. *Established by:* the `Step`
  type of `DepositModal.tsx`.
- **FR-013**: At the amount step the behaviour MUST offer the site's preset amounts for the
  player's currency (default 10, 25, 50, 100, 200 and 500 major units, kept within the site's
  minimum and maximum where it states them) and accept any whole amount of minor units above zero.
- **FR-014**: Confirming an amount MUST call `players`' deposit start once with the amount in minor
  units and the player's currency, under a request id the behaviour mints for that confirmation.
- **FR-015**: A refusal of the start MUST put the behaviour at the refused step with the code and
  fields the brand answered: the deposit gate with the KYC status; a deposit limit with its period,
  its value and what remains; cooling off with the end of the break; a self-exclusion; a restricted
  player with the status; a refusal of the cashier with the cashier's code; a service that did not
  answer with its name. The refused step allows going back to the amount and closing, and nothing else.
- **FR-016**: The behaviour MUST NOT check a limit or a gate itself in place of the brand, and MUST
  hold no route by which a cashier session is opened except `players`' deposit start.
- **FR-017**: An answered start MUST put the behaviour at choosing a terminal with the terminals the
  cashier session offers; with none, at failed for want of a way to pay.
- **FR-018**: `players`' deposit start MUST answer, beside what it answers today, the cashier
  ticket and the terminals of the cashier session it opened (spec 008 FR-043, amended).
- **FR-019**: Choosing a terminal MUST follow the scheme's needs: a crypto scheme asks for the coin;
  a scheme that needs the player's name, telephone, address, or mobile number and province reads
  the player profile and, where a needed field is missing or malformed, asks for it at the details
  step, saves it to the profile, and submits with it; any other scheme submits at once. The table
  of schemes and needs is the original's. *Established by:* `handleTerminalSelect`,
  `handleDetailsSubmit`.
- **FR-020**: Submitting MUST send the cashier session's submit once under the cashier ticket, with
  the terminal, the coin and the details, and then read the payment until its outcome: every 1.5
  seconds until it awaits the player, then every 3 seconds, for at most 20 minutes.
- **FR-021**: A payment awaiting the player with an address MUST put the behaviour at waiting at the
  payment provider with that address; one awaiting a crypto transfer, at waiting on a crypto payment
  with the address, the amount, the coin and the expiry; one awaiting payment, at waiting.
- **FR-022**: A captured payment MUST end the behaviour as succeeded and cause the balance to be
  read; a failed or expired one, as failed with the payment's reason; twenty minutes with no
  outcome, as failed as timed out. A read that fails is ignored until then.
- **FR-023**: Closing the deposit at a waiting step MUST abandon the cashier session under its
  ticket, without waiting for the answer.
- **FR-024**: The cashier ticket MUST be held by the site server and the page that opened the
  deposit only, and MUST NOT be written to page storage that outlives the page.
- **FR-025**: The SDK MUST provide the deposit address rule: a visitor arriving at the site's
  deposit address is asked to sign in and the deposit opens once they have; a signed-in player's
  deposit opens at once. *Established by:* `DepositParamWatcher.tsx`, which the game provider's
  own deposit button depends on.

**Balances and playable funds**

- **FR-026**: The SDK MUST read the player's balance (real balance, restricted balance, and the
  free rounds they hold) on sign-in, after a deposit succeeds, when asked, and every 5 seconds
  while the page is visible, and tell every behaviour bound to it. *Established by:*
  `ClientShell.refreshWallet`.
- **FR-027**: A player has playable funds for a game when their real balance is not zero, or their
  restricted balance is not zero, or they hold free rounds for that game; balances not yet read
  count as playable funds. *Established by:* `hasNoPlayableFunds` and its tests.

**The play behaviour**

- **FR-028**: Picking a game MUST, in this order: ask a visitor to register; open the deposit for a
  signed-in player with no playable funds for the game; otherwise launch. A launch in fun mode
  needs the sign-in and skips the funds rule. *Established by:* `useGuardedPlay.ts`,
  `GameLaunchModal.tsx`.
- **FR-029**: A launch MUST call `game-provider`'s launch once, with the game, the player's currency
  and whether it is fun mode, and on an answer put the behaviour at playing with the game's address
  and the game session.
- **FR-030**: A refusal of the launch MUST put the behaviour at the refused step with the code and
  fields the brand answered: the play gate with the KYC status; cooling off with the end of the
  break; the session limit with the limit and the minutes used; a restricted player with the
  status; the rate limit with the wait; an unavailable service with its name. The behaviour MUST
  hold no route to a game's address except the launch's answer.
- **FR-031**: Where the catalogue says the game is not offered in the player's country, the
  behaviour MUST not give the page the game's address and MUST end any game session opened for it;
  an unknown country or an unreadable catalogue blocks nothing. *Established by:*
  `GameManagerContext.openGame`.
- **FR-032**: The behaviour MUST end the game session, with the player's leaving as the reason,
  when the game is closed, when the page is left, and on sign-out, sent so that it outlives the
  page.
- **FR-033**: Where the player's responsible-gambling status states a reality check interval, the
  behaviour MUST stand at the reality check once the game has been shown for that interval, with
  the time played; keeping on MUST record the acknowledgement against the game session and schedule
  the next; stopping MUST end the game session. No interval, or a status that could not be read,
  fires none. *Established by:* `GameManagerContext.tsx`.

**Module clients**

- **FR-034**: Every call a behaviour or the site server makes of a service MUST go through a module
  client: per module, an interface of typed functions, one per route, and one implementation
  written by hand in this spec. A behaviour MUST depend on the interface only, so that a client
  generated from a service's own description (a later spec) replaces the implementation and no
  behaviour changes.
- **FR-035**: This spec's module clients MUST cover the routes its behaviours call and no others:
  in core, `players` (registration, verification, sign-in, refresh, sign-out, password reset
  request, profile read and change, deposit start, unread count, game session read and end, the
  visitor's country), `wallet` (balance), `responsible-gambling` (status, reality check
  acknowledgement), the cashier's submit, abandon and payment read; in casino, `game-provider`
  (launch, the catalogue's games and one game) and `rewards` (free rounds held); in engagement, the
  push connection.
- **FR-036**: Each module client MUST be tested against its running service on the development
  installation, one test per function, so that a route's change is caught where the client is.

**Amounts, refusals, language**

- **FR-037**: Every amount the SDK gives a page MUST be a whole number of minor units with its
  currency. An amount a service writes in major units as text MUST be read exactly by the
  currency's exponent from the contract's currency table, and one that does not fit MUST be refused
  as malformed; no amount passes through a floating-point number.
- **FR-038**: The SDK MUST format an amount for a language from its minor units, its currency's
  exponent and that language's rules, and offer no other way to show one.
- **FR-039**: Every refusal MUST reach a behaviour as one form: the status, a code, and the named
  fields; read from each of the three forms the services write (the platform's, a handler's with a
  message, a handler's without). A code is the service's own, with the platform's
  `code: explanation` text split at the colon. A refusal with no code the SDK knows is the unknown
  refusal; a service's own text is never the player's.
- **FR-040**: The SDK MUST name every refusal code its behaviours can meet, so that a site's
  catalogue of words can be checked complete for each language it offers.
- **FR-041**: The page's language MUST be the visitor's kept choice; else the first of the browser's
  languages the site offers; else the site's first language. A text missing in a language is shown
  in the site's first.
- **FR-042**: The SDK's behaviours MUST contain no text for a player: only codes, steps, amounts and
  fields.

**The adapter, the reference site and the brand**

- **FR-043**: The brand `guyanabet` MUST exist as a values file naming the modules core, casino,
  engagement, support and player-site, with real money off, French first and English second, and
  the euro as its players' currency.
- **FR-044**: An adapter MUST bind each behaviour to React: a component is told the step, the
  step's fields and the actions it allows, and is told again on each change. The adapter MUST add
  no rule of its own.
- **FR-045**: A module `player-site` MUST hold one web-hosted service, the reference site: the
  SDK's site server and pages for registration, sign-in, the lobby from the catalogue, the balance,
  the deposit, play with the reality check, and the unread count; exposed at the brand's player
  site address; with built files named by their content and the first page never cached.
- **FR-046**: The reference site MUST hold no logic of a deposit, a launch, a sign-in or an amount
  outside the SDK: its own code is appearance, words and the choice of which behaviour to show.
- **FR-047**: A brand's site MUST offer only the behaviours of the modules the brand runs.
- **FR-048**: The SDK and the reference site are TypeScript (constitution VI): they speak to a
  browser and to services over HTTP, hold no state that outlives an instance, and move no money
  themselves.

### Changes from the original

- **A deposit goes through the brand.** The original site opened a cashier session at the shared
  payments itself, under the brand's cashier key, with the customer id and country it chose, after a
  check of the limits in the page that passed when the limits could not be read; the deposit gate
  was never asked. Here the page asks `players`, which asks the deposit gate, the wallet's sums and
  responsible gambling and then opens the session with the player's own id and profile (spec 008
  FR-043). The page's own limit check is dropped: the brand's answer is the check.
- **Amounts are minor units in the page.** The original's page held major units as numbers and
  converted at the cashier with a multiplication by 100.
- **One refusal form.** The original parsed each service's errors where it met them, some by
  searching the text.
- **Languages are the site's.** The original offered English, German, French and Portuguese from one
  file of strings in the site; the SDK holds no strings and a site offers what it translates.
- **Not carried in this slice**, and still the original's to serve where a brand needs them:
  withdrawals and destinations, KYC documents, limits and breaks as the player sets them, rewards
  and bonus codes, the inbox's pages, the support chat, account pages, activity history, favourites,
  content pages, analytics and the affiliate click. Each is a behaviour for a later spec.
- **Dropped**: the original's `packages/cashier` widget (a demonstration against a simulator); the
  page opening the deposit by itself five seconds after sign-in when the player has no funds (an
  appearance choice a site may make with the funds rule); the launch route's handling of a second
  open game session, which `game-provider` here does not refuse.

### Amendments to other specs

- **Spec 008 (players) FR-043**: the deposit start's answer gains the cashier ticket and the
  terminals of the session it opened.
- **Spec 021 (core and modules)**: a module `player-site`; a configuration key for the site's
  languages.
- **Spec 001 (brands and projects)**: a second brand's values file, `guyanabet`.
- **Constitution, Scope**: the player site was out of scope, to change only base URLs. It is in
  scope as the player SDK and a reference site; a site built on the SDK holds no rule of a service
  and reaches a service only as the signed-in player. The affiliate portal stays out of scope.

### Key Entities

- **Player SDK**: what a site builder installs: module clients, the site server, behaviours, the
  adapter, and the rules for amounts, refusals and language.
- **Module client**: one module's player routes as typed functions behind an interface.
- **Site server**: the part of a player site that holds each player's sign-in and makes the page's
  calls; it serves one brand and keeps nothing between instances.
- **Behaviour**: a player's task as steps and allowed actions: session, deposit, play, inbox.
- **Step**: where a behaviour stands, with its fields and the actions it allows.
- **Refusal**: a status, a code and named fields.
- **Amount**: whole minor units and a currency.
- **Reference site**: eitheror's own player site on the SDK, the service of module `player-site`.
- **Brand guyanabet**: a values file; French, English; euro; no real money.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A site builder with the SDK and a running brand has a page on which a player can sign
  in, deposit and launch a game by writing appearance only: the reference site's own code contains
  no call to a service and no rule of a deposit or a launch, checked by a test of its imports.
- **SC-002**: The deposit and play behaviours each run from first step to last with no interface,
  and every step and refusal in this spec is reached in tests that use no browser.
- **SC-003**: No test, at any step of any behaviour, can open a cashier session or obtain a game's
  address after the brand has refused: the count of such paths is zero.
- **SC-004**: After a sign-in, a search of every response to the page, the page's storage, the
  site server's log and its traces finds the sign-in zero times.
- **SC-005**: Every amount shown at the reference site equals the service's amount exactly, for
  every currency in the contract's table, in a test of each.
- **SC-006**: Every refusal code the behaviours can meet has words in French and in English at the
  reference site; a missing one fails the build.
- **SC-007**: On the development installation a visitor registers, verifies, signs in, deposits
  with the payment provider stub and is playing a game within 3 minutes, in French.
- **SC-008**: A second set of components, different in appearance, runs the deposit with no change
  to the SDK.
- **SC-009**: Replacing a hand-written module client with another implementation of its interface
  changes no behaviour's test.
- **SC-010**: `just features` reports 0 findings.

## Assumptions

- French Guiana is the brand's market as the person decided; whether a licence covers real money
  there is not this spec's question, and the brand's values keep real money off.
- The realm's client `casino-frontend` and `players`' password sign-in are used as they are; a
  sign-in by the realm's own pages is not introduced.
- The site server reaches the brand's services at the calling address the platform gives a
  web-hosted service, and the cashier's public routes at the shared payments' public address, as
  `players` does.
- The payment provider stub and the house provider (spec 026) or the simulator (spec 020) are what
  the reference site's end-to-end run uses.
- The SDK's packages are private to this repository in this slice; publishing them for others is
  decided when a site outside the repository needs them.
- Spec 030 (the backoffice) is not merged; where both amend the constitution's *Scope*, the second
  to merge rewords the paragraph to hold both.
- The original's schemes-and-needs table includes schemes no terminal of `guyanabet` offers
  (Interac); it is carried whole, since which terminals a brand offers is the routing tree's.
- A person builds one site per brand; one site serving several brands is out of scope.
