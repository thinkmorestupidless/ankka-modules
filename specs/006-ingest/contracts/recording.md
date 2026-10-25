# Recording the original's cashier edge, and replaying it

Research R15. The method is `tests/original/README.md`; what spec 004's recordings established is
`specs/004-merchant/contracts/recording.md`. This file says what is specific to `tests/port/ingest/`.

## The Go-suite recordings reused (spec *Recordings*)

The public cashier steps of three recordings `tests/port/merchant/` already holds are this service's.
They are copied, not re-recorded: the original's `merchant-service` served the edge and the cashier in
one process, so the recorded answer is both what `ingest` returns and what `FakeMerchant` answers the
forwarded command with.

| Recording | Public steps | Requirement |
|---|---|---|
| `TestScenario14_CasinoDeposit_PlayerToWallet` | `POST /v1/cashier/sessions` (a brand's key, `customerId` `spinvibe:{c}`), `POST .../{c}/submit`, `GET /v1/payments/{c}` | US1, US3; SC-001 |
| `TestScenario13_DreamzPay_FullChain` | the same three | US3 (a provider's full chain) |
| `TestScenario12_OnboardingGate3_FullChain` | steps 10a (session), 10b (submit), 12 (read) | US1, US3 (a new sub-brand's first session) |

Every other step of those recordings (operators, Keycloak, the hierarchy, the tree, the player's
registration, the gateway's relay and webhook, the twin reads) is another service's and is replayed as
`FakeMerchant`'s script and kit setup. `TestScenario10_*CashierSession*` stay skipped in the original
(they probe `SubstrateURL`); their behaviours are the driver's below, and the spec's note that they
are "to be unskipped" is for the original's suite, not this plan.

**The key.** The recorder keeps only `Content-Type`, `Accept` and `X-Brand-Id` (`recorder.go`,
`kept()`), so no recording holds `merchantKey`. The replay takes the raw key from the recorded answer of
the key-generation step (`POST /v1/merchants/{c}/keys`, `201`), hashes it, issues it to `Key` as the
sub-brand's through `KeyFacts`, and sends it as `merchantKey` on the public steps. A replay therefore
also proves FR-002: the digest in the index is the digest of the key the original issued.

## `tests/original/ingest-driver/`: what the suite never reaches

A Go test package after `tests/original/merchant-driver/`, reusing its `Session.Open/Submit/Read` with
`MerchantKey`/`NoAuth`, run with `-tags e2e` against the original's `merchant-service` on the KinD
stack (`localhost:8086`) as 004's were. One scenario per feature scenario the original can show, named
as the feature names it; each creates its own holding, company, sub-brand, key and tree as `setup:`
steps, and the key step's answer is where the replay finds the raw key.

| Feature scenario (`cashier.feature`) | Steps on the original | Replay |
|---|---|---|
| a cashier session created under an active key answers a cashier ticket and the terminals… | create | unmarked |
| submitting a session naming an offered terminal… | create, submit | unmarked |
| reading a payment tells its status in the frontend's words… | create, submit, read with the key | unmarked (the key path) |
| a cashier session that cannot be routed is refused naming why (both rows) | create with no tree; with a tree that matches nothing | unmarked |
| a request with no cashier key is refused as unauthenticated… | create, no header | **marked**: the original's `400` (Pekko's missing-header text) is `401 UNAUTHENTICATED` |
| a request under a key no sub-brand holds active… (both rows) | create with a made-up key; disable the key, create | unmarked (`401 INVALID_MERCHANT_KEY`) |
| a request missing or malforming a field… (every row) | create with each field missing or malformed | **marked**: the original defaults (`amountCents` 0, `USD`, `""` return address, `MISSING_ROUTING_CONTEXT` for country and player id, a brandless player id accepted); the port answers `400 INVALID_REQUEST` naming the field and forwards nothing |
| a repeated create under the same request id with the same content… | create twice under `t-1` | **marked**: the original makes a second session; the port returns the first and `FakeMerchant` records one call |
| a repeated create under the same request id with a different amount… | create twice under `t-1`, amounts differ | **marked**: the original makes a second session; the port `409 REQUEST_ID_REUSED` |
| a request id belongs to the sub-brand and not to the installation | two sub-brands, `t-1` each | unmarked |
| a create with no request id is served under one the shared payments mints… | create twice without `transactionId` | unmarked (two sessions, two ids) |
| submitting a session already submitted… | create, submit, submit | unmarked (`merchant` answers the first) |
| submitting a session naming a terminal it did not offer… | submit `sim-gbp` | unmarked (`merchant`'s `terminal_not_found`) |
| a submit under a cashier ticket no session has… | submit a made-up token | unmarked (`404 SESSION_NOT_FOUND`; the port answers it without asking `merchant`, which the replay shows as `FakeMerchant` recording no call) |
| a payment read by the sub-brand that created it… | read with the key | unmarked |
| a payment read by another sub-brand is refused as not found | read with another sub-brand's key | **marked**: the original answers the payment to anyone; the port `404 PAYMENT_NOT_FOUND` |
| a key presented in a different header case (edge case) | create with `MerchantKey` | unmarked; the header's case is the driver's to name, since the recorder keeps no header |

The provider is the original's in-process simulator (an empty `psp` on the credential), so a submit
initiates without leaving the machine; a `PAYMENT_REJECTED` with the provider's words needs a stub
that refuses, and is held by the feature alone.

**Changed at implementation (2026-10-24, T004, T026).** The driver seeds through the original's seed
routes, as merchant-driver does, so the raw key is the seed's `$.merchantKey` rather than a key step's
`201`; the replay reads both. The original ran natively from a worktree at `659c2d6b` (merchant-service
`localhost:8085`, substrate `localhost:8080`), not on the KinD stack, which no longer existed. Two of the
unmarked rows did not hold on the original, both `merchant`'s answers passed through, so neither is a
divergence of this service: the second submit made a second payment, and `sim-gbp` was `500
terminal_not_found`. The made-up-token row and the same-content repeat answer as the original does; what
differs is that `merchant` is not asked, which the replay reports as that step's `.merchant`.
`tests/port/ingest/README.md` has the run.

## What is not recorded

The rate limit, the lost answer, the audit record, the deactivation bound (SC-004), the request
records and the operator's reads: the original has none of them. They are held by
`features/payments/cashier.feature` and `requests.feature` through `GherkinSuite`, and by the suites
research R14 names.

## Replay (`services/ingest/src/test/scala/io/eitheror/casino/ingest/port/ParitySuite.scala`)

One test per recording, fail-first, named after the scenario. `FakeMerchant` answers each forwarded
command with the recorded answer of the public step it corresponds to, and records what it was asked;
a test that says `merchant` was not asked reads that record. Each divergence marked above is named in
the test that meets it, as plan.md's *Divergences* lists it, through `Parity.divergences`.
