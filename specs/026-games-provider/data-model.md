# Data model: games — eitheror's own game provider

Entities are the provider's unless marked (library) — the core's, shared with the simulator — or
(game) — the game service's, defined by the contract and held by each game.

## Game (registry)

`GameEntity`, key-value, id the code. Written by registration (FR-007), removed by deregistration
or an operator.

| Field | Type | Rule |
|---|---|---|
| code | string | unique across the provider; a second registration under a code another owner holds is refused naming the owner |
| owner | string | the registering game service's name, from its service principal |
| name, category, developer | string | developer is `eitheror` |
| bonusTypes, features, themes | string[] | the catalogue fields of spec 003 FR-021 |
| restrictedTerritories, prohibitedTerritories | string[] | ISO 3166-1 alpha-2 |
| deprecationDate | date? | |
| demo | boolean | supports fun mode (`fun_mode` in the games list) |
| pageAddress | URL | the game page, without a query |
| registeredAt, updatedAt | instant | |

`GameIndex`, key-value, id `index`: the codes, so the games list needs no scan.

## Brand entry

`BrandEntryEntity`, key-value, id the slug (R3). `BrandIndex`, key-value, id `index`: the slugs.

| Field | Type | Rule |
|---|---|---|
| slug | string | the brand as its routes name it |
| baseAddress | URL | the brand's `game-provider` host, where `/eitheror/*` callbacks are posted |
| site | string | the `site` the brand's launches carry; unique across entries |
| operatorPublicKey | base64 DER | the brand's operator public key for the `eitheror` provider; verifies launch, games, bonus (R5) |
| language | `es` \| `pt` | the session's language when the page carries no `lang` |
| testBrand | boolean | |
| games | string[] | the codes the brand may run; empty means every registered game (FR-017) |
| keyDocument | boolean | test brand only: the brand names the key document instead of holding the key; refused `true` with `testBrand=false` |
| providerWaitMillis | int (5000) | the answer wait |
| keyRefreshBoundSeconds | int (60) | |
| caPem | string? | a CA to trust for `baseAddress` (local installations) |
| realMoney | boolean | required; the operator's statement of what the brand's manifest says (the provider cannot read it; the quickstart compares the two as the simulator's does); an entry with `realMoney=true` and `keyDocument=true` refuses every launch (FR-020) |

## Session (library)

`SessionEntity`, event sourced, id the launch ticket. The simulator's shape with two fields
added for the product: `demo`, `language`. Nothing of the launch's `player_profile` is read. Events: `Launched`, `Checked`,
`CallbackPlanned`, `CallbackEnded` (the simulator's), unchanged in meaning.

| Field | Rule |
|---|---|
| launchToken, brand, playerId, currency, gameCode | from the launch |
| demo | `fun_mode` of the launch |
| language | the entry's |
| walletToken | the session ticket, from the check |
| rounds, roundOrder, sent | rounds by the game's round id; transaction and cancel ids sent |
| state | `empty`, `launched`, `checked`, `refused` |

The scenario fields (`scenario`, `seed`, `verdicts`) remain in the type for the simulator and
hold `default`, `0` and none at the provider.

### Round (library, inside Session)

| Field | Rule |
|---|---|
| id | the game's round id, unique within the session |
| state | `open`, `pending` (a stake not yet answered), `won`, `lost`, `cancelled` |
| debits, credits, cancels | transaction ids |
| free | the stake was a `free_debit` |

Transitions: a stake answered `ok` → `open`; a stake not yet answered → `pending` (then `open`
or back to no round on a refusal); a credit > 0 → `won`; a credit 0 → `lost`; a cancel → `cancelled`
when no open debit remains. A credit on a round not `open` or a second credit is refused by the
provider before anything is sent (FR-011).

## Callback (library)

`CallbackEntity`, event sourced, id the callback id. The simulator's shape plus `Abandoned`.

| Field | Rule |
|---|---|
| id, sessionId, brand, kind, transactionId, cancelId | |
| body | the bytes as signed |
| attempts | each: attempt number, key id, time sent, HTTP status, answer word, balance, time taken |
| state | `planned`, `sent`, `acknowledged`, `refused`, `exhausted`, `abandoned` |
| nextRetryAt | when `Deliver` sends again |
| abandonedBy, abandonReason, abandonedAt | FR-021 |

`acknowledged`: the protocol's acceptance; `refused`: a non-retryable refusal (`not_enough_money`,
`session_expired`, …); `exhausted`: the schedule's cap passed. `exhausted` and the retryable states
past the first attempt are what the unanswered list shows.

## Deliver (library, workflow)

Id the callback id. Steps: `send` → on acceptance or refusal `end`; on retryable, `pause` until
the schedule's next interval, then `send`; past the cap, `exhaust`. An `abandon` command ends it.
Idempotent under the transaction id: every attempt re-sends the same signed body.

## Key ring (library, secret store)

`keys/current`, `keys/next`, `keys/withdrawn` hold key ids; `keys/<kid>/private|public` the pairs.
The product's `promote` is the operator's `switch`; `withdrawOld` the operator's `withdraw`
(FR-020). The simulator's `wrong` key stays a simulator affair.

## Bonus index

`BonusIndex`, key-value, id the bonus id: brand, game code, created at. Routes `bonus/get` and
`bonus/cancel` to the game (R9).

## Free-round instance (game)

Held by each game service under the contract: bonus id, brand, player id, count remaining,
status (`active`, `used`, `cancelled`, `expired`), claim deadline (≥ the brand's requested
duration), spin value, currency. The test game uses the library's `BonusBook` for it.

## Views

| View | Over | Rows | For |
|---|---|---|---|
| `SessionsByBrand` | SessionEntity | one per session, keyed by brand | operator reads (FR-021) |
| `RoundsByDay` | SessionEntity | one per settled round: brand, settlement day, stake, win, outcome, transaction ids | the day export; the brand's rounds read |
| `UnansweredCalls` | CallbackEntity | callbacks in `sent` past attempt 1, `exhausted` | the unanswered list |
| `RunCallbacks` (library) | CallbackEntity | callbacks by session | one round's callbacks |

## Audit records

Published on `platform.audit` with the contract's family: `brand_entry_written`, `key_rotation_started`,
`key_switched`, `key_withdrawn`, `callback_abandoned`, `game_removed`, each with the actor (staff
token subject), the time and the subject (slug, key id, callback id, code).

## Licence profile (KYC, spec 009)

`Profile.Anjouan("ANJOUAN")`; `Profiles.Anjouan` equals `Profiles.Curacao` in every field but the
profile name.
