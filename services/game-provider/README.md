# game-provider

The brand's service between the game provider and the wallet (spec 003): ST8's signed seamless-wallet
callbacks, one round per provider round, the launch, the catalogue, and the free rounds `rewards` asks for.
The design is `specs/003-game-provider/plan.md`; the decisions and what established them are its `research.md`.

## Build and test

```bash
(cd ../.. && sbt contract/publishLocal)   # the contract this build depends on
sbt test                                  # Docker must be running: the integration kit starts Postgres
```

`just service game-provider test` does both from the repository root.

## The components

| Kind | Name | What it holds |
|---|---|---|
| key value | `ticket` | a launch ticket and the session ticket it becomes, by token; never expires (FR-010) |
| workflow | `bet` | one provider transaction, `<provider>/<transaction id>` (the cancel id for a cancel): the claim, `may-bet`, the wallet call, `record-round`, and the outcome for the ledger's life; unacknowledged, it pauses until a retry, a replay or a resolve (FR-011, FR-012, FR-020; research R18) |
| event sourced | `round` | one provider round, `<provider>/<round id>`: every transaction and its answer, the open bets, the conflicting repeats, the unacknowledged callbacks; recorded by the workflow before the provider is answered (FR-013 to FR-020) |
| key value | `known-player` | the providers a player is known to (FR-018, FR-025) |
| key value | `launch-window` | one player's launches in the current minute (FR-007) |
| key value | `game` | one provider's game, `<provider>/<code>` (FR-021) |
| key value | `brand-catalogue-configuration` | the brand's disabled developers and categories, and per-game overrides (FR-022) |
| key value | `game-rounds`, `refresh-status` | rounds played per game; each provider's last refresh |
| view | `player-status` | every player's account status, from `casino.players`, read from the topic's start |
| view | `catalogue`, `game-popularity` | the games for the lobby; the games by rounds played |
| view | `rounds-by-player`, `rounds-by-session`, `unacknowledged` | the operator's listings |
| consumer | `round-facts` | `round_settled` on `casino.rounds`, keyed by the wallet (FR-019) |
| consumer | `round-counter` | one round counted per game when a round opens |
| timed action | `catalogue-refresh` | the nightly refresh per provider, rescheduling itself, and an operator's refresh-now |

A money callback resolves who and how much in `api/callbacks/Callbacks.scala`, starts the transaction's
bet workflow (`rounds/BetWorkflow.scala`) and polls it for the outcome, 1 ms apart up to 2 s; the money
rules themselves are `rounds/MoneyMoves.scala` and `rounds/Settlement.scala`, run by the workflow's steps. Everything that is ST8's protocol
is in `provider/st8/`, behind `provider/ProviderAdapter.scala`: a second provider is a second adapter and a
second configuration.

## The three callback prefixes

ST8 strips the path from the wallet URL it is given, so every callback is served at three places on the
`game-provider-<slug>` host (FR-002, research R2):

```text
POST /v1/casino/brands/{brand}/st8/{callback}   the wallet URL the launch names
POST /st8/{callback}
POST /{callback}                                ST8 only; another provider never gets the root
```

A callback is verified as `SHA256withECDSA` over its raw body (`x-st8-sign`) before anything is read; a
failure is HTTP 200 `auth_failed`.

## The provider configuration, and rotating a key

A provider's configuration is one entry in the service's own secret store, `provider/<name>`, written by
an operator and read back without its private key:

```bash
curl -X PUT https://game-provider-goldvibe.<base>/v1/casino/backoffice/goldvibe/game-provider/providers/st8 \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"apiUrl":"https://api.st8.example","site":"goldvibe","operatorPrivateKey":"<PEM or base64 PKCS#8>",
       "providerPublicKey":"<PEM or base64 DER>","jurisdiction":"MT",
       "lobbyUrl":"https://goldvibe.example/casino","depositUrl":"https://goldvibe.example/cashier"}'
```

Add `"walletUrl":"https://game-provider-goldvibe.<base>/v1/casino/brands/goldvibe/st8"`, the address ST8
calls back: the service knows its own host only when `CASINO_PUBLIC_URL` is set, which the brands tool does
not render yet.

Every instance reads the configuration again within 30 seconds, so a rotated key verifies without a
redeploy (FR-026). Write the new key with the same route. A brand that takes no real money may give
`providerKeyDocumentUrl` instead of `providerPublicKey`: the service fetches the provider's current and
next keys from it every 30 seconds, as spec 020's simulator publishes them. A real-money brand is refused that.

## Adding a second provider

1. Implement `ProviderAdapter` for it in `provider/<name>/`, with its callbacks' protocol beside it.
2. Add it to `Settings.fromEnvironment`'s adapters.
3. Write its configuration through the route above under its own name.

Its callbacks are then served at `/v1/casino/brands/{brand}/<name>/{callback}` and `/<name>/{callback}`;
its games are refreshed nightly and listed with `provider: "<name>"`. Nothing stored changes.

## The suites

| Suite | Proves |
|---|---|
| `AmountsSuite`, `St8SignatureSuite`, `St8ProtocolSuite`, `ProviderConfigurationsSuite`, `WalletClientSuite` | the boundary: amounts, signatures, the protocol, the configuration cache, research R7's mapping |
| `TicketSuite`, `PlayersSuite`, `RoundSuite`, `TransactionIndexSuite`, `SettlementSuite`, `CatalogueEntitiesSuite` | each entity's rules, with no runtime |
| `RoundFactsSuite`, `RoundCounterSuite` | the two consumers |
| `features/*Features` | the nine features of `features/game-provider`, 82 scenarios and outline rows, on the whole service |
| `LeakSuite` | SC-003 over 1,000 rounds, and SC-002's timing report in `target/sc-002-timing.txt` |
| `RepeatSuite`, `SignatureSuite` | SC-004's 1,000 repeats and SC-005's 100 foreign keys |
| `BetWorkflowSuite` | the claim, the outcome after `record-round` (with a test delay that makes the order observable), `may-bet` refusing and silent, a wallet that cannot be reached, a resolve, 50 pairs of simultaneous duplicates |
| `PlayerStatusSuite`, `KeyRotationSuite`, `DurabilitySuite` | the players topic from its start; a real-money brand's key; a restart, including one mid-workflow |
| `CatalogueViewSuite`, `CatalogueRefreshSuite`, `UnacknowledgedViewSuite`, `RewardsEndpointSuite` | the views, the refresh and its retries, the gauge, the routes `rewards` calls |
| `ParitySuite`, `ParityFixtureSuite` | SC-001: one test per recording in `tests/port/game-provider` (33), each divergence and extension of plan.md named in the test; the replay machinery on a fixture |

The integration suites share one service (`features/SharedKit`) and namespace every id by the running
test, since the database outlives a test.
