# tests/port/rewards

The original's answers to spec 011's recorded scenarios, which `services/rewards`' `ParitySuite` replays
(one test per file).

Recorded on 2026-11-02 (T066) against the original at `7630feb0` (with #730) on its KinD stack
(`eitheror-dev`), twice, on fresh ids, as `tests/original/README.md` *Recording the rewards* says. 47
recordings: 25 from the Go suite and 22 from `tests/original/rewards-driver/`, every one `pass` on both runs.

| Recordings | Made by | Requirement |
|---|---|---|
| `TestScenario16_RewardGrant_TargetSelectActivateForfeit` | the Go suite | 011/FR-011 |
| `TestScenario18_MultiAccountDetection_BonusBlock`, `TestScenario21_*` (5) | the Go suite | 011/FR-005 |
| `TestScenario21_OfferValidation` | the Go suite | 011/FR-002 |
| `TestScenario19_*`, `TestScenario_BonusBust_*`, `TestScenario244_ConversionRecordsActivationBaseline`, `TestScenario245_{Partial,Zeroed}*`, `TestScenario254_*` | the Go suite | 011/FR-014 |
| `TestScenario244_Cancel*`, `TestScenario244_DepositMatchCancelStillWorks` | the Go suite | 011/FR-016 |
| `TestScenario245_TurnoverDisplayFollowsWalletPending` | the Go suite | 011/FR-030 |
| `TestScenarioFreeSpinsPlay_MultiCurrencyGrant` | the Go suite | 011/FR-023 |
| `TestScenarioFreeSpinsPlay_*` (4 more) | the Go suite | 011/FR-024 |
| 22 named as `features/rewards/*.feature` names them | the driver | as each file says |

The recorder files a test under the service most of its steps reach, so a recording's `service` is
often `game-provider`, `realm`, `substrate` or `players`; they are filed here because spec 011 claims
them. `TestScenario18_MultiAccountDetection_BonusBlock` and the free-spins scenarios are spec 008's and
spec 003's too; those directories keep their own, older recordings.

How the run differed from the README's steps:

- **Emails**: `e2e-recording.patch` spells the suite's run ids' digits as letters in scenarios 16, 18 and
  19's emails. #730's duplicate check strips digits and counted the second run's players duplicates of
  the first's.
- **Order**: each run restarted fake-st8 and ran `FreeSpinsPlay_` and `244_` first; a test with an ST8
  harness replaces fake-st8's key with the adapter.
- **Ports**: substrate forwarded to 18080 and wallet-service's internal port 9001 to 19001 (the suite's
  `WalletInternalURL`); fake-st8 on host 8096, Unleash on 4242.
- **Unleash**: casino-service's token is `*:*`, which reads the `default` environment, absent from the
  `default` project; it was added to the project (`POST /api/admin/projects/default/environments`) and
  the driver run with `-unleash-environment default`.
- **Retries**: `TestScenario21_ConflictsWith` (run 2) and `TestScenarioFreeSpinsPlay_CustomerIOGrant`
  (run 1, whose sign-in helper retried a Keycloak update) were run again into their run until the two
  runs agreed step for step.

## Not recorded

- `TestScenario23_RewardGrantExpiry` fails on the original at `7630feb0`, every run: its cap-lapsed setup
  bets the whole 10.00 restricted balance and loses, which forfeits the grant (the bonus bust,
  `TestScenario_BonusBust_LosingRoundForfeitsGrant`), so it never reaches `CONVERTED_PENDING_CAP` and its
  expiry checks never run.
- `TestScenario21_CodeMinDepositDepositNumberWindow` passed twice in nine runs; its claims with no code
  then answered `OFFER_NOT_FOUND` every time, with the offer listed, active and in its window. Not lag (a
  new offer lists in 0.1 s) nor paging (148 offers in one answer); the cause was not found.
- `TestScenario16_RewardGrant_LegacyStatusStillTransitions` was not run: it exercises legacy shapes spec
  011 does not carry, and the revoke it was kept for is recorded by the driver.

What is left of FR-015 and FR-025 (the expiry timers) is replayed from the capability tables of
`reward-grant-expiry` and `finalize-expired-free-spins-grant` (spec 011 Recordings).
