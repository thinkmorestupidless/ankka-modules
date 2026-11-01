# Quickstart: proving rewards

In build order. Every command from the repository root unless said. Use
`CASINO_VERSION=0.2.0-011-SNAPSHOT` (memory: contract snapshot collisions). kubectl only with
`--context kind-…`.

## 1. Contract

```bash
just schemas && just test
CASINO_VERSION=0.2.0-011-SNAPSHOT sbt -batch contract/publishLocal
```

Expect `FactsSuite` and `Compatibility` green with the new families ([facts.md](contracts/facts.md)).

## 2. Neighbours

```bash
CASINO_VERSION=0.2.0-011-SNAPSHOT just service wallet test          # W1 to W5
CASINO_VERSION=0.2.0-011-SNAPSHOT just service game-provider test   # G1
CASINO_VERSION=0.2.0-011-SNAPSHOT just service players test         # FR-034 to FR-036
just features
```

Expect the six FR-034 to FR-036 scenarios of `features/players/duplicates.feature` bound and green.

## 3. The service

```bash
just rewards-test      # pure, entity, consumer kits; GherkinSuite over features/rewards
just rewards-it        # Postgres (Docker): ClockSuite, RepeatSuite, PublishersSuite, FreeRoundsSuite, OutboundSuite
```

What each proves:

| Suite | Proves |
|---|---|
| `EvaluatorSuite` | the fourteen reasons in the original's order (`EligibilityEvaluatorSpec`'s cases) |
| `OfferRulesSuite` | create and edit refusals in order (`BonusRoutesSpec`'s) |
| `TransitionsSuite` | the ten states' table; a repeat is a no-op |
| `SettlementSuite` | free rounds counted once per round, paid once, closed or expired as the original |
| `ClockSuite` (SC-002) | each timer within a minute of its deadline |
| `RepeatSuite` (SC-003) | one award, forfeit and clawback per reward under any repeat, past 1,000 keys |
| `PublishersSuite` (SC-004) | each transition once on `casino.rewards`; nothing personal |
| `FreeRoundsSuite` (SC-005) | 50 polls then cancelled, no wallet command; a restart mid-poll resumes |
| `OutboundSuite` (SC-006) | only `wallet`, `game-provider`, `players` and the programme's URL are called |

## 4. Run it

```bash
just wallet-run                              # 9000
ANKKA_HTTP_PORT=9009 just service game-provider run
just rewards-run goldvibe                    # 9008, Postgres 5441
```

As an operator, create a deposit match (`POST /v1/casino/brands/goldvibe/offers`); as a player,
select it and deposit through the wallet; read `GET …/rewards/{playerId}/state`: active, with the
requirement at amount × multiplier. Give free rounds against the simulator
(`services/game-provider-simulator`, 9100), play them with `POST /test/free-spin`, and read the
reward closed with the winnings where the offer says.

## 5. Parity

Record (needs the user: the original's kind stack and credentials, research R2), move the
recordings to `tests/port/rewards/`, then:

```bash
just modules recordings check
just rewards-test     # ParitySuite over tests/port/rewards/, each marked divergence named
```

## 6. On kind

Deploy beside `wallet`, `players` and `game-provider` (`just modules apply goldvibe --installation
dev-kind`), drive one deposit match and one free-rounds grant end to end, and read `casino.rewards`.
