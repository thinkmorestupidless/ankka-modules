# Wire translation: the original's recordings against engagement

`WireTranslation` maps a recorded step of the original to the port's request, and the port's answer
back, so `ParitySuite` compares like with like. The CRM calls a recording holds (the stub's
`__received`, research R2) are compared to `FakeCrm`'s calls by event name, attribute names and
types, and data keys, in order per player.

| Original | Port | Notes |
|---|---|---|
| the stub's `POST /api/v2/entity` identify | `FakeCrm`'s identify | attributes compared by name and type; `affiliate_id`, `last_device_os`, `language` marked absent; `marketing_suppressed`, `suppression_reason` marked new; `pot_vip` marked `false` |
| the stub's track | `FakeCrm`'s track | by event name and data keys; `payment_failed`'s crypto keys marked absent; `rg_limit_types` in the Scala form |
| the stub's `POST /v1/send/email` | `FakeCrm`'s send | by `transactional_message_id` and `message_data` keys |
| `POST /v1/casino/customerio/webhook/{brand}` | the same | headers and body as recorded |
| `grant_bonus`'s answer | `rewards`' answer through the webhook | `FakeRewards` scripted from the recording's answer |
| `GET /v1/stream` | the same | frames compared by `event` and data keys; `subjectId` marked (player id, not `casino.player/<id>`) |
| `POST …/agent-stream/ticket`, `GET …/agent-stream?ticket=` | the same | |
| `GET`/`PUT /v1/casino/crm-config/{brand}`, `POST …/seed` | the same | masked values compared |
| `POST /internal/v1/casino/{brand}/daily-deposit-count/reset` | the `deposit-day-<slug>` timer fired by the test clock | the identifies compared; the route not served |
| `GET /v1/presence` | `GET /v1/casino/backoffice/<slug>/presence` | `since` new |
| `platform.push.events` `delivery.outcome`, `presence.changed` | `casino.push` facts | marked divergence (cluster-wide, one fact) |

Steps the port does not serve are marked `not carried`: the reset route, `/health`, `/ready`, the
holding-level configuration.
