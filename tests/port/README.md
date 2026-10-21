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

## merchant (spec 004, T019)

Recorded 2026-10-18 from the original at `659c2d6b`, run on its own KinD stack, twice, on fresh ids:
22 recordings, every one `pass` on both runs. What they establish is `specs/004-merchant/contracts/recording.md`.

| Recordings | Made by | Requirement |
|---|---|---|
| `TestScenario12_OnboardingGate2_ServicesHealthy`, `TestScenario12_OnboardingGate3_FullChain` | the Go suite with `e2e-merchant.patch` | 004/SC-001 (US4, US6, US1) |
| `TestScenario13_DreamzPay_FullChain` | the Go suite with `e2e-merchant.patch` | 004/FR-013 (US1 with a real adapter) |
| `TestScenario14_CasinoDeposit_PlayerToWallet` | the Go suite with `e2e-merchant.patch` | 004/FR-021 (US1 scenario 3, the credit) |
| 18, one per scenario of `features/payments/deposits.feature` and the second-submit cashier scenario | `tests/original/merchant-driver/` | 004/FR-015 to FR-026, each file naming its own |

How they were made, where it differs from the README's compose steps:

- The KinD stack, not compose, for all of them (as game-provider's): it is the one stack with
  substrate, merchant-service, psp-gateway, Keycloak, casino-service and the wallet together. The
  suite's URLs: substrate `localhost:9000` and its internal API `localhost:19001` (port-forwards),
  merchant-service `localhost:8086`, the gateway `localhost:8087`, Keycloak `localhost:8180`, the
  casino backoffice `localhost:8083` (node ports). The driver ran with `-merchant-url
  http://localhost:8086 -substrate-url http://localhost:9000` and the cluster's
  `MERCHANT_SERVICE_INTERNAL_SECRET`.
- The cluster's images predated `927a80d1` (merchant-service's capture durability, substrate's
  projection routes), so substrate and merchant-service were rebuilt at `659c2d6b`
  (`deploy/kind/build-and-load.sh --force`). psp-gateway's image (2026-10-14) has no change since;
  wallet-service's predates spec 253's pool changes, which the recordings read only as the balance a
  replay takes from the fake wallet.
- psp-gateway in the cluster pointed at the providers' real sandboxes and an ngrok URL. Before
  recording, every provider base URL was set to `http://127.0.0.1:9` (nothing answers), DreamzPay's
  to the stub's `http://localhost:18103` (the player's browser's, never called by the suite), and
  `PSP_GATEWAY_BASE_URL` to `http://psp-gateway:8087`. Nothing left the machine.
- The cluster's `PAYMENT_EXPIRY_MINUTES` is 5 (the original's default is 30); the driver's
  deadline scenarios use the sweep with `olderThanMinutes=0`, so no recording depends on it.
- The two sweep recordings were re-recorded on 2026-10-18 with `Listed`: the first run swept before
  substrate's namespace listing showed the payment, so the sweep missed it (`tests/original/README.md`).
- The suite's recordings begin at Keycloak, so the recorder filed them under `realm/` and
  `substrate/`; they are kept here with `service: merchant`, each step keeping its own service.
  `merge` writes `_suite/suite-result.json` for its own run only, so its results were added to the
  existing file, not written over it.
- The merchant keys in the answers are the local cluster's, minted per run for that run's sub-brand;
  the DreamzPay keys are the stub's published test values.

What the recording run found about the original, each a finding in spec 004's *Recordings*, none a
requirement:

- **A DreamzPay terminal made through `psp-credentials` cannot take a deposit.** Since `2add2353`
  (2026-08-03) the gateway reads a real provider's credentials only from the terminal it is sent
  (`hpp.go`, `rejectMissingCredentials`). The cashier's submit matches a `psp-credential` by name
  first and sends it with no properties, so the submit answers `500 psp_error_502`. Scenarios 12 to
  14 onboarded that way and have failed since August (CI skips them: no `DREAMZPAY_*`, and Gate3 was
  skipped). `e2e-merchant.patch` now registers the terminal as the original's backoffice does,
  `POST /v1/merchants/{id}/gateways/DREAMZPAY/integrations` with the credentials as its properties,
  then `…/integrations/{id}/activate`, and routes to its id.
- **Two stale assertions**, corrected in the patch: the relay page's field is `memberId` since
  `8361fa09` (2026-06-05), not `MemberID`; the wallet twin's `real_balance` is in minor units (€10 reads
  `1000`), not major.
- **A new casino player's login is refused `401 email_not_verified`** until Keycloak's `VERIFY_EMAIL`
  is cleared; scenario 14 now clears it as scenario 16 does (`s16ClearEmailVerification`).
- merchant-service answered its first operator request after a restart `401`, and the same token
  `200` a minute later: the realm's keys are fetched lazily. Not a behaviour to keep; noted because a
  first run straight after a deploy fails on it.
- **The expiry sweep does not see a payment made moments before it.** It lists payments through
  substrate's namespace listing, which lags the twin write, so a fresh payment past its deadline
  waits for the next sweep. Harmless at the CronJob's cadence; it made the first deadline
  recordings show an unexpired payment while the driver, checking only codes, passed.

The driver needed two fixes against the running original, neither changing what it asks:
the credential twin it gives a webhook secret is named as the original's `twinUrl` names a non-UUID
id (`nameUUIDFromBytes("merchant." + id)`; substrate takes only UUIDs), and substrate's twin `POST`
always answers `201`, which the setup steps now expect.
