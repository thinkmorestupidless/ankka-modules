# rewards

A brand's offers and the rewards they grant (spec 011):
- the operator's offers: deposit match, free spins, both, and no deposit, with their codes;
- the player's view of what they may take, selection, codes, and their rewards;
- each reward's life, from targeted to closed, held at the wallet as restricted funds;
- free rounds at the game provider, counted from the rounds it settles;
- the `casino.rewards` facts, and each operator action on `platform.audit`.

An ankka 0.13 service in Scala, one sbt build.

## What it is

| Component | Kind | What it holds or does |
|---|---|---|
| `OfferBook` | event sourced, id = brand slug | the brand's offers, their order, the live codes and the roles |
| `PlayerRewards` | event sourced, id = player | what rewards knows of a player: registration, the duplicate check, the no-deposit block, the last deposit, the offers visible, the rewards held, the codes entered; runs `Evaluator` |
| `Reward` | event sourced, id = reward | one reward: its status, amounts, clocks, rule snapshot and, for free rounds, the rounds counted |
| `CodeDocument`, `CodesTimer` | key value, id = brand slug; timed action | the programme's code document, fetched on `codes-<slug>` |
| `WalletStep` | workflow, id = `<reward>/<step>` | one keyed call to the wallet, retried until it answers, the answer delivered to the reward |
| `FreeRounds` | workflow, id = reward | the grant at the provider: create, poll every 30 s up to 50 times, cancel |
| `RewardsView` | view of the rewards | one row per reward, no personal column; the player's list and history, the brand's search |
| `PlayersConsumer`, `MoneyConsumer`, `RoundsConsumer`, `IndexConsumer` | consumers | players' facts, the wallet's deposits and restricted funds, settled free rounds, and the reward index |
| `RewardsPublisher`, `CodesPublisher`, `AuditPublisher` | consumers | the facts on `casino.rewards`; operator actions on `platform.audit` |
| `RewardsEndpoint`, `OffersEndpoint`, `RewardAdminEndpoint`, `CampaignEndpoint` | HTTP | one per prefix and audience; [routes](../../specs/011-rewards/contracts/routes.md) |

| Prefix | Audience |
|---|---|
| `/v1/casino/brands/<slug>/rewards` | the player, on their own rewards; support-agent and game-provider by identity |
| `/v1/casino/brands/<slug>/offers` | a brand, resource or platform admin |
| `/v1/casino/brands/<slug>/reward-grants` | an operator: the offer-less grants, revoke, the brand's search |
| `/v1/casino/brands/<slug>/reward-campaigns` | `engagement` alone |

## The calls out

- **The wallet**, in the brand's project, by identity: award, cancel, close the pending cap, forfeit. Each
  is keyed by the reward (the award) or `<reward>/<step>`, so a repeat is the first answer.
- **The game provider**: a game's bonus types, and the free-rounds grant's create, status and cancel.
- **The programme's code document**, at `REWARDS_CODE_DOCUMENT_URL`; `none` reads no document.

## Run it locally

```bash
cd services/rewards
CASINO_VERSION=0.2.0-SNAPSHOT sbt schema   # ankka's schema into target/ddl
docker compose up -d                       # Postgres on 5441
cd ../.. && just rewards-run               # HTTP on 9008, as brand goldvibe
```

The wallet's local run on 9000, game-provider's on 9009 and players' on 9003.

## Test it

```bash
just rewards-test   # the unit suites
just rewards-it     # the integration, parity and feature suites (Docker)
```
