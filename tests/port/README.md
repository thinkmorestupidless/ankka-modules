# Recordings of the original

What the rebuilt services' parity tests replay (spec 001 FR-029 to FR-032). Made by
`tests/original/README.md`'s method; each file names the requirement it establishes.

## game-provider (spec 003, T015)

Recorded 2026-10-10 from the original at `21da61f0`, run on its own KinD stack (`just dev-kind`,
`just kind-install-wallet`, `just kind-install-casino`, ST8 answered by `fake-st8`), twice, on fresh ids.

| Recordings | Requirement |
|---|---|
| 29 `TestScenario15_St8Wallet_*` | FR-003, FR-005, FR-011 to FR-017 |
| 4 `TestScenarioFreeSpinsPlay_*` | FR-013, FR-025 |

How they were made, where it differs from the README's compose steps:

- The KinD stack, not compose: the original's local defaults for the suite (`just test-st8`) are its KinD
  ports, and its compose file has no substrate. The suite's URLs: substrate `localhost:18080` and its
  internal API `localhost:19001` (port-forwards), the rest the cluster's node ports.
- The free-spins scenarios run first, on a freshly restarted `fake-st8`: the ST8 suite registers its own
  key with the adapter and its teardown resets the adapter to its configured key, not `fake-st8`'s, after
  which `fake-st8` cannot bind a wallet token.
- The free-spins scenarios log in as the cluster's `dev-operator` (`deploy/kind/values-dev.yaml`); its
  password on the fresh cluster did not match the documented one and was reset to it.
- Consecutive reads of the same path are collapsed to the last (`merge.Collapse`): the free-spins
  scenarios poll a grant until it settles, a different number of times each run.
- `TestMain`'s calls (the suite's health checks and its key reset) are not a scenario and are not kept.

The free-spins scenarios' callbacks go from `fake-st8` to the adapter inside the cluster, so the recorder
does not see them: what is recorded is the launch, `fake-st8`'s `/test/free-spin`, and the outcome read
from the wallet and the grant, much of it other services' (`players`, `rewards`, `wallet`).

The parity suite (`services/game-provider`, `ParitySuite`) replays all 33 green. How it stands in for what
is not the service is in its doc comment: control steps as kit setup, other services' steps as recorded,
`fake-st8`'s steps as the callbacks it makes, the player's starting balance from the first money answer
(the suite shares one player across its tests), and a callback the original refused `auth_failed` signed
with another key, since the recorder keeps no signature header.

### Not recorded

- `TestScenario7_CasinoGate3_*` (4): they fail on the original itself at `21da61f0`. Three call the
  substrate's `/internal/actions/execute` with `VerifyPlayer`, `PlaceBet` and `CloseSession`, which the
  catalogue answers `NO_HANDLER` or with `TL0300` where the test expects `INSUFFICIENT_FUNDS`;
  `RGEnforcement` skips itself. A recording of a failing test is not the original's behaviour.
- The Playwright launch and catalogue reads: the recorder is the Go suite's transport, and the
  Playwright suite is spec 001 FR-033's check against a rebuilt brand, not a recording.
