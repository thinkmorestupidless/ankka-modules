# Facts: what is read, and the producers amended on this branch

Every amendment is additive and optional (`Option[...] = None`, or a new family), so a reader
written against the earlier shape still reads it (spec 001 FR-011's compatibility rule;
`contract/.../facts/Compatibility.scala`). The schemas are regenerated (`just schemas`) and the
compatibility suite passes. Decided 2026-11-12 (Trevor): all of [fields.md](fields.md) §1.

## Read

| Topic | Families read | For |
|---|---|---|
| `casino.players` | `player_registered`, `player_email_changed`, `player_profile_updated`, `player_suspended`, `player_reinstated`, `player_self_excluded`, `player_closed`, `player_reopened`, the three `player_kyc_*` | `PlayerRecord` |
| `casino.sessions` | `game_session_started`, `game_session_ended` | `SessionRecord`, figures |
| `casino.rounds` | `round_opened` (new), `round_settled` | `RoundRecord`, figures |
| `money.transactions` | every player family in [fields.md](fields.md) §2's table; `records` (`RecordProgress`) on them | `TransactionRecord`, `BetRecord`, `BonusAwardRecord` (wagering), figures |
| `casino.withdrawals` | `withdrawal_completed` | the WITHDRAWAL transaction's status, figures |
| `casino.rewards` | `bonus_granted`, `free_spins_granted`, `bonus_cancelled`, `bonus_forfeited`, `bonus_completed`, `grant_expired` | `BonusAwardRecord` |

## Amended

| # | Producer | Fact | Change | Value |
|---|---|---|---|---|
| G1 | `players` | `player_registered`, `player_profile_updated` | `language: Option[String] = None` | the profile's language |
| G2 | `players` | `player_registered`, `player_profile_updated` | `stateProvince: Option[Personal[String]] = None` | the profile's `stateProv`, under `player/<id>` |
| G3 | `game-provider` | `casino.rounds` | new family `round_opened(playerId, currency, roundId, provider, providerRoundId, gameId, sessionId, freeRoundsGrant: Option[String], occurredAt)`, keyed as `round_settled` | from the round entity's existing `RoundOpened` event (`rounds/RoundModel.scala:137`), today ignored by `facts/RoundFacts.scala:16-19` |
| G3 | `game-provider` | `round_settled` | `openedAt: Option[Instant] = None` | the entity's `openedAt` (`RoundModel.scala:91`), so a round whose `round_opened` was before the reader started still has its start |
| G4 | `wallet` | `bet_placed`, `win_credited`, `bet_rolled_back`, `round_settled_as_loss` | `gameRoundId: Option[String] = None`, `sessionId: Option[String] = None` | passed by `game-provider` with the move (`rounds/MoneyMoves.scala:60`): `gameRoundId` is exactly the round's `roundId` as `round_settled` and `round_opened` carry it (`Round.entityId`, `<provider>/<providerRoundId>`), so `uuid3("round/" + gameRoundId)` is the round's public id; `sessionId` is the game session's id; the wallet's `PlaceBet` already holds `sessionId` (`entity/Commands.scala:61`) |
| G5 | `rewards` | `bonus_granted`, `free_spins_granted` | `expiresAt: Option[Instant] = None` | the reward's expiry, which `rewards` already passes to the wallet (`clients/Wallet.scala:39`) |
| G6 | `rewards` | `free_spins_granted` | `amountCents: Option[MinorUnits] = None`, `currency: Option[Currency] = None` | spin value × rounds, the original's `face_value` for a free-spins claim (checked against recording 1 before the value is fixed) |
| G7 | `game-provider` | `round_settled` | `realStakeCents: Option[MinorUnits] = None`, `realWinCents: Option[MinorUnits] = None` | the sum of the wallet's answers' `depositPartCents + convertedPartCents` for the round's debits and credits |
| G8 | `game-provider` | (a route, no fact) | `GET /v1/casino/brands/{brand}/game-provider/games` under `Routes.serviceOf("rewards", "public-api")` in `BrandsEndpoint` | `CatalogueReads.toJson` for `Enablement.listed` games, with `removedFromFeed` and `releaseDate` so the caller applies the original's release gate |

Each producer's own suites gain one test per field, and its feature files are unchanged: the new
fields describe nothing a player or an operator observes there.

## Published

`platform.audit` `audit_record`, through `PublicApiAudit` (research R6):

| Action | Actor | Subject | `changes` |
|---|---|---|---|
| `public_api.read` | `access_key/<keyId>` | `access_key/<keyId>` | `operation`, `status`, `duration_ms`, and `player:<id>` → the field names served |
| `public_api.identifying_fields_served` | `access_key/<keyId>` | `player/<id>` | `fields` → the field names; `operation` |
| `access_key.created`, `.changed`, `.disabled`, `.enabled`, `.deleted` | `operator/<sub>` | `access_key/<keyId>` | the fields changed, before and after; never the key or its digest |

`recordClass` is `security` for the key changes and `system` for the reads (spec 018's categories).
