# Feature Specification: players — A Brand's Account of Each Player

**Feature Branch**: `008-players`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The brand's `players` service: registration, the relationship with the
brand's realm, login and player sessions, the profile and its personal fields, verification status
read from `kyc`'s facts, suspension and closure, VIP tier, consents, favourites, game sessions opened
for `game-provider`'s launch, the erasure flow, and the `casino.players` and `casino.sessions` facts
every other service reads."

## Context

Today a player is a twin in `casino-service`'s namespace plus a user in the brand's Keycloak realm.
`PlayerRoutes.scala` (1,913 lines), `PlayerProfileRoutes.scala`, `BackofficePlayerRoutes.scala`
(2,752 lines), `SessionRoutes.scala`, `PlayerEmailRoutes.scala`, `PlayerEmailChangeRoutes.scala`,
`KeycloakWebhookRoutes.scala`, `KeycloakSessionReconciler.scala` and `MultiAccountDetector.scala`
read and write that twin's properties; a Go capability (`close_child_sessions`) cascades a logout to
the game sessions under it; the realm's two custom authenticators (`eitheror-rg-status-authenticator`
and the GAMSTOP check) read the twin straight from the substrate's REST API at every login. Nothing
is a state machine: `account_status` is a string property, every write is fire-and-forget, and
`KeycloakSessionReconciler` exists because an idle realm session produces no event at all.

This spec makes the player one event sourced entity, the player session and the game session two
more, and every fact another service needs a message on `casino.players` or `casino.sessions`. The
realm keeps what it is good at — credentials, email verification, token issue — and `players` keeps
everything else about the player, as the original already does in practice. The realm's
authenticators, which cannot read a twin that no longer exists, ask `players` instead.

The plan's `players` table also listed the brand's configuration entities (`brand-config`,
`withdrawal-config`, `bonus-config`, `rg-config`, `currency-config`, `allow-list`, `kyc-policy`) and
`game-catalog`. Each configuration belongs to the service that enforces it; this spec keeps only
`brand-config`, `currency-config` and the country allow-list. The catalogue is `game-provider`'s
(spec 003); withdrawal configuration is `payouts`' (007); bonus configuration is `rewards`' (011);
responsible-gambling configuration is `responsible-gambling`'s (010); the KYC policy is `kyc`'s
(009, as spec 004 already moved it).

## Clarifications

### Session 2026-10-08

- Q: Who is the source of truth for a player's credentials and for everything else? → A: The realm
  (`casino-<slug>`, spec 001 FR-019) for the password, the email address's verified state and the
  tokens it issues; `players` for the account status, the profile, consents, sessions, favourites
  and every fact. Today the realm's authenticators read the account status from the twin at login;
  they now ask `players` for it (FR-013), so the two never disagree about whether a player may sign
  in.
- Q: What is a player's id? → A: An id `players` mints at registration and writes onto the realm user
  as the claim the original already carries (`eitheror_twin_id`). The original also *derives* an id
  from the brand and the email address in three places (login without the claim, the email routes,
  password change); that derivation is dropped, because it would give an erased player who registers
  again the same id, which spec 001's edge case forbids.
- Q: Which service produces the identity-verification facts on `casino.players`? → A: `kyc` (spec
  009): `player_kyc_verified`, `player_kyc_rejected` and the `player_kyc_submitted` family spec 007
  asked for. `players` builds its verification view from them and never asks `kyc`. Spec 001
  FR-011's producer list for `casino.players` gains `kyc`; the player-facing KYC routes the original
  serves from `PlayerRoutes` (`kyc/requirements`, a document's view link) move to spec 009.
- Q: How does `attribution` learn of a registration? → A: From the `player_registered` fact, which
  carries the tag, the click id and the bonus code exactly as presented and unverified (001 FR-011).
  `players` calls no affiliate service; the original's `notifyRegistration` call and the properties it
  wrote back onto the player (`affiliate_id`, `btag`, `affiliate_attributed_at`) are gone.
- Q: Where does the realm's session webhook land, and what about idle sessions, which the realm never
  reports? → A: On a public route of `players` served only when the shared secret verifies (001
  FR-020; the secret from ankka 038), exactly as today. The idle gap is closed as today, by asking the
  realm for its live sessions on a cadence — a recurring timer (ankka 032) per brand instead of the
  reconciler class — and ending every player session the realm no longer holds.
- Q: Does `players` enforce a session duration limit? → A: It sets the timer, from its own data
  (*as reconciled at reconciliation 2; this answer first had it ask `responsible-gambling` at open*):
  it keeps each player's `SESSION_MINUTES` limit from `rg_limit_applied` facts and counts the minutes
  of its own game sessions started this UTC day, schedules a timer per game session for the
  remainder, and when it fires the session ends with reason `limit`, and the fact says so. The hourly
  scan the original runs over every active session is gone.
- Q: Does multi-account detection still scan every player of the brand? → A: No. It is a consumer of
  `player_registered` matching two lookup tokens (ankka 042) kept on the player: one over the
  normalised given name, family name and date of birth, one over the device fingerprint. A match flags
  both players and opens a fraud case, as today, and the brand's kill switch is honoured.
- Q: Is the login-failure lockout kept? → A: No. The TL function `player_login_failure_lockout` has an
  empty body in the original (`casino_player_login_failure_lockout.tl`: `{ }`); it fires and does
  nothing. The realm's own brute-force protection is the lockout, and it is realm configuration.
- Q: What does `players` do for erasure? → A: It is the brand's filer (001 FR-025): it files the
  request in the brand project, and in `payments` under the brand's erasure right (ankka 040 FR-032),
  under one correlation id and with the hold the brand's retention rule names; it tells the realm to
  remove the user; and it fetches the certificates for the data-subject response.
- Q: Which configuration entities stay here? → A: `brand-config` (names, addresses, the player site's
  base address, feature switches), `currency-config` and the country allow-list. The rest belong to
  the service that enforces them (Context).

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: What does `players` answer at launch, and what does it ask at sign-in? → A: At launch, the
  account status only (FR-018): the KYC play gate and the break, exclusion and session minutes are
  asked of `kyc` and `responsible-gambling` by `game-provider` itself (spec 003 FR-007). At sign-in,
  `players` asks `responsible-gambling`'s `may-log-in` and fails closed (FR-011), and answers the
  realm's authenticator the same way (FR-013); the realm reaches no other service.
- Q: Who excludes a player? → A: `responsible-gambling` (spec 010 FR-018, FR-019), which commands
  `players` to set the status; `players` ends the sessions and produces `player_self_excluded`
  (FR-032). The operator action the original had on the player's backoffice page is
  `responsible-gambling`'s.
- Q: Where does the session timer's allowance come from? → A: From `players`' own data: the limit
  from `rg_limit_applied` facts, the minutes used from its own sessions (FR-019); no call at open.
- Q: Which family carries the profile fields `kyc` needs? → A: `player_profile_updated` (FR-025),
  the one name spec 009 reads; the earlier `profile_updated` is renamed.
- Q: Who starts a deposit for a player? → A: `players` (FR-043), asking `kyc` and
  `responsible-gambling` first and opening the cashier session on `ingest` under the brand's
  merchant key; the wallet's check at credit stays authoritative.

### Session 2026-10-08 (reconciliation 3)

Settled with specs 014, 015 and 017.

- Q: How does `engagement` learn an inbox changed, and write a campaign's message? → A: From three
  new families on `casino.players` (`player_message_created`, `player_message_read`,
  `player_message_deleted`, FR-030, FR-035) and through a service route on `players` that writes a
  marketing-class message under the campaign's delivery id, refusing a suppressed or closed player;
  the delivered state comes back from `engagement`'s `push_delivered` on `casino.push`.
- Q: Where is the brand's reporting zone? → A: In `brand-config` (FR-041), read by `reporting` and
  `attribution`.
- Q: Does the operator's message-suppression flag reach `engagement`? → A: Yes, on
  `player_consent_changed` (FR-030), so suppression is knowable from facts.
- Q: What does `support-agent` ask of `players`? → A: The status and display name
  (`get_player_state`), the game sessions (`get_session_history`), and the end of one game session
  (`close_active_session`, FR-017), each by `support-agent`'s identity within the project (spec 017
  FR-009, FR-010); it reads no `Personal` field but the display name.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A person registers and becomes a player of the brand (Priority: P1)

A visitor gives an email address, a password and a currency, and optionally a display name, given
and family names, a date of birth, a country, a language, a bonus code and the tag the link they
arrived by carried. They become a player with an id, pending email verification; a verification
email is sent; their network address and its country, and their device fingerprint when the site
sent one, are recorded; marketing consents are recorded as given; and the brand's other services
learn of the registration from one fact.

**Why this priority**: Nothing else in the brand exists for a person until this does; `attribution`
(012), `rewards` (011) and `engagement` (014) all begin from this fact.

**Independent Test**: Register with every field, read the player back, confirm the realm user exists
with the id claim, confirm the fact on `casino.players` carries the tag, click id and code as sent.

**Acceptance Scenarios**:

- added `features/players/registration.feature`: a registration with a new email address, an acceptable password and an offered currency creates a player pending email verification
- added `features/players/registration.feature`: a registration with an email address already registered is refused and nothing is created
- added `features/players/registration.feature`: a registration with a password below the realm's policy is refused and nothing is created
- added `features/players/registration.feature`: a registration carrying a tag that is not well formed succeeds with no tag on the fact
- added `features/players/registration.feature`: a registration missing a required field is refused naming the three required fields
- added `features/players/registration.feature`: a registration with marketing consents given records them with their time and source
- added `features/players/registration.feature`: following the verification link within a day verifies the address and makes the player active, and following it again changes nothing
- added `features/players/registration.feature`: a registration keeps the network address and its country as personal data without overwriting the player's own country

---

### User Story 2 - A player signs in, plays in a session, and signs out (Priority: P1)

A player signs in with email address and password. The realm checks the credentials and its
authenticators ask `players` whether the account may sign in; `players` opens a player session bound
to the realm's session, and the player receives tokens, their id, currency, country and any bonus
code still pending from registration. Signing out, the realm expiring the session, or the realm no
longer holding it ends the player session and every game session under it.

**Why this priority**: Every player-facing route of every brand service admits a player by this
session; a wrong answer here locks a player out or lets a restricted one in.

**Independent Test**: Sign in, open two game sessions, sign out; confirm one player session and two
game sessions ended with reason `logout`, and the facts in order.

**Acceptance Scenarios**:

- added `features/players/sessions.feature`: a sign-in with the right password opens a player session and answers the player's details
- added `features/players/sessions.feature`: a sign-in by a player whose email address is not verified is refused and no session opens
- added `features/players/sessions.feature`: a sign-in by a restricted player is refused naming the status and the attempt is kept
- added `features/players/sessions.feature`: the realm asking whether a restricted player may sign in is answered that they may not, before anything is issued
- added `features/players/sessions.feature`: a sign-out ends the player session and every game session under it and revokes the realm's session
- added `features/players/sessions.feature`: a player session whose realm session has gone idle ends as expired when the brand next asks the realm
- added `features/players/sessions.feature`: a refresh of the sign-in issues a new one
- added `features/players/sessions.feature`: a refresh that is expired, reused or revoked is answered refresh expired
- added `features/players/sessions.feature`: a player's request about another player's id is refused as a mismatch

---

### User Story 3 - A game session is opened for a launch and ended (Priority: P1)

`game-provider` (spec 003) asks `players` to open a game session for a player, a game and a currency
at launch. `players` checks that the player may play, opens the session under the player's current
player session, sets the session duration timer from the player's responsible-gambling limit, and
returns the session id. The player site ends the session when the game closes; the timer ends it
when the limit is reached; an operator can end it by force.

**Why this priority**: Spec 003 FR-009 depends on this route; without it no game launches.

**Independent Test**: Open a session as `game-provider`, end it as the player, read it back with
duration; open another with a 1-minute limit and see it end by itself with reason `limit`.

**Acceptance Scenarios**:

- added `features/players/sessions.feature`: a game session opened at launch for an active verified player is recorded under the player session
- added `features/players/sessions.feature`: a game session asked for a restricted or closed player is refused naming the status
- added `features/players/sessions.feature`: a game session's timer is set from the minutes left of the session limit
- added `features/players/sessions.feature`: a game session whose timer fires ends with reason limit
- added `features/players/sessions.feature`: a player ending a game session records its end time and duration, and ending it again changes nothing
- added `features/players/sessions.feature`: an operator ending a game session by force ends it with reason forced and is named in the audit record
- added `features/players/sessions.feature`: a player reading their sessions sees their own and nobody else's

---

### User Story 4 - A player keeps their profile, addresses and consents (Priority: P2)

A player reads and edits their profile: given and family names, telephone, address and the fields
their jurisdiction's banking needs. Some fields lock once set; the country must be one the brand
allows. A player keeps several email addresses, verifies each, promotes one to primary and removes
others; changes their password with an emailed confirmation; and gives or withdraws marketing
consents and records their cookie decision per device.

**Why this priority**: Required for KYC (009) and payments (004 forwards the address to providers),
and for the player's own account page; second to money paths.

**Independent Test**: Edit each field and read back; try to change a locked field and see it ignored;
add a second address, verify it, promote it, remove the first; withdraw consent and read it back.

**Acceptance Scenarios**:

- added `features/players/profile.feature`: a player reads their profile with every personal field readable to them and to no other player
- added `features/players/profile.feature`: a locked date of birth is unchanged by an edit, the other fields apply, and the fact names only what changed
- added `features/players/profile.feature`: a country the brand does not allow is refused and nothing changes
- added `features/players/profile.feature`: a second email address added and verified is verified and not the sign-in address
- added `features/players/profile.feature`: making a verified second email address the sign-in address makes the realm sign them in with it
- added `features/players/profile.feature`: a password change with the current password sends a confirmation email and changes the password only when its link is followed
- added `features/players/profile.feature`: a password change with a wrong current password is refused
- added `features/players/profile.feature`: a withdrawn email marketing consent reads as withdrawn with its time, and marketing email stops from that fact
- added `features/players/profile.feature`: a cookie decision recorded with every field is kept per device and visible to an operator
- added `features/players/profile.feature`: a cookie decision with a field missing is refused rather than defaulted
- added `features/players/profile.feature`: an operator's correction of a date of birth that would make the player younger than eighteen is refused
- added `features/players/profile.feature`: an operator's correction of a date of birth to eighteen or over is applied and audited with the reason

---

### User Story 5 - An operator suspends, reinstates, excludes, closes, and tiers a player (Priority: P2)

A brand operator suspends a player with a reason, reinstates them, self-excludes them on the player's
behalf, sets a VIP tier, marks them PotVIP, sends a password reset, ends their sessions, writes notes,
and closes the account. Each transition is a fact other services act on: a suspension ends sessions
and bars play; a closure ends everything and starts the retention clock.

**Why this priority**: Compliance actions; every one is audited in the original (feature 149) and
read by `rewards`, `payouts`, `engagement` and `attribution`.

**Independent Test**: Suspend, see the fact and the sessions end, see a login refused; reinstate;
close; see `player_closed` and that the realm user is disabled.

**Acceptance Scenarios**:

- added `features/players/status.feature`: a suspension with a reason ends every session, revokes the realm's sessions and carries the previous and new status
- added `features/players/status.feature`: a reinstatement makes a suspended player active
- added `features/players/status.feature`: an exclusion applied through responsible gambling sets the status, keeps the exclusion time and ends every session
- added `features/players/status.feature`: an operator has no exclusion action on the player records themselves
- added `features/players/status.feature`: a tier set on a player at no tier carries an empty previous tier and names the operator in the audit record
- added `features/players/status.feature`: a closure ends everything, disables the realm user and is produced with actor and reason
- added `features/players/status.feature`: a closed player's sign-in is refused and their wallet balances are untouched
- added `features/players/status.feature`: an operator without the brand's role is refused any action on its player however they signed in
- added `features/players/status.feature`: a pinned note is personal data under the player, first in the operator's view and invisible to the player

---

### User Story 6 - A duplicate account is detected and reviewed (Priority: P3)

When a player registers, the brand compares them with every existing player by a lookup over the
normalised given name, family name and date of birth, and by device fingerprint. A match flags both
players as suspected duplicates, links them, and opens a fraud case an operator reviews or dismisses;
`rewards` (011) withholds bonuses from a flagged player.

**Why this priority**: Fraud control with its own end-to-end scenarios (Scenario 18); matters less
than the money paths it protects.

**Independent Test**: Register two players with the same name and date of birth; see the case, both
flags, and the linked ids; dismiss; see the flags cleared.

**Acceptance Scenarios**:

- added `features/players/duplicates.feature`: a registration with the same name and date of birth as a player flags both as suspected duplicates and opens a fraud case with the identity signal
- added `features/players/duplicates.feature`: a registration with the same device fingerprint as a player flags both and opens a fraud case with the device signal
- added `features/players/duplicates.feature`: a flagged player's claim of a reward is withheld by the brand's rewards from the flag they read
- added `features/players/duplicates.feature`: dismissing a fraud case clears the flag of every player it links
- added `features/players/duplicates.feature`: reviewing a fraud case marks it reviewed with the operator and the time
- added `features/players/duplicates.feature`: a brand with detection turned off compares nothing on registration
- added `features/players/duplicates.feature`: a registration without names, date of birth or device fingerprint compares nothing

---

### User Story 7 - A player's data is erased and the response assembled (Priority: P3)

A player, or an operator for them, asks for erasure. `players` files it in the brand project and in
`payments`, with the hold the brand's retention rule names, under one correlation id; when every
service has applied it, `players` tells the realm to remove the user and assembles the certificates
into the data-subject response. Before erasure, a player can be given an export of what the brand
holds about them.

**Why this priority**: Regulatory; required before real money (001 FR-036) but exercised rarely.

**Independent Test**: Register, play, deposit; file erasure with no hold; read every brand service's
store and `payments` for a personal field; fetch the response.

**Acceptance Scenarios**:

- added `features/players/erasure.feature`: a filed erasure exists in the brand and in the shared payments under one correlation id with the brand's hold
- added `features/players/erasure.feature`: an erasure whose hold has lapsed and which every part has applied removes the realm user and assembles the certificates
- added `features/players/erasure.feature`: an erasure filed for a player with an open withdrawal is held until the withdrawal completes or fails
- added `features/players/erasure.feature`: a player asking for their data receives an export with operator notes excluded
- added `features/players/erasure.feature`: a registration with an erased player's email address creates a new player with a new id

---

### Edge Cases

- A registration whose realm user is created but whose player cannot be written: the realm user is
  removed again and the registration answers `service_unavailable`; a retry with the same address
  succeeds. (Today the twin write is fire-and-forget and the orphan stays.)
- A sign-in where the realm's claim is missing the player id: refused as `missing_token`; no id is
  derived from the address.
- A webhook from the realm for a non-casino realm, or without a session id: acknowledged and
  ignored, as today, so the realm never retries.
- A webhook `LOGIN` event: counted for the active-players gauge and nothing else; only `LOGOUT` and
  `SESSION_EXPIRED` end a player session.
- Two player sessions for one player (two devices): both open; the original has no concurrent
  session rule and this spec adds none (Assumptions).
- A game session opened while the player session has already ended: refused as `session_expired`.
- A session duration limit lowered while a game session is open: the open session's timer is
  rescheduled from the new limit by the fact `rg_limit_applied`; a session already over the new limit
  ends at once.
- A cookie decision for a device id the player has recorded before: replaced, keeping the latest.
- A promote of an unverified email address: refused; a removal of the primary address: refused.
- A verification link followed after 24 hours: `token_expired`; the player asks for another.
- A profile country change when the brand's allow-list is empty: every country allowed, as today.
- A suspension of an already suspended player: the reason and time are updated, no second fact.
- An operator date-of-birth correction for a player whose date is locked by KYC verification: refused
  until `kyc` reports the verification withdrawn.
- Multi-account matching across brands: never; the lookup is within the brand, as today.

## Requirements *(mandatory)*

### Functional Requirements

**Registration**

- **FR-001**: `players` MUST register a player from an email address, a password and a currency the
  brand offers, with optional display name, given and family names, date of birth, country,
  language, bonus code, tag, click id, device fingerprint and marketing consents; MUST refuse a
  registration missing any of the three required fields naming them; and MUST answer with the player
  id and the status `pending_email_verification`. (Source: `PlayerRoutes.scala` register.)
- **FR-002**: Registration MUST create the realm user first, with the player id as the identity claim,
  and MUST answer `email_already_exists` when the realm holds the address, `password_too_weak` when
  the realm refuses the password, and `brand_not_found` when the realm does not exist; a player MUST
  NOT exist without its realm user, and a realm user created for a player that could not be written
  MUST be removed. (Source: `RegistrationHandlerSpec`; the last clause is a change.)
- **FR-003**: A tag that is not well formed (`^[A-Za-z0-9_.:-]{1,128}$` today; spec 012's token
  grammar once signed tags exist) MUST be dropped, not refused, and the fact MUST carry no tag.
- **FR-004**: Registration MUST record the player's network address and the country it resolves to,
  and the device fingerprint when sent, as personal data (001 FR-023); it MUST send the verification
  email through `engagement`'s send route with a link valid 24 hours; and it MUST produce
  `player_registered` carrying registered time, country, currency, VIP tier (empty), tag, click id
  and bonus code.
- **FR-005**: Email verification MUST be a signed link `players` can verify without a stored token,
  valid 24 hours, and following it MUST set the realm's address verified and the player `active`;
  following it again MUST change nothing. A player MAY ask for the email again at a rate the brand
  sets. (Source: `EmailVerificationTokenStore`.)
- **FR-006**: The public geo lookup (`country` and `dialCode` for the caller's address) MUST be kept
  and MUST answer `null` for both when the lookup fails, never an error. (Source: `geoRoute`.)

**The realm**

- **FR-007**: `players` MUST be the one service holding the realm's administrative credential (001
  FR-021) and MUST use it only for: creating, disabling and removing a user; direct sign-in and
  refresh; revoking a session; listing live sessions; setting an address verified or changing it;
  password reset and change. The credential MUST come from the secret store (ankka 038).
- **FR-008**: The realm's session webhook MUST be a public route served only when the shared secret
  header verifies, MUST always acknowledge, MUST ignore events of other realms and events without a
  session id, MUST count `LOGIN`, and MUST end the player session bound to the realm session on
  `LOGOUT` (reason `logout`) and `SESSION_EXPIRED` (reason `expired`). (Source:
  `KeycloakWebhookRoutes`.)
- **FR-009**: A recurring timer per brand MUST ask the realm for its live sessions and end every open
  player session the realm no longer holds, reason `expired`, and MUST set the brand's active-players
  gauge from the live set; a failure for one brand MUST NOT stop the others. (Source:
  `KeycloakSessionReconciler`.)
- **FR-010**: Sign-in MUST use the realm's direct grant with the brand's frontend client, MUST read
  the player id and the realm session id from the token, MUST refuse `invalid_credentials` and
  `email_not_verified` as the realm reports them, and MUST NOT open a session when the token carries
  no realm session id. (Source: `PlayerRoutes` login.)
- **FR-011**: After the realm accepts the credentials, sign-in MUST refuse a player whose status is
  `suspended`, `self_excluded`, `blocked` or `closed` as `account_restricted` naming the status, MUST
  then ask `responsible-gambling`'s `may-log-in` within the project (spec 010 FR-009) and refuse as
  `cooling_off` naming the end when told, or as `account_restricted` when there is no answer (fail
  closed), and MUST keep the time of the refused attempt. The status itself is local and its read
  cannot fail.
- **FR-012**: A successful sign-in MUST open a player session recording the realm session id, the
  network address, the user agent and the device fingerprint, MUST stamp the last sign-in time, and
  MUST answer the tokens, brand, player id, currency, country and the bonus code still pending from
  registration. `player_logged_in` MUST carry the session id.
- **FR-013**: `players` MUST serve the realm's authenticators a route answering whether a player may
  sign in — the account status from its own state and, from `responsible-gambling`'s `may-log-in`
  within the project (spec 010 FR-009), any break or exclusion — as a public route served only when
  the authenticators' shared secret verifies, within 100 ms at the 99th percentile. The authenticator
  MUST refuse when `players` does not answer in time (fail closed; a change to the plugin's failure
  behaviour). A GAMSTOP refusal the realm's second authenticator makes MUST be reported to `players`
  on the same route, and `players` MUST pass it to `responsible-gambling` within the project (spec
  010 FR-022). The realm talks to `players` and to no other service (spec 001 FR-022); it registers
  no machine under ankka 040. (Change: the authenticators read the twin directly today.)
- **FR-014**: Sign-out MUST end the player session with reason `logout`, revoke the realm session,
  and produce `player_logged_out`; refresh MUST answer `refresh_expired` for an expired, reused or
  revoked refresh token.
- **FR-015**: Every player route MUST refuse a token whose player id is not the id in the path as
  `player_id_mismatch`, and a player token without the id claim as `missing_token` (001 FR-020).

**Sessions**

- **FR-016**: A player session MUST be an entity keyed by its id, holding player, realm session id,
  start, network address, user agent, device fingerprint, status (`active`, `completed`, `expired`,
  `forced`, `suspended`) and end time, and the game sessions opened under it.
- **FR-017**: A game session MUST be an entity keyed by its id, holding player, parent player session,
  game, currency, start, end, duration in whole minutes, status and end reason (`player`, `limit`,
  `forced`, `logout`, `expired`, `suspended`); `players` MUST open one on `game-provider`'s request
  within the project (001 FR-020 service class) and MUST refuse when the account status bars play
  (FR-018) or the player has no active player session (`session_expired`).
- **FR-018**: `players` MUST answer `game-provider` the account status at launch (spec 003 FR-007):
  refused for `suspended`, `self_excluded`, `blocked` or `closed`, naming the status; the KYC play
  gate is `kyc`'s query (spec 009 FR-020) and a break, an exclusion or the session minutes are
  `responsible-gambling`'s `may-play` (spec 010 FR-008), both asked by `game-provider` itself, so
  `players` neither reads nor asks them at launch.
- **FR-019**: `players` MUST keep each player's `SESSION_MINUTES` limit from `rg_limit_applied` facts
  and, at game session open, MUST set a timer per session for the limit less the minutes of the
  player's game sessions started this UTC day (open ones counted to now); the timer MUST end the
  session with reason `limit`; an `rg_limit_applied` fact changing the limit MUST reschedule open
  sessions' timers, ending at once a session already over the new limit; no call is made. (Change:
  replaces the hourly `session_duration_limit_check` scan.)
- **FR-020**: Ending a player session for any reason MUST end every active game session under it with
  the same reason, in order, before `player_logged_out` is produced. (Source: `close_child_sessions`.)
- **FR-021**: A player MUST read their own sessions and one session by id (`session_not_found` for
  another player's), MUST end their own game session (idempotent), and an operator with the brand's
  role MUST end one or all of a player's sessions by force, each audited.
- **FR-022**: `casino.sessions` MUST carry `player_session_started`, `player_session_ended`,
  `game_session_started` (session id, game id) and `game_session_ended` (session id, duration, reason),
  keyed by the session id with the player as subject (001 FR-012), with the field names of the
  original's JSON schemas (001 FR-014).

**Profile, addresses and consents**

- **FR-023**: The profile MUST hold display name, given and family names, date of birth, telephone,
  address (line, city, state or province, postal code, country), language, the jurisdiction's banking
  fields (mobile, province, bank account, institution code, transit number), VIP tier, PotVIP flag,
  and the times of registration, last sign-in and last refused sign-in. Every field 001 FR-023 names
  MUST be personal data; the banking fields and the device fingerprint are added to that list.
- **FR-024**: Date of birth, address line, city, state or province, postal code and country MUST lock
  once set and a later edit of them MUST be ignored without error; mobile, province and the banking
  fields MUST be clearable; a country MUST be refused as `invalid_country` when the brand's allow-list
  is non-empty and does not hold it; an edit with no known field MUST be refused as
  `no_fields_provided`. (Source: `applyLockOnce`, `ClearableProfileFields`.)
- **FR-025**: A successful profile edit MUST produce `player_profile_updated` naming the fields the
  player changed and carrying, as `Personal` values, the current given name, family name, date of
  birth, telephone, address line, city, postal code and country, so `kyc` applies its completeness
  rule from the fact (spec 009 FR-016); an operator's date-of-birth correction MUST require a reason,
  MUST refuse an age under 18 on the day, and MUST be audited. (Source: `BackofficePlayerRoutes`
  date-of-birth.)
- **FR-026**: A player MUST hold a list of email addresses, one primary and each verified or not;
  MUST add an address with a verification link; MUST promote only a verified address, which then
  becomes the realm's sign-in address; MUST NOT remove the primary; and MUST change an address through
  a verified change flow. (Source: `PlayerEmailRoutes`, `PlayerEmailChangeRoutes`.)
- **FR-027**: Password reset MUST send the realm's reset link; password change MUST verify the current
  password with the realm, send a confirmation link, and change the password only when it is followed.
- **FR-028**: Marketing consents (email, SMS) MUST be kept with the time each was given and their
  source; a change MUST produce `player_consent_changed` (a new family, added to 001 FR-011); the
  cookie decision MUST be kept per device id with every field required, replaced on a repeat, written
  by the player and read by operators only. (Source: `/consent`, `/cookie-consent`.)
- **FR-029**: Favourites MUST be a set of game ids per player, read and added by the player;
  recently played MUST be a view over `casino.rounds`; activity history MUST be a paged view over the
  brand's topics in the player-visible categories (transactions, games, bonus, account, RG), never
  notes or messages.
- **FR-030**: A player MUST read a paged inbox of the messages operators sent them, mark one read and
  delete one for themselves, with the unread count computed on its own; a player-deleted message MUST
  stay visible to operators. Every message created, read or deleted MUST produce
  `player_message_created`, `player_message_read` or `player_message_deleted` on `casino.players`
  (keyed by the player, carrying the message id and category), which `engagement` (spec 014 FR-022)
  turns into a push signal; a message's delivered state MUST be advanced from `engagement`'s
  `push_delivered` fact on `casino.push`. `players` MUST serve `engagement` a service route that
  writes a message of the marketing class for a campaign (origin `crm`, the campaign's delivery id as
  the idempotency key), refused as `marketing_suppressed` for a player whose messages an operator
  suppressed, `account_closed` for a closed player, or `player_not_found`; the operator's
  message-suppression flag MUST be carried on `player_consent_changed` so `engagement` knows it from
  facts. (Source: `/messages`, `BackofficePlayerRoutes` messages, `CustomerIOWebhookRoutes`
  `send_message`.)

**Status and operator actions**

- **FR-031**: A player's status MUST be one of `pending_email_verification`, `active`, `suspended`,
  `self_excluded`, `blocked`, `closed`, `erasure_pending`, `erased`, and every transition MUST be an
  event with actor (`operator`, `player`, `system`), actor id, reason and time, kept as the account
  trail the original calls AccountEvent (feature 149). (Source: `PlayerStatus`,
  `AccountEventChangeType`.)
- **FR-032**: Suspend MUST take a reason, end every session with reason `suspended`, revoke the
  realm's sessions, and produce `player_suspended`; it MUST also be served as a service route to `kyc`
  (reason `kyc_window_expired`, idempotent under the player and deadline, spec 009 FR-025). Reinstate
  MUST clear the reason and produce `player_reinstated`. Setting the status `self_excluded`, and back
  to `active` on reinstatement, MUST be a service route served to `responsible-gambling` only (spec
  010 FR-018, FR-020) — no operator excludes a player on `players` — and MUST keep the exclusion
  time under the one name readers use, end every session with reason `suspended`, revoke the realm's
  sessions, and produce `player_self_excluded`. Close MUST end every session, disable the realm user,
  keep the closure time, and produce `player_closed`.
- **FR-033**: VIP tier MUST be set with the previous tier read first so `vip_tier_changed` carries it
  (empty when none); PotVIP MUST be a flag whose change writes an operator note; an operator's
  password reset MUST send the realm's link and MUST NOT be an account-trail event. (Source:
  `BackofficePlayerRoutes`.)
- **FR-034**: Operator notes MUST be personal data under the player, pinnable, visible to operators
  only; the operator's player list, search, profile, wallet summary (from `money.transactions`
  facts), activity, bets and CRM views MUST be views `players` builds, filtered by brand role (001
  FR-020).
- **FR-035**: `casino.players` MUST carry, from `players`: `player_registered`, `player_logged_in`,
  `player_logged_out`, `player_suspended`, `player_reinstated`, `player_self_excluded`,
  `player_closed`, `vip_tier_changed`, `player_consent_changed`, `player_profile_updated`,
  `player_fraud_flagged`, `player_fraud_cleared`, `player_message_created`, `player_message_read`,
  `player_message_deleted`; keyed by the player id as subject, with the field names of the original's
  JSON schemas (previous_value, new_value, actor, actor_id, reason; previous_tier, new_tier;
  session_id). The last seven families are additions to 001 FR-011.

**Multi-account detection and fraud cases**

- **FR-036**: A consumer of `player_registered` MUST match the new player against the brand's players
  by two lookup tokens (ankka 042) kept on every player: the normalised given name, family name and
  date of birth together, and the device fingerprint; MUST flag every match and the new player as
  suspected duplicates linked to each other; MUST open one fraud case naming the signals; and MUST do
  nothing when the brand's kill switch is on or no signal is present. (Source: `MultiAccountDetector`.)
- **FR-037**: A fraud case MUST be reviewed or dismissed by an operator with the brand's role;
  dismissal MUST clear the flag on every linked player and produce `player_fraud_cleared`; an operator
  MUST also clear one player's flag directly. `player_fraud_flagged` MUST be produced on a flag so
  `rewards` withholds bonuses from the fact.

**Erasure and the data-subject response**

- **FR-038**: `players` MUST file a player's erasure in the brand project and in `payments` under the
  brand's erasure right (ankka 040), under one correlation id, with the hold the brand's retention
  rule names (default: the brand's statutory period from closure), MUST refuse to file for a player
  not `closed`, and MUST show `erasure_pending` with the hold's date.
- **FR-039**: When every service has applied the erasure, `players` MUST tell the realm to remove the
  user, assemble the certificates (ankka 042 FR-016) into the response, and set the status `erased`;
  a later registration with the same address MUST be a new player.
- **FR-040**: A player, or an operator for them, MUST obtain an export of profile, consents, sessions,
  activity history and messages, excluding operator notes, in the original's activity-export shape.
  [NEEDS CLARIFICATION: is the activity export the whole data-subject response, or must it also
  carry wallet transactions (002), payments (004) and KYC documents (009)? Regulatory; Sheldon's.]

**Configuration and limits**

- **FR-041**: `brand-config`, `currency-config` and the country allow-list MUST be key value entities
  of the brand, edited by operators with the brand's role and read by the brand's other services over
  HTTP within the project; `brand-config` MUST carry the brand's reporting zone (an IANA zone,
  default UTC), which `reporting` (spec 015 FR-006) and `attribution` (spec 012 FR-016) read so a
  reporting day and the affiliates' `ggr-daily` close on the same instant; the configuration of
  withdrawals, bonuses, responsible gambling and KYC MUST NOT be here.
- **FR-042**: Per-player rate limits MUST be kept where the action lives; `players` keeps none of the
  original's three (bonus claim, withdraw, session launch), which belong to 011, 007 and 003.

**Starting a deposit**

- **FR-043**: `players` MUST serve the player's deposit-initiation route the original's
  `casino-service` serves: given an amount in minor units, a currency and a payment method, it MUST
  ask `kyc`'s deposit check (spec 009 FR-019) and, as a pre-check so the player is told before
  paying, `responsible-gambling`'s `may-deposit` (spec 010 FR-007) with the sums read from the
  wallet's sums route (spec 002 FR-030), refusing with the code and message either answers; then
  MUST start the cashier session on `ingest`'s public route under the brand's merchant key (spec 006,
  spec 001 FR-020) with the customer fields spec 001 FR-018 names, and answer the cashier's redirect.
  The wallet's own `may-deposit` at credit (spec 002 FR-022) is the authoritative check; this one
  only spares the player a refused payment.

### Key Entities

- **Player**: one person's account at one brand: id, status, profile (personal fields), email
  addresses, consents, cookie decisions, favourites, VIP tier, PotVIP, fraud flag and links, the
  account trail, and the lookup tokens for duplicate detection.
- **Player session**: one sign-in to sign-out, bound to a realm session; parent of game sessions.
- **Game session**: one launch to end of one game in one player session, with its duration timer.
- **Fraud case**: suspected duplicate accounts, the signals, the linked players, review or dismissal.
- **Operator note**, **player message**: text an operator wrote about or to a player.
- **Brand config**, **currency config**, **country allow-list**: the brand's own settings.
- **Erasure request**: the brand's filing for a player, its correlation id, hold and certificates.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every recording named below replays against `players` with the original's answers, and
  the Playwright suite's sign-in, registration and account pages pass with only base URLs changed.
- **SC-002**: A player suspended, self-excluded or closed is refused sign-in by the realm's
  authenticator and by `players` within 2 seconds of the transition, in every test of User Story 5.
- **SC-003**: Every open game session of a player ends within 5 seconds of their player session
  ending, for logout, expiry, suspension and closure.
- **SC-004**: `players` answers the realm's authenticator in under 100 ms at the 99th percentile under
  the brand's peak sign-in rate.
- **SC-005**: A duplicate by identity or device is flagged and its case open within 10 seconds of the
  second registration, with no scan of the brand's players.
- **SC-006**: After an erasure, no personal field of the player is readable in `players`, in any brand
  service's view, on any topic, or in `payments`, within ankka 042's bound; every amount and status
  about the player reads unchanged.
- **SC-007**: No sign-in, registration or profile route calls another service in the request path
  except the realm and, at sign-in, `responsible-gambling`'s `may-log-in`; game session open calls
  nothing.

## Changes from the original

- The player id is minted, never derived from brand and email address (Clarifications).
- A realm user whose player could not be written is removed; the original leaves the orphan.
- The affiliate notification call at registration and the attribution properties written back onto
  the player are gone; `attribution` (012) binds from `player_registered`.
- The realm's authenticators ask `players` for the account status instead of reading the twin.
- `KeycloakSessionReconciler` becomes a recurring timer; `close_child_sessions` becomes the session
  entity's own rule; the hourly session-duration scan becomes a timer per game session.
- Multi-account detection matches by lookup token instead of scanning every player of the brand.
- `player_login_failure_lockout` is dropped as a stub (empty function body); the realm's brute-force
  protection stands in.
- The player-facing KYC routes move to spec 009; the configuration entities for withdrawals, bonuses,
  responsible gambling and KYC move to 007, 011, 010 and 009; the game catalogue to 003.
- Four fact families are added: `player_consent_changed`, `player_profile_updated`,
  `player_fraud_flagged`, `player_fraud_cleared`; `kyc` becomes a producer on `casino.players`.
- An operator's self-exclusion moves to `responsible-gambling`'s route (010 FR-019); `players` sets
  the status on its command and produces the fact.
- Sign-in asks `responsible-gambling` for breaks, and the realm's authenticator fails closed when
  `players` does not answer; the original's authenticator admits on a read failure.
- The player's deposit-initiation route moves here from `casino-service` (FR-043) and asks `kyc`
  and `responsible-gambling` before the cashier is opened.
- The verification token stays stateless and replayable within 24 hours, as the original deliberately
  chose; it is kept because verification is idempotent.

## Recordings

Go end-to-end scenarios whose requests reach `players` (001 FR-030; the suite holds 118 functions):
`TestScenario7_CasinoGate3_FullPlayerJourney`, `TestScenario7_CasinoGate3_SessionLifecycle`,
`TestScenario7_CasinoGate3_RGEnforcement` (the sign-in refusal), `TestScenario12_OnboardingGate3_FullChain`,
`TestScenario14_CasinoDeposit_PlayerToWallet` (registration and sign-in steps),
`TestScenario16_BonusGrant_RegistrationTrigger` (the registration fact),
`TestScenario18_MultiAccountDetection_IdentityMatch`, `TestScenario18_MultiAccountDetection_BonusBlock`,
`TestScenario18_MultiAccountDetection_FraudCaseReview`, `TestScenario9_CasinoRF_SessionDurationLimitCheck`.
`TestScenario9_CasinoRF_LoginFailureLockout` records a function that does nothing and is not a parity
test. Playwright: `ssr-authenticated-first-paint.spec.ts`, `activity-tab-game-lookup.spec.ts`, and the
registration and sign-in specs of the frontend suite 001 names.

## Assumptions

- No concurrent-session rule: the original's login carries a TODO for one and brand configuration
  has no switch; two devices may hold two player sessions. [NEEDS CLARIFICATION: should a brand be
  able to require one session at a time, ending the older on a new sign-in? Product; Sheldon's.]
- A player cannot close their own account; the original offers only permanent self-exclusion (010)
  to the player and closure to operators. [NEEDS CLARIFICATION: should a player be able to close
  their account themselves, and does that differ from self-exclusion for retention? Product and
  regulatory; Sheldon's.]
- The realm's authenticators (`eitheror-rg-status-authenticator`, GAMSTOP) are the casino's Keycloak
  plugins and change only their read, from the twin API to `players`' route (FR-013); the GAMSTOP
  call itself and `GAMSTOP_ENABLED` stay as spec 104 of the original decided.
- The verification and password-change emails are sent through `engagement` (014); `players` holds
  no CRM credential.
- The identity lookup token normalises as the original does (trimmed, lower-cased, `|`-joined) so a
  recording's matches reproduce.
- Operator notes and messages stay here as the plan placed them; `engagement` (014) owns outbound
  CRM, not the in-app inbox.

## Dependencies

- Spec 001 (route classes, personal fields, topic contract — amended by FR-028, FR-035 and the `kyc`
  producer), 002 (wallet summary view, closure touches no balance), 003 (opens game sessions, asks
  eligibility), 007 (reads account and identity families; holds an erasure on an open withdrawal),
  009 (produces the KYC families; the deposit check FR-043 asks; the suspend command it calls), 010
  (`may-log-in` at sign-in and for the realm; `may-deposit` as FR-043's pre-check; `rg_limit_applied`
  for the session timer; the status commands for exclusion and reinstatement), 011 (withholds
  bonuses on `player_fraud_flagged`; reads tier and flags), 012 (reads `player_registered` and
  `player_closed`), 014 (sends the emails; honours consent facts), 006 (the cashier route FR-043
  calls under the merchant key).
- ankka 022 (realm verification), 032 (recurring timer, session timers), 038 (realm credential, webhook
  and authenticator secrets), 040 (the erasure right on `payments`), 042 (personal fields, lookup
  tokens, erasure requests and certificates).
- The original's `substrate-iam` realm bootstrap and the Keycloak plugins in `bundle/keycloak-plugins/`.

## Out of scope

- KYC documents, policy and gates (009); responsible-gambling limits, cooling-off and the player's own
  self-exclusion (010); bonuses (011); the game catalogue and launch (003); outbound CRM and the
  Customer.io webhook (014); the partner read API (016); the backoffice frontend.
- Changing the realm's authentication flow beyond where its authenticators read from.
- The Spinvibe migration of players, sessions and the account trail (019).
