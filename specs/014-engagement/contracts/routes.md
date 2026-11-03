# Routes: engagement

Five endpoints, each with one prefix (research R7). Bodies and answers are JSON; refusals are
`{error: <code>, …}` with the status shown, except where the original's own shape is kept and said.
A `{brand}` other than the slug is `404 wrong_brand` (spec 001).

## `SendEndpoint`: `/v1/casino/brands/<slug>/engagement`

ACL `Routes.service()` (the brand's own services); the erasure route `Routes.playersOnly`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `POST /sends` | `{key, playerId, trigger, to, link?, data?}`; `trigger` one of FR-014's seven; `link` is `data.verification_link` or `data.reset_link` by trigger; `data` strings | 200 `{sendId, status: accepted \| not_configured \| suppressed \| refused, reason?}` at the first attempt's outcome (bounded 2 s: `pending` after four attempts in flight answers `accepted` once the claim is taken); a repeat under the key answers the first | 400 `missing_fields` naming them; 422 `unknown_trigger` |
| `POST /erasure/players/{player}` | — | 200 `{service: "engagement", subject: "player/<id>", crm: deleted \| failed \| none, sends: n}` | — |

`status` `suppressed` carries `reason` `marketing_suppressed` | `account_closed`; `refused` carries
`no_address` or the CRM's status. The verification and reset links are never logged above debug.

## `StreamEndpoint`: `/v1/stream`

ACL `Routes.player(slug)`.

| Method and path | Answer |
|---|---|
| `GET /` (`sse`) | `text/event-stream`: `event: ready` `{subjectId, serverTime}`; `: heartbeat <instant>` every 15 s, the first after 15 s; `event: signal` with `id: <correlationId>` and `{kind: "inbox.changed", correlationId: "<slug>:<messageId>", hint: {}}`; `event: closing` `{reason}` before any close the service makes |

Refusals before the stream opens: 401 `unauthenticated` (no or bad token); 403 `subject_unresolved`
(a token with no `eitheror_twin_id`); 503 `capacity` (`MAX_CONNECTIONS`, 10,000 per instance).
Closing reasons: `revalidation_failed` (the token no longer verifies, or names another player, at the
30 s × 1.0–1.2 re-check), `connection_recycled` (5 min × (1 + 0–19 %), judged at the 30 s sweep),
`shutting_down`. An overflowing queue of 64 frames drops the connection with no frame.

## `WebhookEndpoint`: `/v1/casino/customerio`

ACL `Routes.public`; served only under the signature.

| Method and path | Body | Answer |
|---|---|---|
| `POST /webhook/{brand}` | headers `X-CIO-Signature` (lowercase hex HMAC-SHA256 under the signing key of `v0:<X-CIO-Timestamp>:<raw body>`), `X-CIO-Timestamp` (seconds); body `{action, player_id, delivery_id?, …}` | per action below |

Verification, in order: 400 `could_not_read_body` (5 s); 401 `brand_not_configured` (no
configuration); 401 `webhook_not_configured` (no signing key); 401 `timestamp_invalid` (missing,
non-numeric, or `abs(now − ts) ≥ 300`); 401 `invalid_signature` (missing or wrong). Then 400
`invalid_body` (not a JSON object; new), 422 `unknown_action`. Every refusal is counted on status.

| Action | Fields | Relayed to | Answer |
|---|---|---|---|
| `grant_bonus` | `player_id`, `offer_id`, `role?`, `currency?` (`amount` read and ignored, FR-019; `delivery_id` not used) | `POST …/reward-campaigns/{player}/award` `{offerId, role, currency}` | `rewards`' answer as is: 200 `{ok, bonusAmount}` \| `{ok, grantId, providerStatus}` \| `{ok, alreadyProcessed}`; 422 `{error}` (`offer_not_found`, `offer_not_active`, a decline reason, `grant_failed`, `currency_not_configured`, `player_currency_unresolved`, `spin_value_not_offered`, `game_not_eligible`); 503 `ELIGIBILITY_CHECK_UNAVAILABLE`; 502 `provider_rejected`; 422 `"player_id and offer_id required"` here |
| `send_message` | `player_id`, `delivery_id`, `title`, `body` (`campaign_id`, `reward_id` read and dropped) | `POST …/players/{player}/messages` `{deliveryId, category: "marketing", subject, body}` | 200 `{ok: true}`, 200 `{ok, alreadyProcessed}` on a repeat; 422 `delivery_id_required`; `players`' 422 `marketing_suppressed` \| `account_closed` \| `category_required` \| `unknown_merge_tag` + `tag`, 404 `player_not_found`; 422 `"player_id required"` here |
| `target_offer` | `player_id`, `role`, `context?` | `POST …/reward-campaigns/{player}/target` `{role, context}` | 200 `{ok: true}`; 422 `free_spins_offer_not_targetable` \| `role_not_found` \| `target_failed`; 422 `"player_id and role required"` here |

A neighbour that does not answer is 503 `service_unavailable` naming it.

## `BackofficeEndpoint`: `/v1/casino/backoffice/<slug>`

ACL `Routes.operator(slug)`; the agent stream itself under the ticket, by `withAcl`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `POST /agent-stream/ticket` | — | 200 `{ticket, expires_in: 30}` | — |
| `GET /agent-stream?ticket=` (`sse`, no bearer) | — | `id: <session_id>`, `event: player_online`, `{player_id, player_email_prefix, session_id, occurred_at}`; `: ping` every 15 s | 403 `invalid_ticket` (unknown, used, another brand, older than 30 s) |
| `GET /players/{player}/sends?page=` | — | `{sends: [{sendId, trigger, event, class, channel, time, outcome, reason}], page, total}` newest first, 50 a page | — |
| `GET /parked-sends?page=&search=` | — | the same rows, parked only; `search` on trigger and reason | — |
| `POST /parked-sends/{sendId}/retry` | — | 200 the send's record after the attempt (`accepted`, `parked` again, `marketing_suppressed`, `account_closed`) | 404 `send_not_found`; 409 `not_parked` |
| `POST /parked-sends/{sendId}/discard` | `{reason}` | 200 the record, `discarded` | 400 `missing_fields`; 404; 409 `not_parked` |
| `GET /presence` | — | `{online: [{playerId, since}], count}` | — |
| `GET /engagement/status` | — | `{configured: Boolean, processor: "Customer.io", parked: {count, oldestAt?}, lastHour: {accepted, refused, parked, skipped_stale, no_configuration, marketing_suppressed, account_closed}, webhookRefusals: {<code>: n}, connections: {<instance>: n}}` | — |

## `ConfigEndpoint`: `/v1/casino/crm-config`

ACL `Routes.operatorHolding(slug, "brand_admin", "resource_admin", "platform_admin")`.

| Method and path | Body | Answer | Refusals |
|---|---|---|---|
| `GET /{brand}` | — | `{siteId, region, fromEmail, trackApiKey, appApiKey, webhookSigningKey, triggers: {<CrmEvent>: {transactionalId}}}`; every trigger key present, `""` when absent; each secret masked as its first three characters and `len − 3` asterisks, `""` when none | — |
| `PUT /{brand}` | the same shape; a secret that is non-empty and all `*` after its third character preserves the stored one; `region` `us` \| `eu` (anything else is `us`, as the original) | 200 the masked answer | 400 `invalid_config` naming the field |
| `POST /{brand}/seed` | — | 200 `{seeded: 30, person: "_seed_<slug>"}` | 422 `credentials_not_configured` (no site id); 503 `crm_unavailable` when the stub refuses every call |

The secrets are written to the secret store under `crm/<slug>/track-key`, `app-key`, `signing-key`
and never to the entity; a `PUT` is an audit record naming the keys changed.

## The calls out

| To | Route | From |
|---|---|---|
| Customer.io Track | `POST <track host>/api/v2/entity`, basic `siteId:trackKey`: `{type: "person", action: identify \| event \| delete, identifiers: {id}, name?, attributes}` | `Send` steps; the seed; erasure |
| Customer.io App | `POST <app host>/v1/send/email`, bearer `appKey`: `{transactional_message_id, to, identifiers: {email}, message_data: {all strings}}` | `Send` transactional step |
| `rewards` | `…/reward-campaigns/{player}/target`, `…/award` | the webhook |
| `players` | `…/players/{player}/messages` | the webhook |

Hosts: `us` → `track.customer.io`, `api.customer.io`; `eu` → `track-eu.customer.io`,
`api-eu.customer.io`; overridable by `CRM_TRACK_URL`, `CRM_APP_URL` for the stub. Each call has a
10 s timeout.
