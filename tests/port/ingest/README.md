# Recordings: ingest (spec 006)

What `services/ingest`'s `ParitySuite` replays. The method is `tests/original/README.md`; what is specific
to this directory is `specs/006-ingest/contracts/recording.md`.

## Reused from spec 004's run

Copied unchanged from `tests/port/merchant/`, recorded 2026-10-18 from the original at `659c2d6b` on its
KinD stack, twice, on fresh ids. The original's `merchant-service` served the cashier edge and the cashier
in one process, so each public step's recorded answer is at once what `ingest` returns and what
`FakeMerchant` answers the forwarded command with.

| Recording | This service's steps | `FakeMerchant`'s script and kit setup |
|---|---|---|
| `TestScenario12_OnboardingGate3_FullChain` | `POST /v1/cashier/sessions`, `POST /v1/cashier/sessions/{c4}/submit` | everything else: the realm's tokens and users, the operator, the hierarchy, the integration, the key, substrate's index, the tree, the gateway's captures, the payment twin |
| `TestScenario13_DreamzPay_FullChain` | the create, the submit, `GET /v1/payments/{c5}` | as above, and the gateway's relay page and webhook |
| `TestScenario14_CasinoDeposit_PlayerToWallet` | the create, the submit, `GET /v1/payments/{c8}` | as above, and the casino's player registration and login, the wallet twin |

**The key.** The recorder keeps only `Content-Type`, `Accept` and `X-Brand-Id`, so no recording holds a
`merchantKey`. The replay takes the raw key from the key-generation step's `201` answer
(`POST /v1/merchants/{c3}/keys`, `$.rawKey`), issues its SHA-256 digest to the sub-brand the step names
through `KeyFacts` (as `merchant_key_issued` would), and sends the raw key as `merchantKey` on each public
step. A replay therefore also shows that the digest `ingest` indexes is the digest of the key the original
issued (FR-002).

## The driver's run

Recorded 2026-10-24 from the original at `659c2d6b` (the `eitheror-platform-recording` worktree, whose only
local changes are the e2e suite's patches under `tests/e2e`), twice, on fresh ids, by
`tests/original/ingest-driver/`: 25 recordings, one per scenario and outline example of
`features/payments/cashier.feature` the original can show, and the header-case edge case. The other nine
scenarios are named in the driver's `NotDriven` with why (the original has no rate limit, request
record, audit record or bound of its own). `suite-result.json` holds each scenario's result on both runs:
12 pass on both, 13 fail on both, each failure the original answering otherwise than the feature
expects.

| Recordings | The original answered | Replay |
|---|---|---|
| the active-key create; submit an offered terminal; the read in the frontend's words; the read with the creating key; the two routing refusals (`400 NO_ROUTING_TREE`, `400 NO_ROUTING_MATCH`); a made-up key and a disabled key (`401 INVALID_MERCHANT_KEY`); `t-1` under two sub-brands; two creates with no request id (two sessions); the header spelt `MerchantKey`; a submit of a session already submitted; a submit naming `sim-gbp`; a submit under a made-up ticket (`404 SESSION_NOT_FOUND`) | as the feature expects, but for the three noted below | unmarked, but for the made-up ticket: marked *A submit or abandon under a ticket*, shown by `merchant` not asked |
| no key | `400`, Pekko's missing-header text | marked *A missing `merchantKey`* |
| each field row: a fractional amount, no amount, no currency, no return address, a player id without its brand (`200`, a session on the original's defaults); no player id, no country (`400 MISSING_ROUTING_CONTEXT`) | a session, or the routing refusal | marked *A missing or malformed*, *A `customerId` without its brand* |
| a repeat under `t-1` with the same content; with another amount | `200`, a second session each time | marked *A repeat under one `transactionId`*: the same content shown by `merchant` not asked (the session token varies between runs, so the answers alone cannot differ); the other amount by the `409` |
| a read with another sub-brand's key | `200`, the payment | marked *A payment is read only* |

**Where the original answers otherwise than the feature, and `ingest` passes it through.** A second submit of
one session made a second payment (another `paymentId`), as spec 004 says the original does; and a terminal
the session did not offer is `500 terminal_not_found`, where spec 004's `merchant` answers `400`. Spec
004's plan does not list the `500` among its divergences. Both are `merchant`'s answers, so `FakeMerchant`
replays them and `ingest` returns them unchanged.

**How it was run, where it differs from contracts/recording.md.** Not the KinD stack (the original's
`eitheror-dev` cluster no longer exists, and rebuilding it does not fit the disk): substrate,
merchant-service and psp-gateway ran natively from the `659c2d6b` worktree against Postgres, Neo4j and
NATS in Docker; merchant-service on `localhost:8085`, substrate on `8080`. Substrate's cluster bootstrap
was given a single-node config discovery and `AKKA_MANAGEMENT_HOST=127.0.0.1`. The gateway's every provider
base URL was `http://127.0.0.1:9` (nothing answers); a SIM submit takes its in-process stub path and calls
nothing. Keycloak, Kafka, GraphQL and the wallet were `127.0.0.1:9` for merchant-service: no scenario uses
them. psp-stub ran natively for preflight; `preflight -cashier http://localhost:8085` passed every
provider and the cashier route and refused `st8`, whose fake answers healthy only once the casino's ST8
adapter takes its key. Nothing running here calls ST8.

**How the driver seeds and what the replay reads.** Each scenario seeds its sub-brands through the
original's seed routes (no Keycloak), so the raw key is the seed's `$.merchantKey`, captured as
`merchantKey` (or `otherMerchantKey`); the replay issues it to the seed's `merchantId`. A public step's
recorded `merchantKey` header names the key it sent (`{merchantKey}`), is empty when none was sent, or is the
made-up key as sent. A disabled key is the keys twin written inactive through substrate (the original has
no route for it), and the two trees are the routing twin written as merchant-service writes it; all are
`setup:` steps.
