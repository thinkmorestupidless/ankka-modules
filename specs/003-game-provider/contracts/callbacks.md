# Contract: the provider's callbacks, as the brand answers them

ST8's seamless-wallet protocol as the original served it (`St8Routes.scala`) and spec 020's
simulator sends it (020 FR-009 to FR-013). Every route is `POST`, JSON in and out, served at the
three prefixes research R2 names. ACL `Routes.public`; the signature is the credential.

## Before any handler

| Check | Answer |
|---|---|
| `x-st8-sign` missing, not base64, or not a valid `SHA256withECDSA` signature over the raw body under the brand's provider public key | HTTP 200 `{"status":"auth_failed"}` |
| body is not a JSON object | HTTP 400 `{"status":"session_expired"}`; for `player_profile`, `{"status":"player_not_found"}` |
| a `{brand}` segment that is not this project's | HTTP 404 `{"error":"wrong_brand","brand":"<slug>"}` (spec 001 FR-005) |

Fields the protocol names as strings are also read as numbers; fields it does not name are ignored
(FR-004). `amount` is a decimal string or number converted to minor units of the resolved currency;
a value that is not a whole number of them is HTTP 400 `{"status":"error","error":"amount_not_minor_units","amount":"<as sent>"}`
(FR-005, spec 001 FR-026). Every answered `balance` is real plus restricted balance rendered to the
currency's exponent (`"95.00"`); `currency` is the resolved currency (FR-006).

Every other answer is HTTP 200 with a `status` word. Money answers are `{status, balance,
currency}`; refusals carry `balance` and `currency` where the table says so.

## How a money callback is served (2026-10-10, research R18)

Every money callback (debit, credit, cancel, buyin, payout) starts the bet workflow
`<provider>/<transaction id>` (the cancel id for a cancel) and answers from its state, polled every
1 ms up to 2 s; past the bound it answers `{"status":"error"}` and the provider's retry is answered
from the workflow. A start refused `Conflict` is a repeat: it is answered from the workflow's outcome
and never reaches `responsible-gambling` or the wallet; a body that differs from the first is recorded
as a conflicting repeat on the round. The outcome is written only after the round has recorded the
transaction. "The stored answer" below is the workflow's outcome.

## check

Reads `token`, `game_code` (optional).

| Case | Answer |
|---|---|
| token empty, unknown, already checked, or issued for another game | `{"status":"session_expired"}` |
| player suspended, self-excluded, closed or blocked (PlayerStatus view) | `{"status":"player_locked"}` |
| ok | `{"status":"ok","token","currency","game_code","country","player_id","site":{"id","lobby","deposit"}}` — `country` from `players` (MT when unknown), `site.id` the brand slug, `lobby` and `deposit` from the provider configuration |

Side effects: the ticket becomes the session ticket; `KnownPlayer` records the player for the
provider.

## balance

Reads `player`, `token`, `currency`.

| Case | Answer |
|---|---|
| `player` empty and no token | `{"status":"player_not_found"}` |
| token of 10+ characters not a checked ticket | `{"status":"session_expired"}` |
| token shorter than 10 characters: ignored; `player` resolved through KnownPlayer; unknown | `{"status":"session_expired"}` |
| player locked | `{"status":"player_locked"}` |
| wallet unreachable | `{"status":"error"}` |
| ok | `{"status":"ok","balance","currency"}` |

## player_profile

Reads `player`.

| Case | Answer |
|---|---|
| `player` empty, or neither known to the provider (KnownPlayer) nor registered at the brand (PlayerStatus, from `casino.players`), or `players` has no such player | `{"status":"player_not_found"}` |
| ok | `{"status":"ok","id","jurisdiction","default_currency","reg_country","birth_date","reg_date","bet_limits"}` — `jurisdiction` from the provider configuration (a change from the original's constant `CW`); `reg_country` upper-cased; `reg_date` a calendar day; `bet_limits` from the daily deposit limit: ≤ 50 `low`, ≤ 200 `medium`, else `high`, `medium` when none (FR-018) |

The fields come from `players` at the time of the call and are not stored (FR-028).

## debit

Reads `token`, `transaction_id`, `round` or `round_id`, `provider_kind` (default `debit`),
`currency`, `amount`, `game_code`.

| Case | Answer |
|---|---|
| `transaction_id` already started (the bet workflow's outcome) | the stored `{status, balance, currency}`; neither `responsible-gambling` nor the wallet is asked; a differing body is recorded as a conflicting repeat |
| token missing or not a checked ticket | `{"status":"session_expired"}` |
| player suspended, closed or blocked (PlayerStatus view) | `{"status":"player_locked"}` |
| `amount` = 0 or `provider_kind` = `free_debit` | no wallet call; round tagged with the ticket's grant (an `error` if the tag cannot be written); zero-stake transaction recorded; `{"status":"ok","balance","currency"}` with the current balance |
| wallet refuses `INSUFFICIENT_FUNDS` | `{"status":"not_enough_money","balance","currency"}`; no bet |
| the workflow's `may-bet` answers locked, excluded or cooling off | `{"status":"player_locked","balance","currency"}`; the wallet is not called; the outcome |
| `may-bet` gives no answer within 500 ms, a non-2xx or one it cannot read | `{"status":"error"}`; the wallet is not called; the outcome (fails closed; not unacknowledged) |
| wallet refuses `SCOPE_BLOCKED` or any other responsible-gambling refusal | `{"status":"spending_limit","balance","currency"}` |
| wallet unreachable, `KEY_REUSED`, 5xx (after the step's retries) | `{"status":"error"}`; recorded unacknowledged; the workflow pauses with no outcome and a retry resumes it |
| ok | bet placed keyed by `transaction_id` (game, category, provider; allowed below zero only for `correction_debit`); `{"status":"ok","balance","currency"}` |

A debit naming another game than the ticket's is served the same. `eitheror_casino_bets_total`
counts every ok debit, free ones included.

## credit

Reads `token`, `transaction_id`, `round` or `round_id`, `provider_kind` (default `credit`),
`currency`, `amount`.

| Case | Answer |
|---|---|
| repeat | the stored answer |
| token missing or not checked | `{"status":"session_expired"}` |
| round tagged with a free-rounds grant | no wallet call; recorded; `{"status":"ok","balance","currency"}` with the current balance; the win travels on `round_settled` for `rewards` |
| round has no open bet, or the wallet answers `ROUND_NOT_FOUND` for it | provider credit keyed by `transaction_id`; `{"status":"ok","balance","currency"}` |
| `amount` = 0 | every open bet settled as a loss; `{"status":"ok","balance","currency"}` |
| `amount` > 0 | win on the first open bet, every other open bet settled as a loss (FR-014); `{"status":"ok","balance","currency"}` |
| wallet unreachable or any other refusal | `{"status":"error"}`; recorded unacknowledged; never a silent award |

Every settlement produces one `round_settled` (contracts/round-settled.md).

## cancel

Reads `player`, `token`, `cancel_id`, `transaction_id` (the referenced one), `currency`, `round`
or `round_id`.

| Case | Answer |
|---|---|
| `cancel_id` already answered | the stored answer |
| no checked ticket and `player` unknown | `{"status":"session_expired"}` |
| referenced `transaction_id` has no bet workflow | `{"status":"transaction_not_found","balance","currency"}` |
| referenced transaction is a `credit` or `free_credit` | bet keyed by `cancel_id` for the credited amount, allowed below zero; `{"status":"ok","balance","currency"}` |
| referenced transaction is a free-round or zero debit | round closed as cancelled, no money moves; ok |
| referenced transaction is a debit the wallet still holds open | rollback on its own key; round cancelled when no open bet remains; ok |
| referenced transaction is a debit the wallet no longer holds (`ROUND_NOT_FOUND`) | provider credit of the debit's amount keyed by `cancel_id`; ok |
| wallet unreachable | `{"status":"error"}`; recorded unacknowledged |

A cancel of a credit on an already cancelled round is debited back and the round stays cancelled.

## buyin

Reads `player` (required), `token`, `transaction_id`, `provider_kind` (default `buyin`),
`currency`, `amount`.

| Case | Answer |
|---|---|
| `player` empty | `{"status":"player_not_found"}` |
| repeat | the stored answer |
| wallet refuses `INSUFFICIENT_FUNDS` | `{"status":"not_enough_money","balance","currency"}` |
| `may-bet` refuses / the wallet refuses `SCOPE_BLOCKED` or another RG limit | `{"status":"player_locked"}` / `{"status":"spending_limit"}` with balance (the original answered `ok` to these, a defect not kept); `may-bet` silent is `error` as a debit |
| ok | bet keyed by `transaction_id` outside any round (its own round of that id); allowed below zero for `correction_debit` |

## payout

Reads `player` (required), `token`, `transaction_id`, `currency`, `amount`. A provider credit keyed
by `transaction_id`; `{"status":"ok","balance","currency"}`; `player_not_found` for an empty
player; `error` and unacknowledged when the wallet is unreachable.

## Currency resolution (FR-006)

The checked ticket's currency, else the player's (KnownPlayer, then `players`), else the
callback's `currency`, else `EUR`.

## Status words

`ok`, `error`, `auth_failed`, `session_expired`, `player_locked`, `player_not_found`,
`not_enough_money`, `spending_limit`, `transaction_not_found`. `provider_unavailable` is a word
the simulator reads (020 FR-012) and this service never answers.
