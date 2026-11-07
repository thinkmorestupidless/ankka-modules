# Facts: engagement

## Consumed, and the events each makes (FR-006 as the facts allow; research R8)

Every track carries the identify attributes plus the data below. Amounts are major units with two
decimals. `bonus_name` is the offer id. The send key is `<event>/<fact id>` unless said.

| Topic, family | Condition | Event | Data |
|---|---|---|---|
| `casino.players` `player_registered` | — | `player_registered` | `currency`, `btag?` |
| `player_registered` | `btag` present | `affiliate_attributed` | `btag` |
| `player_logged_in` | not within 2 min of `registeredAt` (a negative gap counts) | `player_logged_in` | — |
| `player_self_excluded` | — | `self_exclusion_activated` | — |
| `rg_case_opened` | `caseType = cool_off` | `cooling_off_set` | `cooling_off_period` = `closesAt` |
| `rg_limit_applied` | `causedBy ∈ {set, lowered}` | `deposit_limit_set` \| `loss_limit_set` \| `session_limit_set` by `limitType` | `limit_type` (`<TYPE>_<PERIOD>`), `limit_value` |
| `rg_limit_loosening_requested` (new, R6) | — | `rg_limit_loosened` | `limit_type`, `limit_value` |
| `rg_limit_applied` | `causedBy = removed` | `rg_limit_loosened` | `limit_type` |
| `player_kyc_verified` | — | `kyc_approved` | — |
| `player_kyc_rejected` | — | `kyc_rejected` | — |
| `player_kyc_document_reviewed` | `decision = approved` | `kyc_document_accepted` | `doc_type` = `category` |
| `player_kyc_document_reviewed` | `decision = rejected` (kyc's word; `declined` read too) | `kyc_document_declined` | `doc_type`, `reason` = `reasonCode` |
| `player_consent_changed` | `channel ∈ {email, sms}` | `consent_updated` | — |
| `casino.sessions` `game_session_started` | — | `game_session_started` | `game_id`, `session_id` |
| `game_session_ended` | — | `game_session_ended` | `game_id`, `session_id`, `session_duration_minutes` |
| `money.transactions` `deposit_confirmed` | — | `deposit_confirmed` | `amount`, `currency`, `transaction_id` = `paymentReference` |
| `deposit_confirmed` | the profile's lifetime count becomes 1 | `first_deposit_confirmed` | as above |
| `payments.payments` `payment.failed`, `payment.expired` | key `payment_failed/<paymentId>` | `payment_failed` | `amount`, `currency` (from the remembered `payment.initiated`), `transaction_id` = `paymentId`, `failure_reason` (`reason`, or `expired`), `provider`, `payment_method` = terminal, `reason` (normalised from `code`), `provider_code` = `code`; each when non-empty |
| `casino.withdrawals` `withdrawal_failed` | — | `withdrawal_failed` | `amount`, `currency`, `transaction_id` = `payoutId`, `failure_reason` |
| `casino.rewards` `bonus_granted`, `free_spins_granted` | — | `bonus_granted` | `bonus_name`, `amount?`, `currency`, `reward_id` |
| `bonus_cancelled`, `bonus_forfeited` | — | `bonus_cancelled` | `bonus_name`, `amount`, `currency` |
| `bonus_completed` | — | `wagering_requirement_met` | `bonus_name`, `amount`, `currency` |
| `bonus_code_entered` | — | `promo_code_entered` | `promo_code` |

Identify only (FR-010), key `identify/<fact id>`: `vip_tier_changed`, `player_email_changed`,
`player_profile_updated`, `player_duplicate_check_ended`, `player_closed` | `suspended` |
`reinstated` | `reopened`, `rg_case_closed`, `player_consent_changed` on `messages`, `player_fraud_flagged`
| `cleared` (no attribute; nothing sent), and any `money.transactions` family with no event (the
balances).

Signals: `player_message_created` → `Channel.signal(inbox.changed, created)`; `player_message_read`,
`player_message_deleted` → `Channel.signal(inbox.changed, read | deleted)`; `player_logged_in` →
`AgentChannel.signal`.

Profile-only (no send): `player_registered`'s profile fields, `payment.initiated`, `rg_case_opened`
of other types, the KYC `submitted` | `pending` | `review_required` families, `player_logged_out`,
`player_session_*`, `round_settled`, the other payments and withdrawals families.

## Produced on `casino.push` (key = player id; `ce-id` `<player>/<seq>`)

| Family | From | Fields |
|---|---|---|
| `push_delivered` | `Channel.Delivered` (kind `inbox.changed`, origin `created`) | `playerId, correlationId` (the bare message id), `connections, occurredAt` |
| `presence_changed` | `Channel.Opened` on 0→1; `Closed` \| `Expired` on 1→0 | `playerId, online, since, occurredAt` |

Nothing for `Leased`, `Acked`, `Signalled`, or a read or delete signal.

## Produced on `platform.audit`

| Action | Subject | Fields |
|---|---|---|
| `engagement.send.refused` | `player/<id>` | `reasonCode` `marketing_suppressed` \| `account_closed`; `changes` trigger, class |
| `engagement.send.retried`, `engagement.send.discarded` | `player/<id>` | the operator as actor; the send id; the outcome |
| `engagement.config.changed` | `brand/<slug>` | the operator; `changes` the keys changed (`trackApiKey` named, never valued) |

## The contract addition (research R6)

`casino.players`, produced by `responsible-gambling`:

```scala
@named("rg_limit_loosening_requested")
final case class RgLimitLooseningRequested(playerId: String, limitType: String, period: Option[String],
    value: Option[Long], currency: Option[String], appliesAt: Instant, occurredAt: Instant)
```

`player_consent_changed` gains no field; `players` produces it at registration with source
`registration` for `email` and `sms` (R6 P2).
