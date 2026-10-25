# Contract: events → families, and the contract additions

Consumers fold the entities' events into the contract's families (research R7). `ce-subject` and the
record key are the player id; `ce-id` is the event's id, so a re-emission is the same fact. The
field names are the original's schemas' (`specs/001-brands-and-projects/contracts/topics.md`,
"Field names kept from the original").

## `casino.players` (`PlayersPublisher`)

| Event | Family | Payload |
|---|---|---|
| `Registered` | `player_registered` | `player_id`, `registered_at`, `country`, `currency`, `vip_tier` (""), `btag`, `click_id`, `bonus_code` — the last three as presented and unverified |
| `SignedIn` | `player_logged_in` | `player_id`, `session_id` |
| `PlayerSessionEntity.Ended` | `player_logged_out` | `player_id`, `session_id`, `close_reason` |
| `Suspended` | `player_suspended` | `player_id`, `previous_value`, `new_value`, `actor`, `actor_id`, `reason` |
| `Reinstated` | `player_reinstated` | the same five |
| `SelfExcluded` | `player_self_excluded` | the same five; `excluded_at` |
| `Closed` | `player_closed` | the same five (`actor` `player` for FR-032a, `reason` `player_request`) |
| `Reopened` | `player_reopened` | the same five — **new family** |
| `VipTierChanged` | `vip_tier_changed` | `player_id`, `previous_tier`, `new_tier` |
| `ProfileEdited`, `DateOfBirthCorrected` | `player_profile_updated` | `player_id`, `changed: [field]`, and as `Personal`: `first_name`, `last_name`, `date_of_birth`, `phone`, `address_line1`, `city`, `postal_code`, `country` (spec 009 FR-016 reads them) |
| `ConsentChanged` | `player_consent_changed` | `player_id`, `email_marketing`, `sms_marketing`, `messages_suppressed`, `source`, `at` |
| `DuplicateFlagged` | `player_fraud_flagged` | `player_id`, `case_id`, `linked_player_ids`, `signals` |
| `DuplicateCleared` | `player_fraud_cleared` | `player_id`, `case_id` |
| `MessageEntity.Created` | `player_message_created` | `player_id`, `message_id`, `category`, `origin` |
| `MessageEntity.Read` | `player_message_read` | `player_id`, `message_id` |
| `MessageEntity.Deleted` | `player_message_deleted` | `player_id`, `message_id` |

Not published: `EmailVerified` (the status change to `active` is read from `player_registered`'s
absence of a later refusal; `kyc` and `rewards` wait for no verification fact today), the email list
changes, cookie decisions, favourites, `Erased` (an erasure is ankka 042's record, not a fact;
readers redact from their own handler). Nothing produces a `blocked` transition: the status comes
only from the migration (spec FR-031), and a migrated `blocked` player's later transitions carry
`previous_value: blocked`.

## `casino.sessions` (`SessionsPublisher`)

| Event | Family | Payload |
|---|---|---|
| `PlayerSessionEntity.Opened` | `player_session_started` | `player_id`, `session_id`, `started_at`, `network_address` (`Personal`), `user_agent` (`Personal`) |
| `PlayerSessionEntity.Ended` | `player_session_ended` | `player_id`, `session_id`, `ended_at`, `close_reason` |
| `GameSessionEntity.Opened` | `game_session_started` | `player_id`, `session_id`, `game_id`, `player_session_id`, `currency`, `demo` |
| `GameSessionEntity.Ended` | `game_session_ended` | `player_id`, `session_id`, `game_id`, `duration_minutes`, `close_reason` |

## `platform.audit` (`AuditPublisher`)

One `audit_record` per operator action (suspend, reinstate, close, reopen, tier, PotVIP, date of
birth, note, message, force-end, config change, fraud review, reset-password, export, erasure) with
the operator's subject, the player as the subject entity, the action, the reason where one was
given, and never a `Personal` value (the note's text, the message's body and the date of birth are
not on the record; the record names the field).

## Contract additions (`contract/`, additive)

| Addition | Where |
|---|---|
| family `player_reopened` (`previous_value`, `new_value`, `actor`, `actor_id`, `reason`) | `facts/CasinoPlayers.scala`; `casino.players.v1` schema regenerated (`just schemas`) |
| `close_reason` values gain `superseded` on `player_logged_out`, `player_session_ended`, `game_session_ended` | the same files; `casino.sessions.v1` schema |
| `click_id`, `bonus_code` on `player_registered` (already typed? the task checks; added if not) | `facts/CasinoPlayers.scala` |
| the two routes every brand service serves to `players` (`contracts/routes.md`) | `contract.access.Routes.playersOnly(slug)`: a `service` ACL admitting `players` of the brand (and, on `merchant`, the erasure grantee) |

Spec 001 FR-011's producer list for `casino.players` is already `players`, `kyc`,
`responsible-gambling`; nothing changes there.
