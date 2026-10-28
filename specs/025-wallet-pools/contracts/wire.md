# Contract: what the driver observes and how parity translates it

Additions to spec 002's [wire.md](../../002-wallet/contracts/wire.md). The driver
(`tests/original/wallet-driver`) records the original's `wallet-service` directly; `WireTranslation`
maps each recorded step to the wallet's routes and fields.

## The original's fields (what a recording can observe)

| Where | Field | Form | Meaning |
|---|---|---|---|
| balance response (every audience) | `depositPoolBalance`, `convertedPoolBalance` | major string | the pools; absent only on an unstamped wallet, which the driver never makes (a wallet the original creates is stamped `0/0`) |
| bet response | `fundedDepositAmount` | major string | the deposit part of the real leg |
| win response | `wonDepositAmount` | major string | the deposit part of the real share |
| transaction twins (`observe:` step, substrate read) | `funded_deposit_amount`, `funded_converted_amount` (bet, rollback), `won_deposit_amount`, `won_converted_amount` (win), `from_deposit_amount`, `from_converted_amount` (withdrawal, bonus_cap_void, bonus_cancel, negative admin_adjustment), `to_deposit_amount`, `to_converted_amount` (withdrawal_return, withdrawal_reversal) | minor integers | the parts; **not** on the original's `GET …/transactions` rows (found in implementation: `WalletServiceClient.getTransactions` builds `TransactionRecord` without them), so replay never sees them |
| wallet twin (`observe:` step, substrate read) | `deposit_pool_balance`, `converted_pool_balance`, `pools_stamped_at` | minor integers | the pools; replay skips `observe:` steps, so a scenario asserts the pools through the balance route too |

## Translation

| Original | Wallet | Rule |
|---|---|---|
| `depositPoolBalance` "50.00" | `depositPoolBalance` 5000 | major → minor, as `realBalance` |
| `convertedPoolBalance` | `convertedPoolBalance` | same |
| `fundedDepositAmount` | `fundedDepositAmount` | same |
| `wonDepositAmount` | `wonDepositAmount` | same |

The four fields carry the original's names, so the decimal rule spec 002 already applies is their
whole translation (`WireTranslationSuite`, "spec 025: the pools and the deposit parts …"). The
rows' parts need none: the original's transactions route does not serve them. The rebuild's rows
carry `depositPart`, `convertedPart`, `depositPoolAfter` and `convertedPoolAfter` (a new field the
original answered nowhere, so replay keeps it out); the movement's parts are held to the original
by the fixtures and the Go tables instead.

## The driver's new scenarios (`pools_test.go`)

One per scenario of `features/wallet/pools.feature`, named exactly as the feature names it,
except the four that are not the original's wallet's to answer: the fact scenario (the rebuild's
topic), the two migration scenarios (spec 019), and "the two pools always sum to the real balance"
(asserted inside every other scenario instead). The starting pools are made by the original's own
rules: deposit for the deposit pool; an award played through to conversion for the converted pool
(the driver's `restricted_funds_test.go` already plays a record to conversion); an allow-negative
bet for the shortfall case.

Each scenario reads the balance after every step and, where the feature names parts, the bet or
win response (the transaction twin's parts only in an `observe:` step, which replay skips). A scenario whose answer differs from the feature is still
recorded and is a finding for the spec (the recording is the original's behaviour).
