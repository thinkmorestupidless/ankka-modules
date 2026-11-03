# Review: 011-rewards — the brand's rewards service, with its neighbours' amendments

**Verdict:** Request changes
**What it does:** Builds `services/rewards/` (offer book, per-player record, per-reward entity, keyed
`WalletStep` and `FreeRounds` workflows, per-reward timers, view, consumers and publishers, four endpoints),
ported from the original's `BonusRoutes`, evaluator and TL functions and held to 47 recordings. It amends the
wallet (W1–W5), game-provider (G1), players (FR-034 to FR-036), attribution (free-rounds cost) and the
contract to match.
**Should it exist:** yes, because spec 011 is next in the build order. The design (one decider per player,
one entity per reward, a keyed workflow as the lifetime record of each wallet command, a timer per deadline in
place of the daily scan) fits the repository's established pattern (wallet, payouts) and the constitution.
**Read:** in full: `reward/` (Model, Reward, Transitions, Settlement, Clocks), `steps/` (WalletStep, Wiring,
WalletAnswer, Awards, FreeRounds), `player/PlayerRewards.scala`, `player/Flows.scala`, `consumers/*`,
`facts/*`, `RewardsService.scala`, `RewardsCodecs.scala`, `Settings.scala`, `api/RewardsEndpoint.scala`,
`api/Ctx.scala`, `api/RewardAdminEndpoint.scala`, `api/Lifecycle.scala`, `api/PlayerGrants.scala`,
`build.sbt`; the diffs of the contract, wallet, game-provider, players and attribution main code, `ci.yml`
and `Justfile`; spec.md, plan.md, tasks.md, contracts/facts.md, research.md R10, the last docs commit
(d7e3222d). Skimmed: `api/OffersEndpoint.scala`, `api/CampaignEndpoint.scala`, `api/FreeRoundsRoutes.scala`,
`reads/RewardsView.scala`, the test suites named below. Not read line by line: `tests/port/rewards/*.json`
(recorded data, ~76k lines), the Go driver under `tests/original/rewards-driver/`, `eligibility/` and
`offers/OfferRules.scala` beyond spot checks (pure ports held to the original's own suites, which pass).
Outside the diff: `services/wallet/.../WalletEntity.switchPrimary` and its `expiresAt` handling, ankka's
`http/Caller.scala` (`CallerMatcher.NamedService`), `contract/.../access/Routes.scala`, the original's
`award_bonus/handler.go:135-195`.
**Size:** 287 files, +98,376 −295. About 76k lines are recordings and ~17k are Scala (~5k main, the rest
tests), plus ~2.5k of docs. The hand-written part is far past the size where review finds defects reliably.
See the first Should-fix finding for the split.

## Blocking

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/reward/Reward.scala:116-117`: issue (blocking).
  The payment for a finalisation that a completing round triggers is lost if the round is delivered again.
  `RoundsConsumer` (`consumers/RoundsConsumer.scala:30`) persists the round, and with it
  `FreeRoundsFinalised` and `Closed`, and only then starts the payment with `WalletSteps.current.pay`. That
  call does a `Reward.get` and a `WalletStep.start`. If either fails, the consumer throws and the fact comes
  back. The second delivery hits `Skip.AlreadyCounted`, which answers only `f.latePaid.get(roundId)`. That is
  `None` for a round counted before finalisation, so no payment is started. Nothing else recovers it:
  `Clocks.changes` cancelled `rounds-<reward>` on `FreeRoundsFinalised`, so `finalise`'s
  "repeat answers the same payment" path never runs. The player's winnings are never paid. The deposit path
  handles the same case (`depositConfirmed` answers the earlier claim on a repeat), and `finalise` does too
  (`s.finalPayment`); only the round path does not. `RewardSuite`'s repeat test
  (`RewardSuite.scala:176-184`) repeats a round that did not complete the count, so it cannot catch this.
  Suggestion: on `AlreadyCounted`, answer the late payment when there is one, otherwise the finalisation's.
  `WalletStep.start` is idempotent by step id, so starting it again is safe:
  ```scala
  case Left(Skip.AlreadyCounted) =>
    val late = f.latePaid.get(c.roundId).filter(_ > 0).map(Payment(f.terms.destination, _, Some(c.roundId)))
    skip(Skip.AlreadyCounted.reason, late.orElse(Option.when(f.tally.finalised)(s.finalPayment).flatten))
  ```
  Add a `RewardSuite` case that repeats the round that completed the count and expects the same `payment`.
  Resolved: a repeated round answers the late payment, else the finalisation's (`s.finalPayment`); `RewardSuite` repeats the completing round, 2876da7d.

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/Wiring.scala:119-141`: issue (blocking).
  A free-rounds win paid to the bonus balance, or a late round paid the same way, becomes a wallet record
  that nothing ever expires, closes, cancels or switches. Its key is `<reward>/winnings` or
  `<reward>/late/<round>`. The 30-day `expiresAt` it is awarded with is stored by the wallet
  (`WalletEntity.scala:428`), but the wallet never acts on it, and rewards sets no timer for it:
  - `Wiring.move` answers `Awarded` for `Winnings` and `LateAward` with `Idle("nothing_to_move")`.
  - `wagering-<reward>`, which research R10 designed for exactly this case ("free-rounds winnings"), is never
    set. No caller passes `wageringExpiresAt` (the only `Open(…, Active, …)` is
    `FreeRoundsGrants.scala:132`, without it), so `Clock.Wagering` is dead code.
  - Once such a record converts, `MoneyConsumer` sends `Convert` to a reward id that is not opened. Its pending
    cap therefore never gets a `cap-` timer and never closes.
  - `PlayerLifecycle.active` (`Lifecycle.scala:45-49`) and `switch` both resolve a record through an opened
    `Reward`, so the player can neither cancel nor switch these records. The plan admits this: "the cancel is
    open, see the report".

  The original's grant for this award had a 30-day expiry that `BonusExpirySweep` enforced
  (`award_bonus/handler.go:186-195`). plan.md's *Divergences* row for winnings ("not a reward of their own")
  does not declare losing the expiry or the cap close. This leaves restricted funds open without end and
  breaks constitution I. Suggestion: give the winnings award a reward of its own, opened `ACTIVE` with the
  30-day `wageringExpiresAt` through the timers that already exist, so expiry, cap, cancel and switch work as
  for any reward. Or, if winnings are to stay a bare record, add the expiry and cap timers keyed by the
  record's key, and declare the remaining cancel and switch gap as a divergence with its follow-up. Either way,
  remove or wire `Clock.Wagering`, and add a `ClockSuite` case for a winnings record reaching 30 days.
  Resolved: winnings to the bonus balance are a reward of their own under the key they had (`<reward>/winnings`, `<reward>/late/<round>`), opened from the free rounds' and activated by the `winnings` step, so the claim deadline, cap, cancel and switch are any reward's; the user chose this (research.md, "Winnings, a reward of their own"); plan.md's row rewritten; `SettlementItSuite` asserts the reward active to the award's deadline, `WiringSuite` the keying and activation, 5e8c21dd, f31e7fed. `Clock.Wagering` is left: no reward has a wagering deadline apart from its claim deadline (research.md).

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/api/PlayerGrants.scala:22-35`: issue
  (blocking). The hand-written ACL accepts a `support-agent` from any project. `supportAgent` matches
  `Caller.Service(_, "support-agent")` and ignores the caller's project. `Routes.serviceOf("support-agent")`
  goes through `Callers.service(name)`, which ankka checks as `cp == self.project && n == cn`
  (`ankka/http/Caller.scala:142`). Every brand runs its own `support-agent` (spec 017), so another brand's
  agent is let through to this brand's players' grants. Whether it can reach the route depends on the
  installation's network, and the ACL must not rely on that. The same check also decides, in the handler
  (`RewardsEndpoint.scala:64`), to skip the `mine(p)` ownership check. This is also the only ACL in any
  service's main code built outside `contract.access.Routes`, which CLAUDE.md forbids ("It declares ACLs only
  through `contract.access.Routes`"). Suggestion: add a combinator to `Routes`, e.g.
  `Routes.playerOrService(slug, "support-agent")(authenticate)`, built from `Callers.service(name)` so the
  project check is ankka's. Have the handler test the principal that combinator yields rather than the raw
  caller. Add an integration case where a `support-agent` of another project is refused.
  Resolved: `support-agent` is admitted only from this project (the brand's slug unless the endpoints are given one, as wallet's `serviceNamed(ownProject)`); the handler tests the same; `PlayerGrantsSuite` refuses another project's, 389502dc. The ACL still composes `Routes.player` in rewards rather than a new `Routes` combinator: a contract change would need a contract publish this branch does not make; a follow-up for the contract.

## Should fix

- `Description`: suggestion. Land the neighbour amendments as their own pull requests ahead of rewards:
  wallet W1–W5, game-provider G1, players FR-034 to FR-036 (a behaviour change to every registration: the
  weak signal now blocks no-deposit offers), attribution's free-rounds cost, and the contract. Each changes a
  live service's money or registration path, and each deserves a reviewer who is not also reading 5k lines of
  a new service. The commits are already split per task, so this is a matter of rebasing them out.
  Declined: the user chose one pull request (research.md, "One pull request"); spec 011 scopes the amendments as part of the feature, and the pull request body lists them by service.

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/api/FreeRoundsRoutes.scala:55-58`: issue
  (non-blocking). `GET /{playerId}/free-rounds/eligible`, the read game-provider makes at launch, answers from
  `RewardsView`, which lags the reward. T071's local run recorded the result in research.md: "A launch made
  before the new grant reaches the free-rounds list opens a session without `freeSpinsGrantRef` … the reward
  never counts them … shows up here as lost rounds". The same commit says of the kind run "none of it in
  rewards", but this lag is rewards'. Suggestion: answer from the player's record and the reward, which are
  consistent at once. `PlayerRewards` holds each reward's status and `Reward` its free-rounds terms. Keep the
  view for the operator lists. Or, if the view stays, declare the divergence and file the follow-up the
  research note implies.
  Resolved: the eligible read uses the player's record and each reward; a grant reports its rounds to the record at once (the record learned of an operator's rounds only through the index consumer); the view's query is removed; `FreeRoundsRoutesSuite` launches before the view has the grant, 2286aa14.

## Nits

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/WalletStep.scala:200-204`: nitpick
  (non-blocking). The comment says winnings take "the open records' lowest priority less one, so they are spent
  after every open record". The wallet funds from the lowest number first, so `min - 1` makes the winnings the
  primary, spent first. That is the original's rule (`award_bonus/handler.go:139-150`) and what the code does.
  The comment says the opposite, and `Destination.RestrictedFunds`'s doc ("ahead of every open record") is
  right. Correct the comment.
  Resolved: the comment says the winnings are the primary, spent first, 5e8c21dd.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/reward/Clocks.scala:105-122`, `RewardsCodecs.scala:67-75`:
  nitpick (non-blocking). The comments still say the wiring comes "once it exists (task T027)". Between
  `.start(…)` and `RewardsService.started` (`RewardsService.scala:60-66`), consumers, timers and steps throw
  until the globals are set, and recover by retrying. Say that in one line at `started`, and drop the
  task-era wording.
  Resolved: the comments name `RewardsService.started`, and one line at `started` says what retries until then, 3a3bed59.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/WalletStep.scala:135,149-150`, `steps/FreeRounds.scala:119,126,147,171`,
  `steps/Wiring.scala:100`, `consumers/IndexConsumer.scala:35`: nitpick (non-blocking). The steps read
  `currentState.request.get`, `answer.get` and `cancel.get`, and two places test the type with
  `isInstanceOf`. The invariants hold (`start` sets the request before any step runs), but a `match` that
  fails the step with a message says so where a `NoSuchElementException` does not. Same pattern everywhere,
  noted once.
  Left: nit.

## Questions

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/api/Lifecycle.scala:69,88`, `steps/Wiring.scala:102`:
  question. A step that the wallet refused stays refused for its id. `switch-<n>` uses `s.switches + 1`,
  which only a `Switched` event advances. After one refused switch, every later switch of that reward answers
  the old refusal. Likewise `<reward>/cancel`: a refused player cancel is final. The compensating cancel for
  an award that lands after its reward ended reuses that same id, so it becomes a no-op if the player's
  cancel ran first. Is a refusal meant to be final for these steps, as it is for `award`, where
  `Claim.replaces` gives a fresh id? If not, number the attempt rather than the success, as `replaces` does.
  Answered: a refusal is meant to be final. The wallet refuses a switch or cancel for a record that is not open, which does not reopen. The player's cancel is only started for a reward `ACTIVE` here and open at the wallet, and the compensating cancel only for a reward already ended by another path, so the two never share an id for one reward.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/FreeRounds.scala:131-138`: question. A
  create that times out (`Unreachable`) is recorded as `failed`, and the reward is cancelled
  `provider_failed` without a cancel at the provider. The provider may well have created the rounds. Its
  rounds would then settle against a cancelled reward (`not_live`), and their winnings would never be paid.
  Was this the original's behaviour, recorded? If so, name it among the divergences or the known gaps.
  If not, ask the provider's status (or cancel) before cancelling the reward.
  Answered: the original's behaviour: an unanswered create is marked `provider_failed` and the claim voided with no cancel at the provider (`FreeSpinsProvisioner.scala:192-201`). Listed among the pull request's known gaps.
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/WalletEvent.scala:346-349`,
  `publish/Facts.scala:118-120`: question. `PendingCapClosed` events journaled before W5 replay with
  `realAfter = 0` and the pools at 0. If the wallet's publisher ever reads them again (a reset offset, a new
  publisher id), it publishes `restricted_funds_closed` with zero balances. Is the publisher guaranteed past
  them? If not, publish nothing for an event whose balances are absent, or mark the fields `Option`.
  Answered: no wallet journal holds a `PendingCapClosed` from before W5 outside test installations (no casino installation is live; kind's is rebuilt), so no publisher can read one.

## Praise

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/WalletStep.scala:97-130`: praise. Making
  the step's state the lifetime record of a wallet command means a key the wallet has forgotten is answered
  here and never resent. `RepeatSuite`'s "after 1,000 other keys" cases test exactly that. This is the right
  answer to the wallet's bounded memory of recent keys.
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/entity/Commands.scala:238-248`: praise.
  `CancelAward.Unset` and `ClosePendingCap.Unset` leave the new fields out of the digest while they hold
  their defaults. A cancel or close keyed before this change therefore keeps its digest, rather than turning
  into `KEY_REUSED` across the rolling deploy.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/reward/Settlement.scala`, `reward/Transitions.scala`, `reward/Clocks.scala`:
  praise. The money and state rules are pure functions, each tied to the original's handler by line and held
  to its tables. The timers' stale-deadline check (`Due` carries the deadline it was set for) makes a
  redelivered or replaced timer a no-op without bookkeeping.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/player/PlayerRewards.scala:107-110`: praise. A
  redelivered deposit answers the claim it made, so a consumer that failed after the claim starts the same
  award step again. This is the pattern the round path is missing (first Blocking finding).

## Out of scope

- note: T071's findings in research.md (game-provider reads a key document format the simulator does not
  publish; `modules apply` blocking on a core service that reads a casino schema; unchanged image tags not
  rolled; players' `INGEST_URL` unreachable from a pod) are defects of specs 003, 020, 021 and 008. Each needs
  an issue of its own; none is filed from this branch.

## Checks applied

General checklist: Correctness, Design and scope, Error handling, Concurrency and state, Security (the new
entry points, their ACLs and the CI workflow), Data and migrations (event and command evolution in wallet,
players and the contract: additive, defaulted, and digest-compatible), Performance (the request path's 2 s
busy-poll follows payouts' pattern and was not flagged), Tests, Observability, API and compatibility (the
contract additions are `Option` with `None` defaults), Dependencies and build (no new libraries beyond
munit-scalacheck and the Postgres driver, both `% Test`), Documentation, Commit and PR hygiene (Conventional
Commits with scope `011`, trailer present). No group skipped.

Language: `pr-review-scala` (bundled; the repository has no skill of its own), Scala 3.9.0. Applied:
concurrency and actors (consumers and timers calling entities synchronously; static `@volatile` wiring),
serialization (journaled types named and versioned through `RewardsCodecs.serializer`; enums written by wire
name), types and totality, error handling, collections, tests, build, API, idioms. `-Wunused:all` is on and
owns unused code. There is no `.scalafmt.conf` and no `-Werror`.

Project rules: CLAUDE.md (root and the eitheror brain), the constitution's principles as plan.md cites them.
There is no `.claude/rules/` and no `REVIEW.md`.

Tests: `CASINO_VERSION=0.2.0-011-SNAPSHOT sbt test` in `services/rewards/` ran 265 unit suites: 265 passed,
0 failed. The contract snapshot was published on 2026-11-01. The only later contract change is docs. Not
run: the integration, parity and feature suites (`-Dit=true`, Docker, several minutes each), and the wallet,
players, game-provider and attribution suites. The first two Blocking findings rest on reading, and each
names the test that would show it.

## Second round

# Review: 011-rewards — the first round's fixes (d7e3222d..6126e13a)

**Verdict:** Request changes
**What it does:** Answers the first round's 11 findings in 7 commits. A repeated completing round answers the
finalisation's payment again. Only this project's `support-agent` reads grants. Free-rounds winnings to the
bonus balance become a reward of their own. The launch's eligible read uses the player's record and each
reward rather than the view. The task-era comments are corrected.
**Should it exist:** yes, because each commit answers a finding of the first round, and nothing else is in
the range.
**Read:** in full, the diff of every main and test file in the range, plus plan.md's and research.md's
changes. Outside the diff: `reward/Reward.scala` (`open`, `activate`, `activeDeadline`), `reward/Clocks.scala`,
`reward/Settlement.scala` (`Destination`, `Payment.key`), `reward/Model.scala` (`Opening`),
`player/PlayerRewards.scala` (`select`, `rewardChanged`, `indexed`), `player/Model.scala` (`HeldReward`,
`PlayerRewardsState`), `eligibility/Evaluator.scala` (`Held`, `Standing`, rules 4 to 7),
`consumers/IndexConsumer.scala`, `api/Lifecycle.scala` (`active`, `cancel`, `switch`),
`api/RewardsEndpoint.scala:85-170` (the player's state and visible offers), wallet's
`api/ServiceEndpoint.scala:302` (`ownProject`), attribution's `inputs/RewardsFacts.scala`, and the original's
`tl/runtime/capabilities/award_bonus/handler.go:130-240` plus spec 196's `data-model.md` (`bonus_source_ref`).
Not reviewed: the recordings, which are unchanged.
**Size:** 22 code files, about +170 −77 hand-written lines, plus docs. Small.

### Resolutions of the first round

- **Blocking 1 (repeated completing round loses the payment): holds.** `Reward.roundSettled` answers
  `late.orElse(s.finalPayment)` on `AlreadyCounted`. The new `RewardSuite` case repeats `rd-2`, the round that
  completed the count, and expects the same payment. Without the fix it would fail, because the old code
  answered `None`. Restarting the step is safe, since `WalletStep.start` is idempotent by step id and
  `Wiring.pay`'s new `Reward.open` is a no-op on an opened reward. A repeat of an earlier round, counted
  before finalisation, now also restarts the payment. That is harmless for the same reason.
- **Blocking 2 (winnings record never expired, closed, cancelled or switched): partly holds.** The winnings
  are a reward of their own under the wallet key they had. `Activated` sets the `claim-` timer
  (`isFreeRounds` is false for a `BonusFunds` opening). `Converted` sets the cap timer. `Lifecycle.active`
  resolves the wallet record's `grantRef` to the reward, so cancel and switch reach it. `SettlementItSuite`
  drives the real path to an `ACTIVE` `<reward>/winnings`. The record's lifetime is fixed. But the new reward
  is opened under the free-rounds **offer's** id, and that changes the player's eligibility and visible offers.
  See the new Blocking finding below. Leaving `Clock.Wagering` unused, with the reason in research.md, is
  acceptable.
- **Blocking 3 (any project's `support-agent`): holds.** `supportAgent(ctx, c)` compares the caller's project
  with `ctx.ownProject`, which is the brand's slug unless one is given. That is the same convention as wallet's
  `ServiceEndpoint.scala:302`. The handler uses the same test, and the principal now names the project.
  `PlayerGrantsSuite` refuses `other-brand`'s agent with 401. The ACL is still composed outside
  `contract.access.Routes`. The reason, a contract publish this branch does not make, is fair, but the
  follow-up is recorded nowhere in the repository (see Should fix).
- **Should fix (split the PR): declined, and the decline is recorded** in research.md ("One pull request").
  Accepted.
- **Should fix (eligible read lags the view): holds,** with a gap in its test (see Should fix). The read now
  goes through `PlayerRewards.get` and `Reward.get` for each held, non-terminal reward. It applies the filters
  the view applied: active, game, provider, provider status not failed, oldest grant first. The operator
  grant path reports to the record as soon as the reward is opened. The view query and its test are removed.
- **Nit (priority comment): holds.**
- **Nit (task-era comments): holds.**
- **Nit (`.get` and `isInstanceOf`): left as a nit.** That is fine.
- **Question (refused switch or cancel is final): answered.** The reasoning holds for the two paths named.
- **Question (unanswered provider create): answered.** It is the original's behaviour. The answer says it is
  "listed among the pull request's known gaps", but there is no pull request yet and nothing in specs/ records
  it (see Should fix).
- **Question (`PendingCapClosed` replay): answered.** No live journal predates W5. Accepted.

### Blocking

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/Wiring.scala:142-145`: issue (blocking).
  The winnings' own reward is opened with the free-rounds **offer's** id (`s.offerId`). The player's record
  then indexes it as a reward of that offer, and the evaluator counts it.
  - `Held.claim` is true for an `ACTIVE` reward. Rule 6 (`maxClaimsPerPlayer`) therefore counts one free-rounds
    claim twice once its winnings go to the bonus balance. An offer limited to 2 claims allows one.
  - Rule 5 (`conflictsWith`) and rule 7 (`excludedOfferIds`) now see the free-rounds offer as open or claimed
    for as long as the winnings are wagered, after the free-rounds reward itself has closed.
  - While the winnings award is in flight, and for good if the wallet refuses it, the reward is `AVAILABLE`.
    `Wiring.move` maps a refused `Winnings` to `RefuseAward`, which leaves the status as it was. The record holds
    it as `unclaimed`, and three things follow:
    - `GET /{playerId}/state` lists it among `visibleOffers` while the offer is live (`RewardsEndpoint.scala:154`).
    - A `select` of the free-rounds offer finds it through `unclaimed(offerId)` and claims a free-rounds offer
      against a `BonusFunds` reward.
    - `makeVisible` (`PlayerRewards.scala:36`) treats the offer as already visible.

  The original did not do this. Its grant's `OfferRef` is `params.BonusSourceRef` (`handler.go:212`), which is
  the free-spins **grant** id (spec 196 `data-model.md:79`, "points back to the Free Spins Grant `grant_id`").
  The grant therefore never counted as a claim of the offer. plan.md's rewritten row says "the free rounds'
  offer" but does not declare these consequences, and no test covers them.

  Suggestion: open the winnings' reward with `offerId = s.rewardId` (the free-rounds reward, as the original's
  `offer_ref`), and keep `offerName` and `snapshot` from the parent so the operator lists still read well. An
  id the offer book does not hold is already left out of the visible offers (`inBrandOrder`) and matches no
  offer's rules. Also end the reward when its award is refused, rather than leaving it `AVAILABLE`: for
  example, `Wiring.move` answers a refused `Winnings` with `Landing.Cancel` or `Landing.Expire`, with a
  reason. Add an evaluator or `PlayerRewards` case: a free-rounds offer with `maxClaimsPerPlayer = 2`, one
  claim whose winnings went to the bonus balance, and a second claim allowed. Add a `WiringSuite` case for the
  refused winnings.
  Resolved: the winnings reward's offer is the free-rounds reward (`offerId = s.rewardId`), with the offer's name and snapshot, so no offer's claim limit, conflicts, exclusions or visible offers count it; a refused `winnings` award expires it (`winnings_award_refused`, from `AVAILABLE`, no fact). `WiringSuite` covers the opening's offer and the refusal; the evaluator counts held rewards by offer id, so the claim-limit case follows from the opening's, e34d9812.

### Should fix

- `services/rewards/src/test/scala/io/eitheror/casino/rewards/it/FreeRoundsRoutesSuite.scala:31-41,79-83`:
  issue (non-blocking). The new "launch just after the grant" test sends the `IndexConsumer.current`
  report itself, in its `live` helper. It therefore shows that the read uses the record, but not that the
  grant path reports to the record. Remove the two lines added to `FreeRoundsGrants.scala:133-136` and the test
  still passes. Suggestion: drive the real operator grant route (or `FreeRoundsGrants`) and call `eligible`
  immediately after it, without waiting for the view. Keep the helper's direct report only for tests that do
  not cover the grant.
  Resolved: `FreeRoundsGrantSuite` gives an operator's rounds through `FreeRoundsGrants.give` and reads the player's record as the grant answers, `ACTIVE`, 283e7dd3. The routes suite's helper keeps its direct report for the read alone.
- `specs/011-rewards/research.md`: todo. Two first-round answers point at records that do not exist yet. The
  contract combinator follow-up (`Routes.playerOrService`) has none. The unanswered provider create, "listed
  among the pull request's known gaps", points at a pull request that has not been written. Add both to
  research.md's findings, or to a "Known gaps" section that the pull request body will copy, so they survive
  until the pull request exists.
  Resolved: research.md's "Known gaps for the pull request" holds both, with the cancel clawback and the winnings deadline below, 343c0c0b.

### Nits

- `services/rewards/src/test/scala/io/eitheror/casino/rewards/port/Parity.scala:81-82`: nitpick
  (non-blocking). The divergence was renamed "a reward of their own", but its explanation still says "a wallet
  record of their reward …, not a reward of their own". Update the text to match the plan row.
  Resolved: the explanation matches the plan row, 343c0c0b.

### Questions

- `services/rewards/src/test/scala/io/eitheror/casino/rewards/port/Parity.scala:83-85`: question. The
  divergence still covers scenario 244's cancel steps (`CancelWithBaselineClawsBackOnlyWinnings` 32,
  `CancelWithoutBaseline…` 31). The old plan row explained those steps as "the player's cancel finds no reward
  to cancel". Now that the winnings are a reward that the cancel reaches, does the port answer those steps as
  the original did, apart from the grant id? If so, narrow the predicate to the fields that still differ, so
  that parity holds the new cancel to the recording. If not, what still differs? The answer is settled by
  running the parity suite with those steps unmarked. It was not run here, because it needs Docker.
  Answered: run with both steps unmarked, step 31 (no baseline) matches the original and is unmarked; step 32's clawback is 0.00 against the original's 20.00, so it stays marked and is a known gap in research.md and plan.md, 343c0c0b.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/Wiring.scala:156`: question. The
  winnings' deadline is `Reward.activeDeadline(snapshot, false, at)`, which is the offer's
  `wageringWindowDays`, else 30 days. The original set `expires_at` to a fixed 30 days (`handler.go:184-190`)
  and a separate `wagering_expires_at` only when `BonusWageringExpiryDays` was passed. research.md says the
  original "did the same (… a 30-day expiry)". For an offer with a wagering window other than 30 days, the two
  differ. Is that intended? If so, add a divergence row. If not, use 30 days. `SettlementItSuite` covers only an
  offer with no window.
  Answered: intended; the offer's window is what the wallet's award for the offer itself uses. Recorded among the known gaps and in plan.md's row, 343c0c0b, e34d9812.
- `services/rewards/src/main/scala/io/eitheror/casino/rewards/api/FreeRoundsRoutes.scala:68-76`: thought.
  The launch read now costs one `Reward.get` for each of the player's non-terminal held rewards, and that
  includes every offer made visible but not claimed. A heavily targeted player makes a launch do dozens of
  entity reads where the view did one query. This is probably fine at today's volumes. Filtering `held` to
  rewards whose `HeldReward` is not `Available` or `Pending`, or that are `claiming`, would bound it to the
  rewards that can be live.
  Resolved: only rewards being claimed, or held neither ended nor unclaimed, are read, 9de3af8e.

### Praise

- `services/rewards/src/main/scala/io/eitheror/casino/rewards/steps/Wiring.scala:133-137`: praise. Keeping
  the wallet key the winnings already had (`<reward>/winnings`, `<reward>/late/<round>`) as the new reward's
  id means the wallet's records and the attribution's `grantRef`s are unchanged. The reward is also reached by
  the lookups that already exist (`Lifecycle.active`'s `grantRef` to reward). No migration and no new lookup.
- `services/rewards/src/test/scala/io/eitheror/casino/rewards/reward/RewardSuite.scala:187-194`: praise. The
  regression test repeats exactly the round that completed the count and asserts that the repeat writes
  nothing. It fails on the old code.

### Checks applied

General checklist groups applied:
- Correctness, Design and scope, Error handling, Concurrency and state (the direct report and the consumer's
  report racing to `rewardChanged`, which is idempotent on equal reports).
- Security (the ACL change).
- Data and migrations (the step ids of winnings move from `<reward>/winnings` to `<reward>/winnings/winnings`
  and the `late-award` call is removed. Nothing is live, so no stored step is orphaned).
- Tests, Observability, API and compatibility, Documentation, Commit and PR hygiene. The commits follow
  Conventional Commits with scope `011` and carry the trailer.

General checklist groups skipped: Performance beyond the thought above, and Dependencies and build (none
changed).

Language checks: `pr-review-scala` (bundled), applied to types and totality, error handling, tests and
idioms.

Project rules: CLAUDE.md.

Tests run: `CASINO_VERSION=0.2.0-011-SNAPSHOT sbt "testOnly …RewardSuite …WiringSuite"`, 35 tests, all
passed.

Tests not run: the integration and parity suites (`SettlementItSuite`, `FreeRoundsRoutesSuite`,
`PlayerGrantsSuite`, parity), which need Docker and several minutes. The new Blocking finding rests on reading
`Evaluator` and `PlayerRewards`. The test it names would show it.
