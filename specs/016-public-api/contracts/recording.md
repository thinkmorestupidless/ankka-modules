# Recordings and the original's suites

## The driver's recordings (FR-040, research R14)

`tests/original/public-api-driver/` (Go, in the original's toolchain) runs against the original on
kind. It is modelled on `tests/e2e/scenario252_partner_api_e2e_test.go`: it seeds two brands, a
partner client and grant per brand (one grant with every identifying field and an agreement, one
with none), and activity with known figures. It writes `tests/port/public-api/<name>.json` with
`contract.port.Recording`. The parity tests in `services/public-api/src/test/scala/.../port/` replay
each recording against the service, with the same facts published to its topics and an access key
of the matching settings in place of the token.

**Comparison rules**:

- statuses and bodies are compared field by field;
- ids are compared by role: the recording's twin ids and the service's record ids are mapped through
  the seeding (player 1 of brand A, round 3 of session 2…);
- authentication refusals are compared by status and by the code's class: `jwt_missing` →
  `key_missing`; `jwt_invalid`, `jwt_verification_failed`, `jwt_issuer_mismatch` → `key_invalid`;
  `jwt_expired` → `key_expired`; `grant_disabled` → `key_disabled`. `scope_*`, `actor_type_invalid`
  and `grant_absent` have no counterpart and are not replayed (contracts/amendments.md);
- `computedAt` and snapshot ids are compared by presence and by shape.

| # | Recording | Parity test |
|---|---|---|
| 1 | `walk-two-brands`: every route for brand A's own records (scenario 252) | `WalkParitySuite` |
| 2 | `other-brand-ids`: brand B's ids on every by-id route and as every collection's parent | `IsolationParitySuite` |
| 3 | `transactions-paged`: a player's 250 transactions at limit 100, then `limit=101` | `PagingParitySuite` |
| 4 | `boundaries`: offset past the end; an empty bonus-award collection; a malformed id | `BoundariesParitySuite` |
| 5 | `identifying-fields`: one player read under the full grant and the empty one | `FieldsParitySuite` |
| 6 | `games`: the listing, one game, a disabled game by code | `GamesParitySuite` |
| 7 | `game-stats`: rankings by each sort and period (stake and ggr in one currency), one game's stats, a money sort without a currency | `GameStatsParitySuite` |
| 8 | `player-ranking`: pages across a snapshot change, a retired snapshot, `status=ACTIVE` | `PlayerRankingParitySuite` |
| 9 | `player-stats`: one player's stats; a player with none; another brand's player | `PlayerStatsParitySuite` |
| 10 | `quota`: a grant at 5 per minute driven to its sixth request | `QuotaParitySuite` |
| 11 | `contract`: `/v1/openapi.json`, `/v1/contract`, `/v1/docs`, `/health`, a POST | `ContractParitySuite` |

The parity suites skip, naming the recording, when `tests/port/public-api/` is absent, so the
service builds before the recordings are made.

## The original's suites, re-expressed (FR-040)

`casino/public-api/test/` in the original. Each is ported one for one where its behaviour is kept.

| Original | Here | Note |
|---|---|---|
| `audit.test.ts` | `AuditSuite` | record per request, per player served; refusals with a key named |
| `boundaries.test.ts` | `BoundariesSuite` | |
| `entitlements.test.ts` | `IdentifyingFieldsSuite` | per key, not per grant; snake_case fallbacks dropped (the key holds the contract's names) |
| `enums.test.ts` | `EnumsSuite` | over the fact words here (contracts/fields.md §2) |
| `grant-reload.test.ts` | `KeyChangeSuite` | the next request, not a 60-second reload |
| `isolation.test.ts` | `IsolationSuite` | two brands' facts in two service instances |
| `lawful-basis.test.ts` | — | the agreement condition is dropped (spec *Changes*) |
| `logger.test.ts` | `DiagnosticsSuite` | no identifying value, no key, no digest in logs |
| `money.test.ts` | `MoneySuite` | |
| `no-pii-leak.test.ts` | `DiagnosticsSuite` | full walk and ranking walk |
| `paging.test.ts` | `PagingSuite` | plus the tie-break by id |
| `partnerToken.test.ts` | `AdmissionSuite` | every key refusal |
| `playerStatsMapper.test.ts` | `PlayerFiguresSuite` | |
| `playerStatsQuery.test.ts` | — | the twin store's derived ids; none exist here |
| `quota.test.ts` | `QuotaSuite` | `MovableClock`; across two instances on one database |
| `requestPipeline.test.ts` | `PipelineSuite` | the order of refusals |
| `scope-levels.test.ts` | — | no scope levels |
| `scopeSettings.test.ts` | — | no scope id |
| `substrateClient.test.ts` | — | no store client |
| `unit.test.ts` | — | brand-scope derivation and namespaces |
| `contract/clientGeneration.test.ts` | `ContractValiditySuite` | OpenAPI 3.1 validity (research R2) |
| `contract/responses.test.ts` | `ContractResponsesSuite` | |
| `contract/snapshot.test.ts` | `ContractSnapshotSuite` | plus `ContractAmendmentSuite` |
| `contract/validator.test.ts` | `ContractResponsesSuite` | |
| `games/catalogue.test.ts` | `CatalogueSuite` | |
| `games/ttlCache.test.ts` | `CatalogueCacheSuite` | |
| `routes/byId.test.ts` | `ByIdSuite` | |
| `routes/droppedRecords.test.ts` | `ProjectionRefusalSuite` | a record that cannot be mapped is refused at projection, logged without personal data, and is 404 by id |
| `routes/games.test.ts` | `GamesSuite` | |
| `routes/gameStats.test.ts` | `GameStatsSuite` | |
| `routes/gameStatsEmpty.test.ts` | `GameStatsSuite` | |
| `routes/gameStatsUnreadable.test.ts` | — | stats rows are this service's; nothing is unreadable |
| `routes/playerCollections.test.ts` | `CollectionsSuite` | parent 404 first; no store call counts |
| `routes/playerRanking.test.ts` | `PlayerRankingSuite` | |
| `routes/playerRankingCrawl.test.ts` | `RankingCrawlSuite` | 1,000 crawls (SC-007) |
| `routes/players.test.ts` | `PlayersSuite` | impostor records do not exist here |
| `routes/playersClosed.test.ts` | `PlayersSuite` | a closed player's status |
| `routes/playerStats.test.ts` | `PlayerStatsSuite` | |

New suites: `KeysSuite` (the operator routes), `KeyAuditSuite` (FR-021), `StatsRunSuite` (the
hourly run's figures against a hand count), `StatsPerfSuite` (SC-008), `ErasureSuite` (FR-025), `BudgetSuite` (FR-035),
`GherkinSuite` over `features/public-api/`.
