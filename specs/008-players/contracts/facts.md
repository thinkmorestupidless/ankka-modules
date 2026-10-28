# Contract: events → families, and the contract additions

Consumers fold the entities' events into the contract's families (research R7). `ce-subject` and the
record key are the player id; `ce-id` is the event's id, so a re-emission is the same fact. The
field names are the contract's families as spec 001 defined them in
`contract/src/main/scala/io/eitheror/casino/contract/facts/` (themselves the original's schemas'
names, `specs/001-brands-and-projects/contracts/topics.md`); where this table and the contract
disagree, the contract wins.

## `casino.players` (`PlayersPublisher`)

| Event | Family | Payload |
|---|---|---|
| `Registered` | `player_registered` | `player_id`, `registered_at`, `country`, `currency`, `vip_tier` (none), `btag`, `click_id`, `bonus_code` — the last three as presented and unverified |
| `SignedIn` | `player_logged_in` | `player_id`, `session_id` |
| `PlayerSessionEntity.Ended` | `player_logged_out` | `player_id`, `session_id`, `close_reason` |
| `Suspended` | `player_suspended` | `player_id`, `previous_value`, `new_value`, `actor`, `actor_id`, `reason` |
| `Reinstated` | `player_reinstated` | the same five |
| `SelfExcluded` | `player_self_excluded` | the same five; `excluded_at`, `until` |
| `Closed` | `player_closed` | the same five (`actor` `player` for FR-032a, `reason` `player_request`) |
| `Reopened` | `player_reopened` | the same five — **new family** |
| `VipTierChanged` | `vip_tier_changed` | `player_id`, `previous_tier`, `new_tier`, `actor_id` |
| `ProfileEdited`, `DateOfBirthCorrected` | `player_profile_updated` | `player_id`, `changed_fields`, and as `Personal`: `given_name`, `family_name`, `date_of_birth`, `telephone`, `address_line`, `city`, `post_code`; `country` plain (spec 009 FR-016 reads them) |
| `ConsentChanged` | `player_consent_changed` | `player_id`, `channel` (`email` \| `sms` \| `messages`, the last the operator's suppression of in-app marketing, FR-030), `consented`, `source`; one fact per channel changed |
| `DuplicateFlagged` | `player_fraud_flagged` | `player_id`, `fraud_case_id`, `signal` (`identity` \| `device` \| `identity,device`) |
| `DuplicateCleared` | `player_fraud_cleared` | `player_id`, `fraud_case_id` |
| `MessageEntity.Created` | `player_message_created` | `player_id`, `message_id`, `send_class` (the message's category) |
| `MessageEntity.Read` | `player_message_read` | `player_id`, `message_id` |
| `MessageEntity.Deleted` | `player_message_deleted` | `player_id`, `message_id` |

Not published: `EmailVerified` (`kyc` and `rewards` wait for no verification fact today), the email
list changes, cookie decisions, favourites, `Erased` (an erasure is ankka 042's record, not a fact;
readers redact from their own handler). Nothing produces a `blocked` transition: the status comes
only from the migration (spec FR-031), and a migrated `blocked` player's later transitions carry
`previous_value: blocked`.

## `casino.sessions` (`SessionsPublisher`)

| Event | Family | Payload |
|---|---|---|
| `PlayerSessionEntity.Opened` | `player_session_started` | `player_id`, `session_id`, `network_address` (`Personal`), `user_agent` (`Personal`) |
| `PlayerSessionEntity.Ended` | `player_session_ended` | `player_id`, `session_id`, `close_reason` |
| `GameSessionEntity.Opened` | `game_session_started` | `player_id`, `session_id`, `game_id`, `player_session_id` |
| `GameSessionEntity.Ended` | `game_session_ended` | `player_id`, `session_id`, `game_id`, `duration_minutes`, `close_reason` |

## `platform.audit` (`AuditPublisher`)

One `audit_record` per operator action (suspend, reinstate, close, reopen, tier, PotVIP, date of
birth, note, message, force-end, config change, fraud review, reset-password, export, erasure) with
the operator's subject, the player as the subject entity, the action, the reason where one was
given, and never a `Personal` value (the note's text, the message's body and the date of birth are
not on the record; the record names the field).

## Contract additions (`contract/`, additive; made by T001)

| Addition | Where |
|---|---|
| family `player_reopened` (`previous_value`, `new_value`, `actor`, `actor_id`, `reason`) | `facts/CasinoPlayers.scala`; `casino.players.v1` schema regenerated (`just schemas`) |
| `close_reason` on `game_session_ended`, optional; the other two close reasons are free strings already, so `superseded` needs no change there | `facts/Others.scala`; `casino.sessions.v1` schema |
| `click_id`, `bonus_code` on `player_registered` | already typed by spec 001; no change |
| `Routes.playersOnly` (admits `players` of the caller's own project: the erasure and export routes every brand service serves) and `Routes.serviceOf(names*)` (named services of the project: the service endpoint's per-caller routes) | `access/Routes.scala` |

Spec 001 FR-011's producer list for `casino.players` is already `players`, `kyc`,
`responsible-gambling`; nothing changes there. `kyc`'s families this service reads are
`player_kyc_verified` and `player_kyc_rejected` (data-model.md's `KycFactsConsumer`).
