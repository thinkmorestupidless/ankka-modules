# Feature Specification: engagement — Every Message a Brand Sends a Player, and the Channel It Sends It On

**Feature Branch**: `014-engagement`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `engagement` service: the CRM adapter (Customer.io
first), the event catalogue derived from facts, profile sync and the data-processor boundary,
suppression, transactional sends with retries and a dead letter, the Customer.io webhook, push
registration and dispatch, presence, and the operator's view of sends and failures — replacing the
`notify_*` TL functions, `customerio_dispatch`, `CrmService`, `push-gateway`, `PushEventConsumer`
and `AgentStreamConsumer`."

## Context

Today a brand reaches a player through four paths that grew separately and overlap. (1) The
`notify_player` TL function fires on *every* write to a player twin — a login, a deposit, an
operator changing a VIP tier — and hands the whole twin to the `customerio_dispatch` Go capability,
which decides what happened by checking whether each lifecycle timestamp is less than fifteen
minutes old, deduplicates in a process-local map that empties on every restart, and sends an
`identify`, a `track` and sometimes a transactional email to Customer.io. Four more TL functions
(`notify_session`, `notify_bonus`, `notify_payment_failed`, `notify_withdrawal_failed`) do the same
for one event each. (2) `CrmService` in `casino-service` sends the rest directly from route
handlers — promo codes, consent changes, email and password flows, KYC document outcomes, RG limit
events — fire-and-forget, each failure logged and lost; a nightly `DailyDepositCountReset` sweep
re-identifies every player of the brand through Keycloak so Customer.io's copy of a rolled-over
count reads zero. (3) `CustomerIOWebhookRoutes` lets a Customer.io campaign grant a bonus, target
an offer or write an in-app message, under an HMAC signature per brand. (4) The in-app inbox is
signalled over a separate `push-gateway` service: `NotificationService` writes a content-free
envelope to `platform.push.envelope`, every gateway pod reads every envelope under a unique
consumer group, and the pod holding the player's SSE connection pushes a signal; the gateway
publishes delivery outcomes and presence transitions to `platform.push.events`, which
`casino-service` reads to advance a message to `delivered` and to answer the backoffice's "online"
filter, while `AgentStreamConsumer` fans a `player_logged_in` Kafka record out to connected
backoffice agents as a browser alert (feature 182's stopgap).

Three facts about the original decide this spec. First, the stale-event defence (the fifteen-minute
freshness gate and the 25-hour in-memory cache) exists only because the triggers were predicates on
*state*; a consumer of *facts* sees each thing once, so the defence becomes a durable record of
what was sent, which the original never had. Second, nothing enforces marketing suppression on the
outbound side: `CrmService` sends `email_subscribed`, `sms_subscribed` and `is_cooling_off` as
attributes and leaves every decision to Customer.io's campaign segments, while the in-app composer
refuses `category=marketing` to a player whose `message_suppressed` flag an operator set, and
nothing at all to a self-excluded one; spec 010 FR-031 now requires that an excluded or
cooling-off player gets no marketing. Third, the push channel's one hard requirement — a signal
reaches the instance holding the connection, at most once, within seconds — is what ankka's SSE
routes (028) and sharding give a service directly, so a separate gateway deployment is no longer
needed to get a different scaling profile.

`engagement` is one brand service that owns every outbound message to a player and the live
channel they arrive on. It consumes the brand's facts and nothing else; it calls `players` (008)
and `rewards` (011) only when a Customer.io campaign asks it to; it is called by `players` to send
the emails a registration, an address change or a password change needs; and it holds the brand's
Customer.io credentials, which no other service does. It never computes a balance, never writes a
player's state, and never decides what a campaign should do.

## Clarifications

### Session 2026-10-08

- Q: Scala or Python? The plan made `engagement` Python, as an adapter that mostly speaks external
  HTTP. → A: Scala. The plan's reason for Python was the JSONata shaping that spec 006 found to be
  dead code; what remains is two HTTP calls to Customer.io, consumers over seven topics, a durable
  send record, and an SSE route whose connections must be reached from a cluster-wide consumer —
  which is the runtime's sharding and 028's socket routes, in process. The adapter seam
  (FR-001) keeps a second CRM possible; a second language buys nothing here.
- Q: Does the push channel stay a separate service, as the original's `push-gateway` is? → A: No.
  The original split it for a connection-bound scaling profile and for domain-agnosticism under
  decision 0025. The first is a replica count on this service; the second does not apply to a brand
  service. One service, one database, one manifest entry.
- Q: What stops a stale fact firing a campaign weeks late, now that the freshness gate is gone? → A:
  A durable **send record** per (fact id, event) created before any call is made, so a replayed or
  re-delivered fact finds its record and sends nothing; and a **staleness bound** (brand setting,
  shipped 24 hours): a fact whose `occurred-at` is older than the bound when first consumed is
  recorded as `skipped_stale` and never sent. The original's 15 minutes guarded a state-predicate
  trigger; a fact arrives once, so the bound only matters after an outage or a rebuild (024), where
  a late registration email is worse than none, as the original's own comment says.
- Q: Who enforces marketing suppression? → A: This service, for everything it sends, and Customer.io
  for its campaigns, which this service makes knowable and unsubscribes. A suppressed player is one
  who withdrew consent for the channel, is self-excluded, is cooling off, or is closed. For such a
  player this service refuses every transactional send of the **marketing** class, writes an
  in-app message of the marketing class nowhere, and sends an `identify` with
  `email_subscribed=false`, `sms_subscribed=false`, `marketing_suppressed=true` and the reason;
  **service** class sends (a withdrawal outcome, a KYC decision, a verification link, an exclusion
  confirmation) are still sent. Campaign logic lives in Customer.io; which events this service
  withholds from a suppressed player is the open question below.
- Q: Which `Personal` fields leave the installation? → A: Exactly those the original's `identify`
  sends: email address, player name (display name, else first name, else the part of the address
  before `@`), date of birth, and the free text of a transactional email's data. No network
  address, no document, no postal address. The spec names Customer.io as a processor of those four
  and the data-processor boundary is the brand's agreement with it (FR-012). On a player's erasure
  (042) this service's erasure handler asks Customer.io to delete the person.
- Q: Which transactional triggers exist? → A: The original's thirty, kept by name (FR-006's table):
  twenty-two derived from facts and eight sent on request by `players` (verification, change and
  reset flows) through this service's send route. `cooling_off_removed` (spec 099) was never built
  and is not added; `OperatorInvitation`/`OperatorStatusChanged` are the two the payments backoffice
  used from the holding-level configuration (open question below).
- Q: Does the in-app inbox move here? → A: No. `players` (008 FR-030) owns the inbox: messages,
  read state, recipient-local deletion, operator composition and merge tags. This service owns the
  **signal** that an inbox changed (the envelope and the connection it is pushed down), the
  delivery outcome, and the message a Customer.io campaign asks to have written, which it writes
  through `players`' message route as the CRM origin.
- Q: Is presence kept, and from what? → A: Kept, from live connections (decision 175/D9): a player
  is online while this service holds at least one connection for them, anywhere in the cluster.
  The backoffice's online filter reads it from the `presence_changed` facts; the agent's "player is
  online" alert (feature 182) is the open question below.
- Q: Where do live balances in the profile come from, now that nothing reads the wallet's store? →
  A: From the wallet's facts: every `money.transactions` family carries the real and restricted
  balance after the movement (a requirement this spec places on 002, see Dependencies), and this
  service keeps a balance view per player. When the view has no row yet the attributes are
  omitted, as the original omits them on a failed wallet read — a wrong zero fires every
  "balance is low" campaign at once.
- Q: Where does the Customer.io configuration live? → A: Per brand, in this service: the
  non-secret part (site id, region, sender address, the trigger table) in a key value entity, the
  three secrets (track key, app key, webhook signing key) in the secret store (038) under names the
  entity records. The original's second, holding-level copy in `merchant-service` is not carried
  over as a fallback: a brand's configuration is its own, and a brand with none sends nothing and
  says so in status.

### Session 2026-10-08 (reconciliation 3)

- Q: Were the families and fields this spec asked of 001, 002, 008, 009, 010 and 011 added? → A:
  The ones that were new are (001 FR-011 and FR-015, 002 FR-031, 008 FR-030 and FR-041, 011 FR-022
  and FR-027). Two were not needed: 009 already produces `player_kyc_document_reviewed` with an
  outcome, and 010's `rg_limit_applied` already carries a `cause`; FR-006's table now derives from
  those rather than asking for new families, and Dependencies says which is which.

### Session 2026-10-12 (from spec 004)

- Q: Whose `engagement` sends the payments backoffice's operator invitations? → A: None. A shared project
  holds no grant into a brand's `engagement`, and a payments operator belongs to no brand: `merchant`
  sends them through the installation's provider configured on it (spec 004 FR-027, research R10). The
  open question below is answered by this.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A fact becomes one Customer.io event, exactly once (Priority: P1)

A player deposits. The wallet's `deposit_confirmed` fact arrives; this service updates the
player's profile view (last deposit, lifetime count and amount, today's count), identifies the
player in Customer.io with the refreshed profile, tracks `deposit_confirmed` with the amount,
currency and transaction id, tracks `first_deposit_confirmed` as well when the lifetime count is
now one, and sends the transactional email the brand configured for the trigger. The same fact
arriving again — a redelivery, a view rebuild, a restart mid-batch — finds the send record and
sends nothing. A fact a day old at first sight is recorded as stale and never sent.

**Why this priority**: This is the whole marketing and lifecycle feed. Every campaign the brand
runs hangs off these events, and a duplicate or phantom event fires a campaign — including bonus
grants — at a player who did nothing.

**Independent Test**: Run the service against a Customer.io stub, produce one `deposit_confirmed`
fact twice and one `player_registered` fact dated yesterday, and verify two calls (identify and
track) for the deposit, one transactional send, nothing for the repeat, and a `skipped_stale`
record for the registration.

**Acceptance Scenarios**:

- added `features/engagement/tracks.feature`: a deposit confirmed fact makes one identify, one track and one transactional send, and a send record names them
- added `features/engagement/tracks.feature`: the same fact delivered again makes no call and leaves the send record unchanged
- added `features/engagement/tracks.feature`: a deposit that makes the lifetime count one is tracked as a first deposit beside the deposit
- added `features/engagement/tracks.feature`: a fact older than the staleness bound at first sight is skipped as stale
- added `features/engagement/tracks.feature`: a brand with no CRM configured sends nothing and names the gap on its status
- added `features/engagement/tracks.feature`: a retryable failure from the CRM is retried with backoff and then becomes a parked send

---

### User Story 2 - The profile Customer.io holds reflects the brand's facts (Priority: P1)

An operator building a lapsed-player campaign segments on `last_login_at`, `deposit_count` and
`real_money`. Every identify this service sends carries the full profile the original sent — from
facts, not from a read of any other service's store — and the numeric attributes are numbers, so a
segment on `deposit_count >= 10` works. At 03:00 UTC each day, every player whose daily deposit
count was above zero is identified again with zero, so a lapsing player's profile does not say
they deposited three times today forever.

**Why this priority**: Campaigns segment on these attributes; a wrong or stale attribute sends the
wrong campaign to the wrong player.

**Independent Test**: Drive facts for registration, login, a deposit, a bet, an RG limit and a
consent withdrawal, read the identify calls the stub received, and verify each attribute by name
and type against FR-008's table; advance the clock past 03:00 UTC and verify the zero.

**Acceptance Scenarios**:

- added `features/engagement/profiles.feature`: the identify before any track carries every attribute whose source fact has been seen, as numbers where numeric
- added `features/engagement/profiles.feature`: the deposit day rolling over identifies a player who deposited today with a daily deposit count of zero, and not one already at zero
- added `features/engagement/profiles.feature`: a tier change or a potential VIP mark identifies the player with the new value and tracks nothing
- added `features/engagement/profiles.feature`: a player with no row in the balance view is identified with the balance attributes absent, not zero

---

### User Story 3 - A suppressed player gets no marketing and still gets service messages (Priority: P1)

A player self-excludes. From the `player_self_excluded` fact this service tracks
`self_exclusion_activated`, sends the exclusion confirmation (service class), identifies the player
as suppressed with the reason, and from then on refuses every marketing-class transactional send
for them until the exclusion ends. A player who withdraws email consent is unsubscribed in
Customer.io on the next identify and gets no marketing email from this service; a service-class
email (their withdrawal failed) still reaches them. A closed player gets the closure confirmation
and nothing after.

**Why this priority**: Spec 010 FR-031 and the licence require it; the original enforces none of it
outbound.

**Independent Test**: Self-exclude a player, then drive a marketing-class trigger and a
service-class trigger, and verify one send refused with `marketing_suppressed` and one made; close
a player and verify every later trigger is refused with `account_closed`.

**Acceptance Scenarios**:

- added `features/engagement/suppression.feature`: a marketing class trigger for a player with an open case is refused as suppressed and audited
- added `features/engagement/suppression.feature`: a service class trigger for a player with an open case is sent
- added `features/engagement/suppression.feature`: a player who withdrew email consent is identified as unsubscribed and a marketing class email is refused
- added `features/engagement/suppression.feature`: a closed player is refused every trigger but the closure confirmation
- added `features/engagement/suppression.feature`: an exclusion ending identifies the player as not suppressed and marketing resumes subject to consent

---

### User Story 4 - `players` sends a verification email through this service (Priority: P1)

A visitor registers. `players` asks this service to send the address-verification email with the
link it minted, naming the trigger and the recipient. This service sends the brand's transactional
message for that trigger, or the original's built-in fallback template when none is configured,
records the send under the request's idempotency key, and answers whether it was accepted. A
repeat of the request with the same key sends nothing and answers the first outcome.

**Why this priority**: Registration, address change and password change cannot complete without
these emails; today each handler sends them fire-and-forget and loses the failure.

**Independent Test**: Call the send route twice with one key and verify one send; call it for a
trigger with no transactional id and verify the fallback template is used for
`EmailAddressVerification` and nothing is sent for the others.

**Acceptance Scenarios**:

- added `features/engagement/transactional.feature`: a request naming the email address verification trigger makes one transactional send with the verification address and answers accepted
- added `features/engagement/transactional.feature`: the same request again under the same key makes no send and answers the first outcome
- added `features/engagement/transactional.feature`: a trigger with no transactional id and no fallback sends nothing and answers not configured
- added `features/engagement/transactional.feature`: a request from a caller that is not a part of the brand is refused

---

### User Story 5 - A Customer.io campaign acts on a player through the webhook (Priority: P2)

An operator's re-engagement campaign posts `grant_bonus` to the brand's webhook with the campaign's
delivery id. This service verifies the signature and timestamp with the brand's signing key, asks
`rewards` (011) to award the offer to the player as the CRM origin, and answers the campaign with
the outcome. `send_message` writes an in-app message of the marketing class through `players`,
idempotent by the delivery id; `target_offer` asks `rewards` to target a role at the player.

**Why this priority**: Spec 129 delegated bonus automation to Customer.io; without this route no
campaign bonus can be granted.

**Independent Test**: Post each action with a valid signature and verify the call to `rewards` or
`players` and the answer; post with a bad signature, a stale timestamp and an unknown action and
verify the refusals.

**Acceptance Scenarios**:

- added `features/engagement/campaigns.feature`: a bonus action with a valid signature and time names a player and an active offer, and the brand's rewards are asked to award it as the CRM's
- added `features/engagement/campaigns.feature`: a bonus action for an offer the player already holds a claim of is answered ok with already processed
- added `features/engagement/campaigns.feature`: a bonus action for an offer the brand's rewards refuse carries their reason and awards nothing
- added `features/engagement/campaigns.feature`: a send action with a delivery id, a title and a body writes one inbox item of the marketing class, and a repeat writes nothing
- added `features/engagement/campaigns.feature`: a send action without a delivery id is refused rather than given a fresh key
- added `features/engagement/campaigns.feature`: a request whose signature is missing, malformed or wrong, or for a brand with no signing key, is refused as unauthenticated and does nothing
- added `features/engagement/campaigns.feature`: an unknown action with a valid signature is refused as unknown

---

### User Story 6 - A player's open page learns the inbox changed within seconds (Priority: P2)

A player has the lobby open on two devices. An operator sends them a message; `players` writes it
and produces `player_message_created`. This service pushes a content-free `inbox.changed` signal
down every connection it holds for that player, on whichever instance holds it, and produces
`push_delivered` so `players` can show the message as delivered. The page refetches the inbox. A
connection whose token stops verifying is closed with a reason; a connection older than five
minutes is closed and the page reconnects; an instance at capacity refuses a new connection.

**Why this priority**: The inbox and in-game notifications ship on this signal; without it a
message waits for the next page load.

**Independent Test**: Open two connections for one player on two instances, produce one
`player_message_created`, and verify one signal frame on each connection and one `push_delivered`
fact naming two connections.

**Acceptance Scenarios**:

- added `features/engagement/push.feature`: a player opening the stream with their sign-in is answered ready, kept alive, and told before any close
- added `features/engagement/push.feature`: an inbox item created reaches each of the player's connections on two instances as one inbox changed push, and one delivered fact counts them
- added `features/engagement/push.feature`: an inbox item for a player with no connection anywhere pushes nothing and produces no fact
- added `features/engagement/push.feature`: a connection whose sign-in no longer verifies at the re-check is closed as invalid
- added `features/engagement/push.feature`: an operator's or a part's sign-in without a player id is refused the stream
- added `features/engagement/push.feature`: an inbox item read on one device pushes inbox changed to every other connection so the unread count converges

---

### User Story 7 - An operator sees what was sent, what failed, and who is online (Priority: P2)

An operator opens a player and sees every send this service made for them: the event, the
channel, the time, the outcome, and for a refusal the reason. A brand page lists the dead letter —
sends Customer.io refused after retries — and lets the operator retry one or discard it. The
online filter in the player list reads this service's presence. The configuration page shows the
site id, region, sender address and the trigger table with masked secrets, and a "seed event
catalogue" action fires one synthetic identify and one track per trigger at a seed person so the
campaign builder lists every event before any player has caused one.

**Why this priority**: Today a failed send is a log line; the operator has no way to know a player
never got their withdrawal email.

**Independent Test**: Make a send that Customer.io refuses, open the dead letter, retry it against a
now-healthy stub, and verify it leaves the list; read the player's sends and the brand's online
count.

**Acceptance Scenarios**:

- added `features/engagement/operators.feature`: an operator reads a player's sends newest first with event, class, channel, time, outcome and reason
- added `features/engagement/operators.feature`: a parked send retried and accepted by the CRM becomes accepted and is no longer parked
- added `features/engagement/operators.feature`: a parked send discarded records who discarded it and why
- added `features/engagement/operators.feature`: a new CRM app key is stored in the secret store and read back masked, and a masked value sent back preserves the secret
- added `features/engagement/operators.feature`: seeding the track list fires one identify and one track per trigger at the seed person, and seeding twice updates the same person
- added `features/engagement/operators.feature`: an operator reading presence sees each player with an open connection as online with the time they came online

---

### Edge Cases

- A registration fact arrives, then a login fact within two minutes: the login is the registration's
  own session and `player_logged_in` is not tracked (the original's auto-login rule).
- The staleness bound is lowered after a backlog built up: facts already recorded are untouched;
  facts not yet seen are judged against the new bound.
- A view rebuild (024) replays a year of facts: every fact finds its send record or is stale; the
  profile view is rebuilt in full; nothing is sent; the rebuild is logged with the counts.
- Customer.io accepts an identify and refuses the track that follows: the send record holds both
  outcomes; the track is retried alone, never the identify again in the same attempt.
- A brand rotates its track key while sends are in flight: the attempt in flight fails and is
  retried under the new key; a send parked under the old key retries under the new one.
- A `send_message` whose player `players` has since closed: refused as `account_closed`, answered to
  the campaign as such, never parked.
- A player with no email address on file: transactional sends are refused as `no_address` and
  recorded; identify and track are still made (Customer.io keys the person by id).
- A connection's client cannot keep up: its queue fails and the connection is closed with
  `slow_consumer`; the client reconnects and refetches, which is correct because push carries no
  content.
- An instance stops: every connection it holds gets `closing: shutting_down` before its consumers
  stop; the clients reconnect to another instance.
- A fact for a player this service has never seen (their registration fact was before the topic's
  retained history): the profile view is created from what the fact carries; attributes with no
  source stay absent.

## Requirements *(mandatory)*

### Functional Requirements

**The adapter**

- **FR-001**: The service MUST reach the CRM through one adapter contract — identify a person with
  attributes, track a named event with data, send a transactional message by id with data, delete a
  person — with Customer.io as the first and only adapter in this feature; Customer.io's Track API
  (`/api/v2/entity`, basic authentication with site id and track key) and App API
  (`/v1/send/email`, bearer app key) in the US or EU region the brand configured.
  (Source: `CustomerIOClient.scala`, `cio_client.go`.)
- **FR-002**: Every outbound call MUST have a timeout, and a retryable answer (a connection failure,
  429, 5xx) MUST be retried four times with a 500 ms backoff doubling each time and ±25 % jitter;
  any other failure MUST NOT be retried. A send still failing after the retries MUST be parked in
  the brand's dead letter with the last answer. (Source: `withRetry`, `cio_client.go`.)
- **FR-003**: The attributes `created_at`, `deposit_count`, `daily_deposit_count`, `real_money`,
  `bonus_money`, `total_balance`, `total_deposit_amount`, `amount` and
  `session_duration_minutes` MUST be sent as numbers; every other attribute as a string; a numeric
  attribute whose value is not a number MUST fall back to a string rather than be dropped; message
  data of a transactional send MUST always be strings. (Source: `numericAttributes`, both clients.)
- **FR-004**: The brand's Customer.io configuration MUST be: site id, region (`us` | `eu`), sender
  address, a trigger table of transactional ids keyed by trigger, and three secrets — track key,
  app key, webhook signing key — held in the secret store (038) under names the configuration
  records, never in the configuration entity, never in a message. (Source: `CustomerIOConfig`.)

**Facts to events**

- **FR-005**: The service MUST consume the brand's `casino.players`, `casino.sessions`,
  `money.transactions`, `payments.payments`, `casino.withdrawals` and `casino.rewards` topics, one
  consumer per topic, and MUST derive every event from a fact and from its own views; it MUST NOT
  read another service's store or call another service to build an event.
- **FR-006**: Each fact family below MUST produce the named event, with the data named, and nothing
  else MUST produce it:

  | Fact (topic) | Event | Data beyond the profile |
  |---|---|---|
  | `player_registered` (players) | `player_registered` | `currency`, `btag` when a tag was presented |
  | `player_registered` with a tag | `affiliate_attributed` | `btag`; the network and affiliate the tag names (012 FR-002) |
  | `player_logged_in` (players), not within two minutes of registration | `player_logged_in` | — |
  | `game_session_started` (sessions) | `game_session_started` | `game_id`, `session_id` |
  | `game_session_ended` (sessions) | `game_session_ended` | `game_id`, `session_id`, `session_duration_minutes` |
  | `deposit_confirmed` (money) | `deposit_confirmed` | `amount`, `currency`, `transaction_id` |
  | `deposit_confirmed` when the lifetime count becomes 1 | `first_deposit_confirmed` | as above |
  | `payment.failed` (payments) | `payment_failed` | `amount`, `currency`, `transaction_id`, `failure_reason` |
  | `bonus_granted` (rewards) | `bonus_granted` | `bonus_name`, `amount`, `currency`, `wagering_required`, `reward_id` |
  | `bonus_cancelled` (rewards) | `bonus_cancelled` | `bonus_name`, `clawback_amount`, `currency`, `reward_id` |
  | `restricted_funds_converted` (money) | `wagering_requirement_met` | `bonus_name`, `amount_wagered`, `currency`, `reward_id` |
  | `withdrawal_failed` (withdrawals) | `withdrawal_failed` | `amount`, `currency`, `transaction_id`, `failure_reason` |
  | `rg_limit_applied` (players) with cause `set` or `lowered`, a deposit limit | `deposit_limit_set` | `limit_type`, `limit_value` |
  | `rg_limit_applied` with cause `set` or `lowered`, a loss limit | `loss_limit_set` | `limit_type`, `limit_value` |
  | `rg_limit_applied` with cause `set` or `lowered`, a session limit | `session_limit_set` | `limit_type`, `limit_value` |
  | `rg_limit_applied` with cause `applied` or `removed` (spec 010 FR-030) | `rg_limit_loosened` | `limit_type` |
  | `rg_case_opened` (players), type cooling-off, with its `closes-at` | `cooling_off_set` | `cooling_off_period` (the end) |
  | `player_self_excluded` (players) | `self_exclusion_activated` | `exclusion_period` |
  | `player_kyc_verified` (players) | `kyc_approved` | — |
  | `player_kyc_rejected` (players) | `kyc_rejected` | `rejection_reason` |
  | `player_kyc_document_reviewed` (players, 009 FR-030) with outcome approved | `kyc_document_accepted` | `doc_type` (the category) |
  | `player_kyc_document_reviewed` with outcome declined | `kyc_document_declined` | `doc_type`, `reason` (the reason code) |
  | `bonus_code_entered` (rewards, 011 FR-027) | `promo_code_entered` | `promo_code` |
  | `player_consent_changed` (players) | `consent_updated` | — |

  Amounts MUST be rendered in major units with two decimals, as the original's templates expect;
  the fact's minor units are the source. (Source: `playerEventCandidates`, `resolveSingleEvent`,
  `CrmEvent`, every `crmService.dispatch` call site.)
- **FR-007**: Before any call for a fact, the service MUST create a **send record** keyed by the fact
  id and the event, holding the player, the trigger, the class, the channel, each call's outcome
  and time; a fact whose record exists MUST produce no call; the record MUST survive a restart,
  a redeploy and a view rebuild (024). A fact whose `occurred-at` is older than the brand's
  staleness bound (shipped 24 hours) at first sight MUST be recorded as `skipped_stale` and
  produce no call.
- **FR-008**: Every identify MUST carry these attributes where their source has been seen, and omit
  the rest, never sending an empty string: `id`, `created_at` (registration as a Unix time),
  `email`, `player_name`, `brand_id`, `currency`, `country`, `language`, `date_of_birth`,
  `registration_date`, `vip_tier`, `pot_vip` (`true`/`false`), `last_login_at`,
  `last_deposit_at`, `deposit_count`, `daily_deposit_count` (always sent; 0 when the day has
  rolled over), `total_deposit_amount`, `real_money`, `bonus_money`, `total_balance` (from the
  balance view; omitted when it has no row), `has_rg_limits` (`true`/`false`), `rg_limit_types`,
  `is_cooling_off`, `suspected_duplicate`, `kyc_status`, `account_status`, `email_subscribed`,
  `sms_subscribed`, `email_consent_at`, `sms_consent_at`, `consent_source`, `btag`,
  `marketing_suppressed` and `suppression_reason`. The profile MUST be a view this service builds
  from facts, one row per player. (Source: `buildIdentifyAttributes`, both implementations.)
- **FR-009**: A deposit day MUST begin at 03:00 UTC; at that moment the service MUST identify every
  player whose `daily_deposit_count` was above zero with the count 0, from its own view, and no
  other player. (Source: `DepositDay`, `DailyDepositCountReset`; the Keycloak enumeration is
  replaced by the view.)
- **FR-010**: An identify with no event MUST be sent when `vip_tier_changed`, `player_fraud_flagged`
  or a profile fact changes an attribute of FR-008 that no event of FR-006 carries. (Source:
  `syncAttributes` call sites.)

**Transactional sends and classes**

- **FR-011**: Every trigger MUST be of one class: **marketing** (`player_registered`,
  `player_logged_in`, `game_session_started`, `game_session_ended`, `deposit_confirmed`,
  `first_deposit_confirmed`, `bonus_granted`, `bonus_cancelled`, `wagering_requirement_met`,
  `promo_code_entered`, `affiliate_attributed`) or **service** (every other trigger of FR-006 and
  every trigger of FR-014). A transactional send of the marketing class MUST be refused for a
  suppressed player (FR-012) with the reason recorded and an audit record written; a service-class
  send MUST NOT be refused for suppression. A closed player MUST receive no send but the closure
  confirmation.
- **FR-012**: A player MUST be suppressed for a channel while any of these holds, from the brand's
  facts: consent for the channel withdrawn or never given (`player_consent_changed`,
  `player_registered`); an open exclusion or cooling-off case (`player_self_excluded`,
  `rg_case_opened` until `rg_case_closed`; spec 010 FR-031); the account closed; or an operator's
  message suppression (`players`, 008). The next identify after a change MUST carry
  `email_subscribed`, `sms_subscribed`, `marketing_suppressed` and `suppression_reason` so
  Customer.io's campaigns can segment on them, and MUST be sent within one minute of the fact.
- **FR-013**: A transactional send MUST be made only when the brand configured a transactional id for
  the trigger, or the trigger has the built-in fallback the original has
  (`EmailAddressVerification`); MUST address the player's primary email address; MUST carry the
  data of FR-006 or FR-014 plus `player_name`, `player_email`, `brand_id` and `brand_name`; and
  MUST be refused as `no_address` when the player has none. The `language` the data names MUST be
  passed as the message's language, not as data. (Source: `maybeTransactional`,
  `sendTransactional`.)
- **FR-014**: The service MUST serve a **send route** to the brand's services only (001 FR-020
  service class), taking a trigger, a recipient address, an idempotency key and data, for the
  triggers `EmailAddressVerification`, `EmailChangeVerification`, `EmailChangeNotification`,
  `EmailPrimaryChanged`, `PasswordChangeVerification`, `PasswordChangeNotification`,
  `PasswordResetRequest` and `ConsentUpdated`; MUST send at most once per key; and MUST answer
  `accepted`, `refused` with the reason, or `not_configured`. The verification and reset links are
  the caller's data, never read or logged by this service above debug. (Source: the `send*`
  methods of `CrmService`.)

**Personal data and erasure**

- **FR-015**: The email address, player name, date of birth, the free text of transactional data and
  the title and body of an in-app message written through the webhook MUST be `Personal` values
  (042) under the player's id wherever this service holds them — the profile view, the send record,
  the dead letter. Exactly the email address, the player name, the date of birth and the
  transactional data MUST leave the installation, to the CRM; no other `Personal` field of 001
  FR-023 MUST be sent to it.
- **FR-016**: The service MUST implement the erasure handler (042): it MUST ask the CRM to delete the
  person, record the answer, and redact its own views and records of the subject; a later fact for
  an erased subject MUST produce no call. The CRM's own retention of what it was sent is the
  brand's agreement with it, stated in the brand's status page as the processor named.

**The webhook**

- **FR-017**: The service MUST serve a public route per brand for Customer.io's campaign actions,
  served only when `X-CIO-Signature` is a correct HMAC-SHA256, under the brand's signing key, of
  `v0:<X-CIO-Timestamp>:<raw body>` and the timestamp is within the window the original enforces
  (five minutes of the service's clock); a brand with no signing key MUST refuse every request. A
  refusal MUST do nothing and MUST be counted per brand. (Source: `CustomerIOWebhookRoutes`.)
- **FR-018**: The actions MUST be: `grant_bonus` (player, offer, optional role, optional deposit
  amount and currency) — asks `rewards` (011 FR-022's award route) to award the offer with source
  `customerio_webhook`, re-resolving a stale offer by its role once, and answers `ok`, `ok` with
  `alreadyProcessed`, or the refusal `rewards` gave; `send_message` (player, delivery id, title,
  body, optional campaign id) — writes a marketing-class in-app message through `players` (008
  FR-030) under the delivery id as the key, origin `crm`, and answers as `players` did; and
  `target_offer` (player, role) — asks `rewards` to target the role at the player, refusing a
  free-spins offer as `free_spins_offer_not_targetable`. An unknown action MUST be refused as
  `unknown_action`; a `send_message` without a delivery id as `delivery_id_required`.
  (Source: `dispatchAction`.)
- **FR-019**: The deposit amount a `grant_bonus` carries MUST NOT size a deposit-match award; the
  award is sized by `rewards` from the wallet's facts (011), as the original sizes it from the
  player's last confirmed deposit and never from the webhook body. (Source: ClickUp 869erhfnk
  comment in `CustomerIOWebhookRoutes`.)

**The push channel**

- **FR-020**: The service MUST serve a player-class SSE route that opens a connection for the token's
  player: a `ready` frame naming the player and the server time, a heartbeat comment every fifteen
  seconds, `signal` frames (kind, correlation id, content-free hint), and a `closing` frame with a
  reason before any close the service makes. A token without a player id MUST be refused as
  `subject_unresolved`; an instance at its connection cap MUST refuse as `capacity`.
  (Source: `StreamRoutes`, contract `push-gateway-api.md`.)
- **FR-021**: Every connection MUST have its token re-verified every thirty seconds and be closed as
  `token_invalid` when it fails; MUST be closed as `max_age` after five minutes, jittered by up to
  20 % per connection; MUST be closed as `slow_consumer` when its queue of sixty-four frames
  overflows; and MUST be closed as `shutting_down` before the instance stops consuming. (Source:
  `Revalidator`, `ConnectionSweeper`, `ConnectionRegistry`, `GracefulShutdownSpec`.)
- **FR-022**: On `player_message_created`, `player_message_read` and `player_message_deleted`
  (`casino.players`, see Dependencies) the service MUST push one `inbox.changed` signal with the
  message's correlation id (`<brand>:<message id>`) down every connection it holds for the player,
  on any instance, at most once per connection, within two seconds; and on `player_message_created`
  MUST produce `push_delivered` naming the correlation id and the number of connections when that
  number is above zero. Delivered MUST mean only that a frame was written to a live connection
  (175 FR-009b).
- **FR-023**: The service MUST produce `presence_changed` (`online` | `offline`) when a player's
  connection count across the cluster crosses zero, and MUST serve an operator-class presence view
  listing online players with the time they came online, keyed by the facts it produced. A player
  flagged as a test account MUST be excluded from the agent alert of FR-024.
  (Source: `PresenceTracker`, `PresenceView`, `AgentStreamConsumer.isTestAccount`.)
- **FR-024**: The service MUST serve an operator-class SSE route per brand that pushes a
  `player_online` frame (player id, the part of the email address before `@`, session id, time) to
  every connected agent when `player_session_started` arrives for a player who is not a test
  account, at most once, within five seconds. (Source: `AgentStreamRegistry`, feature 182 — see the
  open question.)

**Operators**

- **FR-025**: An operator with the brand's role MUST be able to read a player's sends (event, class,
  channel, time, outcome, reason) newest first and paged; read and search the brand's dead letter;
  retry a parked send, which re-runs FR-002 and updates the record; and discard one with a reason.
  Every retry and discard MUST be an audit record naming the operator.
- **FR-026**: An operator with the brand's role MUST be able to read and write the brand's
  configuration (FR-004) with secrets masked to their first three characters and asterisks, a
  masked value sent back unchanged preserving the stored secret; and MUST be able to seed the event
  catalogue: one identify and one track per trigger of FR-006 and FR-014 at the person
  `_seed_<brand>` with the original's seed data, idempotent, answering the count. (Source:
  `CustomerIOConfigRoutes`.)
- **FR-027**: The brand's status MUST say whether Customer.io is configured, the dead letter's size
  and age of its oldest entry, the number of sends in the last hour by outcome, and the number of
  open connections per instance.

**Origins and bounds**

- **FR-028**: Every route of this service MUST be of a 001 FR-020 class: the stream (player), the
  agent stream, sends, dead letter, configuration, presence (operator), the send route (service,
  the brand's own services), the webhook (public, under its signature).
- **FR-029**: The service MUST hold no money, no balance of its own beyond the view of FR-008, no
  credential of another service, and no realm administration; it MUST write audit records (001
  `platform.audit`) for every refusal of FR-011, every retry and discard of FR-025, and every
  configuration change.

### Key Entities

- **Profile** (view, one per player): the attributes of FR-008 as last seen from facts, the
  suppression state of FR-012 with its reason, the deposit-day count, the balance after the last
  movement.
- **Send record** (event sourced, one per fact id and event, or per send-route key): player,
  trigger, class, channel, each call (identify, track, transactional) with its outcome, attempts
  and last answer; states `pending` → `accepted` | `refused` | `skipped_stale` |
  `no_configuration` | `parked` → `accepted` | `discarded`.
- **Dead letter** (view over parked send records per brand).
- **Configuration** (key value, one per brand): site id, region, sender address, trigger table, the
  names of the three secrets in the secret store.
- **Connection** (in memory, per instance): id, player, token, queue, opened-at; the set per player
  across the cluster answers presence.
- **Presence** (view): online players and since when, from `presence_changed`.
- **Seed person**: `_seed_<brand>`, the synthetic person the catalogue is seeded at.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every fact family of FR-006, delivered twice, produces exactly one event at the
  Customer.io stub, measured over the recordings.
- **SC-002**: A year's replay of a brand's facts into a rebuilt view sends nothing and ends with every
  fact recorded.
- **SC-003**: A send for a suppressed player of the marketing class is refused 100 % of the time, and
  a service-class send for the same player is made 100 % of the time, over FR-011's trigger list.
- **SC-004**: An `inbox.changed` signal reaches every open connection of the player within two
  seconds of the fact, on a three-instance cluster, 99 % of the time.
- **SC-005**: A transactional email is accepted by Customer.io within sixty seconds of its trigger
  when Customer.io is healthy (the original's SC-001).
- **SC-006**: A database dump of this service holds no readable email address, name, date of birth or
  message text.
- **SC-007**: After a player's erasure the CRM has no person for them and no later fact produces a
  call, proven by replaying a fact for the subject.
- **SC-008**: No send is lost: every refusal, parking and discard is a send record and an audit
  record; a count of Customer.io's accepted calls over a day equals the count of `accepted` records.
- **SC-009**: The Playwright suites for notifications, the inbox, in-game and mobile notifications,
  offline recovery, the backoffice message status and consent, and the online filter pass unchanged
  but for base URLs.

## Changes from the original

- The five `notify_*` TL functions, the `customerio_dispatch` capability with its freshness gate
  and in-process dedup cache, and `CrmService`'s route-handler dispatches are replaced by consumers
  of facts and a durable send record. Phantom and duplicate events — the cause of the stale
  `player_registered` campaigns after every rollout — cannot occur.
- Failures are no longer fire-and-forget: retries, a dead letter, operator retry and discard, and a
  per-player send history are new.
- Marketing suppression is enforced outbound (FR-011, FR-012); the original enforced it only for
  in-app messages an operator composed. The `marketing_suppressed` and `suppression_reason`
  attributes are new.
- `push-gateway`, `PushEventConsumer`, `AgentStreamConsumer`, the `platform.push.envelope` and
  `platform.push.events` topics and the dedicated agent-presence topic are replaced by this
  service's SSE routes and the `push_delivered` and `presence_changed` facts; the `delivered` state
  of a message is advanced by `players` from the fact, not by `casino-service` from a Kafka record.
- The holding-level Customer.io configuration in `merchant-service` is not carried over as a
  fallback; a brand's configuration is its own (see the open question on operator mail).
- The nightly `DailyDepositCountReset` CronJob, which enumerated Keycloak's users, becomes a timer
  over this service's own view.
- Live balances come from the wallet's facts, not from a read of the wallet's twin.
- `cooling_off_removed` (planned in spec 099, never built) is not added. `affiliate_attributed` is
  derived from the tag on `player_registered` rather than from an `affiliate_id` the affiliate
  service wrote back, since no service in the brand knows an affiliate account (012).
- The Customer.io reporting webhook (opened, bounced) is not added; the send record holds
  Customer.io's acceptance of the call, which is all the original held.
- The daily, 03:00 UTC deposit day and the auto-login rule are kept exactly.

## Recordings

The original's suites that cover this service, which become the recordings and the oracle (001
FR-029 to FR-032):

- Go end-to-end: `TestScenarioFreeSpinsPlay_CustomerIOGrant` (skipped without operator credentials
  today; recorded with them), `TestScenario16_RewardGrant_TargetSelectActivateForfeit` (the webhook's
  `target_offer` path). No other Go scenario exercises Customer.io or push; the recordings of this
  service's outbound calls come from the unit suites below replayed against the `crm-stub`.
- Playwright (`tests/playwright/tests/`): `notifications-inbox`, `notifications-inbox-pagination`,
  `notifications-inbox-after-dismiss`, `notifications-lobby`, `notifications-in-game-desktop`,
  `notifications-mobile-lobby`, `notifications-mobile-in-game`, `notifications-offline-recovery`,
  `backoffice-message-status`, `backoffice-message-consent`, `backoffice-merge-tags`,
  `backoffice-online-filter`, `47-casino-backoffice-player-online-notification`,
  `11-email-verification`, `31-player-email-change`, `40-player-registration`.
- Unit and integration, as expected answers: `customerio_dispatch/handler_test.go` (763 lines: the
  candidate events, dedup ids, freshness, attribute encoding), `NotificationDeliveryIntegrationSpec`,
  `PushEventConsumerSpec`, `AgentStreamConsumerSpec`, and the ten `push-gateway` specs
  (`StreamRoutesSpec`, `ConnectionRegistrySpec`, `RevalidatorSpec`, `ConnectionSweeperSpec`,
  `PresenceTrackerSpec`, `GracefulShutdownSpec`, `EnvelopeConsumerSpec`, `StreamRoutesAuthSpec`,
  `MultiRealmJwtValidatorSpec`, `KeepAliveSocketOptionsSpec`).
- A `crm-stub` image standing in for Customer.io's two APIs, recording every call, is added beside
  005's `psp-stub`; recording MUST refuse to start without it.

## Assumptions

- Customer.io deduplicates nothing server-side (the v2 entity API carries no dedup id), so the send
  record is the only guard; stated in the original's own comments.
- Customer.io honours `email_subscribed=false` for campaign email and `sms_subscribed=false` for
  SMS; SMS is sent only by Customer.io campaigns, never by this service.
- The CRM's deletion of a person on erasure is irreversible and removes the person from campaign
  statistics; that is accepted.
- Amounts in events are major units with two decimals because the brand's Customer.io templates
  render them; the facts carry minor units and the conversion is this service's.
- The webhook's timestamp window is five minutes either side of the service's clock, read from
  `CustomerIOWebhookRoutes`' `tsValid` (300 seconds of absolute skew).
- The operator's "online" filter tolerates a few seconds' lag; presence is derived, not polled.
- Every Playwright notification suite drives the player frontend through its proxy routes
  (`/api/player/stream`), which change only their upstream base URL.

## Dependencies

- Spec 001 (taken at reconciliation 3): `casino.players` carries `player_message_created`,
  `player_message_read`, `player_message_deleted` and `player_fraud_flagged`; the contract holds the
  ephemeral topic `casino.push` (families `push_delivered`, `presence_changed`, retention one day,
  read by `players` and `reporting`) beside `casino.sessions` (001 FR-011, FR-015).
- Spec 002 (taken): every `money.transactions` family carries the real and restricted balance after
  the movement (002 FR-031), so the balance view of FR-008 is built from facts.
- Spec 008 (taken): the message route this service writes through for `send_message` (008 FR-030:
  origin `crm`, the delivery id as the key, category `marketing`, the three refusals); the operator
  message-suppression flag on `player_consent_changed`; the fallback-template contract for
  `EmailAddressVerification` (008 FR-004).
- Spec 009: the document outcomes are `player_kyc_document_reviewed` with its outcome and category
  (009 FR-030); FR-006 derives `kyc_document_accepted` and `kyc_document_declined` from it. No new
  family.
- Spec 010: `rg_limit_applied` carries `cause` (`set`, `lowered`, `applied`, `removed`), type,
  currency and value; `rg_case_opened` carries the case type and `closes-at` (010 FR-030); FR-006
  derives tightened and loosened from `cause`. No new field.
- Spec 011 (taken): `bonus_code_entered` on `casino.rewards` (011 FR-027), and the award and target
  routes of 011 FR-022 admitting this service with the original's answers.
- Spec 004: `merchant` sends operator invitations through the installation's provider configured on it,
  not through `engagement` (spec 004 FR-027; answered 2026-10-12).
- ankka 028 (SSE and socket routes), 032 (the deposit-day timer), 038 (the three secrets), 040 (a
  grant if the open question on operator mail is answered with one), 042 (`Personal`, the erasure
  handler).

## Out of scope

- The in-app inbox, operator composition and merge tags (008).
- Campaign logic, templates, unsubscribe pages and SMS sending: Customer.io's.
- Affiliate communications: the programme's (013).
- Support chat: Chatwoot stays external, integrated by the frontend.
- A second CRM adapter; the contract of FR-001 is the seam for one.
- The Customer.io reporting webhook (delivery, open, bounce events).

## Open questions

- Answered 2026-10-12 (from spec 004): no brand's `engagement` sends the payments backoffice's operator
  mail; `merchant` sends it through the installation's provider configured on it (Clarifications).
- [NEEDS CLARIFICATION: For a suppressed player, should the service withhold the marketing-class
  *events* of FR-011 from Customer.io too (safer: no campaign can fire), or track every event and
  rely on the suppression attributes (as the original does; keeps RG welfare campaigns, which key
  off the same events, working)? Sheldon's call.]
- [NEEDS CLARIFICATION: Keep the backoffice agent's "player is online" browser alert (FR-024,
  feature 182, written as a stopgap "until automated welcome messages ship")? It has a Playwright
  suite and is cheap here; dropping it removes one operator-class stream route.]
