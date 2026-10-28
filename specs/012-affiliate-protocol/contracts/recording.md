# Recording the original's affiliate behaviour, and replaying it

Research R19. The method is `tests/original/README.md`. The KinD stack spec 003 recorded on ran no
affiliate service, so scenario 17 and the scenario-15 GGR tests skipped themselves; T035 recorded them on
2026-10-28 with it running (`tests/port/attribution/README.md`). This file says what
`tests/port/attribution/` holds and how a recording of an internal notification is held against a
service that has no such route.

## The suite's scenarios, recorded with the affiliate service running

Run on the original's KinD stack with `affiliate-service` installed and `affiliate_notify_ggr` on
(`values.yaml:337-343`), twice, on fresh ids. The original hard-skips all four (`t.Skip("blocked:
affiliate-service NodePort 30093 has no KinD extraPortMappings entry ...")`), so the run removes the skips
in its local checkout, and corrects scenario 17's seed namespaces (`affiliate-plans/<brand>` and
`affiliates/<brand>`, which the service never reads, for `affiliate-plans-<brand>` and `affiliates-<brand>`).
`tests/port/attribution/README.md` has the steps:

| Recording | Steps the recorder sees | Requirement |
|---|---|---|
| `TestScenario17_AffiliateGate3_CPAPipeline` | seeds the plan, affiliate and link twins; `notify/registration` (first tag wins); `notify/deposit` (the FTD); the revenue twin read; the same two again (no double count) | FR-007, FR-015, SC-001 |
| `TestScenario15_St8Wallet_RestrictedOnlyRoundEarnsNoAffiliateGGR` | a bonus-only player's round; no affiliate fact appears | R10 (real money only), SC-001 |
| `TestScenario15_St8Wallet_SplitRoundEarnsRealGGROnly` | a split-funded round; one GGR fact with the real part | R10, SC-001 |
| `TestScenario15_St8Wallet_ConvertedMoneyRoundEarnsNoAffiliateGGR` | a round funded by converted money; no fact | R10, spec 025: matches (the original's GGR fact of 0 is the port's day with no `ggr-daily`); was marked until the wallet carried the pools |

## `tests/original/attribution-driver/`: what the suite never reaches

A Go test package after `tests/original/ingest-driver/`, run with `-tags e2e` against the original's
`affiliate-service` (`localhost:8093`, its NodePort) and the substrate on the KinD stack. One scenario per feature
scenario the original can show, named as the feature names it; each seeds its own affiliate and link
twins as `setup:` steps.

| Feature scenario | Steps on the original | Replay |
|---|---|---|
| a landing with a valid tag records one click per browser session and emits a click fact | `POST /v1/track/click` with a seeded `btag` and a `clickId`, `deviceType`, `utm*` | **marked**: the original mints its own click id and answers it; the port answers the one sent. The path, the body and `ok` replay unmarked |
| the same, with no `btag` | `POST /v1/track/click` without `btag` | unmarked (`400`) |
| a visitor who registers within the attribution window is bound once and for good | click, `notify/registration` with the `btag`, the registration twin read | the notification becomes a `player_registered` fact on the kit's broker; the twin read becomes the expected `registration` fact |
| a later tag does not change a binding and is recorded as a later touch | two `notify/registration` for one player with two tags | unmarked: the first wins in both |
| a player's first captured deposit emits a first-deposit fact and a deposit fact | `notify/deposit` twice, the revenue twin read | the notifications become `payment.captured` facts; **marked** for a third deposit in a later month: the original counts a new FTD per period, the port never |
| what happens to an attributed player's money is emitted as the matching fact (withdrawal rows) | `notify/withdrawal`, `notify/withdrawal-reversal` | **marked**: the original is told at the approval; the port emits `withdrawal-requested` at the hold and `withdrawal-completed` at completion, and the reversal maps one to one |
| what happens to an attributed player's money … (bonus-cost row) | `notify/bonus-cost` as casino-service sends it (`claimId`) | **marked**: the original answers `400` and records nothing; the port emits `bonus-cost` from the wallet's award |
| what happens to an attributed player's money … (chargeback row) | `notify/chargeback` | **marked**: nothing in the original calls it; the port emits from a dispute or a refund |
| closing … emits one player-closed fact | none: the original has no closure notification | no recording; `ClosureSuite` |

The third deposit in a later month cannot be driven: the original takes the period from its own clock.
The first-deposit recording holds two deposits in one month, and `MoneySuite` holds the rule for a later
one. The driver's `NotDriven` and `NotDrivenFeatures` name every other feature scenario it does not
record, with the reason, and its offline test fails when a feature scenario is neither driven nor named.
The affiliate service answers on KinD's host port 8093 (its NodePort 30093; host 8082 is
`substrate-graphql`), and `go run ./original/preflight -affiliate http://localhost:8093` checks it answers
first. *Recorded 2026-10-28*: `tests/port/attribution/README.md` says how, and where the run differed.

## The translation (`ParitySuite`'s `Translate`)

A recorded step is one of:

- **a public click**: replayed as the request it is, against the service, the answer compared with the
  divergence marked;
- **a notification** (`notify/*`): turned into the brand fact that now carries it and published on the
  kit's `InMemoryBroker`: `registration` → `player_registered` (`btag`, `clickId`, `bonusCode`,
  `registered_at` = the recorded `occurredAt`); `deposit` → `payment.captured` (`amountCents` from the
  major-unit string × the currency's exponent, the payment id = `transactionId`); `withdrawal` →
  `withdrawal_requested` then `withdrawal_completed`; `withdrawal-reversal` → `withdrawal_reversed`;
  `ggr` → the wallet's `bet_placed` and `win_credited` with the recorded `real_stake_value` and
  `real_win_value` as the real balance movements; `bonus-cost` → `restricted_funds_awarded`;
  `chargeback` → `payment.dispute.opened`;
- **a twin read** (`affiliate-registration-*`, `affiliate-facts-*`, the revenue twin): turned into the
  attribution facts expected on the topic, compared by kind, pseudonym (derived under the kit's key from
  the recorded player id), amount in minor units and reference; the recorded major-unit strings are
  converted exactly, and a daily GGR expectation is the sum of the recorded per-round GGR for the day in
  the kit's zone (SC-001);
- **a seed** (`affiliate-links`, plans, affiliates): kit setup: the link's `btag` UUID goes into the
  `FakeNetwork`'s legacy tag document for the affiliate and link, so the recorded bare UUID binds through
  the transition rule (US5).

The divergences above are the rows plan.md's *Divergences* names; `CoverageSuite` checks them against it.
The recording run is against a local original only, never a shared or production one, and is a task of
its own; the parity tests are written first and fail until it lands.
