# Feature Specification: Affiliate protocol

**Feature Branch**: `012-affiliate-protocol`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "A brand records attribution and emits facts; it never computes
commission. A standardised protocol for tags and integration between our casinos and our
affiliates, so an affiliate can work across multiple casino installations and brands."

## Context

Today every affiliate belongs to exactly one brand, and the brand's `affiliate-service` does
everything at once: it resolves the `btag` a visitor arrives with, records the click and the
registration, receives seven kinds of notification from `casino-service` over internal HTTP
(`notify/registration`, `deposit`, `ggr`, `withdrawal`, `withdrawal-reversal`, `bonus-cost`,
`chargeback`), folds them into commission under the affiliate's plan (ADR 0042), and fires a
one-shot postback `GET` to a URL the affiliate configured. The tag is a bare UUID resolved through a
link index the brand holds; there are no sub-ids; the first tag a player registers with wins; no
attribution window is enforced on the server, only a 30-day cookie in the browser; and two things
are wrong: a player who registers with an affiliate's bonus code is written to the player record but
not to the registration record, so their later deposits and play are never attributed, and the
registration notification never forwards the `clickId`, so the `{clickid}` macro in registration
postbacks is always empty.

This spec is the **protocol** between a casino brand and any affiliate programme: eitheror's own
`programme` project (spec 013), a second eitheror installation's programme, or a third-party
network. The brand's side of it is one service of the brand project, `attribution` (spec 001
FR-002), which this spec also specifies: it serves the click route, binds players from the brand's
own facts, holds the pseudonym keys, produces the facts, and serves the feed and webhooks. The
programme's side is spec 013. The protocol is what lets one affiliate send traffic to many brands and be paid once,
and what lets a brand swap or add a network without changing how it records attribution.

Three things move. **The tag** becomes a signed token the network issues, under the same `btag`
parameter name, so every link and tool that writes `?btag=` keeps working. **The notifications**
become facts on a topic the brand owns, delivered to a programme by a grant (ankka 040), to a
network's Kafka client through the installation's broker, or over a paged feed and webhooks for a
network with no Kafka. **Commission** leaves the brand entirely: a brand never knows a plan, a rate,
or what an affiliate is owed.

## Clarifications

### Session 2026-10-08

- Q: Does the brand compute anything an affiliate is paid? → A: No. The brand records who sent
  a player and emits what that player did, in minor units. Plans, rates, networks of
  sub-affiliates, overrides and payouts are the programme's (spec 013). A brand-specific commission
  rule is a rule in the programme about that brand, never a rule in the brand.
- Q: What is the tag? → A: Still `btag`, so existing links and affiliate tooling do not change.
  Its value becomes a signed token naming the network that issued it, the affiliate, the link, an
  optional campaign, up to five sub-ids, and when it was issued. The brand verifies it with the
  network's published key. A bare UUID is still accepted for a transition period and resolved
  through the legacy tag document eitheror's own programme publishes (reconciliation session).
- Q: Which touch wins? → A: The first. The attribution a player is bound to at registration is
  final; a later tag, bonus code or link records a later touch and changes nothing. A bonus code
  that is an affiliate's binds the player exactly as a tag does, which fixes the first bug.
- Q: Is there an attribution window? → A: Yes, enforced by the brand: a tag older than the window
  at registration binds nothing, and the registration is recorded as organic with the stale tag
  noted. The window is a brand setting with an installation default.
- Q: How is a player named in a fact? → A: By a pseudonym the brand derives per network: a keyed
  digest of the player id under a key the brand holds for that network. Two networks cannot join
  players, and nothing in a fact is personal data under ankka 042; the `Personal` fields stay inside
  the brand.
- Q: Per-round or per-day GGR? → A: Per day, per player, in the brand's reporting zone, emitted
  when the day closes, with an adjustment fact for rounds that settle after the close. The original
  reported every settled round and had the feature switched off by default; a per-round stream is
  an optional brand setting for a network that asks for it.
- Q: How do facts reach a programme? → A: Three carriers with one payload. A topic the brand
  declares, granted to the programme project or to a registered machine (ankka 040); a cursor-paged
  feed over HTTPS; webhooks with retries and a dead letter, replacing today's postback. Every
  carrier is at least once and every fact carries an id the reader deduplicates by.
- Q: What may a programme tell a brand? → A: Nothing that changes money or an attribution already
  made. A programme publishes its signing keys and may revoke a link or a tag, which stops new
  registrations binding to it. That is the whole reverse direction, because the attribution record
  is the brand's regulated record and no outside party may alter it.
- Q: What happens to facts when a player is erased? → A: Nothing. Facts carry a pseudonym, not
  personal data, and are kept as long as the ledger. A closed-player fact is emitted when the
  player's account closes, whatever the reason.

### Session 2026-10-08 (reconciliation)

Settled with specs 001 and 013 once all three existed.

- Q: Where does the brand's side live? → A: In a service of its own, `attribution`, in the brand
  project — not inside `players`. It owns the click route, the binding, the pseudonym keys, the
  facts, the feed and the webhooks, and learns of registrations and bonus codes from the brand's
  own topics (FR-029, FR-030); it calls no other service and no other service calls it.
- Q: What is the topic called? → A: `affiliates.attribution`, listed in spec 001's topic contract,
  keyed by the player's pseudonym (by the link for `click`).
- Q: Who holds the legacy link index for bare-UUID tags? → A: The programme, which imported the
  links. It publishes a legacy tag document beside its key document; the brand fetches and caches
  it like the others and resolves a bare UUID from it, so the brand calls nothing at registration
  and holds no index of its own.
- Q: Does every click have a click id? → A: Yes. The brand carries the one the link brought
  (`clickId`) and assigns one when the link brought none, so a registration fact can always name
  its click and a programme's postback can always fill `{clickid}`.
- Q: Is the player's country in a fact? → A: Yes, on `click` and `registration`, as a country code
  the brand already derives for every visit. The original's programme reports revenue by country
  and spec 013 keeps that; a country code identifies no one and spec 001 FR-024 lists it as not
  personal. The open question that excluded it is closed.

- Q: Which `casino.withdrawals` families become withdrawal facts? → A: Four of spec 007's six, one to
  one by name (FR-030); an approval and a cancellation produce nothing, since the programme counts
  only completed and reversed withdrawals (spec 013).

### Session 2026-10-25

The markers left open for product and regulatory decisions, settled with Trevor before 008 is planned.

- Q: How long are bare UUID tags accepted? → A: Until the first of 12 months from the brand's
  first day on the protocol or 90 days with no legacy tag presented; the brand records which
  ended it (FR-004).
- Q: The attribution window's installation default? → A: 30 days, today's cookie (FR-008).
- Q: Does every network receive rounds? → A: No. The daily aggregate is the default; rounds are
  opt-in per network, since every round to every network multiplies volume and personal-data
  exposure for nothing (FR-016).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A visitor arrives on an affiliate's link and registers (Priority: P1)

An affiliate on any network puts a link to the brand in front of a visitor. The link carries
`btag=<token>` and may carry `clickId` and `utm_*` as today. The visitor lands, the brand counts one
click for that link, and when the visitor registers within the window the brand binds the new
player to that network, affiliate, link and sub-ids, once and for good, and emits a registration
fact that names them.

**Why this priority**: Nothing else in the protocol has a subject until a player is bound to an
affiliate.

**Independent Test**: Issue a token for a test network, land on the brand with it, register, and
read the brand's attribution record and its topic: one click fact, one registration fact, both
naming the same network, affiliate, link, sub-ids and click.

**Acceptance Scenarios**:

- added `features/attribution/landing.feature`: a landing with a valid tag records one click per browser session and emits a click fact
- added `features/attribution/landing.feature`: a visitor who registers within the attribution window is bound once and for good
- added `features/attribution/landing.feature`: a tag older than the attribution window binds nothing and the registration is organic
- added `features/attribution/landing.feature`: a later tag does not change a binding and is recorded as a later touch
- added `features/attribution/landing.feature`: a tag the brand cannot verify never fails a registration and is kept with its reason
- added `features/attribution/landing.feature`: a tag for a revoked link binds nothing and the record says the link was revoked

---

### User Story 2 - A player registers with an affiliate's bonus code (Priority: P1)

Some affiliates hand out a code instead of a link. A visitor types it at registration or later on
the bonus page. If the code is an affiliate's, the brand binds the player to that affiliate exactly
as a link would have, and every later fact about the player is attributed.

**Why this priority**: This is the first bug. Today the code binds the player record only, so
nothing after registration is attributed.

**Independent Test**: Register with an affiliate's code and no link, deposit, play a day, and read
the topic: registration, first-deposit, deposit and daily GGR facts all name the affiliate.

**Acceptance Scenarios**:

- added `features/attribution/bonus-codes.feature`: a bonus code presented at registration binds the player as a tag would
- added `features/attribution/bonus-codes.feature`: a bonus code entered after an organic registration within the window binds the player
- added `features/attribution/bonus-codes.feature`: a bonus code entered by a player already bound applies its reward and changes no binding

---

### User Story 3 - A programme reads a brand's facts (Priority: P1)

eitheror's own programme, another installation's, or a third-party network reads every fact the
brand emits, by whichever carrier suits it, and never misses one or reads one twice without being
able to tell.

**Why this priority**: The protocol exists to carry facts out of the brand; a carrier that loses
one pays an affiliate wrongly.

**Independent Test**: With a programme project granted the topic, a machine registered outside
the installation reading the same topic through the broker, and a webhook subscription, emit one of
every fact and check all three readers hold the same facts with the same ids.

**Acceptance Scenarios**:

- added `features/attribution/carriers.feature`: the programme reads a fact it was allowed within a minute with its id, kind and protocol version in the open
- added `features/attribution/carriers.feature`: a network registered as a machine reads the same facts with its own reader and nothing else of the brand's
- added `features/attribution/carriers.feature`: the feed returns facts from a cursor in the order they were emitted, each page signed by the brand
- added `features/attribution/carriers.feature`: reading the feed from the same cursor twice returns the same page
- added `features/attribution/carriers.feature`: a network webhook posts each fact, retries for a day, then keeps it as an undelivered fact the operator can resend
- added `features/attribution/carriers.feature`: a reader can discard a fact whose id it has seen because the id and the content are the same both times
- added `features/attribution/carriers.feature`: facts from two brands about one affiliate name their brand, share the affiliate's id and differ in pseudonym

---

### User Story 4 - A player's money and play reach the programme as facts (Priority: P1)

After registration the brand emits what the programme needs to pay commission: the first deposit,
every deposit, each day's gross gaming revenue, the cost of bonuses, chargebacks, withdrawals and
their reversals, and closure. Every amount is a whole number of minor units in the player's
currency; nothing is a plan, a rate or a commission.

**Why this priority**: These are the inputs ADR 0042's ledger folds; without them nothing is
payable.

**Independent Test**: For an attributed player, deposit twice, play rounds across a day boundary,
receive a bonus, charge back one deposit, withdraw, and reverse the withdrawal; compare the facts to
the commission inputs the original's notifications carried for the same recording.

**Acceptance Scenarios**:

- added `features/attribution/facts.feature`: a player's first captured deposit emits a first-deposit fact and a deposit fact, the first once in the player's life
- added `features/attribution/facts.feature`: a later captured deposit emits only a deposit fact
- added `features/attribution/facts.feature`: one daily GGR fact is emitted per player when the brand's reporting day closes
- added `features/attribution/facts.feature`: a round of a closed day that settles after the close emits a GGR adjustment for that day
- added `features/attribution/facts.feature`: a network that opted into the round stream receives a round fact at settlement and the daily fact at the close
- added `features/attribution/facts.feature`: a network with the default configuration receives the daily fact and no rounds
- added `features/attribution/facts.feature`: what happens to an attributed player's money is emitted as the matching fact with its amount and reference
- added `features/attribution/closure.feature`: closing, permanently self-excluding or erasing an attributed player emits one player-closed fact with a category and no reason
- added `features/attribution/facts.feature`: no fact carries a personal field

---

### User Story 5 - A network's tokens are trusted, rotated and retired (Priority: P2)

A brand trusts a network's tokens because it holds the network's published keys. The network
rotates a key without a visitor noticing, and a brand that stops working with a network stops
trusting its tokens.

**Why this priority**: Signing is what lets a tag travel across installations without a shared
link index; the transition from bare UUIDs is what keeps today's links alive.

**Independent Test**: Publish two keys for a network, issue tokens under each, withdraw one, and
land with all three kinds of token.

**Acceptance Scenarios**:

- added `features/attribution/networks.feature`: a tag naming a signing key the brand does not hold makes the brand fetch and cache the network's key document
- added `features/attribution/networks.feature`: a tag signed with a key the network has withdrawn is refused once the brand's cache has refreshed
- added `features/attribution/networks.feature`: a legacy tag the programme's legacy tag document lists is treated as the programme's tag during the transition period
- added `features/attribution/networks.feature`: a legacy tag after the transition period is refused and the player is organic
- added `features/attribution/networks.feature`: a tag from a network the operator removed is refused and facts already emitted are unchanged

---

### User Story 6 - A programme revokes a link, and nothing else (Priority: P2)

A network finds an affiliate sending fraudulent traffic and revokes their links. New registrations
stop binding to them. Nothing the network says can unbind a player, move a fact, or change an
amount.

**Why this priority**: A brand's attribution record is part of its regulated books. The reverse
channel must be small enough that no outside party can touch them.

**Independent Test**: Revoke a link at the network, land with its token, and attempt every other
message the reverse channel does not define.

**Acceptance Scenarios**:

- added `features/attribution/networks.feature`: a revoked link binds no new player and players already bound stay bound
- added `features/attribution/networks.feature`: a revoked affiliate has every tag naming them refused for new bindings
- added `features/attribution/networks.feature`: anything but signing keys and revocations from a network is refused
- added `features/attribution/networks.feature`: the transition period ends at the first of 12 months or 90 days without a legacy tag

---

### User Story 7 - A player is closed or erased (Priority: P3)

When a player's account closes, the programme learns it so it can stop expecting activity. When
the player is erased under ankka 042, the brand's facts about them stand, because they were never
personal.

**Why this priority**: Correct, but no money depends on it.

**Independent Test**: Close an attributed player, then erase them, and read the topic and the
attribution record.

**Acceptance Scenarios**:

- added `features/attribution/closure.feature`: closing, permanently self-excluding or erasing an attributed player emits one player-closed fact with a category and no reason
- added `features/attribution/closure.feature`: an erased player's attribution record keeps its binding and every personal field reads as erased
- added `features/attribution/closure.feature`: facts already emitted about an erased player are unchanged

---

### Edge Cases

- **Two tags in one session.** A visitor lands with one token, then lands again with another
  before registering: the first landing's tag is the one the browser carried into registration
  (today's session record), and the second is recorded as a later touch.
- **A token and a bonus code at registration naming different affiliates.** The token binds;
  the code is a later touch and its bonus applies.
- **A deposit captured before the registration fact is emitted.** Facts are ordered per player on
  the topic by the player's key, so the registration fact precedes the first-deposit fact; the feed
  preserves the same order.
- **A chargeback for a deposit that was the first deposit.** A chargeback fact is emitted; the
  first-deposit fact is never retracted, and what that means for a CPA is the programme's rule.
- **A GGR day with zero activity.** No fact.
- **A round that settles on a day before the player was bound** (binding by a later bonus code):
  the daily fact for that day is emitted at the binding, dated to the day, and marked as before the
  binding so the programme can apply its own rule.
- **The network's key document cannot be fetched.** Tokens under keys already cached verify
  until the cache expires; after that they are refused, and the brand's status shows the network
  unreachable.
- **A token longer than the brand accepts** (1 KiB): refused as malformed.
- **A machine or programme that falls behind.** The topic keeps facts for the brand's retention
  (ankka 043), set long for this topic; the feed keeps every fact; the webhook dead letter keeps a
  fact until it is resent or the operator discards it.
- **A brand that uses several networks.** A tag names its network; the brand holds a pseudonym
  key per network and a feed, webhook subscriptions and grants per network; a fact goes only to the
  network that owns the affiliate it names.

## Requirements *(mandatory)*

### Functional Requirements

**The tag**

- **FR-001**: A brand MUST read the affiliate tag from the `btag` query parameter and MUST carry
  `clickId` and `utm_source`, `utm_medium`, `utm_campaign`, `utm_content` and `utm_term` alongside
  it unchanged, as the player site captures them today.
- **FR-002**: A tag MUST be a token stating the protocol version, the network that issued it, the
  affiliate, the link, an optional campaign, up to five sub-ids (`sub1` to `sub5`), the key that
  signed it and when it was issued, signed by the network. A token MUST be verifiable by a brand
  that holds the network's keys and nothing else of the network's, MUST survive being carried in a
  URL unchanged, and MUST be refused when longer than 1 KiB.
- **FR-003**: A brand MUST verify a token's signature against the network's published key
  document, MUST cache keys for the time the document states, and MUST refuse a token whose key the
  document no longer lists.
- **FR-004**: For a transition period from the brand's first day on this protocol, a brand MUST
  accept a bare UUID tag that the programme's legacy tag document (FR-023) lists, treating it as a
  token of eitheror's own programme naming that link's affiliate and link with no sub-ids. After the period a bare UUID
  MUST be refused as legacy. The period MUST end at the first of 12 months from that first day
  or 90 days in which no legacy tag was presented; the brand MUST keep the day it ended and which
  of the two ended it, and the programme (spec 013) MUST be able to read it to tell affiliates
  (Clarifications 2026-10-25).
- **FR-005**: A refused tag (bad signature, unknown network, withdrawn key, revoked link or
  affiliate, legacy after the period, malformed) MUST never fail a registration; the player MUST be
  recorded as organic with the refused tag and the reason kept.

**Capture and binding**

- **FR-006**: A brand MUST record at most one click per browser session per landing with a valid
  tag, holding the network, affiliate, link, campaign, sub-ids, click id, landing time, device type
  (`mobile`, `desktop` or `unknown`), the visitor's country code, a digest of the visitor's address
  and never the address, and the `utm_*` values. The click id MUST be the one the link carried as
  `clickId`, or one the brand assigns when the link carried none, so every click has one. Recording a click MUST NOT depend on the visitor's consent decision and MUST
  NOT set any cookie.
- **FR-007**: A brand MUST bind a player to an affiliate at most once, at the first of: a valid
  tag presented at registration, an affiliate's bonus code presented at registration, or an
  affiliate's bonus code presented after an organic registration within the window counted from
  registration. The binding MUST hold the network, affiliate, link, campaign, sub-ids, click id when
  there was one, the pseudonym, the bound-at time and the kind of touch that bound it.
- **FR-008**: A tag presented at registration MUST bind only when it was issued no longer ago
  than the brand's attribution window. The window MUST be a brand setting with an installation
  default of 30 days, matching the original's cookie, so affiliates' terms do not change at the
  migration (Clarifications 2026-10-25).
- **FR-009**: A tag, code or link presented for a player already bound MUST NOT change the
  binding and MUST be recorded on the player's attribution record as a later touch with its time.
- **FR-010**: The click id MUST be carried from the click to the binding and into every fact about
  the player that has one, which corrects the original's empty `{clickid}`.

**Facts**

- **FR-011**: A brand MUST emit facts of these kinds: `click`, `registration`, `first-deposit`,
  `deposit`, `ggr-daily`, `ggr-adjustment`, `ggr-round` (only when the brand turns the stream on
  for a network), `bonus-cost`, `chargeback`, `withdrawal-requested`, `withdrawal-completed`,
  `withdrawal-failed`, `withdrawal-reversed` and `player-closed`.
- **FR-012**: Every fact MUST carry: the protocol version, a fact id that is the same every time
  the fact is emitted, the kind, when it occurred, the brand, the network, the affiliate, the link,
  the campaign and sub-ids as bound, the click id when there is one, and for every kind but `click`
  the player's pseudonym for that network. A `click` and a `registration` fact MUST also carry the
  country code the brand derived for the visit, and never anything finer. A fact about money MUST carry the currency and every
  amount as a whole number of minor units, signed where a value can be negative, and the reference
  of the payment, round, bonus or withdrawal it is about.
- **FR-013**: A player's pseudonym for a network MUST be a keyed digest of the player's id under
  a key the brand holds for that network in its secret store; it MUST be stable for the player's
  life, MUST differ between networks and between brands, and MUST NOT be derivable without the key.
- **FR-014**: No fact MUST carry a `Personal` value or any field that identifies a person: no
  name, email, address, date of birth, document, device fingerprint or raw network address.
- **FR-015**: A `first-deposit` fact MUST be emitted once in a player's life, at the first
  captured deposit, beside a `deposit` fact; the original's per-period first-deposit guard MUST NOT
  be carried over (the commission consequence is spec 013's, which holds the open confirmation).
- **FR-016**: A `ggr-daily` fact MUST be emitted once per attributed player per reporting day with
  activity, when the day closes in the brand's reporting zone, holding total stake, total win and
  GGR. A round settling after its day's close MUST produce a `ggr-adjustment` fact for that day.
  A day earlier than the binding, for a player bound by a later code, MUST be emitted at the binding
  marked as before the binding. The daily aggregate MUST be what every network receives by default;
  the per-round facts MUST be emitted to a network only when its configuration at the brand opts in
  (Clarifications 2026-10-25).
- **FR-017**: Facts MUST be versioned by the protocol version; a change to a fact's shape MUST be
  a new version emitted beside the old until no reader of the brand's reads the old.

**Carriers**

- **FR-018**: A brand MUST declare one topic, `affiliates.attribution` (spec 001 FR-011), for its
  attribution facts, keyed by the player's pseudonym (by the link for `click`) so facts about one
  player are ordered, declared as a fact topic under spec 001 FR-015 (the ledger's retention, three
  copies), and MUST publish every fact to it with the fact id, kind and protocol version readable
  from the message's attributes.
- **FR-019**: A brand MUST be able to grant the topic to a programme project and to a registered
  machine (ankka 040), and the grant MUST be the only way a programme reads the topic.
- **FR-020**: A brand MUST expose a feed over HTTPS that returns facts in emission order from a
  cursor, in pages of at most 1,000, each page signed over its facts under the brand's key with the
  key id named, and reading from a cursor MUST return the same page every time. The feed MUST admit
  only a machine token (ankka 040) whose grant names it, and MUST serve a machine only the facts of
  the network it is registered for.
- **FR-021**: A brand's operator MUST be able to create a webhook subscription for a network with
  an address and a shared secret; the brand MUST post each fact once, signed under the secret, MUST
  retry on failure with increasing delay for 24 hours, and MUST then hold the fact in a dead letter
  the operator can see, resend or discard. The original's postback URL and its macros MUST NOT be
  carried over.
- **FR-022**: Every carrier MUST deliver at least once, and a reader MUST be able to discard a
  repeat by its fact id.

**The reverse direction**

- **FR-023**: A network MUST publish a key document listing its current signing keys by id, with
  how long a brand may cache it, and a revocation list naming links and affiliates whose tokens
  bind no new player; eitheror's own programme MUST also publish, for the transition period, a
  legacy tag document mapping each imported bare-UUID tag to its affiliate and link. A brand MUST
  fetch each as a public client, cache it for the stated time and refresh it on the stated
  interval; it MUST NOT call a network at registration.
- **FR-024**: A brand MUST refuse any other message from a network; nothing from a network
  changes a binding, a fact or an amount.

**Networks**

- **FR-025**: A brand's operator MUST be able to add a network with its id, key document address
  and revocation list address, generate the brand's pseudonym key for it, turn the per-round stream
  on or off for it, and remove it; removing a network MUST stop new bindings to it and MUST change
  no existing binding or fact.
- **FR-026**: A brand MUST be able to serve several networks at once; a fact MUST reach only the
  network that owns the affiliate it names.

**Closure and erasure**

- **FR-027**: A `player-closed` fact MUST be emitted once when an attributed player's account is
  closed, permanently self-excluded or erased, with the category `closed` or `self-excluded` and no
  reason text; erasure MUST emit the category `closed`.
- **FR-028**: Erasure of a player (ankka 042) MUST leave the attribution record's binding and
  every emitted fact unchanged, and MUST erase every `Personal` field of the player as spec 008
  says.

**The service**

- **FR-029**: The brand's side of this protocol MUST be one service, `attribution`, in the brand
  project (spec 001 FR-002), owning the click route, the attribution records, the pseudonym key per
  network, the fact production, the feed, the webhook subscriptions and the network settings; no
  other brand service MUST hold any of these.
- **FR-030**: `attribution` MUST learn everything it binds and emits from the brand's own topics
  and from nothing else: the tag, click id and bonus code presented at registration from
  `player_registered` on `casino.players`; a code presented later from `bonus_granted` on
  `casino.rewards`; deposits, chargebacks and refunds from `payments.payments`; stakes and wins
  from `casino.rounds`; bonus costs from `casino.rewards`; withdrawals from `casino.withdrawals`
  (`withdrawal_requested` → `withdrawal-requested`, `withdrawal_completed` →
  `withdrawal-completed`, `withdrawal_failed` → `withdrawal-failed`, `withdrawal_reversed` →
  `withdrawal-reversed`; `withdrawal_approved` and `withdrawal_cancelled` produce no fact — spec
  007 FR-025, FR-026); closure from `casino.players`. No brand service MUST call `attribution`, and `attribution` MUST
  call no brand service.

### Key Entities

- **Network**: an issuer of tags and reader of facts — eitheror's programme, another
  installation's, or a third party — known to a brand by an id, a key document, a revocation list,
  a legacy tag document for eitheror's own programme, and a pseudonym key.
- **Tag**: the signed token in `btag`: version, network, affiliate, link, campaign, sub1–sub5, key
  id, issued-at, signature.
- **Click**: one landing with a valid tag in one browser session: the tag's fields, click id,
  time, device type, country code, address digest, `utm_*`.
- **Attribution record**: a player's binding (network, affiliate, link, campaign, sub-ids, click
  id, pseudonym, bound-at, touch kind), the refused or stale tag when there was one, and the later
  touches.
- **Fact**: one versioned, idempotent statement about a click or an attributed player, of one of
  the kinds in FR-011.
- **Feed cursor**: an opaque position in the brand's fact sequence for one network.
- **Webhook subscription**: a network's address and secret, with its dead letter.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Replaying the original's recordings (the Go end-to-end affiliate scenarios) against
  the brand yields, per attributed player, the same registrations, first deposits, deposits, bonus
  costs, chargebacks, withdrawals and reversals the original's notifications carried, with the
  same amounts in minor units, and daily GGR equal to the sum of the original's per-round GGR for
  each day.
- **SC-002**: A player who registers with an affiliate's bonus code and no link has every later
  fact attributed, where the original attributed none.
- **SC-003**: Every registration fact and every webhook carries the click id when the click had
  one; in the original, none did.
- **SC-004**: A fact is readable on the topic within 60 seconds of the event it describes, and on
  the feed within the same bound, under the brand's normal load.
- **SC-005**: Over a day of production traffic, no fact is delivered to a webhook fewer than once,
  and no fact reaches the dead letter while the subscriber is reachable.
- **SC-006**: No fact on any carrier contains a `Personal` value or a field a reviewer can map to
  a person; an automated check over a day's facts finds none.
- **SC-007**: A token issued by a test network at one installation verifies at a brand on another
  installation that holds only the network's key document.
- **SC-008**: Every legacy link in the programme's legacy tag document resolves through the
  transition rule on the first day, and the share of legacy tags falls to zero before the period
  ends, visible in the brand's status.

## Assumptions

- The brand side of this protocol is the `attribution` service (FR-029), a consumer over the
  brand's own topics (FR-030); the plan doc's `affiliates` brand service, which computed
  commission, does not exist in this design, and `players` (spec 008) only carries the tag, click id
  and code it was given onto its registration fact.
- The brand's attribution topic, `affiliates.attribution`, is listed in the topic contract of spec
  001 with the key, retention and grant this spec needs.
- eitheror's own programme (spec 013) is a network like any other to a brand: it issues tags,
  publishes keys, and reads facts by a project grant. Nothing in a brand is special-cased for it.
- The player site's capture (`utm.ts`, the consent-gated `eo_btag` cookie, one click per session)
  stays as it is; it already sends `btag`, `clickId` and `utm_*`, and a signed token is an opaque
  string to it.
- The pseudonym key per network is a service secret of `attribution` (ankka 038); it is never
  rotated, because rotation would change every player's pseudonym.
- The brand's signing key for feed pages and webhooks is a service secret with a key id; a
  network holds the brand's key document to verify them. Rotation is an operator action and the
  old key stays listed until no page or webhook signed under it is in flight.
- Retention of the attribution topic is set to the longest the installation allows (ankka 043),
  and the feed is served from a view the brand keeps for the ledger's retention.
- The transition rule reads the programme's legacy tag document, built from the links spec 013
  imports from the original's `affiliate-links` twins under spec 019; the brand holds no index.
- Clicks are counted by the brand and reported as facts; the programme shows per-link click
  counts from them. The brand keeps no commission, revenue or payout numbers of any kind.
- Fraud signals the original raised in the brand (3+ bonus deductions, 2+ chargebacks) are the
  programme's to derive from facts (spec 013); the brand emits the facts, not the flags.

## Dependencies

- ankka 040 cross-project access: the topic grant to the programme project, the machine
  registration and consume grant for an external network, and machine tokens for the feed.
- ankka 042 personal data erasure: `Personal` fields in the player and the subject's erasure.
- ankka 043 topic retention: the attribution topic kept as long as the ledger.
- ankka 038 secret store: the pseudonym keys and the brand's signing key.
- Spec 001 for the topic contract and the brand project layout; spec 008 for the registration fact
  that carries the tag, click id and code; spec 013 for the programme side and the legacy tag
  document; spec 019 for migrating existing bindings.

## Changes from the original

- The seven `notify/*` internal routes and the `affiliate_notify_ggr` capability are replaced by
  facts on a topic derived from the brand's own facts; the brand calls no affiliate service.
- The postback URL with `{event}`, `{clickid}`, `{payout}` and `{status}` is replaced by signed
  webhooks carrying the fact, with retries and a dead letter.
- The tag gains a network, sub-ids and a signature; bare UUIDs are accepted only for the
  transition.
- An attribution window is enforced by the brand; the original enforced none.
- A bonus code binds the attribution record, not only the player record (bug 1); the click id is
  forwarded (bug 2).
- GGR is emitted per player per day, with adjustments, instead of per round through a feature
  that shipped switched off; the per-round stream is optional.
- The first-deposit guard is per player for life, not per player per period.
- Commission, plans, overrides, the network tree, payouts, the portal and fraud flags leave the
  brand for the programme (spec 013).

## Open Questions

- The three markers above: the transition period, the attribution window default, and whether
  the per-round GGR stream is opt-in. The lifetime first-deposit guard is confirmed in spec 013.
