# Contract: the facts this feature adds

Spec 002's [facts.md](../../002-wallet/contracts/facts.md) stands: `ce-type` `money.transactions.v1`,
the family in the body's `type`, `ce-subject` the key, `ce-id` the dedupe id the publisher sets (the
transaction id). These are the additions (FR-011; spec 001 FR-013: additive only).

## Contract changes

| Change | Where |
|---|---|
| Seven families extending `MoneyTransactionsFact` (below) | `contract.facts.MoneyTransactions`; `families` gains seven `FamilyDef.of` |
| `sealed trait AccountFact extends Fact` (`accountKind`, `accountName`, `currency`, `transactionId`, `balanceAfter: SignedMinorUnits`, `creditLine: MinorUnits`, `occurredAt`; `key = s"$accountKind/$accountName/${currency.code}"`) with one family, `account_adjusted` | the same file; `families` gains it, so the schema's union holds eight more |
| `Routes.serviceNamed(grantedRoute: Option[GrantTarget.Route] = None): Acl` | `contract.access.Routes`: `Routes.service`'s admission as `Acl.Authenticate`, principal `service:<project>/<name>` for an in-project caller and the grant's principal for a grantee |
| rows in `contract/access/routes.md` | [routes.md](routes.md) |

`just schemas` regenerates `contract/schemas/money.transactions.v1.json`; `SchemaDriftSuite` fails
until it does; `FactsSuite` tests each family's sample. No existing family, field or key changes.

## The eight families

Common to the seven wallet-keyed families: `player_id`, `currency`, `transaction_id`,
`real_balance_after`, `restricted_balance_after`, `reserved_balance_after`, `occurred_at`, and the
optional `deposit_pool_after` and `converted_pool_after` of spec 025; the five that move real
balance also carry the optional `deposit_part_cents` and `converted_part_cents` (research R16).
The verb names what happened to the keyed ledger.

| Family | Key | Fields beyond the common ones | Meaning |
|---|---|---|---|
| `reservation_placed` | wallet | `order_id`, `amount_cents` | real unchanged; reserved up |
| `reservation_released` | wallet | `order_id`, `amount_cents` | reserved down |
| `settlement_debit` | wallet (buyer) | `trade_id`, `order_id`, `amount_cents`, `released_cents`, `counterparty_player_id` | real down by the cost; reserved down by cost + released |
| `settlement_credit` | wallet (seller) | `trade_id`, `amount_cents`, `counterparty_player_id` | real up |
| `resolution_payment` | wallet | `market_id`, `position_id`, `amount_cents` | real up, from the market's pool account |
| `account_debit` | wallet | `account_kind`, `account_name`, `purpose`, `reference`, `amount_cents` | real down, into the account (a split; a clearing move from the wallet) |
| `account_credit` | wallet | `account_kind`, `account_name`, `purpose`, `reference`, `amount_cents` | real up, from the account (a merge; a clearing move to the wallet) |
| `account_adjusted` | account (`<kind>/<name>/<currency>`) | `amount` (signed), `reason` (the category, never the text), `actor_id`, `balance_after`, `credit_line` | an operator's adjustment |

## `money.transactions` from wallet events (`Facts.moneyTransactions`, consumer `MoneyTransactionsPublisher`)

| Event | Family | `ce-id` |
|---|---|---|
| `ReservationPlaced` | `reservation_placed` | the transaction id |
| `ReservationReleased` | `reservation_released` | |
| `SettlementDebited` | `settlement_debit` | |
| `SettlementCredited` | `settlement_credit` | |
| `MovedFromAccount` with purpose `resolution_payment` | `resolution_payment` (`market_id` = `account_name`, `position_id` = `reference`) | |
| `MovedToAccount` | `account_debit` | |
| `MovedFromAccount` with any other purpose | `account_credit` | |
| `CommandRefused` | none | audit only |

## `money.transactions` and `platform.audit` from account events (consumer `AccountAuditPublisher`)

| Event | Topic, family | `ce-id` |
|---|---|---|
| `AccountAdjusted` | `money.transactions`, `account_adjusted` | the transaction id |
| `AccountClaimed`, `CreditLineSet`, `CommandRefused` | `platform.audit` in spec 001's audit shape (the account id as the subject, the actor or owner, the code for a refusal) | `<accountId>/<sequence>` |
| `AccountDebited`, `AccountCredited` | none: the wallet's side of the same move is the fact (below) | |

## Reconstructing balances (SC-004)

A wallet: as spec 002, from its own facts' `*_after` fields; `reserved_balance_after` on the seven
new families (0 is the value before any of them).

An account `<kind>/<name>/<currency>`: the fold over the topic of every fact naming it, in any order:

```text
balance = Σ account_debit.amount_cents        where account_kind/name match   (money entered)
        − Σ account_credit.amount_cents       where account_kind/name match   (money left)
        − Σ resolution_payment.amount_cents   where market_id = name, kind = pool
        + Σ account_adjusted.amount           keyed by the account
```

`account_adjusted.balance_after` is the account's own figure at that moment and is the check on the
fold. Reporting (spec 015) reads the account's balance as the brand's liability to its players
(Assumptions); if it needs the account's balance after every move, that is two added families from a
consumer over `AccountEntity` in a later spec (research R8).
