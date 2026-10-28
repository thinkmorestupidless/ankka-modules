# Contract: the pools and parts on `money.transactions`

Additions to spec 002's [facts.md](../../002-wallet/contracts/facts.md). Additive (spec 001
FR-013): every new field is optional, absent on a message written before this change, so such a
message decodes, and no family, name or existing field changes. (Planned as defaults of 0; built as
`Option` fields defaulting to `None`, so a reader tells "no parts" from "a part of 0". `attribution`
counts a fact without parts as 0.) `just schemas` regenerates
`contract/schemas/money.transactions.v1.json`; `SchemaDriftSuite` fails until it does.

## On every family (the trait)

| Field | Type | Meaning |
|---|---|---|
| `deposit_pool_after` | signed minor units | the wallet's deposit pool after the movement |
| `converted_pool_after` | signed minor units | the wallet's converted pool after the movement (negative only after an allow-negative bet's shortfall) |

`deposit_pool_after + converted_pool_after == real_balance_after` on every message.

## Per family

| Family | New fields | The parts are of |
|---|---|---|
| `deposit_confirmed` | `deposit_part_cents` (= `amount_cents`), `converted_part_cents` (= 0) | the deposit |
| `bet_placed` | `deposit_part_cents`, `converted_part_cents` | the real leg of the stake (not `amount_cents`, which is the whole stake; the real leg is their sum) |
| `win_credited` | `deposit_part_cents`, `converted_part_cents` | the real share of the win (their sum; `amount_cents` is the whole win) |
| `bet_rolled_back` | `deposit_part_cents`, `converted_part_cents` | the real amount restored |
| `round_settled_as_loss` | none beyond the trait's | — |
| `restricted_funds_awarded` | none beyond the trait's | — |
| `restricted_funds_converted` | `deposit_part_cents` (= 0), `converted_part_cents` (= `amount_cents`) | the conversion |
| `restricted_funds_forfeited` | `deposit_part_cents`, `converted_part_cents` | `clawback_cents` (0/0 when no clawback; on the first record of a cancel or hold, as `clawback_cents` is) |
| `admin_adjusted` | `deposit_part_cents`, `converted_part_cents` | |`amount`| when `target` is real; 0/0 on restricted |
| `hold_placed` | `deposit_part_cents`, `converted_part_cents`, `voided_deposit_cents`, `voided_converted_cents` | `amount_cents` (= paid + voided) and, apart, `voided_cents`; the paid parts are the difference |
| `hold_released` | `deposit_part_cents`, `converted_part_cents` | `amount_cents` (paid + voided restored) |
| `withdrawal_reversed` | `deposit_part_cents`, `converted_part_cents` | `amount_cents` (the paid parts restored) |
| `provider_credited` | `deposit_part_cents` (= 0), `converted_part_cents` (= `amount_cents` when `landed_on` is real, else 0) | the credit |

## What `attribution` reads (spec 012 FR-030 as amended)

| Family | Leg |
|---|---|
| `bet_placed` | stake of `deposit_part_cents` |
| `win_credited` | win of `deposit_part_cents` |
| `bet_rolled_back` | rollback of `deposit_part_cents` |
| `round_settled_as_loss` | the settling leg, 0 |
| `provider_credited` | nothing (bonus-side money) |

The real delta `WalletCursor` computed is no longer an amount; the cursor keeps the dedupe of a
redelivered message.

## Spec 022's families

`reservation_placed`, `reservation_released`, `settlement_debit`, `settlement_credit`,
`resolution_payment`, `account_debit`, `account_credit` carry the trait's two `after` fields when
built; their parts are the spec's *Assumptions* for 022.
