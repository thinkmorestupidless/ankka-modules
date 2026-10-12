# Feature Specification: Game Provider — Launch, Catalogue and the Seamless Wallet Callbacks

**Feature Branch**: `003-game-provider`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The `game-provider` service of a brand's project: the game catalogue
the provider feeds and the brand curates, a game's launch for a player (eligibility, the launch token,
the session), the provider's signed seamless-wallet callbacks (check, balance, player profile, debit,
credit, cancel, buyin, payout), each money callback keyed by the provider's transaction id and
answered with the balance, the round it keeps for every callback, the `casino.rounds` facts it
produces on settlement, the provider calls other brand services make through it (free rounds), and the
stub the original is recorded against."

## Context

Today one game provider, ST8, aggregates every game a brand offers. The original handles it in three
places: `casino-st8-adapter` (the signed callbacks, the token store, the transaction store, the bet
and round twins, the internal registration surface), `casino-service` (the launch route with its
responsible-gambling preflight, the catalogue routes and the nightly catalogue seed, the free-rounds
calls to the provider's bonus API, the per-brand credentials) and `casino-provider-gateway`, a Go
process the chart calls a dev stub, forwarding a generic bet/win/rollback shape to substrate actions
with decimal amounts and no idempotency; its end-to-end tests are skipped as blocked. The build plan
("eitheror on ankka", 2026-10-08) folds the first two into one brand service, `game-provider`, with a
launch-token entity, a bet workflow per provider transaction (idempotent on the transaction id), a
round entity per provider round, views over rounds, and a consumer producing `casino.rounds`.

Everything a provider does with money goes through the wallet (spec 002) over HTTP within the brand
project, keyed by the provider's transaction id. The provider's protocol is decimal strings on the
wire; the boundary converts to minor units (spec 001 FR-026) and back. The callbacks answer HTTP 200
with a `status` word even when refusing, because that is the provider's protocol: `auth_failed`,
`session_expired`, `player_locked`, `player_not_found`, `not_enough_money`, `spending_limit`,
`transaction_not_found`, `error`, `ok`.

What the original does that nobody wrote down, established from its source and its tests:

- The wallet key of a bet is the provider's **transaction** id, not the provider's round id, so a
  round with several debits holds several wallet bets (`St8Routes` debit: "keyed by transactionId
  for precise per-txn rollback").
- A credit settles only the **first** debit of its round (`debitTxnIds.head`); the other debits of a
  multi-debit round stay open in the wallet. A zero-amount credit settles the first debit as a loss
  the same way. This is the leak decision 0032 and the 2026-08-19 incident were about; see *Changes*.
- A credit for a round with no debit, or whose bet the wallet no longer finds, is a direct award
  (`directCredit`) keyed by the transaction id, and so is a payout. A cancel of a debit the wallet no
  longer finds refunds the debit's amount as a direct award keyed by the cancel id.
- A cancel of a credit is a **bet** keyed by the cancel id for the credited amount, allowed to take
  the balance negative; a `correction_debit` is a bet allowed negative too.
- The balance the provider is told is real plus restricted balance, to two decimals.
- A free round (`free_debit`, or a zero-amount debit) never reaches the wallet: the round is tagged
  with the free-rounds grant it belongs to, its credit is **not** paid by this service (the player's
  current balance is answered instead), and the reward service settles the winnings (spec 011).
- The provider's `player_profile` answer hardcodes the jurisdiction to `CW`; the launch request reads
  it from the brand's credentials. The coverage analysis in the original names this as a gap.
- The provider strips any path from the wallet URL, so every callback is served both under `/st8`
  and at the root.
- A repeated callback with the same transaction id is answered from the stored outcome without
  reading the wallet, and the repeat's body is **not** compared with the first: a conflicting repeat
  gets the first outcome.

## Clarifications

### Session 2026-10-08

- Q: Does the generic provider API (`/v1/casino/provider/bet|win|rollback`, HMAC, the Go gateway)
  survive? → A: No. The chart calls it a dev stub, it forwards decimal amounts to substrate actions
  with no idempotency and no round, and every end-to-end test of it is skipped as blocked. Under
  constitution I it is dropped; the demo game-provider tool that drove it is a dev tool. The one
  provider route family is the ST8 seamless-wallet protocol. A second real provider is a second
  adapter in this service, under the same money rules (FR-010 to FR-019), specified when it exists.
  Confirmed by Trevor at reconciliation on 2026-10-08; spec 020's simulator speaks ST8's protocol only.
- Q: Where does game launch live, and who opens the game session? → A: Launch is this service's: it
  issues the launch ticket, calls the provider, and answers the frontend. Eligibility to play is asked
  over HTTP within the project of `players` (account status), `kyc` (the play gate) and
  `responsible-gambling` (break, exclusion and session minutes) — *as reconciled at reconciliation 2;
  this answer first put the KYC gate and cooling-off on `players`*. The game session itself is `players`' (spec 001 FR-011,
  `casino.sessions`): this service asks `players` to open one at launch and the frontend ends it
  through `players` as today. Wagered and won totals per session are no longer written onto the
  session: readers derive them from `casino.rounds`.
- Q: Where does the catalogue live? → A: Here. The provider feeds it, nightly; the brand curates it
  (disabled developers and categories, per-game overrides); players browse it. The original keeps it
  in `casino-service`; it moves with the provider it comes from.
- Q: Who talks to the provider's bonus API for free rounds? → A: This service, on behalf of
  `rewards`. `rewards` (spec 011) owns the grant and its lifecycle; it asks this service to create,
  poll and cancel the provider's bonus and to price spin values, over HTTP within the project, so the
  provider's credentials and signing live in one place.
- Q: Does a credit settle one bet or the round? → A: The round. The original settles only the first
  debit and leaves the rest open, which is the open-rounds leak the 2026-08-19 incident was about. Here
  a credit settles every open bet of the round: the win against the first bet, the others as losses;
  a zero credit settles every open bet as a loss. Recorded under *Changes from the original*.
- Q: What is kept per callback, and for how long? → A: A round entity per provider round (the
  transaction id when the provider sends no round) holds every transaction the provider sent for it
  with the outcome answered; a transaction index finds a round by transaction id. These replace the
  adapter's transaction, round-index and callback-failure stores. Kept for the ledger's life (spec 001
  FR-015): a provider may dispute a round years later. *Revised 2026-10-10:* the idempotency and the
  transaction's outcome are a workflow per transaction; the round entity keeps the round's history
  and the transaction index is dropped (session 2026-10-10).
- Q: Does the test-control surface (`/st8/test/*`) survive? → A: No. It was the original's harness.
  Recordings are replayed by parity tests in this service's test kit, where the provider's public key
  and a player's wallet are set up directly. The provider's public key is a brand setting in the
  secret store (ankka 038), so a test installation registers a test key the same way production
  registers the real one.
- Q: Which amounts cross to the provider? → A: Decimal strings, as the protocol says. Every amount
  the provider sends is converted to minor units at the boundary and refused when it is not a whole
  number of them; every balance answered is minor units rendered to the currency's exponent.

### Session 2026-10-08 (reconciliation, wave 1)

- Q: Is the generic provider API dropped for good? → A: Yes, confirmed; spec 020 implements ST8's
  protocol only and keeps its core-and-adapter seam for a second real provider.
- Q: Do this spec and the simulator (020) speak the same protocol? → A: Yes: the signature header is
  `x-st8-sign` (FR-003), the fields are those FR-004 lists and 020 FR-009 sends, the status words are
  this spec's, and a malformed body is HTTP 400 on both sides. Where they differed, the original's
  adapter decided.
- Q: How does a test installation register the simulator's key? → A: As configuration (FR-026): a
  non-real-money brand may name the simulator's key document as its provider public key; a
  real-money brand holds the literal key. The original's test-only control route is gone.
- Q: Where do provider credentials come from? → A: The brand's secret store (ankka 038), moved from
  `merchant-service`'s `CredentialRoutes` (spec 004).

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: Who answers what at launch? → A: Three services, each its own rule: `players` the account
  status, `kyc` the play gate, `responsible-gambling` the break, exclusion and session minutes
  (FR-007). Cooling-off was never `players`' (spec 010), and the KYC gate needs the policy only `kyc`
  holds (spec 009).
- Q: How does a debit know a player is locked? → A: Suspension, closure and blocking from this
  service's view of `casino.players`; a break or exclusion from the wallet's refusal, which asks
  `responsible-gambling` before funding (FR-012, spec 002 FR-007a). Nothing here reads a flag over
  HTTP and nothing fails open. *Revised 2026-10-10:* the `may-bet` question is this service's own
  step, before the wallet call (session 2026-10-10).

### Session 2026-10-08 (reconciliation 3)

- Q: What must `round_settled` carry for reporting? → A: Besides the provider, round, game, session,
  currency, stake, win and outcome it already carried: the wallet transaction ids of the round's
  movements (FR-019), so `reporting`'s day-end and dispute report match a provider round to the
  wallet's facts without calling either service (spec 015 FR-017, FR-018).

### Session 2026-10-09

- Q: May a brand run two game providers at once? → A: Yes, from the start. A game is identified by
  provider and code; the brand holds one provider configuration per provider; launch, the catalogue
  and the free-rounds routes name the provider; each provider's callbacks are served under its own
  prefix. The adapter for a second provider is still written only when one is contracted (Out of
  scope); the stored data and the routes do not change when it is. Recorded under *Changes from the
  original*.
- Q: What ranks the brand's popular games? → A: Rounds played at the brand, counted from this
  service's own rounds over all time. The original ranks by game sessions opened; rounds are this
  service's data and measure play, where one session may be one spin or a thousand. Recorded under
  *Changes from the original*.
- Q: How long do a launch ticket and a session ticket live? → A: As the original: neither expires.
  A launch ticket is valid until it is checked; a session ticket serves every later provider call
  for its player and wallet, whatever `players` records about the game session, so the provider's
  late cancels and resettlements still resolve. Every such call is signed by the provider, which is
  the credential; the ticket only names the player and wallet.
- Q: What can an operator do with an unacknowledged callback the provider has given up retrying?
  → A: List it, replay it or resolve it. A replay re-drives the stored callback against the wallet
  under the same provider transaction id, so a late provider retry is still answered from the one
  outcome; a resolve marks it settled by other means with a reason. Both are recorded on the round.
  The original only records the failure and clears it on a provider retry. Recorded under *Changes
  from the original*.
- Q: What is the player's launch rate limit? → A: 20 launches per player per minute, as the
  original; the refusal carries the seconds until the window opens again.

### Session 2026-10-10

- Q: Where does a provider transaction's idempotency live, now that the wallet keeps only its recent
  1,000 keys? → A: In a **bet workflow per provider transaction**, id the transaction id (the cancel
  id for a cancel). The wallet's key store was unbounded (spec 002 research R4: 4.3 MB at 10,000
  transactions) and has been bounded; the caller's workflow is the lifetime record. Its `start` is
  the atomic claim (a second start is refused `Conflict`, which the endpoint treats as "already
  started"); its steps are `may-bet` to `responsible-gambling` (fail closed: `PLAYER_LOCKED`,
  `RG_UNAVAILABLE` become the outcome and the wallet is never called), the wallet call keyed by the
  transaction id, and `record-round`, which records the transaction and its outcome on the round
  entity before ending, off the reply path (*revised in planning, below: on it*). The wallet keeps its recent keys as the atomic backstop:
  a step runs at least once, and a re-run after a crash is answered with the first outcome. The
  transaction index is dropped: the workflow's id is the transaction id. The round entity stays as
  the round's history for disputes and as the source of the views and `casino.rounds` (an ankka view
  cannot source a workflow).
- Q: How does the callback answer ST8 when the outcome is a workflow's? → A: ankka 0.11 has no await
  on a workflow, so the endpoint starts it and polls its `status` every 1 ms, bounded; it answers ST8
  from the state as soon as the outcome is present. Past the bound it answers ST8's `error`, so the
  provider retries and the retry is answered from the workflow's state. Measured in spec 002's
  prototype (`it.proto.BetWorkflowItSuite`, research R17): median 9.7 ms and p99 121.8 ms on a
  10,000-transaction wallet through the component client.
- Q (planning): Does the round learn of a debit before or after the provider is answered? → A:
  Before. The workflow writes its outcome to its state only after `record-round`, so the endpoint
  never answers before the round holds the bet. Off the reply path races: a credit settles the open
  bets the round holds, ST8 sends it as soon as it has the debit's answer, and a debit not yet
  recorded would be left open, the leak FR-014 closes (research R18). One entity command per callback.
- Q (planning): What does the workflow hold when the wallet cannot be reached? → A: No outcome. After
  its retries the workflow records the callback unacknowledged on the round and pauses; a provider
  retry or an operator replay resumes the wallet call, a resolve ends it (FR-020). A wallet failure is
  never an outcome, so a retry is never answered from one (research R18).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player launches a game and the provider opens the session (Priority: P1)

A logged-in player picks a game. The brand checks they may play (not suspended or self-excluded, KYC
gate met, not cooling off, session minutes left), issues a one-time launch ticket, tells the provider
to launch the game for this player with the brand's site, and hands the frontend the game URL and a
session id. The provider's game client then calls back with the token; the brand answers the
player's currency, country, the session ticket and the site's lobby and deposit links, and the token
becomes a session ticket for the rest of the session.

**Why this priority**: nothing is played without it; it is the first callback of every recording.

**Independent Test**: replay `TestScenario7_CasinoGate3_SessionLifecycle` and the launch steps of
`TestScenario7_CasinoGate3_FullPlayerJourney` and `_RGEnforcement`; drive `check` with a recorded
token.

**Acceptance Scenarios**:

- added `features/game-provider/launch.feature`: an eligible player's launch answers the game's address, a session id and a launch ticket, and opens a game session
- added `features/game-provider/launch.feature`: a check of an unused launch ticket for its own game is answered ok with the player's details and marks the ticket used
- added `features/game-provider/launch.feature`: a launch ticket already used is answered session expired when checked again
- added `features/game-provider/launch.feature`: a launch ticket checked for another game than it was issued for is answered session expired
- added `features/game-provider/launch.feature`: a suspended or self-excluded player's launch ticket is answered player locked
- added `features/game-provider/launch.feature`: a player whose brand requires KYC before play and whose KYC is not verified is refused at launch naming their KYC status
- added `features/game-provider/launch.feature`: a player cooling off or at their session limit is refused at launch naming the reason and no ticket is issued
- changed `features/game-provider/launch.feature`: a launch of a game whose game provider the brand holds no credentials for is refused as not configured
- added `features/game-provider/launch.feature`: a launch names the game by its game provider and code and asks that game provider to launch it
- added `features/game-provider/launch.feature`: a launch ticket not yet checked stays valid however long ago it was issued
- added `features/game-provider/launch.feature`: a provider call under a session ticket whose game session has ended is served as any other
- added `features/game-provider/launch.feature`: a player may launch twenty games in a minute and the twenty-first is refused as too many requests naming the seconds to wait
- added `features/game-provider/launch.feature`: a launch in fun mode is marked so to the game provider and carries no free rounds
- added `features/game-provider/launch.feature`: a launch naming no free rounds attaches the oldest eligible free rounds to the launch ticket

---

### User Story 2 - The provider debits a stake, credits a win, or closes a round as a loss (Priority: P1)

During play the provider sends a debit for each stake, then a credit for the win or a zero credit for
a loss, each under a transaction id and a round id, signed. The brand places the bet with the wallet
keyed by the transaction id, settles the round on the credit, and answers the balance each time.

**Why this priority**: this is every spin's money path.

**Independent Test**: replay `TestScenario15_St8Wallet_BasicDebit`, `_BasicCredit`,
`_LargeWinOnLargeStake`, `_ZeroDebit`, `_ZeroCredit`, `_NotEnoughMoney`,
`_ZeroBalanceFreshSpinDeclined`, `_StandaloneCredit`, `_BigRoundMultipleDebits`,
`_BigRoundMultipleCredits`, `_BigRoundFailedDebit`, `_TableSwitch`, `_NewParams`,
`_CorrectionDebitNegativeBalance`, `_Resettlement`.

**Acceptance Scenarios**:

- added `features/game-provider/rounds.feature`: a debit places an open bet keyed by the transaction id and answers the balance
- added `features/game-provider/rounds.feature`: a credit settles the round's bet with the win, closes the round and publishes a round settled fact
- added `features/game-provider/rounds.feature`: a zero credit settles the bet as a loss and publishes a round settled fact with outcome loss
- added `features/game-provider/rounds.feature`: a debit the wallet cannot fund is answered not enough money with the balance and opens no bet
- added `features/game-provider/rounds.feature`: a fresh spin at a zero balance is declined
- added `features/game-provider/rounds.feature`: a credit for a round with no debit is a provider credit keyed by the transaction id
- added `features/game-provider/rounds.feature`: a credit to a round with two debits settles the first with the win and the second as a loss
- added `features/game-provider/rounds.feature`: a credit to a round already settled under a new transaction id is a provider credit
- added `features/game-provider/rounds.feature`: a zero debit or a free debit places no bet, answers the current balance and records the round with a zero stake
- added `features/game-provider/rounds.feature`: a free debit under a session ticket carrying free rounds marks the round with them before the answer
- added `features/game-provider/rounds.feature`: a credit to a round marked with free rounds credits the wallet nothing and publishes the win for the reward rules to settle
- added `features/game-provider/rounds.feature`: a correction debit larger than the balance is placed as a bet allowed below zero
- added `features/game-provider/rounds.feature`: a debit naming another game than the session ticket was issued for is served the same
- added `features/game-provider/rounds.feature`: fields the brand does not read are ignored
- added `features/game-provider/rounds.feature`: a debit for a player cooling off or self-excluded is answered player locked and places nothing
- added `features/game-provider/rounds.feature`: a debit the wallet refuses for a responsible gambling limit is answered spending limit with the current balance
- added `features/game-provider/rounds.feature`: a debit is answered error and places nothing when responsible gambling does not answer
- added `features/game-provider/rounds.feature`: a credit sent once its debit is answered settles that debit

---

### User Story 3 - The provider cancels a debit or a credit (Priority: P1)

The provider cancels a transaction under a cancel id: a cancelled debit is rolled back on the wallet;
a cancelled credit is debited back, even below zero. A cancel for a transaction the brand never saw
is answered `transaction_not_found` with the balance.

**Why this priority**: cancels are the provider's correction path and the place the original's
tests found the most edges.

**Independent Test**: replay `TestScenario15_St8Wallet_BasicCancel`, `_BigRoundMultipleCancels1`,
`_BigRoundMultipleCancels2`, `_LimitedCancel`, `_StandaloneCancel`,
`_CancelEverythingWithinRound`.

**Acceptance Scenarios**:

- added `features/game-provider/cancels.feature`: cancelling a debit in an open round rolls the bet back, restores the balance and closes the round as cancelled
- added `features/game-provider/cancels.feature`: cancelling a credit debits its amount back as a bet keyed by the cancel id, allowed below zero
- added `features/game-provider/cancels.feature`: cancelling a second debit of a round whose first was already rolled back rolls it back on its own key
- added `features/game-provider/cancels.feature`: cancelling a debit the wallet no longer holds open refunds its amount as a provider credit keyed by the cancel id
- added `features/game-provider/cancels.feature`: a cancel naming a transaction the brand never recorded is answered transaction not found with the current balance
- added `features/game-provider/cancels.feature`: a cancel with no session ticket naming only the player and the transaction is served by player id
- added `features/game-provider/cancels.feature`: cancelling a round's credit and then its debit leaves the balance as it was before the round

---

### User Story 4 - A repeated callback is answered as the first was (Priority: P1)

The provider retries any callback it did not get an answer to. A repeat with the same transaction id
(or cancel id) is answered with the first outcome and balance without touching the wallet.

**Why this priority**: the provider's retries are the only thing between a timeout and a double
debit; the wallet (spec 002) is idempotent too, so a repeat that gets through is still harmless.

**Independent Test**: replay `TestScenario15_St8Wallet_IdempotentDebit`, `_IdempotentCredit`,
`_IdempotentCancel`.

**Acceptance Scenarios**:

- added `features/game-provider/repeats.feature`: a repeated debit is answered with the first outcome and balance and the wallet is not called
- added `features/game-provider/repeats.feature`: a repeated credit with a different amount is answered with the first outcome and the difference is recorded as a conflicting repeat
- added `features/game-provider/repeats.feature`: a repeated debit refused as player locked is answered the same and responsible gambling is not asked again
- added `features/game-provider/repeats.feature`: a repeated cancel is answered ok with the same balance
- added `features/game-provider/repeats.feature`: a retry of a debit that failed to reach the wallet is served afresh and clears the unacknowledged call on success
- added `features/game-provider/unacknowledged.feature`: an operator lists every unacknowledged call with its kind, amount, round and error
- added `features/game-provider/unacknowledged.feature`: replaying an unacknowledged credit pays it to the wallet under its transaction id and clears it
- added `features/game-provider/unacknowledged.feature`: a provider retry after an operator's replay is answered from the replay's outcome and the wallet is not called again
- added `features/game-provider/unacknowledged.feature`: resolving an unacknowledged call with a reason clears it, moves no money and records the reason on the round

---

### User Story 5 - A callback is verified, and an unverifiable one is refused (Priority: P1)

Every callback carries the provider's signature over the raw body. A missing, malformed or wrong
signature is answered `auth_failed`; a body that is not a JSON object is a bad request.

**Why this priority**: the callbacks move money with no other credential.

**Independent Test**: replay `TestScenario15_St8Wallet_WrongSignature`; sign recordings with the
test key the parity kit registers.

**Acceptance Scenarios**:

- added `features/game-provider/signatures.feature`: a provider call signed with the key matching the brand's provider signing key is served
- added `features/game-provider/signatures.feature`: a provider call with a missing or wrong signature is answered auth failed and nothing is read or written
- added `features/game-provider/signatures.feature`: a provider call whose body is not readable is refused as a bad request naming the call's own refusal word
- added `features/game-provider/signatures.feature`: a rotated provider signing key verifies the next provider call without a redeploy

---

### User Story 6 - The provider reads a balance or a player profile; a buyin or payout outside a round (Priority: P2)

The provider asks for a balance with or without a token, asks for a player's profile before granting
free rounds, debits a buyin for a tournament or table, or pays a payout, each outside any round.

**Why this priority**: the free-rounds flow fails at `player_profile` when it is wrong, as it did for
18 grants in September.

**Independent Test**: replay `TestScenario15_St8Wallet_BasicBalance`, `_BalanceWithoutToken`,
`_BasicBuyin`, `_BasicPayout`.

**Acceptance Scenarios**:

- added `features/game-provider/reads.feature`: a balance read with a session ticket answers real plus restricted balance to two decimals and the currency
- added `features/game-provider/reads.feature`: a balance read with the player id and no session ticket resolves the player by id
- added `features/game-provider/reads.feature`: a player profile read for a registered player who never launched answers their profile
- added `features/game-provider/reads.feature`: a player profile read for an unknown player id is answered player not found
- added `features/game-provider/reads.feature`: a buyin places a bet keyed by its transaction id and answers the balance
- added `features/game-provider/reads.feature`: a buyin the wallet cannot fund is answered not enough money
- added `features/game-provider/reads.feature`: a credit outside any round is a provider credit keyed by its transaction id

---

### User Story 7 - The catalogue is fed by the provider and curated by the brand (Priority: P2)

Nightly, the brand fetches the provider's game list and refreshes its catalogue: new games appear,
games the provider stops reporting are marked removed, a game's own enabled flag is kept. An operator
disables developers, categories or single games for their brand, or overrides a game on. A player
browses the catalogue by category, theme, feature, bonus type, tag, developer, search and country,
sees the popular games, and reads a game.

**Why this priority**: the lobby is empty without it, but it moves no money.

**Independent Test**: seed a catalogue from `fake-st8`'s game list, apply a brand's configuration,
and read the routes the frontend's Playwright suite reads (spec 001 FR-033).

**Acceptance Scenarios**:

- added `features/game-provider/catalogue.feature`: the nightly refresh puts every game the game provider lists in the catalogue with its details
- added `features/game-provider/catalogue.feature`: a game the game provider stops listing is marked removed with its enabled flag unchanged
- added `features/game-provider/catalogue.feature`: a refresh that fetches nothing marks nothing removed
- added `features/game-provider/catalogue.feature`: the nightly refresh fetches each game provider the brand runs and marks removed only the games that game provider stopped listing
- added `features/game-provider/catalogue.feature`: every game listed carries its game provider and code
- added `features/game-provider/catalogue.feature`: a game named by code alone is served when one game provider carries the code and refused naming both when two do
- added `features/game-provider/catalogue.feature`: no game of a developer the brand disabled is listed to a player
- added `features/game-provider/catalogue.feature`: a game the game provider disabled is listed when the brand enables it
- added `features/game-provider/catalogue.feature`: a game marked removed cannot be enabled by the brand
- added `features/game-provider/catalogue.feature`: a game that prohibits or restricts the player's country is not listed to them
- changed `features/game-provider/catalogue.feature`: popular games are ranked by rounds played at the brand and leave out a game disabled since the ranking was built
- added `features/game-provider/catalogue.feature`: an operator reads every game of the catalogue, removed and disabled included, with each layer of the enablement decision

---

### User Story 8 - `rewards` grants free rounds through the provider (Priority: P2)

The reward service asks this service to create a free-rounds bonus at the provider for a player and
games, to poll it, to cancel it, and to price spin values; the provider calls the player profile back
during creation.

**Why this priority**: free rounds are a live product; the provider calls are the brand's only
outbound money-adjacent calls.

**Independent Test**: replay `TestScenarioFreeSpinsPlay_*` (4) and the free-rounds branch of
`TestScenario16_*` against `fake-st8`'s bonus routes.

**Acceptance Scenarios**:

- added `features/game-provider/free-rounds.feature`: free rounds asked for a player known to the game provider are created under the reward's id and their status reported back
- added `features/game-provider/free-rounds.feature`: free rounds asked for a player who never launched make the player known to the game provider first
- added `features/game-provider/free-rounds.feature`: free rounds on games of a second game provider are created at that game provider under the brand's credentials for it
- added `features/game-provider/free-rounds.feature`: cancelling active free rounds cancels them at the game provider
- added `features/game-provider/free-rounds.feature`: spin values asked for game codes and a currency are answered from the game provider and cached for the day

---

### Edge Cases

- A callback for a brand the service does not serve: refused (spec 001 FR-005).
- A debit whose token is unknown or unregistered: `session_expired`; a debit with no token:
  `session_expired` (a debit always carries one).
- A balance request with no player: `player_not_found`; with a token shorter than ten characters:
  the token is ignored and the player id used.
- A credit while the wallet is unreachable: `error`, the credit recorded as unacknowledged, and the
  provider's retry served afresh; a credit the wallet refuses for a reason other than an unknown
  round: `error`, never a silent award.
- A debit the wallet refuses as a scope block: `spending_limit`.
- A cancel referencing a free-round debit: the round is closed as cancelled, no money moves.
- A cancel of a credit on a round already cancelled: the credit is debited back regardless; the round
  stays cancelled.
- A launch while a launch is in flight for the same player: served; two sessions open.
- A debit, credit or cancel under a session ticket whose game session `players` has ended: served as
  any other; the token is the provider's handle on the player and wallet, not on the session.
- A 21st launch by one player within 60 seconds: refused as too many requests with the seconds to
  wait; the 20 before it are served.
- A provider that answers the launch with no game URL: the launch fails as `launch_failed`, the token
  is discarded, no session is opened.
- A refresh when the provider is down: the last catalogue stands; the failure is on status.
- An amount with more decimals than the currency: refused as a bad request naming the amount.

## Requirements *(mandatory)*

### Functional Requirements

**The service and its shape**

- **FR-001**: `game-provider` MUST be one service of the brand project (spec 001 FR-002), holding a
  **launch ticket** key value entity per token, a **bet workflow** per provider transaction id (the
  cancel id for a cancel) whose `start` is the claim and whose state is the transaction's outcome for
  the life of the ledger, a **round** event sourced entity per provider round (the round's history,
  the source of the views and of `casino.rounds`), a **catalogue** key value entity per provider and
  game code, a **brand catalogue configuration** key value entity, and a **provider configuration**
  per provider of the brand in the secret store; with views over rounds (by player, by session,
  unacknowledged callbacks) and a consumer producing `casino.rounds`. (The transaction index was
  dropped on 2026-10-10: the workflow's id is the transaction id.)
- **FR-002**: Every ST8 callback route MUST be served both under its prefix (`/st8`) and at the
  root, because ST8 strips the path from the wallet URL; a second provider's callbacks MUST be
  served under that provider's own prefix and never at the root. The callback route the launch
  names as the wallet URL keeps the `{brand}` segment (spec 001 FR-005); the prefix and root forms
  carry no brand and resolve the project's own brand, since one host serves one brand. Every
  callback route is a public route served only when its signature verifies (spec 001 FR-020).

**Signatures and the protocol**

- **FR-003**: Every callback MUST be verified as an ECDSA P-256 SHA-256 signature, base64 in the
  `x-st8-sign` header, over the raw request bytes, against the provider public key of the
  brand's configuration for that provider (FR-026), a change applying within 60 seconds. A missing, non-base64 or failing signature
  MUST be answered HTTP 200 `auth_failed` with nothing read or written.
- **FR-004**: A body that is not a JSON object MUST be answered HTTP 400 `session_expired`
  (`player_not_found` for the profile). Fields the protocol does not name MUST be ignored. A field
  the protocol names as a string MUST also be accepted as a number where the original accepts one.
  The fields read are `token`, `transaction_id`, `round`, `currency`, `amount`, `provider_kind` and
  `game_code`, and for a cancel `cancel_id` and the referenced `transaction_id`; spec 020 FR-009
  sends exactly these.
- **FR-005**: Every amount MUST be read as a decimal string or number and converted to minor units of
  the named currency, refused as a bad request naming the amount when not whole (spec 001 FR-026);
  every balance MUST be answered as real plus restricted balance rendered to the currency's exponent.
- **FR-006**: The currency of a callback MUST be the session ticket's currency when it has one, else
  the player's currency, else the callback's, else EUR, in that order, as the original resolves it.

**Launch and session**

- **FR-007**: Launch MUST be a player route requiring the player's token to name the launching
  player, MUST refuse a player who is suspended, self-excluded or blocked (`account_restricted`),
  whose brand's KYC play gate is not met (`PLAY_BLOCKED_KYC` with their status), who is cooling off
  (`cooling_off_active`), or who has used their session minutes for the day
  (`session_limit_reached` with the limit and the used minutes), asking over HTTP within the project
  `players` for the account status (spec 008 FR-018), `kyc`'s play check for the gate (spec 009
  FR-020) and `responsible-gambling`'s `may-play` for break, exclusion and minutes (spec 010 FR-008),
  each failing closed as a refusal naming the service; MUST refuse the 21st launch by one player
  within a 60-second window as too many requests, naming the seconds until the window opens again;
  and MUST refuse with `st8_not_configured` (the word names the game's provider) when the brand holds no
  configuration for the provider the game belongs to.
- **FR-008**: Launch MUST name the game by provider and code, MUST issue a one-time launch ticket,
  resolve the free-rounds grant to attach (the caller's, or the player's oldest eligible grant for the
  game; none in demo mode), call that provider's launch with the brand's site at it, lobby and deposit
  links, the provider's wallet URL, the player's profile and fun mode, signed with the brand's
  operator key for that provider, and answer the game URL, the launch ticket, the session id and the
  attached grant. When no provider is configured locally it MUST answer a stub game URL.
- **FR-009**: Launch MUST ask `players` to open a game session for the player, game and currency,
  linked to the player's login session (the session the player's token names) and marked demo
  when it is, and MUST record the game as
  recently played; a session that cannot be opened MUST NOT fail the launch.
- **FR-010**: `check` MUST consume the launch ticket once: an unknown, already used or mismatched-game
  token is `session_expired`; a locked player is `player_locked`; otherwise the token becomes the
  session ticket carrying player, brand, game, session, currency and grant, the player is recorded as
  known to the provider, and the answer carries the token, currency, game code, country (MT when
  unknown), player id and the site's id, lobby and deposit links. Neither token expires: an unchecked
  launch ticket stays valid until checked, and a session ticket serves every later callback for its
  player and wallet regardless of the game session's state in `players`.

**Money callbacks**

- **FR-011**: Every money callback MUST be idempotent under the provider's transaction id (the cancel
  id for a cancel), by starting the bet workflow of that id: a repeat finds it started (`Conflict`)
  and MUST be answered with the stored status, balance and currency from the workflow's state without
  calling `responsible-gambling` or the wallet; a repeat whose amount, kind or round differs MUST
  still be answered with the first outcome and MUST be recorded as a conflicting repeat on the round.
  The endpoint polls the workflow's status (1 ms, bounded) and answers as soon as the outcome is
  present; past the bound it answers `error`, and the provider's retry is answered from the workflow.
- **FR-012**: A debit MUST require a registered session ticket (`session_expired` otherwise), MUST
  answer `player_locked` for a suspended, closed or blocked player (from this service's view of
  `casino.players`) and for a cooling-off or excluded one (from its own `may-bet` step to
  `responsible-gambling`, spec 010 FR-010, failing closed: no answer within 500 ms is `error`; this
  was spec 002 FR-007a until 2026-10-10), and otherwise MUST
  place a wallet bet keyed by the transaction id with the game, the game category and the provider,
  allowed below zero only for kind `correction_debit`; the wallet's refusals MUST map to
  `not_enough_money` (insufficient funds), `player_locked` (cooling off, self-excluded) and
  `spending_limit` (every other responsible-gambling refusal and a scope block), each with the
  current balance; a wallet unreachable MUST answer `error` and record the debit as unacknowledged.
- **FR-013**: A debit of amount zero or of kind `free_debit` MUST place no bet, MUST tag the round
  with the session ticket's free-rounds grant before answering (an `error` when the tag cannot be
  written), MUST record a zero-stake bet on the round, and MUST answer the current balance.
- **FR-014**: A credit MUST find its round by the round id (the transaction id when none) and settle
  it: a positive amount is a win against the round's first open bet and every other open bet of the
  round is settled as a loss; a zero amount settles every open bet as a loss; a round with no bet, or
  whose bet the wallet reports unknown, receives the amount as a provider credit keyed by the
  transaction id; a round tagged with a free-rounds grant MUST NOT be credited and MUST answer the
  current balance; any other wallet refusal MUST answer `error` and record the credit as
  unacknowledged.
- **FR-015**: A cancel MUST resolve the player by token or by id, MUST answer `transaction_not_found`
  with the balance when the referenced transaction is unknown, MUST debit back a credit (kinds
  `credit`, `free_credit`) as a bet keyed by the cancel id allowed below zero, and MUST roll back a
  debit on its own wallet key, refunding the debit's amount as a provider credit keyed by the cancel
  id when the wallet no longer holds the bet; the round MUST be closed as cancelled when no open bet
  remains.
- **FR-016**: A buyin MUST require a player id (`player_not_found`), resolve the token when present,
  and place a bet keyed by the transaction id outside any round, allowed below zero for
  `correction_debit`, the wallet's refusals mapped as a debit's are (FR-012); a payout MUST make a
  provider credit keyed by the transaction id.
- **FR-017**: A balance MUST resolve the player by a token of ten or more characters, else by player
  id, MUST answer `player_locked` for a locked player, `session_expired` when neither resolves, and
  otherwise the balance and currency.
- **FR-018**: A player profile MUST answer `player_not_found` for a player neither known to the provider
  nor registered at the brand (the players topic, `casino.players`),
  else the player's id, the brand's jurisdiction from its provider configuration, default currency,
  registration country upper-cased, birth date, registration date as a calendar day, and a
  bet-limits band from the daily deposit limit (at most 50 low, at most 200 medium, else high,
  medium when none).
- **FR-019**: Every settlement, cancellation and standalone credit MUST produce one `round_settled`
  message on `casino.rounds` (spec 001 FR-011) keyed by the wallet (the player and the currency,
  spec 001's topic contract), carrying the player, the provider, the
  provider round id, the game, the session, the currency, the total stake, the win, the outcome (win,
  loss, cancelled, credit), the free-rounds grant when tagged, the provider's transaction ids and the
  wallet transaction ids of every movement in the round; `rewards` settles a grant's winnings from
  it (spec 011), readers derive session totals from it, and `reporting` (spec 015 FR-017, FR-018)
  matches the provider's figures to the wallet's facts by the wallet transaction ids without a call.
- **FR-020**: A callback the wallet did not acknowledge MUST be recorded as unacknowledged with its
  kind, amount, round and error, listable by an operator, and cleared when a retry succeeds. An
  operator MUST be able to replay one: the service re-drives the stored callback against the wallet
  under the same provider transaction id, and on success clears the entry and answers any later
  provider retry from that outcome (FR-011). An operator MUST be able to resolve one without replay,
  giving a reason, when the money was settled another way. Each replay and resolve MUST be recorded
  on the round with the operator and the time.

**The catalogue**

- **FR-021**: A nightly refresh MUST fetch, for each provider the brand has configured, that
  provider's game list for the brand's site at it, signed, and upsert every game under its provider
  and code with the fields US7 scenario 1 lists; games of a provider absent from that provider's
  non-empty list MUST be marked removed from the feed with their enabled flag untouched; an empty
  list MUST change nothing for that provider and nothing for any other; each provider's refresh MUST
  be retried three times with growing delays and its outcome shown on status per provider.
- **FR-022**: A game MUST be listed for a brand only when it is not removed from the feed, its
  category and developer are not disabled for the brand, and its brand override (else the provider's
  flag) is on; an operator MUST be able to set the brand's disabled developers, disabled categories
  and per-game overrides, taking effect on the next read.
- **FR-023**: The player catalogue MUST filter by category, themes, features, bonus types, tag,
  developer (by id or slug), name search, and country (excluding restricted and prohibited
  territories), MUST exclude games past their deprecation date, MUST page by limit (1 to 3,000) and
  offset with the total and the next offset, MUST serve a batch of up to 50 games by code ignoring
  every other filter, MUST serve one game by code, MUST list developers, and MUST serve the brand's
  popular games ranked by the number of rounds played at the brand over all time, counted from this
  service's own rounds, with disabled games excluded at read time. A game is
  identified by provider and code; every listed game carries its provider; a route that names a game
  by code alone MUST resolve it among the brand's providers and MUST refuse a code that two of them
  share as a bad request naming both.
- **FR-024**: The operator catalogue MUST list every game with each layer of the enablement decision.

**Free rounds for `rewards`**

- **FR-025**: The service MUST serve `rewards` (service class, spec 001 FR-020) routes to create a
  provider bonus for a player, provider, games, count and grant id, to poll it, to cancel it, and to
  price spin values for a provider's games and a currency, each signed with the brand's operator key
  for that provider, answering the provider's status as active, processing, rejected, failed,
  cancelled or not configured, and MUST make the player known to that provider before creating.

**Configuration, identity and data**

- **FR-026**: A brand MUST hold one provider configuration per provider it runs, each naming the
  provider and holding its API address, the brand's site at it, the operator private key, the
  provider public key, the jurisdiction, the lobby and deposit links, in the secret store (ankka
  038), read on change without a redeploy; a provider with no configuration is not configured for the
  brand. An operator MUST write and read a provider's configuration over an operator route, the
  private key never answered on a read. On an installation that takes no
  real money, the provider public key MAY instead be the address of a key document the service
  fetches and refreshes within 60 seconds, so that spec 020's simulator registers its current and
  next keys by publishing them (020 FR-011); a real-money brand MUST hold the key itself. These
  settings move here from `merchant-service`'s `CredentialRoutes` (spec 004).
- **FR-027**: Every route MUST be of one class (spec 001 FR-020): launch, catalogue and session
  routes are player routes; the catalogue configuration, the operator catalogue (FR-024), the
  provider configuration (FR-026), the refresh now and its status (FR-021), the rounds by player or
  session and one round's transactions (FR-001), and the unacknowledged list, replay and resolve
  (FR-020) are operator routes; the callbacks are public and signature-verified; the free-rounds and
  known-player routes are service routes for `rewards`.
- **FR-028**: The service MUST hold no personal field: the player id, game ids, amounts and the
  provider's identifiers are not personal data (spec 001 FR-024); the profile it answers is read from
  `players` at the time of the call and not kept.
- **FR-029**: A metric MUST count bets placed per brand (the original's `eitheror_casino_bets_total`),
  and the open unacknowledged callbacks MUST be a metric per brand
  (`eitheror_casino_unacknowledged_callbacks`).

**The port**

- **FR-030**: Recordings MUST be made with the provider answered by `fake-st8` (spec 001 FR-029),
  which serves launch, the game list, the bonus routes and a game client that calls back; the parity
  kit MUST register the recordings' test key as the brand's provider public key.

### Key Entities

- **Launch ticket** (the original's *launch ticket*; the session ticket is its *session ticket*): token, player, brand, game, session, currency, free-rounds grant, issued, used;
  becomes the session ticket when checked; no expiry in either state.
- **Round**: provider, provider round id (the transaction id when none), player, game, session, currency,
  transactions (id, kind, amount, wallet key, status, balance answered, conflicting repeats),
  total stake, win, outcome, free-rounds grant, opened and settled times.
- **Bet workflow**: one per provider transaction id (the cancel id for a cancel): the callback, the
  player, round and kind, its status, and the outcome answered (status, balance, currency, or the
  refusal); kept for the ledger's life. Replaces the transaction index (session 2026-10-10).
- **Unacknowledged callback**: transaction id, kind, amount, round, error, recorded and cleared times,
  how it cleared (provider retry, operator replay, or operator resolve with its reason and operator).
- **Game**: provider and code (its identity), name, developer and developer name, category, tags, themes, features, bonus types,
  restricted and prohibited territories, deprecation date, enabled, removed from feed.
- **Brand catalogue configuration**: disabled developers, disabled categories, game overrides.
- **Provider configuration**: per provider of the brand, in the secret store (FR-026).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every `TestScenario15_St8Wallet_*` recording (30) replays with the same status word and
  balance, to the minor unit, as the original answered.
- **SC-002**: A money callback is answered in under 100 ms at the 95th percentile with the wallet in
  the same project, measured in the parity kit.
- **SC-003**: After a debit, a credit, a zero credit or a cancel, the wallet holds no open bet for the
  round, measured over 1,000 rounds including multi-debit rounds: zero leaked bets.
- **SC-004**: A repeated callback never calls the wallet: 1,000 repeats, zero wallet calls.
- **SC-005**: A callback signed with any key but the brand's is refused: 100 of 100.
- **SC-006**: The catalogue routes answer the frontend's Playwright suite unchanged (spec 001 FR-033).
- **SC-007**: A provider's unknown-field or table-switch callback is served: `NewParams` and
  `TableSwitch` replay green.

## Changes from the original

- A credit settles every open bet of its round, not only the first (FR-014); a zero credit likewise.
  The original leaves the other debits of a multi-debit round open in the wallet, which is the
  open-rounds leak decision 0032 bounded and the 2026-08-19 incident reported.
- The generic provider API and the `casino-provider-gateway` are dropped as a stub (Clarifications).
- The test-control surface (`/st8/test/*`) and the launch stub's harness are dropped; parity tests set
  state in the test kit.
- The jurisdiction in the player profile answer comes from the brand's configuration (FR-018), not
  the constant `CW`.
- The bet limits in the launch request are computed from the player's profile as the player profile
  answer computes them (FR-018), not the constant `medium` the original sent at launch.
- The transaction store, round index, player-brand map and callback-failure store become the bet
  workflow per transaction, the round entity and the unacknowledged view; the player-brand map is
  unneeded in a brand project (the known-player record remains, for the profile callback).
- The bet-time `responsible-gambling` check is this service's workflow step, failing closed; the
  original's lives in the ST8 adapter and fails open (spec 001's defect list; moved here from spec
  002 on 2026-10-10).
- Session wagered and won totals are not written to the session; readers derive them from
  `casino.rounds`.
- The bet and round twins, their edges and the read-model projection are replaced by the round entity,
  its views and `casino.rounds`.
- The catalogue, the launch route and the provider's bonus calls move from `casino-service` to this
  service; `rewards` reaches the provider only through it.
- Idempotency records a conflicting repeat (FR-011); the original answers it silently.
- A buyin the wallet refuses is answered `not_enough_money`, `player_locked` or `spending_limit` as
  a debit is (FR-016); the original answered `ok` to a refused buyin.
- A fresh spin at zero balance is declined (the original fixed this; kept).
- A debit the wallet cannot fund is answered `not_enough_money` with the player's balance (FR-012).
  The original means to (`St8Routes.scala` answers the balance the wallet's refusal carries), but its
  wallet's refusal carries none, so it answered `0.00` (found by the recording `NotEnoughMoney`).
- A brand may run several providers at once: a game is identified by provider and code, provider
  configuration is per provider, and launch, the catalogue and the free-rounds routes name the
  provider (FR-001, FR-008, FR-021, FR-023, FR-025, FR-026). The original knows one provider, ST8,
  and keys games by code alone.
- Popular games are ranked by rounds played, from this service's rounds (FR-023); the original ranks
  by game sessions opened at the brand.
- An operator may replay or resolve an unacknowledged callback (FR-020); the original only records
  the failure and clears it when the provider retries.

## Recordings

The Go end-to-end suite, run against the original with `fake-st8`, recorded by ankka-port:
`TestScenario15_St8Wallet_*` (30: BasicBalance, BasicDebit, BasicCredit, LargeWinOnLargeStake,
BasicCancel, BasicBuyin, BasicPayout, ZeroDebit, NotEnoughMoney, ZeroBalanceFreshSpinDeclined,
ZeroCredit, StandaloneCredit, BigRoundMultipleDebits, BigRoundMultipleCredits,
BigRoundMultipleCancels1, BigRoundMultipleCancels2, BigRoundFailedDebit, IdempotentDebit,
IdempotentCredit, IdempotentCancel, BalanceWithoutToken, LimitedCancel, WrongSignature, NewParams,
TableSwitch, CorrectionDebitNegativeBalance, Resettlement, StandaloneCancel,
CancelEverythingWithinRound); `TestScenario7_CasinoGate3_*` (FullPlayerJourney, InsufficientFunds,
RGEnforcement, SessionLifecycle); `TestScenarioFreeSpinsPlay_*` (BonusBalanceDestination,
RealBalanceDestination, StacksOnActiveBonus, CustomerIOGrant); the launch and catalogue reads of the
Playwright suite (spec 001 FR-033).

Recorded 2026-10-10 (T015, `tests/port/README.md`): the 29 `TestScenario15_St8Wallet_*` and the 4
`TestScenarioFreeSpinsPlay_*`. Not recorded: `TestScenario7_CasinoGate3_*`, which fail on the original
itself, and the Playwright reads, which the Go suite's recorder cannot record.

The adapter's own suites, replayed against the service's handlers: `St8RoutesMultiDebitSpec`,
`St8CancelRollbackKeySpec`, `St8StandaloneCreditSpec`, `St8ZeroCreditSettlesLossSpec`,
`St8CallbackFailureLedgerSpec`, `St8DebitCurrencyResolutionSpec`, `St8BalanceCurrencyResolutionSpec`,
`St8ResolvePlayerBrandSpec`, `St8TokenStoreRoundIndexSpec`, `St8WageringTotalsSpec`,
`St8InternalRegistrationRoutesSpec`, `St8DebitBetsPlacedMetricSpec`.

`TestScenario11_CasinoHTTPAPIGate2/3_Provider*` are not recordings: they are skipped in the original
and describe the dropped generic API.

## Assumptions

- The provider's protocol is as `docs.st8api.com` describes it and as the original's coverage
  analysis and scenario 15 exercise it; ST8's own testing tool is the external oracle before go-live.
- The wallet (spec 002) serves bet, win, rollback, settle-as-loss, provider credit and balance over
  HTTP within the project, keyed by the caller's key, with a bet that may be allowed below zero
  (002 US1 "correction debit"); win on an unknown round is `ROUND_NOT_FOUND`.
- `players` (spec 008) serves the account status, the profile fields FR-018 needs, a game session's
  opening (008 FR-017) and the recently-played list; `kyc` (spec 009 FR-020) serves the play gate;
  `responsible-gambling` (spec 010 FR-008) serves `may-play`, whose allowed answer carries the session
  limit and the minutes used. Reconciled on 2026-10-08 once 008–010 existed.
- A `blocked` player is refused at a debit from this service's view of `casino.players` (FR-012),
  which needs spec 008 to publish the transition to and from `blocked` (a `player_blocked` family
  beside `player_suspended`, cleared by `player_reinstated`; 008 FR-031 has the status, 008 FR-035
  no family yet). Until it does, a blocked player is refused at launch only (FR-007, over HTTP).
- `rewards` (spec 011) settles a free-rounds round's winnings from `round_settled` and owns the
  grant; the provider's `winnings_destination` logic (real or restricted) is 011's.
- A provider's retry of a `error` answer arrives within its own retry window; the unacknowledged
  list exists for the ones it gives up on.
- One provider, ST8, is configured per brand today; the model admits several (Clarifications
  2026-10-09), so a second provider is a configuration and an adapter, not a change to stored data.

## Dependencies

- Spec 001 (project, topic contract, route classes, money and port rules), spec 002 (the wallet's
  commands; as built on 2026-10-09: `POST `/v1/casino/brands/<brand>/wallets/{player}/{currency}`/bets`, `/wins`, `/rollbacks`, `/losses`,
  `/provider-credits` and `GET …/balance`, minor units, the provider's transaction id as `key`, a win,
  rollback or loss naming the bet it settles as `betKey` in a round with several bets; since 2026-10-10
  the wallet keeps only its recent 1,000 keys and asks no `may-bet`: this service's bet workflow is the
  transaction's lifetime record and asks `may-bet` itself; specs/002-wallet/contracts/routes.md), spec 008 (`players`), spec 009 (`kyc`, the play gate), spec 010
  (`responsible-gambling`), spec 011 (`rewards`).
- ankka 022 (player and operator tokens), 038 (the provider configuration), 032 (the nightly refresh
  as a recurring timer), 024/043 (`casino.rounds` as a fact topic).
- `fake-st8` (the original's stub) for recordings; spec 020 (the simulator) for local runs and the
  failure scenarios — its ST8 adapter (020 FR-007 to FR-014) and FR-002 to FR-006 here describe one
  protocol, reconciled on 2026-10-08.

## Out of scope

- The generic provider API and a second provider's adapter (the model admits one; its adapter is
  written when a provider is contracted).
- The game sessions themselves and their ending (`players`, spec 008).
- Free-rounds grant lifecycle, eligibility and winnings settlement (`rewards`, spec 011).
- The frontends and the lobby's presentation of the catalogue.
