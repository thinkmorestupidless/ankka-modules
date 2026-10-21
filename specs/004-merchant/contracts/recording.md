# Contract: recording the original's payments path, and replaying it

Research R6. The method is `tests/original/README.md`; this file says what is specific to
`tests/port/merchant/`.

## The Go scenarios (spec *Recordings*)

| Recording | Requirement | Stack | Notes |
|---|---|---|---|
| `TestScenario12_OnboardingGate2_ServicesHealthy` | SC-001 | compose | two health reads; `/health` replayed as `/_ankka/health` (marked) |
| `TestScenario12_OnboardingGate3_FullChain` | US4, US6, US1: FR-002, FR-004, FR-006, FR-010, FR-013 to FR-018, FR-027 | compose + stubs | needs `e2e-merchant.patch` (below); operator steps 1–3 touch Keycloak's admin API directly (the realm, not this service) and are replayed as kit setup (`FakeKeycloak`) |
| `TestScenario13_DreamzPay_FullChain` | US1 with a real adapter: FR-006, FR-010, FR-013 to FR-015 | compose + stubs | `DREAMZPAY_*` from the dreamzpay stub's README; the relay page and the webhook are the gateway's steps, replayed as recorded; the capture the gateway then makes is a step on this service |
| `TestScenario14_CasinoDeposit_PlayerToWallet` | US1 scenario 3, the credit: FR-020 to FR-022 | KinD (casino + wallet) | the casino player steps and the wallet balance read are other services'; replayed as kit setup and as the fake wallet's balance |

## `tests/original/e2e-merchant.patch`

Applied beside `e2e-recording.patch` on the local `local/recording` branch, never pushed. It
removes the `t.Skip` on `TestScenario12_OnboardingGate3_FullChain` and corrects
`scenario12DeployRoutingTable` and `scenario13DeployDreamzPayRoutingTable` to the route the original
serves: the draft body `{"level": "subBrand", "entityId": <subBrandId>, "tree": …}` and the deploy
query `?level=subBrand&entityId=<subBrandId>` (`RoutingTreeRoutes.scala:230-233, 376-385`). The finding is
filed in the original's `tests/e2e/GAPS.md`.

The recording run (2026-10-18, `659c2d6b`) found scenarios 12 to 14 failing on the original itself
for three more reasons, and the patch corrects each to the original as it now is:

- the DreamzPay terminal is registered with `POST /v1/merchants/{id}/gateways/DREAMZPAY/integrations`
  (its credentials as the terminal's properties) and activated, and the tree and the submit name the
  id it answers; a `psp-credential`'s terminal reaches the gateway with no properties and is refused
  `502` since `2add2353`;
- scenario 14 clears the new player's `VERIFY_EMAIL` before logging in, as scenario 16 does;
- two assertions are corrected: the relay page's `memberId` (since `8361fa09`) and the wallet's
  `real_balance` in minor units.

## `tests/original/merchant-driver/` — the cashier behaviours the suite never reaches

A Go test package after `wallet-driver/`, run with `-tags e2e` against the original's
`merchant-service` (compose, `localhost:8085`) with the in-process simulator as the provider (an
empty `psp` on the credential; `hpp.go:98-100`) and `MERCHANT_SERVICE_INTERNAL_SECRET` for the
capture, fail and sweep routes. One scenario per feature scenario, named exactly as the feature
names it; each creates its own holding, company, sub-brand, credential, key and tree as `setup:`
steps:

| Feature scenario | Steps on the original | Replayed as |
|---|---|---|
| a capture reported by the gateway captures the payment… | session, submit, capture, read | the commands as `ingest`; the capture as `psp-gateway` with the fake wallet up |
| the same capture reported again… | … capture, capture, read | as above; the fake wallet records one call |
| a capture for a sub-brand of type payments that names no brand… | the sub-brand of type `Payments`; capture with a customer id of one part | as above; no wallet call |
| a failure reported by the gateway fails the payment with the provider's code and reason… | fail with `failureCode`, `failureReason`; fail with neither; read | the outcome route call is the fake wallet's record |
| a failure, an expiry or an abandon arriving for a captured payment… | capture, then fail; capture, then `expire-stale` with `olderThanMinutes=0`; capture, then internal abandon | the sweep step as the kit's clock past the deadline; the internal abandon as the token command |
| a payment past its deadline that nothing has yet touched reads as expired… | submit, `expire-stale?olderThanMinutes=0`, read; and read alone with the suite's `paymentTtl` set low | the clock |
| a payment past its deadline whose outcome the gateway still holds undelivered… | the gateway's session marked `outcome_undelivered` through the substrate's twin API (a `setup:` step), then the sweep | the fake gateway's `undelivered: true` |
| a player abandoning the session… | the internal abandon with the player's own id; and another player's (refused) | the token command; the other player's refusal is the original's and is marked (the token proves ownership here) |
| the five signature scenarios at the end of `deposits.feature` (signed, unsigned, one header without the other, stale or mismatched, store unreadable) | a credential with `webhookSecret`; captures under each header combination; the store-unreadable case cannot be driven on the original and is recorded as `inspected` | the same headers to this service |
| the four variance scenarios and the overpayment | captures with `variance` bodies | the same bodies |

Time-driven steps (the deadline) are driven by the original's sweep route with its window
parameter, as the recording contract allows; the rebuild replays them under the kit's clock and
the test names the substitution.

## Replay (the parity suite, `services/merchant/src/test/scala/io/eitheror/casino/merchant/port/`)

One test per recording, fail-first, named after the scenario. What is not this service's is
answered as recorded or stood in for: Keycloak's admin steps and a casino player's registration as
kit setup; the gateway's relay and webhook steps as recorded; the substrate's twin reads as the
corresponding entity read; the wallet's balance as the fake wallet's. Public cashier steps are
replayed as `ingest`'s commands with the sub-brand resolved from the recorded key's digest; capture
and fail steps as `psp-gateway`'s identity, the internal secret dropped; the original's
`401 UNAUTHORIZED` for a missing secret is replayed as another caller's `403` and marked. Each
divergence of plan.md is named in the test that meets it.
