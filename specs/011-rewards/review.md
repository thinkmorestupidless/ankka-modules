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
