# Contract: the routes this feature adds

Spec 002's [routes.md](../../002-wallet/contracts/routes.md) stands; these are the additions.
Prefix `/v1/casino/brands/<brand>` on `ServiceEndpoint`; `W` = `/wallets/{player}/{currency}`. A route
takes at most two path parameters, so the currency of an account route rides in the body; an
account route's `{kind}` must be `pool` or `clearing`, else `INVALID_REQUEST`. Amounts on service
routes are minor-unit integers; on operator and player routes major-unit strings with two decimals.
A missing key is `MISSING_KEY`; a zero or negative amount `INVALID_REQUEST` (spec 001 FR-026).

Every money reply carries `realBalance`, `bonusBalance`, `currency`, `txnId` as today, plus
`reserved` and `available` (`MoneyReply` gains the two, optional). A refusal is `RefusalBody(error,
message, details)` with the status `Wire.status` gives: the new codes `RESERVATION_NOT_FOUND` and
`ACCOUNT_NOT_FOUND` are 404, `NOT_OWNER` 403, `CREDIT_LINE_BELOW_BALANCE` and `NOT_CLEARING` 400,
the rest 409 (the default), `OUTCOME_PENDING` 503.

## Service routes (`Routes.service()`: the project's services, no token)

| Method, path | Caller | Command or flow | Body in | Reply | Refusals |
|---|---|---|---|---|---|
| `POST W/reservations` | exchange | `reserve` | `key` (= order id), `amount` | balances, `reserved`, `available`, `txnId` | `INSUFFICIENT_FUNDS`, `KEY_REUSED`, `WALLET_NOT_FOUND` |
| `POST W/reservations/release` | exchange | `release-reservation` (key `<orderId>/<sequence>`) | `orderId`, `sequence`, `amount` | balances, `reserved`, `available`, `txnId` | `RESERVATION_NOT_FOUND`, `RELEASE_EXCEEDS_RESERVATION` (details: `amount`, `reserved`), `KEY_REUSED` |
| `POST /settlements` | exchange | `SettlementWorkflow` (id `<currency>/<tradeId>`) | `tradeId`, `currency`, `buyer`, `orderId`, `seller`, `amount`, `unused` (default 0) | `buyer{realBalance, reserved, available, txnId}`, `seller{realBalance, txnId}`, `currency` | `INVALID_REQUEST` (buyer = seller), `WALLET_NOT_FOUND`, `RESERVATION_NOT_FOUND`, `RESERVATION_SHORT` (details: `required`, `reserved`), `KEY_REUSED`, `OUTCOME_PENDING` |
| `POST /accounts/{kind}/{name}/splits` | exchange | `AccountMoveWorkflow` to-account, purpose `split` | `key`, `currency`, `player`, `amount` | `wallet{realBalance, reserved, available, txnId}`, `account{balance, txnId}` | `INVALID_REQUEST` (kind ≠ pool), `NOT_OWNER`, `WALLET_NOT_FOUND`, `INSUFFICIENT_FUNDS`, `KEY_REUSED`, `OUTCOME_PENDING` |
| `POST /accounts/{kind}/{name}/merges` | exchange | from-account, purpose `merge` | `key`, `currency`, `player`, `amount` | as splits | `INVALID_REQUEST`, `WALLET_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `NOT_OWNER`, `INSUFFICIENT_FUNDS`, `KEY_REUSED`, `OUTCOME_PENDING` |
| `POST /accounts/{kind}/{name}/payments` | exchange | from-account, purpose `resolution_payment` (key `<name>/<positionId>`) | `positionId`, `currency`, `player`, `amount` | as splits | as merges |
| `POST /accounts/{kind}/{name}/moves` | the pool's `clearing` (granted); in-project services | to-account (`direction: from-wallet`) or from-account (`to-wallet`), purpose `clearing` | `key`, `currency`, `player`, `amount`, `direction` | as splits | as splits or merges by direction; `CREDIT_LINE_REACHED` (details: `amount`, `balance`, `floor`) |
| `PUT /accounts/{kind}/{name}/credit-line` | the pool's `clearing` (granted); the owner | `set-credit-line` (creates the account under the caller when absent) | `currency`, `creditLine` | `balance`, `creditLine`, `floor`, `owner` | `INVALID_REQUEST`, `NOT_OWNER`, `NOT_CLEARING`, `CREDIT_LINE_BELOW_BALANCE` |
| `GET /accounts/{kind}/{name}/balance?currency=` | any | `balance` | — | `balance`, `creditLine`, `floor`, `owner`, `currency` | `ACCOUNT_NOT_FOUND` |
| `GET W/balance` | any | `balance` | — | gains `reserved`, `available` | — |
| `GET W/holds/preview?amount=` | payouts | `preview-hold` | — | gains `available`; the refusal is the hold's against available | — |
| `GET W/transactions?type=` | any | view | — | `type` accepts the seven new types; rows gain `reference` | — |

The account routes and the two clearing routes use `Routes.serviceNamed(...)`: the same admission as
`Routes.service`, as an authenticating ACL whose principal is the caller's `service:<project>/<name>`,
which the flow passes as the account's owner. The clearing routes name their grantee through
`withAcl(Routes.serviceNamed(Some(GrantTarget.Route("wallet", "POST",
"/v1/casino/brands/{brand}/accounts/{kind}/{name}/moves"))))` and the same for the credit line; the
grant is made in the predictions module's manifest (spec 023 FR-009) as `principal:
service:<pool project>/clearing` once spec 024 names the project, and `Layout.BrandGrantsMade`
reads it then. Until then the grantee list is empty and the routes admit the project's services.

## Operator routes (`Routes.operator(slug)`: a staff token with the brand's role)

Under `OperatorEndpoint`'s prefix; amounts as major-unit strings; the actor is the token's subject.

| Method, path | Command | Body in | Reply | Refusals |
|---|---|---|---|---|
| `POST /accounts/{kind}/{name}/adjustments` | `adjust` | `key`, `currency`, `amount` (signed), `reason`, `category` (`goodwill`, `correction`, `other`) | `balance`, `creditLine`, `txnId` | `ACCOUNT_NOT_FOUND`, `REASON_REQUIRED`, `DEBIT_EXCEEDS_BALANCE` (details: `amount`, `balance`), `KEY_REUSED` |
| `GET /accounts/{kind}/{name}/balance?currency=` | `balance` | — | as the service route, major units | `ACCOUNT_NOT_FOUND` |
| `GET W/balance` | `balance` | — | `MajorBalance` gains `reserved`, `available` | — |

## Player routes (`Routes.player(slug)`)

`GET /balance` (the player's, per currency) gains `reserved` and `available`; `GET /transactions`
lists the new types with `reference`. No new route.

## Rows for `contract/access/routes.md`

| Service | Method | Path | Class | Spec |
|---|---|---|---|---|
| wallet | POST | /v1/casino/brands/{brand}/wallets/{player}/{currency}/reservations | service | 022 FR-001 |
| wallet | POST | /v1/casino/brands/{brand}/wallets/{player}/{currency}/reservations/release | service | 022 FR-002 |
| wallet | POST | /v1/casino/brands/{brand}/settlements | service | 022 FR-004 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/splits | service | 022 FR-008 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/merges | service | 022 FR-008 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/payments | service | 022 FR-005 |
| wallet | POST | /v1/casino/brands/{brand}/accounts/{kind}/{name}/moves | service | 022 FR-006, FR-013 (granted to the pool's clearing by the predictions module's manifest) |
| wallet | PUT | /v1/casino/brands/{brand}/accounts/{kind}/{name}/credit-line | service | 022 FR-007 (granted likewise) |
| wallet | GET | /v1/casino/brands/{brand}/accounts/{kind}/{name}/balance | service | 022 FR-012 |
| wallet | POST | <operator prefix>/accounts/{kind}/{name}/adjustments | operator | 022 FR-009 |
| wallet | GET | <operator prefix>/accounts/{kind}/{name}/balance | operator | 022 FR-012 |

## The exchange's calls, in order (the ledger port, ankka-predictions spec 004)

| Port call | Route | Key |
|---|---|---|
| reserve(order, amount) | `POST W/reservations` | order id |
| release(order, ordinal, amount) | `POST W/reservations/release` | order id + ordinal |
| settle(trade, buyer order, seller, quantity × price, unused) | `POST /settlements` | trade id |
| split(market, player, sets) | `POST /accounts/pool/{market}/splits` | the exchange's key |
| merge(market, player, sets) | `POST /accounts/pool/{market}/merges` | the exchange's key |
| pay(market, position, player, amount) | `POST /accounts/pool/{market}/payments` | market + position |

The port's answers: done (200 with balances), insufficient (`INSUFFICIENT_FUNDS`, `RESERVATION_SHORT`,
`CREDIT_LINE_REACHED`), or a refusal with a reason (every other code); `OUTCOME_PENDING` means retry.
