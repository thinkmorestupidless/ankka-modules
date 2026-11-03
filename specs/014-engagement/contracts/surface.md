# Surface: the original's CRM, webhook, push and agent stream, moved, dropped or new

Every route, function, job and topic of the original that this service takes over, and where it
goes. `S` = `casino-service/.../io/eitheror/casino/`, `G` = `tl/runtime/capabilities/customerio_dispatch/`,
`PG` = `substrate/push-gateway/`. New paths are in [routes.md](routes.md).

## Dispatch (TL functions and capabilities)

| Original | Disposition |
|---|---|
| `notify_player` on every Player write → `customerio_dispatch` player mode (`G/handler.go:118-276`) | moved: `PlayersConsumer`, `MoneyConsumer` and `Events` (R8); the freshness gate and the 25 h cache replaced by the `Send` claim and the staleness bound |
| `notify_session` (`notify_session.tl:7-23`) | moved: `SessionsConsumer` |
| `notify_bonus` (`notify_bonus.tl:35-68`) | moved: `RewardsConsumer` |
| `notify_payment_failed`, `notify_payment_expired` | moved: `PaymentsConsumer` (both → `payment_failed`) |
| `notify_withdrawal_failed` | moved: `WithdrawalsConsumer` |
| `notify_agents_player_online` → `kafka_publish` `<prefix>.casino.player_logged_in.v1` | moved: `PlayersConsumer` → `AgentChannel.signal`; the topic dropped |
| `affiliate-status-notify`, `affiliate-pending-notify`, `notify-affiliate-ggr` | not this service's (spec 013) |
| `G/cache.go` (dedup, config cache) | dropped: the claim; `Secrets`' 30 s cache |

## `CrmService` call sites (`S`)

| Original | Disposition |
|---|---|
| `dispatch` from the deposit route (`first_deposit_confirmed`), `RgRoutes` (limits), `BackofficePlayerRoutes` (documents), `BonusRoutes` (promo code), `PlayerRoutes` (consent) | moved: the consumers over the facts (R8) |
| `syncAttributes` from `vip-tier`, `pot-vip`, the reset | moved: an identify `Send` on `vip_tier_changed` and the reset; `pot_vip` has no fact (divergence) |
| `sendEmailChangeVerification`, `sendEmailAddressVerification`, `sendPasswordChangeVerification`, `sendEmailChangeNotification`, `sendPasswordChangeNotification`, `sendEmailPrimaryChanged`, `sendPasswordResetRequest` (`CrmService.scala:532-675`) | moved: the send route, seven triggers (FR-014); `players` calls three today |
| `sendTransactionalInline` (never called) | dropped |
| `CustomerIOClient` (`/api/v2/entity`, `/v1/send/email`) | moved: `clients.CustomerIo` |

## Configuration

| Original | Disposition |
|---|---|
| `GET`/`PUT /v1/casino/crm-config/{brandId}`, `POST …/seed` (`CustomerIOConfigRoutes.scala`) | moved: `ConfigEndpoint`, same paths |
| the holding-level `merchant-service` `/v1/merchant/brands/{holdingId}/crm-config` and its `OperatorInvite`/`OperatorPasswordReset` | not carried (spec 004 FR-027) |
| `fromEmail` stored, never used | kept in the configuration, still unused |
| secrets in the twin's JSON | moved: the secret store |

## The reset

| Original | Disposition |
|---|---|
| CronJob `5 3 * * *` → `POST /internal/v1/casino/{brand}/daily-deposit-count/reset` with `X-Internal-Secret` (`CasinoServiceMain.scala:1050-1066`; `DailyDepositCountReset.scala`) | moved: the recurring timer `deposit-day-<slug>` over `ProfilesView`; the route dropped |
| `DepositDay` (`ResetHourUtc = 3`) | moved: `profile.DepositDay` |

## The webhook

| Original | Disposition |
|---|---|
| `POST /v1/casino/customerio/webhook/{brandId}` (`CW:895`) | moved: `WebhookEndpoint`, same path |
| `grant_bonus` → `EligibilityEvaluator`, the wallet, `FreeSpinsProvisioner` (`CW:120-652`) | moved: relayed to `rewards`' award route (011 FR-022), which holds the logic |
| `send_message` → `NotificationService.createNotification` (`CW:676-745`) | moved: relayed to `players`' message route (008 FR-030); `campaign_id`, `reward_id` dropped |
| `target_offer` → `BonusTargetingService` (`CW:747-805`) | moved: relayed to `rewards`' target route |

## Push

| Original | Disposition |
|---|---|
| `GET /v1/stream` (`PG/StreamRoutes.scala:64`) | moved: `StreamEndpoint`, same path |
| `GET /v1/presence` (pod-local; `PushGatewayMain.scala:147-150`) | moved: `GET /v1/casino/backoffice/<slug>/presence`, cluster-wide |
| `/health`, `/ready` | dropped: ankka's own |
| `platform.push.envelope` (`NotificationService.scala:381-396`) | dropped: `player_message_*` on `casino.players` |
| `platform.push.events` (`delivery.outcome`, `presence.changed`, `presence.snapshot`) | dropped: `casino.push` (`push_delivered`, `presence_changed`) |
| `PushEventConsumer` (`S`) | moved: `players`' `PushDeliveredConsumer` (built) |
| `PresenceView` (`S`) | moved: `Channel`; `players`' online filter reads `presence_changed` (*Elsewhere*) |
| `ConnectionRegistry`, `Revalidator`, `ConnectionSweeper`, `PresenceTracker`, `GracefulShutdown`, `EnvelopeConsumer`, `MultiRealmJwtValidator`, `KeepAliveSocketOptions` | moved: `Channel`, `StreamEndpoint`'s tick, `Frames`, `Leases`; ankka's OIDC and socket keep-alive |

## Agent stream

| Original | Disposition |
|---|---|
| `POST /v1/casino/backoffice/{brandId}/agent-stream/ticket`, `GET …/agent-stream?ticket=` (`BackofficeAgentStreamRoutes.scala`) | moved: `BackofficeEndpoint`, same paths |
| `AgentStreamTicketStore` (in memory) | moved: `AgentTicket` |
| `AgentStreamRegistry`, `AgentStreamConsumer` | moved: `AgentChannel` |

## New here

The send route; the sends and parked-sends routes with retry and discard; the status route; the
erasure route; the audit records; `marketing_suppressed` and `suppression_reason`; the deletion of a
person.
