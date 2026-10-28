# attribution

The brand's side of the affiliate protocol (spec 012). It records clicks on an affiliate network's signed
tag, binds a player once from the brand's own facts (a tag or a bonus code at registration, a code
entered later), and reports what that player's money does as facts on `affiliates.attribution`, in a
signed feed per network and in signed webhooks. A player is named outside the brand only by a
per-network pseudonym; no personal field, address or player id leaves it (SC-006).

The plan is [specs/012-affiliate-protocol/plan.md](../../specs/012-affiliate-protocol/plan.md); the wire
protocol is its [contracts/protocol.md](../../specs/012-affiliate-protocol/contracts/protocol.md).

## Routes

One endpoint per prefix; [contracts/routes.md](../../specs/012-affiliate-protocol/contracts/routes.md)
has every answer.

| Prefix | Audience | Routes |
|---|---|---|
| `/v1/track` | public, through the gateway | `POST /click` |
| `/v1/casino/brands/{brand}/attribution` | public (`/keys`); the network's machine (`/networks/{n}/feed`); `players` (`/players/{p}/erasure`) | the brand's key document, the feed, the erasure |
| `/v1/casino/backoffice/{brand}/attribution` | the brand's operators (`Routes.operator`) | settings, status, networks, pseudonym keys, records, signing keys |
| `/v1/casino/backoffice/{brand}/attribution-webhooks` | the brand's operators | subscriptions, the undelivered list, resend, discard |

## Components, by package

| Package | Components | What |
|---|---|---|
| `clicks` | `Click` (key value, click id), `ClickFacts` | a click once with the address as a salted digest; its `click` fact keyed by the link |
| `tag` | `Tag`, `TagCheck`, `Signing`, `Pseudonyms`, `Jcs` | the token's check against the network's keys and revocations; Ed25519; the pseudonym |
| `record` | `AttributionRecord` (event sourced, player id) | the binding, made once; touches, money, closure and erasure |
| `inputs` | `PlayersFacts`, `RewardsFacts`, `PaymentsFacts`, `WithdrawalsFacts`, `MoneyFacts`, `Registrar` | the five brand topics into the record and the day tallies |
| `money` | `DayTally` (event sourced, player/currency/day), `WalletCursor`, `RoundIndex`, `OpenDays` (view), `DayCloser` (timer `close-days`, 5 min) | deposit-origin money's GGR per reporting day (each fact's deposit part, spec 025), closed after `CLOSE_GRACE` |
| `facts` | `RecordFacts`, `TallyFacts`, `Publish` | the facts onto `affiliates.attribution`, `ce-id` the fact id, keyed and subjected by the pseudonym |
| `carriers` | `FeedAppender`, `NetworkFeed` (event sourced, network), `Pages`, `WebhookSubscription`, `WebhookFanout`, `Delivery` (workflow), `DeliveryRetry` (timed action), `Deliveries` (view) | the feed and the webhooks, retried 1, 5, 15 and 60 minutes apart, then hourly, for 24 hours |
| `networks` | `Network`, `NetworkDocuments` (key value, network), `DocumentRefresh` (timer `refresh-documents`, 1 min), `Cache`, `Codes` | the networks the operator adds and the documents fetched from them |
| `settings` | `SettingsEntity` (key value, `brand`) | the windows, the first day and the transition from legacy tags, the brand's signing keys |
| `views` | `Records` | the bound counts per network |

At every start `AttributionService.started` writes the first day once, sets both recurring timers (a
second start replaces them) and makes the click salt when the store has none.

## Settings

| Variable | Default | |
|---|---|---|
| `CASINO_BRAND` | | the brand's slug |
| `CASINO_MODULES` | none | `casino` registers the `casino.rewards` consumer (bonus codes entered later) |
| `CASINO_REPORTING_ZONE` | `UTC` | the reporting day's zone |
| `CLOSE_GRACE` | `20m` | how long after local midnight a day closes |
| `COUNTRY_HEADER` | `CF-IPCountry` | the gateway's header for the visitor's country, when a click sends none |
| `DOCUMENT_TIMEOUT` | `5s` | a network document's fetch |
| `WEBHOOK_TIMEOUT` | `10s` | a webhook post |
| `WEBHOOK_PLAIN_HTTP` | `false` | allows `http://` webhook addresses; tests and local runs only |

## Secrets

The service keeps in its own store, and never answers or logs:

- `pseudonym-key/<network>`: made once by the operator's route, never rotated, never returned.
- `signing-key/<kid>`: the private half of each of the brand's signing keys; the public half is in the
  key document at `/keys`.
- `webhook/<subscription>/secret`: the secret a network gave for its webhook, deleted with it.
- `click-salt`: the salt of the click's address digest.

The click's address digest is on the click's row and nowhere else; `it.LeakSuite` checks that.

## Running and testing

- `just attribution-test`: the unit suites. `just attribution-it`: `it.*`, `port.*` and `features.*`,
  which need Docker (the integration kit's Postgres). Suites that move the clock, restart the service or
  count everything run in JVMs of their own (`own` in `build.sbt`).
- `just attribution-run`: the service on 9007 against Postgres on 5437 (`sbt schema && docker compose up
  -d` here first). `sbt "Test/runMain io.eitheror.casino.attribution.it.fakes"` serves the programme as a
  `FakeNetwork` on 9103: `/keys`, `/revocations`, and `/tag?aff=aff-1&lnk=lnk-1` minting a tag. A local
  run has no broker, so the topic consumers read nothing: the click route and the operator routes are what
  it shows. Export `ANKKA_SECRET_KEY` first (`openssl rand -base64 32`, kept). With no
  `ANKKA_KAFKA_BOOTSTRAP_SERVERS` the facts are read and published in memory. The operator routes need a
  token from dev-kind's casino Keycloak through a port-forward on 18180.

## What was found at implementation

- **`ce-id` on a consumed message** (plan Phase 0, T057): ankka 0.13 hands a topic consumer the subject
  and the sequence number, not `ce-id`. Every input is deduplicated by the data model's fallback: the
  family and the payment, payout or transaction id.
- **`casino.rewards` without the casino module** (T033): the consumer is registered only when
  `CASINO_MODULES` names `casino`, so the question of an undeclared topic never arises. Whether ankka
  would refuse such a consumer was not tested; the in-memory broker cannot tell.
- **The country header** (T073): dev-kind's gateway sets none and passes `CF-IPCountry` through (a click
  carrying it was recorded with that country), so `COUNTRY_HEADER` keeps its default; which header the
  production edge sets is the installation's to confirm.
- **`ce-subject`** (T067): ankka sets a published message's `ce-subject` to the source entity's id unless
  the consumer sets it, and for a record or a day's tally that id is the player's. `Publish.of` sets it to
  the fact's key. The leak suite found the player id there before the fix.

## What is verified where

| What | Where |
|---|---|
| the start, the ACLs | `it.StartSuite` |
| clicks (US1) | `it.LandingSuite`, `features.LandingFeatures` |
| binding (US1, US2) | `it.BindingSuite`, `it.WindowsSuite`, `features.BindingFeatures`, `features.BonusCodesFeatures` |
| the carriers (US3, SC-003) | `it.CarriersSuite`, `features.CarriersFeatures` |
| money and GGR (US4, SC-001, SC-002) | `it.MoneySuite`, `it.GgrSuite`, `features.FactsFeatures` |
| networks and the transition (US5, US6, SC-008) | `it.NetworksSuite`, `features.NetworksFeatures` |
| closure and erasure (US7) | `it.ClosureSuite`, `features.ClosureFeatures` |
| SC-004 (2,000 players' day) | `it.ScaleSuite`, which prints its measurements |
| SC-006 (no personal data) | `it.LeakSuite` |
| the operator's routes | `it.OperatorSuite` |
| a restart | `it.DurabilitySuite` |
| the original's answers | `port.ParitySuite` over `tests/port/attribution`'s twelve recordings (T035, 2026-10-28); `port.CoverageSuite` |

## Runs

- **Locally, 2026-10-25** (quickstart §6, ankka 0.13.0, without a staff token): the service started on
  9007 with the facts in memory and `FakesMain` on 9103. A `btag` of `nonsense` answered
  `{"ok":false,"clickId":"c-0","reason":"malformed"}`; a tag the fake minted answered `unknown_network`
  twice, the same answer, since no network could be added; an operator route without a token answered
  `401`; `/keys` answered an empty key set with today's first day. The local entity reads showed
  `Click` `c-1` refused `unknown_network` with no address digest, and `Settings` `brand` with
  `firstDay` 2026-10-25. Not yet run: the network's add, its pseudonym key and a bound click, which need
  a staff token.
- **On kind**, 2026-10-28 (kind-ankka at platform 0.14.2, the service on 0.13.0, goldvibe applied with
  `just modules apply goldvibe --installation dev-kind`; players, kyc, responsible-gambling and payouts
  paused, having no image on this branch): every topic contract `checked`, no undeclared topic, no
  `casino.rewards` consumer (core only). Under a staff token from the `eitheror` realm: the network
  `programme` added with `FakeNetwork`'s documents (reached from the pod at `host.docker.internal:9103`),
  its pseudonym key made (the answer `{"network","made"}`, no key), a signing key made. Through the
  gateway: a click with a minted tag `200 ok` twice under one click id, one fact; `country` `ZA` from the
  `CF-IPCountry` header, which the gateway passes through. Under machine tokens: `acme-feed`, granted the
  feed route, read a signed page holding the click fact; `other-feed`, ungranted, `403`; `acme-feed` on a
  network not its own `404`. The log held the click id and outcome only: no tag, key, token or visitor
  address. Not run: the topic read from the broker's external listener, which dev-kind does not have
  (the grant says `broker not exposed`). The run found five things, fixed or raised:
  - a core-only brand's `casino-modules` rendered empty, which the platform refuses; it now lists `core`;
  - attribution was OOM-killed twice at the default 512 MiB; the manifest gives it `medium`;
  - the network's `machine` in the CLI's grantee form (`machine:<org>/<name>`) matched no caller; it is
    now taken and held as `<org>/<name>`;
  - the modules tool asked for the descriptor of a restarted generation (the platform answers 409) and
    read the history oldest first (the platform's is newest first); it now compares with the newest
    generation that recorded one;
  - two ankka faults that leave every machine route refused, worked around on kind by hand (the control
    plane's `JAVA_TOOL_OPTIONS=-Dankka.http.tls.enabled=true` and `ANKKA_MACHINE_ISSUER`): the keys port
    7629 is started only under a flag the deployment never sets, and the machine issuer is derived from a
    configuration path the base domain is not at, so tokens name `http://localhost:9000`.
