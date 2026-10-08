# Feature Specification: Affiliate programme

**Feature Branch**: `013-affiliate-programme`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The shared `programme` project: eitheror's own affiliate network, one per installation, serving every brand. One affiliate account across brands with a membership per brand; facts in from every brand under the attribution protocol (012), immutable commission facts out; the programme issues the signed tags; the portal and the operator surface keep their meaning with brand as a filter; affiliates are data subjects; Spinvibe's single-brand affiliates migrate in."

## Context

Today `affiliate-service` is one service per brand deployment. Every twin namespace it writes is keyed by brand (`affiliates-{brand}`, `affiliate-plans-{brand}`, `affiliate-facts-{brand}-{affiliate}`, `affiliate-payouts-{brand}`), an affiliate belongs to exactly one brand, and the brand an affiliate works for is read from the first `brand_{id}` realm role on their token. The casino tells the service what happened over nine internal `notify/*` routes (registration, deposit, ggr, withdrawal, withdrawal-reversal, pending-approval, bonus-cost, chargeback) and the service turns each into commission under ADR 0042: immutable facts, folded fresh on every read, settled by a payout close that stamps each payable fact with the payout that claimed it. The portal (`/v1/my/{affiliate}/…`) and the operator surface (`/v1/affiliates/{brand}/…`) read that fold. Spec 107 planned a brand selector for an affiliate working several brands; it was never built, and spec 181 put multiple brands out of scope.

This spec moves the programme into a shared ankka project, `programme`, one per installation, that serves every brand and knows a player only by the pseudonym the brand gives it. Facts arrive under the attribution protocol spec 012 defines, from a brand in this installation over its granted `affiliates.attribution` topic, or from a brand elsewhere over the protocol's feed. Everything about the tag, the facts, the click and the pseudonym is 012's definition; this spec restates none of it. Commission, settlement, the portal, the operator surface, fraud flags, notes, audit and affiliate communications stay as they behave today, with brand as a dimension of the ledger and a filter on every read rather than the boundary of the service. The original's behaviour is the specification (constitution I); every departure is listed under *Changes from the original*.

## Clarifications

### Session 2026-10-08

- Q: Is an affiliate an account per brand, or one account across brands? → A: One account per network (the programme is one network), with a *membership* per brand. Applying, approval, rejection, suspension and reactivation are per membership; an account with no active membership can sign in and see nothing but its applications. The old prototype's `AffiliateRegistrationEntity` modelled exactly this.
- Q: Is a commission plan per affiliate, or per affiliate per brand? → A: Per membership. A brand's programme settings name the plan a new membership starts on; a manager with that brand's role may change it. One affiliate can be on CPA at one brand and hybrid at another.
- Q: Is the network tree per brand or per network? → A: Per network. A parent affiliate is a parent at every brand both belong to; override is computed per brand from the sub's earnings at that brand, and a parent earns nothing at a brand where the sub has no membership.
- Q: Where is the ledger kept? → A: One commission account per (affiliate, brand, currency), immutable facts exactly as ADR 0042: NGR inputs (GGR, bonus cost, chargeback, admin, payment and provider fees) and payable facts (CPA, RevShare, override, adjustment) carrying the payout that settled them.
- Q: The original's first-deposit guard is per player per *period*, so a player who deposits in January and again in March pays CPA twice. Bug or choice? → A: A bug in the original (spec 107 FR-015 says CPA is paid on a qualifying *first* deposit). The guard becomes lifetime per (player, brand). Listed under *Changes from the original* for Sheldon to confirm.
- Q: How does the programme receive facts? → A: As spec 012 says: a brand in this installation grants the programme its attribution topic (ankka 040) and the programme consumes it; a brand elsewhere delivers the same facts over the protocol's signed feed or webhook. Every fact is deduplicated by its idempotency id before anything is written.
- Q: Who issues the tag? → A: The programme. It is the network the tag names; it signs the tag spec 012 FR-002 defines for its links and bonus codes, publishes its key document and revocation list, and during the transition a legacy tag document mapping each imported `btag` UUID to its affiliate and link (012 FR-023). A brand fetches those documents; it never calls the programme at registration.
- Q: Who tracks clicks? → A: The brand's `attribution` service (012 FR-006), which emits a `click` fact for each. The programme counts clicks from those facts and serves no click route; the player site reports clicks to the brand, as it does today.
- Q: Who talks to affiliates? → A: The programme. Affiliates are not a brand's players, so engagement (014) is not involved: the programme sends its own transactional email (application received, approved, rejected, payment processed, invitations, password flows), keeps the in-portal notifications, and fires postbacks.
- Q: Postbacks have no retry today. Keep that? → A: No. A postback is retried with backoff for a bounded time and its last outcome is visible to the affiliate and to a manager. Listed under *Changes from the original*.
- Q: Is CSV export of approved payouts in scope? → A: Yes. Finance pays affiliates outside the platform and needs the file (spec 107 FR-047); it now carries the brand and the currency.
- Q: What does the programme know about a player? → A: The pseudonym the brand gave it under the protocol, the brand, the country the brand sent at registration, and what the facts say happened. No name, no email, no player id of the brand's.
- Q: Which realm do affiliates and managers sign in to? → A: The casino's own operator Keycloak, the `eitheror` realm, as today and as spec 198 agreed: the portal admits the `affiliate` role, the operator surface admits operator roles, and `brand_{id}` roles say which brands a manager or an affiliate may see. The programme verifies those tokens through ankka's service identity (022).
- Q: Is the programme audited by each brand's audit service? → A: No. It is a shared project and holds no brand's player data; it keeps its own audit record of every account, membership, plan, payout, adjustment and network action, readable per brand by a manager with that brand's role.

### Session 2026-10-08 (reconciliation)

Settled with specs 001 and 012 once all three existed.

- Q: Which facts does the programme consume, by name? → A: Exactly spec 012 FR-011's fourteen kinds: `click`, `registration`, `first-deposit`, `deposit`, `ggr-daily`, `ggr-adjustment`, `ggr-round` (when a brand turns the stream on), `bonus-cost`, `chargeback`, `withdrawal-requested`, `withdrawal-completed`, `withdrawal-failed`, `withdrawal-reversed`, `player-closed`; with the fields 012 FR-012 names, and no other.
- Q: Does the programme serve a click route? → A: No. Clicks are the brand's (012 FR-006) and arrive as `click` facts; click counts and the click id on every later fact come from them. The programme's postback fills `{clickid}` from the fact.
- Q: How does a brand resolve a legacy `btag`? → A: From the legacy tag document the programme publishes beside its key document (012 FR-023), built from the imported links; the programme answers no per-tag lookups.
- Q: When does the programme forget a player's country and click details? → A: On any `player-closed` fact. Spec 012 FR-027 emits the category `closed` for an erasure and never says erasure, so the programme cannot tell the two apart and treats closure as the signal.
- Q: Where does the player's country come from? → A: The `click` and `registration` facts (012 FR-012); it is a country code and not personal data (001 FR-024).
## Changes from the original

Named here because constitution I keeps the original's behaviour unless a change is stated.

1. **One account, many memberships.** An affiliate is one account with a membership per brand. Today an affiliate is one account per brand, and the brand is read from a realm role.
2. **The first-deposit guard is lifetime.** CPA is paid once per (player, brand), never again in a later period. Today the guard is per period.
3. **Postbacks are retried**, with backoff, for up to 24 hours, and their outcome is visible. Today one attempt, failures swallowed.
4. **The tag is a signed token** issued by the programme (spec 012). Today `btag` is a UUID looked up in a global index. Legacy UUIDs keep working for the transition.
5. **Facts arrive under the protocol**, not over nine internal routes. The programme has no `notify/*` routes.
6. **The ledger is per currency.** Today a payout's currency is "the first currency found among the period's facts", acknowledged in the code as best effort. A commission account is per (affiliate, brand, currency), so no fold ever mixes currencies.
7. **Bonus-code attribution feeds commission.** Today a player who signs up with an affiliate bonus code gets the affiliate id on their profile but no registration record, so their deposits and play are never attributed (a gap the survey of the original found). Under spec 012 the brand emits the registration fact for a bonus-code signup like any other, and the programme treats it the same.
8. **`clickId` reaches the registration postback.** Today the casino never forwards it, so `{clickid}` is empty. Spec 012's registration fact carries it.
9. **The affiliate's payment details are `Personal`** under the affiliate's subject (ankka 042), which closes the ADR 0040 decision-6 exception (free-text IBANs in event-sourced state) rather than carrying it forward.
10. **Dropped:** the "impersonate affiliate" feature (already removed by ADR 0040, kept removed); the per-player deposit-funnel milestone counters that the original acknowledges as racy "analytics-grade" counts are derived from facts in a view rather than written in the hot path.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An affiliate works several brands from one account (Priority: P1)

An affiliate who already sends traffic to one brand applies to a second. They sign in once, see both brands side by side, and every page of the portal — revenue, breakdown, deductions, payouts, players, links, bonus codes, network — answers for one brand or for all of them. Each brand decides on its own whether to approve them, and on what plan.

**Why this priority**: It is the reason the programme becomes a shared project; without it the split buys nothing over one service per brand.

**Independent Test**: Create an account, apply to brands A and B, approve A on a CPA plan and B on a hybrid plan, deliver facts for each, and read the portal filtered by A, by B and by all.

**Acceptance Scenarios**:

1. **Given** an affiliate with an active membership at brand A, **When** they apply to brand B, **Then** brand B shows one pending application, brand A's membership is unchanged, and the affiliate's portal lists A as active and B as pending.
2. **Given** a pending application at brand B, **When** a manager holding brand B's role approves it on brand B's default plan, **Then** the membership is active on that plan, the affiliate is told, and a manager holding only brand A's role cannot see or act on it.
3. **Given** active memberships at A and B with facts at each, **When** the affiliate reads revenue for brand A, **Then** only A's facts are folded; **When** they read with no brand filter, **Then** each brand's figures are listed separately and totals are given per currency, never summed across currencies.
4. **Given** an affiliate suspended at brand A, **When** facts arrive from brand A for players attributed to them, **Then** the facts are recorded against the ledger and no new payable fact is written while the membership is suspended; brand B is unaffected.
5. **Given** an affiliate rejected at brand B, **When** they apply to brand B again, **Then** the application is accepted (as today, a rejected applicant may reapply); **When** an applicant's email already has a pending or active membership at that brand, **Then** the application is refused as a duplicate.

---

### User Story 2 - A brand's facts become commission (Priority: P1)

A brand emits attribution facts; the programme turns each into the commission the affiliate's plan at that brand says, folding the ledger fresh from facts every time so late and out-of-order facts correct themselves and nothing is ever counted twice.

**Why this priority**: It is the money.

**Independent Test**: Deliver a registration, a first deposit, three settled rounds, a bonus cost and a chargeback for one pseudonymous player at one brand, in and out of order and with one duplicate, and compare the ledger with the fold the original's `CommissionEngine` produces for the same inputs.

**Acceptance Scenarios**:

1. **Given** a registration fact naming the programme, an affiliate, a link and a pseudonymous player, **When** it is consumed, **Then** the player is recorded as attributed to that affiliate at that brand with the link, the campaign, the sub-ids, the click and the country, and a second registration fact for the same player at the same brand is ignored as a duplicate.
2. **Given** an attributed player on a CPA or hybrid plan with a minimum deposit threshold, **When** a `first-deposit` fact at or above the threshold arrives, **Then** one CPA fact for the plan's amount is written in the deposit's currency, dated when the deposit occurred; **When** a later deposit fact arrives in any period, **Then** no second CPA fact is written and the deposit is still counted in the player's totals.
3. **Given** a first-deposit fact below the threshold, **When** it is consumed, **Then** no CPA fact is written and the player's first-deposit guard is not set, so a later deposit that meets the threshold pays CPA.
4. **Given** an attributed player on a RevShare or hybrid plan, **When** `ggr-daily`, `ggr-adjustment` (or `ggr-round`), `bonus-cost` and `chargeback` facts arrive, **Then** NGR is folded as GGR minus bonus cost, chargebacks and the brand's admin, payment and provider fee percentages, and a RevShare fact is written for exactly the difference between the commission on the cumulative NGR and what has already been emitted, positive or negative.
5. **Given** a plan without negative carryover, **When** a period's NGR is negative, **Then** that period's RevShare floors at zero and the next period starts fresh; **Given** a plan with negative carryover, **Then** the deficit persists until positive NGR covers it.
6. **Given** a fact whose idempotency id has been consumed before, **When** it arrives again on any channel, **Then** nothing is written and the duplicate is counted.
7. **Given** a fact for a player the programme has no attribution for at that brand, **When** it is consumed, **Then** it is recorded as unattributed for the brand's manager to see and no commission is written.
8. **Given** a `withdrawal-completed` fact and later a `withdrawal-reversed` fact with the same reference, **When** both are consumed, **Then** the player's withdrawal total rises then falls by that amount and never below zero; a `withdrawal-requested` or `withdrawal-failed` fact changes no total, and a reversal without a matching completed withdrawal is ignored.

---

### User Story 3 - A sub-affiliate network earns override (Priority: P1)

A parent affiliate invites sub-affiliates, approves, suspends and reactivates them, edits their plan and override percentage, and earns override on what every affiliate below them earns at each brand. Authority flows downward only.

**Why this priority**: Networks are the affiliates that move volume; spec 193's behaviour is live.

**Independent Test**: Build a three-tier network, deliver facts for a tier-3 player at one brand, and verify each ancestor's override fact independently against the original's `emitOverrideDelta`.

**Acceptance Scenarios**:

1. **Given** a tier-1 with 5% override over a tier-2 with 3% override over a tier-3, **When** tier-3 earns 1,000.00 of commission at brand A in a period, **Then** tier-2 is written an override fact of 30.00 and tier-1 one of 50.00, each scoped to tier-3 as the source, and each recomputed as a delta when tier-3's earnings change.
2. **Given** a parent and a sub who both have memberships at brand A but only the parent at brand B, **When** the sub earns at brand A, **Then** the parent's override is written at brand A; **When** facts for the sub arrive from brand B, **Then** they are unattributed at B and no override is written.
3. **Given** a parent, **When** they invite a sub by email or share an invite link, **Then** the sub's account is created pending under the parent, inherits the parent's plan at each brand the parent names unless the invitation says otherwise, and the parent is notified when the sub applies.
4. **Given** a parent, **When** they approve, suspend or reactivate a sub, or change its plan or override, **Then** it applies within their own subtree and is audited as the parent's action; **When** an operator suspends that sub, **Then** the operator's action stands and the parent sees it but cannot reverse it.
5. **Given** an affiliate, **When** they read their network, **Then** they see every descendant with status, plan, override and revenue for the period, and nothing above or beside them; a request about an affiliate outside their subtree is refused.
6. **Given** a corrupted parent chain that forms a cycle, **When** override is computed, **Then** each ancestor is visited once and the computation ends.

---

### User Story 4 - A period closes and affiliates are paid (Priority: P1)

A manager closes a period for a brand. Every unsettled payable fact that occurred on or before the period's end is claimed by that affiliate's payout for the period, in that currency; the payout is approved, rejected or paid; and finance exports the approved ones.

**Why this priority**: Exactly-once settlement is the regulatory and commercial core of ADR 0042.

**Independent Test**: Close a period twice, approve, deliver a late fact, close again, and verify the payout reverted to pending with the fact included; mark paid, deliver another late fact, close the next period, and verify it lands there.

**Acceptance Scenarios**:

1. **Given** unsettled payable facts for an affiliate at brand A in EUR, **When** a manager closes the period for brand A, **Then** every unsettled payable fact that occurred on or before the period's end is stamped with the payout for (affiliate, brand A, EUR, period), the payout's total is the fold of everything stamped with it, and the payout is pending.
2. **Given** an affiliate whose claimed total is below their minimum payout threshold, **When** the period closes, **Then** nothing is stamped, no payout is written, and the facts carry forward to the next close.
3. **Given** a pending payout, **When** a manager approves it, optionally overriding the override-commission figure, **Then** it is approved with the figure recorded; **When** a late fact is swept into an approved payout by a later close, **Then** the payout reverts to pending and the affiliate's previous approval is recorded as superseded.
4. **Given** a paid payout, **When** a late fact arrives that would have belonged to it, **Then** the payout is unchanged and the fact is claimed by the next close, dated when it occurred.
5. **Given** an affiliate with facts in EUR and in USD at brand A, **When** the period closes, **Then** two payouts are written, one per currency, and the export lists both.
6. **Given** approved payouts for a period, **When** finance exports them, **Then** the file lists affiliate, display name, email, payment details, brand, currency, period, CPA, RevShare, override, adjustments, total, who approved and when; **When** the export is taken, **Then** it is audited as a read of payment details.
7. **Given** a payout, **When** a transition is requested that the status machine does not allow (paid to pending, rejected to paid), **Then** it is refused naming the current status.

---

### User Story 5 - Links, bonus codes and invite links carry the programme's tag (Priority: P2)

An affiliate creates tracking links, bonus codes and invite links; each carries a tag the programme signs, and a brand verifies without asking the programme. Clicks land at the brand and reach the programme as facts.

**Why this priority**: Attribution starts here, but the flow runs on legacy UUIDs until 012's transition ends.

**Independent Test**: Create a link, click it, register a player at the brand with its tag, and verify the registration fact carries the link, campaign and click.

**Acceptance Scenarios**:

1. **Given** an affiliate with an active membership at brand A, **When** they create a tracking link for A with a campaign and sub-ids, **Then** the link exists with a signed tag naming the programme, the affiliate, the link and the sub-ids, and the brand's landing URL carries it as `btag`.
2. **Given** a brand that recorded a click on the programme's link, **When** its `click` fact arrives, **Then** the programme records the click against the link with the click id, device type and country the fact carries, and the link's click count rises by one.
3. **Given** a brand holding the programme's published key document, **When** a player registers with a tag, **Then** the brand verifies it without calling the programme; **When** the tag belongs to a link on the programme's revocation list, **Then** the brand binds no one and the programme receives no fact for the affiliate.
4. **Given** an affiliate, **When** they create a bonus code for brand A, **Then** the code is unique at brand A (case-insensitive), two concurrent creations of the same text yield one, and revoking it frees the text for later reuse.
5. **Given** a legacy `btag` UUID from Spinvibe, **When** the programme publishes its legacy tag document during the transition, **Then** the document maps it to the affiliate and the link it was imported against; after the transition the document is withdrawn.
6. **Given** an affiliate's link, **When** they read it, **Then** click counts come from the `click` facts received, never from a counter on the account.

---

### User Story 6 - Managers run the programme per brand and across brands (Priority: P2)

A programme manager applies, approves and rejects applications, manages plans, settings, managers, adjustments, fraud flags, notes and creatives for the brands their roles cover, sees the full ledger, closes periods, and reads a read-only view of what an affiliate sees.

**Why this priority**: Everything a manager does today must still be possible; most of it is unchanged.

**Independent Test**: Replay the operator-route recordings from ankka-port against the programme with the brand carried as a filter.

**Acceptance Scenarios**:

1. **Given** a manager with brand A's role only, **When** they list accounts, revenue, payouts or fraud flags, **Then** only brand A's memberships and facts are listed; a request naming brand B is refused.
2. **Given** a manager with roles for A and B, **When** they read revenue with no brand filter, **Then** figures are listed per brand and per currency.
3. **Given** a manager, **When** they create or change a commission plan for brand A (CPA, RevShare, hybrid; CPA amount; RevShare percentage; minimum deposit; negative carryover), **Then** it applies to that brand's memberships that name it, from the next fact onward, and the change is audited.
4. **Given** a manager, **When** they enter a credit or debit adjustment for an affiliate at brand A in a currency with a reason, **Then** an adjustment fact is written, debits negative, and it is swept by the next close like any payable fact.
5. **Given** the programme's fraud thresholds for brand A, **When** a player's bonus cost or chargebacks cross them, **Then** a fraud flag is raised once per (player, brand, reason) for the manager to review; **When** a manager dismisses it, **Then** the affiliate is told the flag was reviewed and dismissed.
6. **Given** a manager, **When** they open an affiliate's read-only view, **Then** they see the dashboard, stats, commission, links, payouts and notifications the affiliate would see, and can change nothing from there.
7. **Given** a manager with the right role, **When** they add a programme manager for brand A, reset their password or revoke them, **Then** the manager is provisioned in the realm with `affiliate_manager` and brand A's role and the action is audited.
8. **Given** a brand's programme page (terms, contact, payout cycle, public description), **When** a manager changes it, **Then** affiliates applying to that brand see the new text.

---

### User Story 7 - The programme talks to affiliates (Priority: P2)

Affiliates are told what happened to them: application received, approved, rejected, payment processed, a sub applied, a flag dismissed, an invitation, a password reset; by in-portal notification, by email, and by postback to their own system.

**Why this priority**: Nothing else owns affiliate communications once the programme leaves the brand.

**Independent Test**: Approve an application, close and pay a period, and verify a notification, an email send and a postback for each.

**Acceptance Scenarios**:

1. **Given** an affiliate with a postback URL, **When** a registration, first deposit, deposit or commission event occurs for their player, **Then** a postback is sent with the event, the click id, the payout amount and the status substituted; **When** it fails, **Then** it is retried with backoff for up to 24 hours and the last outcome is visible to the affiliate and a manager.
2. **Given** an application, approval, rejection, payout paid, invitation or password reset, **When** it happens, **Then** an in-portal notification is written and a transactional email is sent with the brand's sender configuration, and a failed send never blocks the action.
3. **Given** a sub applies under a parent, **When** the application is recorded, **Then** the parent is notified that an approval is pending.
4. **Given** notifications, **When** the affiliate reads them or marks all read, **Then** they are marked read for that affiliate only.

---

### User Story 8 - An affiliate is a data subject (Priority: P2)

An affiliate's name, email, company, website and payment details are personal data under the affiliate's subject; a player exists in the programme only as a pseudonym. An affiliate who leaves can be erased once the records finance must keep have aged out.

**Why this priority**: Constitution V, and the ADR 0040 exception this closes.

**Independent Test**: Erase a closed affiliate with a hold, let it lapse, and verify payouts still total and the identity fields read as erased.

**Acceptance Scenarios**:

1. **Given** an affiliate account, **When** its identity and payment fields are written, **Then** they are personal values under the affiliate's subject; a database dump holds no readable name, email or payment details.
2. **Given** a closed account, **When** a manager files its erasure, **Then** the request carries a hold until the end of the retention period finance names, and when it lapses the account's identity fields read as erased while every fact, payout amount and audit entry remains.
3. **Given** facts and the players view, **When** a manager or affiliate reads a player, **Then** they see the pseudonym the brand gave, the country and the figures, and nothing that names the player.
4. **Given** a brand closes or erases a player, **When** the `player-closed` fact arrives, **Then** the programme forgets the pseudonym's country and click details and keeps the facts' amounts; it cannot tell an erasure from a closure, and does not need to.

---

### User Story 9 - Spinvibe's affiliates move in (Priority: P3)

Today's single-brand affiliates, their plans, links, bonus codes, invite links, creatives, notes, flags, audit and the whole commission ledger become the programme's, with Spinvibe as their one membership, and nothing owed changes.

**Why this priority**: Needed for the cutover (019), not for the pilot brand.

**Independent Test**: Import a recorded Spinvibe affiliate data set and compare every affiliate's revenue, breakdown and payouts for every period with the original's answers.

**Acceptance Scenarios**:

1. **Given** Spinvibe's affiliate accounts, **When** they are imported, **Then** each becomes one account with one active, pending, suspended or rejected membership at Spinvibe on its plan, with its parent, override, threshold and postback URL, and the realm user is unchanged.
2. **Given** the commission ledger, **When** it is imported, **Then** every fact keeps its type, amount, currency, occurred-at, recorded-at, source affiliate and the payout that settled it; every payout keeps its status, figures and approval; and a fold of the imported facts equals the original's revenue for every affiliate and period.
3. **Given** tracking links and bonus codes, **When** they are imported, **Then** each keeps its legacy `btag` for the transition and gains a signed tag.
4. **Given** the import, **When** a fact arrives from Spinvibe for a player attributed before the import, **Then** it is attributed because the import carried the player's pseudonym and attribution.
5. **Given** the import ran, **When** it is run again, **Then** nothing is duplicated.

---

### Edge Cases

- A fact arrives for a brand the programme has no membership record for from that affiliate: recorded as unattributed with the reason, never silently dropped.
- A fact names an affiliate and link that do not match the programme's record of the player's attribution (the brand's view and the programme's disagree): the programme's attribution for the player at that brand wins, the disagreement is recorded for a manager, and commission follows the programme's record.
- A plan is changed mid-period: the next fold uses the new plan for the whole fold of that period, as the original does (the fold is stateless), and the change is audited with the time.
- A brand's fee percentages change mid-period: fee facts already written stay; later rounds use the new percentages.
- A parent's override is changed: subsequent recomputation emits the delta against what was already emitted, positive or negative, exactly as a late chargeback does.
- An affiliate is a member of a brand that is retired: the membership is frozen, its ledger stays readable, its unpaid facts can still be closed and paid.
- Two closes for the same brand and period run at once: the payout id is deterministic per (affiliate, brand, currency, period), so both stamp the same payout and the second recomputes the same total.
- A fact's currency has no plan amount (a CPA plan priced in EUR, a first deposit in USD): the CPA fact is written in the plan's currency at the plan's amount; the deposit itself is counted in its own currency.
- An invitation token is used twice: the second use is refused and the parent is told.
- The programme's signing key is rotated: tags signed by the previous key verify until its published expiry; links are not re-issued.
- A manager loses a brand role: their next request for that brand is refused; nothing they did is undone.
- The feed for a brand elsewhere delivers facts out of order by days: the fold is by occurred-at, so the ledger is the same as if they had arrived in order.

## Requirements *(mandatory)*

### Functional Requirements

**Accounts and memberships**

- **FR-001**: The programme MUST keep one affiliate account per person or company, identified within the programme, with display name, contact, company, website, traffic source, accepted terms version, payment details, postback URL, minimum payout threshold, an optional parent affiliate and an override percentage.
- **FR-002**: An account MUST hold a membership per brand with status pending, active, suspended or rejected, the plan at that brand, the application details and the terms version accepted for that brand; status transitions MUST be pending→active, pending→rejected, active↔suspended, and rejected→pending on reapplication, each audited with the actor.
- **FR-003**: A public application for a brand MUST create the account when the email is new and otherwise add a membership to the existing account; it MUST refuse an email with a pending or active membership at that brand.
- **FR-004**: An affiliate MUST see and act only within their own account and subtree; a manager MUST see and act only on brands whose role they hold; the programme MUST decide admission by realm roles (`affiliate`, `affiliate_manager`, operator roles, `brand_{id}`), never by the token's actor type.
- **FR-005**: Every account, membership, plan, link, code, payout, adjustment, flag, note and network action MUST be audited with actor, time, before and after, readable per brand by a manager with that brand's role and, for network actions, by the ancestor who acted.

**Plans and settings**

- **FR-006**: A commission plan MUST belong to one brand and carry type (CPA, RevShare, hybrid), CPA amount and currency, RevShare percentage, minimum deposit for CPA, and whether negative NGR carries over; the brand's settings MUST name the default plan for new memberships.
- **FR-007**: A brand's settings MUST carry admin, payment and provider fee percentages, the bonus-cost and chargeback fraud thresholds, the payout cycle and the reporting time zone; a brand's programme page MUST carry the public terms, contact and description applicants see.

**Facts in**

- **FR-008**: The programme MUST consume the fourteen attribution fact kinds spec 012 FR-011 defines, with the fields 012 FR-012 names and no other, from a brand's granted `affiliates.attribution` topic in this installation and from the protocol's feed or webhook for a brand elsewhere, verifying the sender, and MUST treat the two channels identically after verification.
- **FR-009**: The programme MUST deduplicate every fact by its idempotency id before writing anything, on every channel, and MUST count duplicates per brand.
- **FR-010**: A `registration` fact MUST create the player's attribution at that brand (affiliate, link, campaign, sub-ids, click id, country, occurred-at) once, every field taken from the fact; a later `registration` fact for the same player at the same brand MUST be ignored. A `click` fact MUST be recorded against its link with the click id, device type and country it carries.
- **FR-011**: A fact for a player without attribution at that brand, for a revoked link, or for an affiliate with no membership at that brand MUST be recorded as unattributed with its reason and MUST write no commission.

**Commission**

- **FR-012**: The ledger MUST be immutable facts per (affiliate, brand, currency): NGR inputs (GGR, bonus cost, chargeback, admin fee, payment fee, provider fee) and payable facts (CPA, RevShare, override, adjustment), each with amount in minor units, occurred-at, recorded-at, period, an optional source affiliate, and the payout that settled it when settled. A fact MUST never change except to record its settlement.
- **FR-013**: A first-deposit fact at or above the plan's minimum deposit MUST write one CPA fact in the plan's currency; the first-deposit guard MUST be lifetime per (player, brand); a first deposit below the threshold MUST not set the guard.
- **FR-014**: Every fact that moves NGR MUST trigger a fresh fold of the affiliate's facts at that brand and currency under the plan's window (lifetime or period) and MUST write a RevShare fact for the delta between commission on the cumulative NGR and the RevShare already emitted, positive or negative, zero omitted; admin, payment and provider fee facts MUST be written per GGR fact (`ggr-daily`, `ggr-adjustment` or `ggr-round`) at the brand's percentages without flooring.
- **FR-015**: After any change to an affiliate's payable earnings in a period, every ancestor MUST be written an override fact for the delta between their override percentage of the source's period earnings (CPA, RevShare, adjustment) and the override already emitted for that source, scoped per (ancestor, source), visiting each ancestor once.
- **FR-016**: A suspended membership MUST still receive NGR inputs and MUST receive no new payable facts until reactivated; on reactivation the next fold MUST catch up.
- **FR-017**: Every read of revenue, breakdown, deductions or a player's figures MUST be a fold of the facts, served from a view that is rebuilt from the facts and never accumulated; period selection MUST accept a month, a month range, a day range in the brand's reporting zone, or all time, as today.

**Settlement**

- **FR-018**: Closing a period for a brand MUST, for every membership at that brand and every currency, claim every unsettled payable fact that occurred on or before the period's end for the payout identified by (affiliate, brand, currency, period), compute the total as the fold of everything that payout claims, and write the payout as pending; a total below the affiliate's minimum threshold MUST claim nothing.
- **FR-019**: A payout MUST move pending→approved, pending→rejected, approved→paid, and approved→pending when a close changes its total; any other transition MUST be refused. Approval MAY record an override-commission figure, zero included. A paid payout MUST never change.
- **FR-020**: Closing MUST be idempotent: a repeated close for the same brand and period MUST re-claim nothing already claimed and recompute the same totals.
- **FR-021**: A manager MUST be able to export approved payouts for a brand and period as a file carrying affiliate, display name, email, payment details, brand, currency, period, CPA, RevShare, override, adjustments, total, approver and time; the export MUST be audited as a read of payment details.
- **FR-022**: A manager MUST be able to write a credit or debit adjustment for an affiliate at a brand in a currency with a reason; it MUST be a payable fact.

**Tags, links, codes and clicks**

- **FR-023**: The programme MUST issue the signed tag spec 012 FR-002 defines for every tracking link and bonus code, naming the programme, the affiliate, the link or code and the sub-ids, MUST publish the key document and the revocation list 012 FR-023 defines, and MUST revoke a link, code or affiliate by listing it there; it MUST define nothing about the tag that 012 does not.
- **FR-024**: The programme MUST serve no click route; it MUST record each `click` fact a brand emits against the link it names, and click counts MUST be a view over those facts.
- **FR-025**: A bonus code MUST be unique per brand regardless of case; concurrent creation of the same text MUST yield one; revocation MUST free the text.
- **FR-026**: During the transition spec 012 defines, the programme MUST publish the legacy tag document 012 FR-023 defines, mapping every imported `btag` UUID to the affiliate and link it was imported against, and MUST withdraw it when the transition ends; it MUST answer no per-tag lookups.
- **FR-027**: An affiliate MUST be able to create invite links and email invitations for subs; an invitation MUST be usable once, MUST create the sub pending under the inviter, and MUST inherit the inviter's plan at each named brand unless the invitation names another.

**Network**

- **FR-028**: The network MUST be one tree per programme; a sub's parent MUST be fixed at creation and changeable only by a manager; cycles MUST be refused on write and tolerated on read.
- **FR-029**: An ancestor MUST be able to approve, suspend and reactivate a sub's memberships, change its plan at a brand where the ancestor also holds a membership, change its override, and edit its account fields, all audited as the ancestor's action; an operator's suspension MUST stand over an ancestor's reactivation.
- **FR-030**: An affiliate MUST be able to read their subtree with status, plan, override and revenue per period and brand, and network health counts, and MUST be refused anything outside it.

**Managers**

- **FR-031**: A manager with the right operator role MUST be able to list, add, reset and revoke programme managers for a brand, provisioning them in the realm with `affiliate_manager` and the brand's role.
- **FR-032**: A manager MUST be able to raise, list and dismiss fraud flags; the programme MUST raise a flag once per (player, brand, reason) when the brand's thresholds are crossed; a dismissed flag MUST notify the affiliate.
- **FR-033**: A manager MUST be able to read an affiliate's dashboard, stats, commission, links, payouts and notifications read-only, and MUST have no way to act as the affiliate.
- **FR-034**: A manager MUST be able to keep notes on an affiliate per brand and manage creatives per brand, as today.

**Communications**

- **FR-035**: The programme MUST write in-portal notifications and send transactional email for application received, approved and rejected, payout paid, sub pending approval, flag dismissed, manager and affiliate invitations, password reset and password changed, using the brand's sender configuration; a failed send MUST never block the action.
- **FR-036**: The programme MUST send an affiliate's postback for registration, first deposit, deposit and commission events with the event, the click id the triggering fact carries, payout amount and status substituted, MUST retry with backoff for up to 24 hours, and MUST show the last outcome per event to the affiliate and a manager.

**Personal data**

- **FR-037**: An affiliate's name, email, company, website, traffic source and payment details MUST be personal values under the affiliate's subject; a manager MUST be able to file the affiliate's erasure with a hold to the end of the retention period finance names; after erasure every amount, fact, payout and audit entry MUST remain with identity fields erased.
- **FR-038**: The programme MUST hold a player only as the pseudonym the brand gave it (012 FR-013), the brand, country, click details and figures, never a player id, and MUST forget country and click details on the player's `player-closed` fact.

**Migration**

- **FR-039**: An import from Spinvibe MUST carry accounts, memberships, plans, links with their legacy `btag`, bonus codes, invite links, creatives, notes, audit, fraud flags, settings, the programme page, player attributions with pseudonyms, every commission fact with its settlement, and every payout; MUST be repeatable without duplication; and MUST produce the same fold as the original for every affiliate and period.

### Key Entities

- **Affiliate account**: one person or company in the programme; identity and payment fields personal; parent and override; memberships.
- **Membership**: an account's standing at one brand: status, plan, application, terms.
- **Commission plan**: a brand's terms: type, CPA amount and currency, RevShare percentage, minimum deposit, carryover window.
- **Brand settings** and **programme page**: a brand's fees, thresholds, payout cycle, zone, default plan; its public text.
- **Attribution**: a pseudonymous player's binding to an affiliate at a brand: link, campaign, sub-ids, click, country, registered-at; first-deposit guard; totals.
- **Commission account**: the immutable facts for one (affiliate, brand, currency).
- **Fact**: type, amount, occurred-at, recorded-at, period, source affiliate, settled-by.
- **Payout**: (affiliate, brand, currency, period), status, figures, approver, override figure.
- **Tracking link**, **bonus code**, **invite link**, **creative**: an affiliate's instruments at a brand; link and code carry a signed tag and, when imported, a legacy tag.
- **Click**: a `click` fact received from a brand, recorded against its link.
- **Fraud flag**, **note**, **notification**, **postback attempt**, **audit entry**.
- **Programme manager**: a realm user with `affiliate_manager` and brand roles.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every ankka-port recording of the original's portal and operator routes replays against the programme with the same observable outcome, the brand carried as a filter.
- **SC-002**: For every recorded fact sequence, the programme's ledger fold equals the original `CommissionEngine`'s fold to the minor unit, except where *Changes from the original* says otherwise, and those differences are each covered by a scenario.
- **SC-003**: One affiliate holds active memberships at two brands and the portal answers correctly for each and for both, with no figure summed across currencies.
- **SC-004**: A fact delivered twice on the same channel, or once on each of two channels, writes exactly one set of ledger entries.
- **SC-005**: A fact is reflected in the affiliate's revenue view within 10 seconds of its delivery under the installation's normal load.
- **SC-006**: Across a thousand closes with interleaved late facts, no payable fact is ever claimed by two payouts and no unsettled fact older than the latest closed period remains unclaimed.
- **SC-007**: A database dump of the programme holds no readable affiliate name, email or payment details, and no brand player id.
- **SC-008**: Spinvibe's import reproduces every affiliate's revenue and payouts for every period, and runs twice without duplication.
- **SC-009**: A failed postback is retried and its last outcome is visible within one minute of the final attempt.

## Assumptions

- The programme is one network per installation; a second network (a partner's programme) is a separate consumer of spec 012, not a second instance of this spec.
- Affiliates and managers sign in to the casino's operator Keycloak, `eitheror` realm, with the roles spec 198 agreed; the programme verifies those tokens through ankka's service identity (022). Keycloak stays external (constitution scope).
- Spec 012 defines the fact kinds and fields, the tag, the keys, the click, the pseudonym, the channels and the transition; this spec consumes them, issues the tag and publishes the documents, and restates none of the definitions. The protocol's feed authenticates a brand elsewhere; the programme trusts a brand's statement of attribution only when it matches its own record.
- Transactional email goes through Customer.io with the brand's sender configuration, as today; the programme holds the credentials in its secret store (ankka 038).
- The old journal and the brand's registration records remain the source for the import; the import is a one-off job the migration (019) sequences.
- The pilot brand launches with the programme empty; the import is for Spinvibe's cutover.

## Dependencies

- ankka 040 for the brand's topic grant to the programme and the protocol's outside-machine channel; 042 for personal values and erasure; 038 for the email and feed credentials; 022 for realm token verification.
- Spec 012 (attribution protocol) for everything the programme consumes and the tag it issues.
- Spec 014 (engagement) is explicitly not a dependency: the programme owns affiliate communications.
- Spec 019 (migration) sequences the import.

## Out of scope

- A second network, a third-party network's own portal, or reselling the programme to other operators (see the open question).
- Changing commission mathematics beyond the lifetime first-deposit guard and per-currency ledgers.
- Frontends: the affiliate portal and the backoffice's affiliate pages change base URLs and gain a brand filter; their design is not this spec's.
- Paying affiliates: the programme records and exports; payment happens outside, as today.

## Open Questions

- **Multi-currency payout.** [NEEDS CLARIFICATION: with the ledger per currency, is a payout per currency (recommended: no conversion, finance pays each) or does the affiliate name one payout currency and the programme convert at a recorded rate at close?]
- **Who the programme serves.** [NEEDS CLARIFICATION: is the programme only eitheror's brands, or also brands on other installations (the feed channel makes it possible) as a hosted affiliate network — which changes who may create a brand in the programme and who pays?]
- **Lifetime first-deposit guard.** [NEEDS CLARIFICATION: confirm the change from per-period to lifetime CPA per (player, brand); if the per-period behaviour was a deliberate "re-acquisition" deal, it becomes a plan option instead.]
