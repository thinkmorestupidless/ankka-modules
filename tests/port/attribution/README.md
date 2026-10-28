# attribution's recordings (spec 012, T035)

Recorded 2026-10-28 from the original at `a78decf1` on its own KinD stack, twice, on fresh ids, with
`affiliate-service` installed and `functions.notifyAffiliateGgr` on (`deploy/kind/casino-values.yaml`).
`services/attribution`'s `ParitySuite` replays all twelve (specs/012-affiliate-protocol/contracts/recording.md).

- **The suite's four**: `TestScenario17_AffiliateGate3_CPAPipeline` (`012/FR-015`) and
  `TestScenario15_St8Wallet_RestrictedOnlyRoundEarnsNoAffiliateGGR`, `…_SplitRoundEarnsRealGGROnly` and
  `…_ConvertedMoneyRoundEarnsNoAffiliateGGR` (`012/FR-016`), by `tests/original/recorder`. Each `pass` on
  both runs. The recorder filed scenario 15's under `game-provider` and scenario 17's under `substrate`, by
  each one's first request; they were moved here, so their `service` still reads so, and each was given
  the requirement it shows.
- **The driver's eight**, from `tests/original/attribution-driver/`, one per scenario of
  `features/attribution/` the original can show, named as the feature names it. Each passed on both runs.
  The driver's `NotDriven` and `NotDrivenFeatures` say why each other scenario is not recorded.

## How they were made, where it differs from `tests/original/README.md`

- **The stack.** The original's KinD stack, in the worktree `eitheror-platform-recording-players` on its
  local branch `local/recording-players` (never pushed), the one spec 008's recordings were made on:
  `just dev-kind`, `just kind-install-wallet`, `kind-init-casino-db`, `kind-build-casino-pack`, then the
  casino starter pack installed with `deploy/kind/casino-values.yaml` and `kind-register-casino-functions`.
  The TL function images were the ones spec 008's run built with TinyGo 0.42.0, loaded with `kind load`
  rather than rebuilt (Homebrew's TinyGo is 0.43.0). The install reported a failure at its post-install
  hook only: `eitheror-pack-casino-minio`'s image is in the private registry, as in spec 008's run; only
  KYC uploads use it.
- **The ports.** The affiliate service answered on its NodePort, `localhost:8093`: KinD's config now
  maps 30093, which the skips in scenario 17 say it did not. Host 8082 is `substrate-graphql`, so the
  driver's earlier advice to forward the affiliate service there was wrong; `doc.go` now says 8093. The
  substrate's twin API was forwarded to `localhost:9000`.
- **The suite's changes, in the local checkout only.** Scenario 17's hard skip was lifted, and three
  namespaces it names that the affiliate service never reads were corrected: the plan and the affiliate
  (`affiliate-plans-<brand>`, `affiliates-<brand>`, not `…/<brand>`), and the revenue twin it waits for
  (`affiliate-revenue-<affiliate>-<brand>`, not `affiliate-revenue/<affiliate>/<brand>`; found by this
  run, which read `404` until it was corrected). Scenario 15's three only skip when a system is missing,
  and none was.
- **The operator** for scenario 15's restricted bonus: the realm-sync job's `dev-operator`, through
  `casino-backoffice` (`TEST_OPERATOR_EMAIL`, `TEST_OPERATOR_PASSWORD`). The recordings hold the sign-in
  with the password and tokens redacted.
- **Nothing left the machine.** psp-stub ran on the host; `psp-gateway`'s provider base URLs were pointed
  at it (`host.docker.internal:18101`–`18109`), ConnecX at `http://127.0.0.1:9` and `PSP_GATEWAY_BASE_URL`
  off its ngrok address, with `kubectl set env` on the kind-only kubeconfig.
  `go run ./original/preflight -affiliate http://localhost:8093` then found every outside system stubbed.
- **The merge.** The driver's runs merged straight into `tests/port` (its recordings' results read `""`,
  as `ingest`'s driver's do: the outcome is each subtest's, both runs `pass`). The suite's runs merged
  into a scratch directory and were moved here with their `pass, pass`. `just modules recordings check`:
  239 recordings, 0 problems.

## How they are replayed

`services/attribution/src/test/scala/io/eitheror/casino/attribution/port/Translate.scala` turns each
step into what the port is held to, one step at a time. A public click is replayed as it is, and its
captures (the original's minted click id) take the port's answer for the steps after it. A notification
becomes the brand fact that now carries it, on the kit's broker. A twin read becomes the attribution
facts expected on the topic. A seed of the link, under the brand or in the brandless tag index, becomes
kit setup, with the link's bare UUID as a legacy tag. Scenario 15's settled round (the casino's round
twin) becomes the wallet's real-money `bet_placed` and `win_credited`, and its read of the affiliate's
facts the GGR expected for the day: the sum of the original's commissionable `amountCents`, or no fact when
the original wrote none. The day is closed as its timer would close it before the expectation is checked.

Every placeholder is given a fresh UUID, the shape the original's ids and tags had. An answer the
recording kept with the first run's ids (a round's `player_ref`) is mapped to this replay's through the
captures that named them. Each recording's instants move onto a reporting day of the replay's own, their
spacing kept, and the kit's clock goes past the latest of them: a day another replay closed would make a
round an adjustment, and money stamped with the kit's clock would fall before the binding.

One divergence of plan.md's *Divergences* shows in these recordings and is `observable`: money converted
from restricted funds counts in the port's GGR. The converted round's original wrote 0 and the port
emits 1000; the split round's original wrote 500 (its 10.00 real stake was half converted in the same bet)
and the port 1000. The restricted-only round makes no fact on either. The others do not show: the click
id varied between the original's two runs and is compared for presence only, the `notify/*` answers are
translated rather than replayed, and no recording reaches a third deposit.

## What cannot be recorded

A third deposit in a later month: the original counts a first deposit per month by its own clock, which
a run cannot move. `MoneySuite` holds the port's rule (one first deposit in a player's life).
