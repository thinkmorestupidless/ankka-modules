# Quickstart: proving attribution

In build order. Every command runs from the repository root unless it says otherwise; the ones that need
Docker say so. Expected outcomes are what [plan.md](plan.md)'s *Divergences* and the contracts say;
nothing here repeats them.

## 1. The features and the glossary

```bash
just features
```

Expected: 0 findings. The bonus-cost outline of `features/attribution/facts.feature` has one row
(research correction 1); the spec's acceptance lists are unchanged.

## 2. The contract and the manifests

```bash
sbt contract/publishLocal && just schemas
just modules validate --all
```

Expected: `GgrDaily` carries `beforeBinding` in `contract/schemas/affiliates.attribution.v1.json`;
`Routes.machine` compiles; `modules/core/module.json`'s `attribution` entry renders `CASINO_REPORTING_ZONE`,
`CASINO_MODULES`, `COUNTRY_HEADER`, `DOCUMENT_TIMEOUT`, `CLOSE_GRACE`, `WEBHOOK_TIMEOUT`; `contract/keys.md`
lists `reporting-zone` and moves `attribution` out of "Not yet built".

## 3. Unit suites

```bash
just attribution-test   # sbt test in services/attribution, without the it, port and features packages
```

Expected: `TagSuite` (every refusal reason of research R6, a token over 1 KiB, a UUID), `DocumentsSuite`,
`PseudonymsSuite` (a fixed key and player id give the documented digest), `SigningSuite`, `JcsSuite`,
`RealMoneySuite` (each family's counted delta), `DaysSuite` (23:30 in `Africa/Johannesburg` is that day;
the next close), `FactIdsSuite`, `FactsSuite` (every row of contracts/facts.md), `BackoffSuite`; the
entity kits for `AttributionRecord` (every row of its command table: a second bind refused, a seen money
event a no-op, pending emitted at the binding, `firstDepositAt` never moves), `DayTally`, `NetworkFeed`,
`WalletCursor`, `RoundIndex`, `Network`, `Settings`, `Click`, `WebhookSubscription`; the workflow kit for
`Delivery`; the consumer kits for the five topic consumers and the three publishers (an `InMemorySecretStore`
holding a pseudonym key; a fact's id, key and content the same on a second delivery).

## 4. Integration, features and parity (Docker)

```bash
just attribution-it   # AnkkaTestKit over Postgres, InMemoryBroker on six topics, TimerRuntime on a MovableClock, FakeNetwork
```

Expected, by suite:

- `LandingSuite`: a click with a valid tag answers the click id sent and emits one `click` fact; the same
  click id again emits nothing more; a refused tag answers `ok: false` with the reason.
- `BindingSuite` (US1, US2): a `player_registered` with a valid tag binds and emits `registration`; a stale
  tag is organic with `stale` kept; a code at registration binds; a `bonus_code_entered` within the window
  binds and emits the closed days before it with `beforeBinding`; a code for a bound player is a touch.
- `MoneySuite` (US4): a `payment.captured` emits `first-deposit` and `deposit`, the next only `deposit`; a
  dispute and a refund emit `chargeback`; the four withdrawal families emit their kinds; an award emits
  `bonus-cost`; a deposit delivered before the registration is emitted at the binding.
- `GgrSuite` (US4, SC-001): bets and wins across a midnight in `Africa/Johannesburg`, the clock moved past
  the close plus `CLOSE_GRACE`: one `ggr-daily` per day with the real parts only; a bonus-funded round adds
  nothing; a leg after the close emits `ggr-adjustment`; a network with `roundStream` on gets `ggr-round`
  at each settlement; a network without gets none.
- `CarriersSuite` (US3, SC-004): every fact on the broker within the bound, `ce-id` the fact id; the feed
  from the empty cursor pages in emission order, each page's signature verifying under the brand's key
  document; the same cursor twice gives the same page; a machine token of another network's machine is
  `404`; a webhook receiver gets each fact signed, a failing receiver is retried on the schedule as the
  clock moves and the fact is undelivered after 24 hours, resent on the operator's call.
- `NetworksSuite` (US5, US6): an unknown `kid` makes the kit's `FakeNetwork` serve its key document once;
  a withdrawn key is refused after the cache refreshes; a revoked link binds no new player while the
  bound stay bound; a removed network's tag is `unknown_network`; a legacy tag binds during the
  transition and is refused after; the transition ends by each rule with the clock moved.
- `ClosureSuite` (US7): `player_closed`, a permanent `player_self_excluded` and the erasure route each
  emit one `player-closed` with its category; a period exclusion emits nothing; the record after erasure
  keeps its binding.
- `LeakSuite` (SC-006): every personal value the suite sent (name, email, address, raw IP, fingerprint)
  searched for in every fact on the broker, every feed page, every webhook body and every table: none.
- `OperatorSuite`: a staff token with `brand_goldvibe` reads and changes settings, networks, webhooks and
  the undelivered list; one without the role is `403`; the pseudonym key route never returns the key.
- `GherkinSuite` over the six `features/attribution/*.feature`: every scenario bound and green.
- `ParitySuite` over `tests/port/attribution/`: every recording replayed through the translation
  (contracts/recording.md), the marked divergences named; `CoverageSuite` matches them to plan.md.

## 5. The recordings (needs the original's KinD stack with the affiliate service; [contracts/recording.md](contracts/recording.md))

```bash
cd tests && go run ./original/preflight -affiliate http://localhost:8093
cd tests && go test -tags e2e ./original/attribution-driver/... -count=1 -args \
    -affiliate-url http://localhost:8093 -substrate-url http://localhost:9000   # twice, EITHEROR_RECORD_TO per run
# and the suite's four affiliate scenarios, twice, as tests/original/README.md says
just modules recordings check
```

Expected: one recording per driver scenario and the four suite scenarios, every one `pass` on the
original both runs; the check reports every file well formed. Done 2026-10-28 (T035):
`tests/port/attribution/README.md` says how, and `ParitySuite` replays all twelve.

## 6. Locally

```bash
just attribution-run   # ANKKA_HTTP_PORT=9007, CASINO_BRAND=goldvibe, CASINO_REPORTING_ZONE=Africa/Johannesburg
```

`sbt schema && docker compose up -d` in `services/attribution` first, and `ANKKA_SECRET_KEY` exported
(`openssl rand -base64 32`, kept: the secret store is encrypted under it). With no
`ANKKA_KAFKA_BOOTSTRAP_SERVERS` the facts are read and published in memory (ankka refuses to start a topic
consumer with no subscriber), so the click route and the operator routes are what a local run shows. The
operator's `curl`s take a token from dev-kind's casino Keycloak through a port-forward on 18180
(`Authorization: Bearer $STAFF_TOKEN`); the `staff|…` token is the integration kit's stand-in and is
refused by a running service.
With `sbt "Test/runMain io.eitheror.casino.attribution.it.fakes"` serving a `FakeNetwork` on 9103:

```bash
TOKEN=$(curl -s -X POST localhost:9007/v1/casino/backoffice/goldvibe/attribution/networks -H "Authorization: Bearer staff|op-1|brand_goldvibe" \
  -H 'Content-Type: application/json' -d '{"id":"programme","keyDocument":"http://127.0.0.1:9103/keys","revocationList":"http://127.0.0.1:9103/revocations"}')
curl -s -X POST localhost:9007/v1/casino/backoffice/goldvibe/attribution/networks/programme/pseudonym-key -H "Authorization: Bearer staff|op-1|brand_goldvibe"
curl -s -X POST localhost:9007/v1/track/click -H 'Content-Type: application/json' \
  -d "{\"btag\":\"$(curl -s 127.0.0.1:9103/tag?aff=aff-1\&lnk=lnk-1)\",\"clickId\":\"c-1\",\"deviceType\":\"desktop\"}"
```

Expected: `{"ok":true,"clickId":"c-1"}`; the same again, the same answer; a `btag` of `nonsense`:
`{"ok":false,"clickId":"…","reason":"malformed"}`. Inspect with `ankka local console`: `Click` `c-1`,
`NetworkDocuments` `programme` with the fetched keys, `Settings` `brand` with today's `firstDay`.

## 7. An installation (kind)

```bash
just modules validate brands/goldvibe --installation dev-kind
just modules apply brands/goldvibe --installation dev-kind --secrets <a file outside the repository>
ankka services list --project goldvibe
```

The current kubeconfig context must be `kind-ankka` (never production). Expected: `attribution` running
beside the other core services, exposed at `attribution-goldvibe.<base domain>`, its variables from the
manifest's `attribution` entry (research R18), the `affiliates.attribution` topic declared and granted to
`programme`/`affiliate-programme`. Then the operator's steps as §6 against the gateway, and for a machine:

```bash
ankka organizations machines register <org> <network>
ankka projects grants make machine:<org>/<network> route attribution GET '/v1/casino/brands/{brand}/attribution/networks/{network}/feed' -p goldvibe
curl -s https://api.<base>/oauth/token -d grant_type=client_credentials --data-urlencode client_id=machine:<org>/<network> --data-urlencode client_secret="$SECRET"
curl -s "https://attribution-goldvibe.<base>/v1/casino/brands/goldvibe/attribution/networks/<network>/feed?cursor=" -H "Authorization: Bearer $MACHINE_TOKEN"
```

Expected: a signed page (empty until a bound player acts), and `404` for a machine the network's
`machine` field does not name. The player site's click proxy target becomes this hostname (out of
scope; it sends `btag`, `clickId` and `utm_*` already).
