# Contract: topics

The eleven topics of FR-011, as the manifest declares them and `contract.facts` types them. The
producer, readers and families are FR-011's; this file adds the mechanics every service follows.

## Mechanics

- **Name**: the contract name (`money.transactions`) is what components use; the broker holds
  `<slug>.<name>`. ankka 0.11 allows dots (`[a-z0-9]([a-z0-9.-]{0,98}[a-z0-9])?`).
- **Message**: a CloudEvent in binary mode. `ce-type` is the topic's contract name (`money.transactions.v1`),
  which ankka sets and checks at start; the family travels in the body's `type` field
  (`deposit_confirmed`, `bet_placed.v2`); `ce-subject` = the record key; `ce-id` = the fact's id, the
  same every time it is emitted (dedup by the reader).
- **Key** (FR-012): the id of the entity the fact is about. `money.transactions`: the wallet
  (`<playerId>/<currency>`). `payments.payments`: the payment id. `casino.players`, `casino.sessions`,
  `casino.withdrawals`, `casino.rewards`: the player id. `casino.rounds`: the round's wallet.
  `affiliates.attribution`: the per-network pseudonym (the link for `click`). `casino.push`: the
  player id. `platform.audit`: the audit record's subject entity. `payments.merchants`: the key id.
- **Player id**: a field `player_id` on every family where a player is involved, except on
  `affiliates.attribution` (never) and `payments.merchants` (no player).
- **Personal fields**: typed `Personal[A]` in `contract.facts`, enveloped under subject
  `player/<playerId>`; never a value in the clear in a message (SC-002).
- **Contract and schema**: one ankka contract per topic, `<name>.v1`, schema
  `contract/schemas/<name>.v1.json` = `oneOf` of the families, discriminated by the body's `type`,
  generated from `contract.facts` (`just schemas`) and shipped in the contract artifact. A family
  gains fields additively; a new family version is a new branch of the `oneOf`.
- **Retention** (FR-015, 043): fact topics keep everything or the installation's longest; three
  copies, two in sync, cleanup `delete`. `casino.sessions` 90 days, `casino.push` one day.

## Topics

| Topic | Producer | Key | Retention | Families (version 1 unless noted) |
|---|---|---|---|---|
| `money.transactions` | `wallet` | wallet | fact | deposit_confirmed (with `deposit_ordinal`), bet_placed, win_credited, bet_rolled_back, round_settled_as_loss, restricted_funds_awarded, restricted_funds_converted, restricted_funds_forfeited, admin_adjusted, hold_placed, hold_released; every family carries `real_balance_after`, `restricted_balance_after` |
| `payments.payments` | `wallet` | payment id | fact | payment.initiated, payment.captured, payment.failed, payment.expired, payment.abandoned, payment.refunded, payment.dispute.opened, payment.dispute.closed; every family carries `payment_provider` |
| `casino.players` | `players`, `kyc`, `responsible-gambling` | player id | fact | from `players`: player_registered, player_logged_in, player_logged_out, player_suspended, player_reinstated, player_self_excluded, player_closed, vip_tier_changed, player_profile_updated, player_consent_changed, player_fraud_flagged, player_fraud_cleared, player_email_changed, player_message_created, player_message_read, player_message_deleted; from `kyc`: player_kyc_submitted, player_kyc_document_reviewed, player_kyc_verified, player_kyc_rejected, player_kyc_pending, player_kyc_review_required, player_sof_cleared; from `responsible-gambling`: rg_case_opened, rg_case_closed, rg_limit_applied |
| `casino.sessions` | `players` | player id | 90 days | game_session_started, game_session_ended, player_session_started, player_session_ended |
| `casino.rounds` | `game-provider` | wallet | fact | round_settled (provider, provider round id, game, session, currency, total stake, win, outcome, wallet transaction ids) |
| `casino.withdrawals` | `payouts` | player id | fact | withdrawal_requested, withdrawal_approved, withdrawal_completed, withdrawal_cancelled, withdrawal_failed, withdrawal_reversed |
| `casino.rewards` | `rewards` | player id | fact | bonus_granted, bonus_cancelled, bonus_forfeited, bonus_completed, grant_expired, free_spins_granted, free_spins_settled, bonus_code_entered; grant families carry the offer's id, name and type |
| `affiliates.attribution` | `attribution` | pseudonym | fact | the fourteen kinds of spec 012 FR-011 |
| `casino.push` | `engagement` | player id | 1 day | push_delivered, presence_changed |
| `platform.audit` | every service | subject entity | fact | audit_record (spec 018 FR-001) |
| `payments.merchants` (in `payments`) | `merchant` | key id | fact | merchant_key_issued, merchant_key_deactivated (key id, sub-brand, digest; never a raw key) |

## Field names kept from the original (FR-014)

The original's analytics export (`shared/json-schemas/`, envelope `envelope.v1`) defines 22
families. Where a family above has the same meaning, its payload keeps these names; the brand
families add fields beside them and never rename.

| Original family | Payload fields kept |
|---|---|
| player_registered | `registered_at`, `btag`, `country`, `currency`, `vip_tier` |
| deposit_confirmed | `amount_cents`, `currency`, `transaction_id` |
| bonus_granted, bonus_cancelled | `bonus_id`, `bonus_name`, `amount_cents`, `currency` |
| game_session_started | `session_id`, `game_id` |
| game_session_ended | `session_id`, `game_id`, `duration_minutes` |
| player_logged_in | `session_id` |
| player_logged_out | `session_id`, `close_reason` |
| player_kyc_verified, player_kyc_rejected, player_suspended, player_self_excluded, player_reinstated, player_closed | `previous_value`, `new_value`, `actor`, `actor_id`, `reason` |
| rg_case_opened | `rg_case_id`, `case_type`, `closes_at` |
| rg_case_closed | `rg_case_id`, `case_type` |
| withdrawal_requested, withdrawal_completed, withdrawal_cancelled | `payout_id`, `amount_cents`, `currency` |
| withdrawal_failed | `payout_id`, `amount_cents`, `currency`, `failure_reason` |
| game_round_settled → `round_settled` | `round_id`, `game_id`, `stake_cents`, `win_cents`, `net_result_cents`, `currency` |
| vip_tier_changed | `previous_tier`, `new_tier` |

`*_cents` fields are `Long` minor units (the name is the original's; the unit is the currency's
minor unit). The export topics partners read today (`eitheror.casino.<family>.v1`) are not brand
topics; who serves them is spec 015's or a 040 machine grant's (research R13).
