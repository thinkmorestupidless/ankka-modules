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

### Session 2026-10-08 (reconciliation 2)

Settled with specs 008–011 once they existed.

- Q: How does a brand's `rewards` match an affiliate's bonus code? → A: From the code document the
  programme publishes per brand (FR-023a), fetched as a public client like the key document; the
  programme answers no per-code lookups and a brand calls nothing at claim time (spec 011 FR-019,
  FR-021).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An affiliate works several brands from one account (Priority: P1)

An affiliate who already sends traffic to one brand applies to a second. They sign in once, see both brands side by side, and every page of the portal — revenue, breakdown, deductions, payouts, players, links, bonus codes, network — answers for one brand or for all of them. Each brand decides on its own whether to approve them, and on what plan.

**Why this priority**: It is the reason the programme becomes a shared project; without it the split buys nothing over one service per brand.

**Independent Test**: Create an account, apply to brands A and B, approve A on a CPA plan and B on a hybrid plan, deliver facts for each, and read the portal filtered by A, by B and by all.

**Acceptance Scenarios**:

- added `features/programme/memberships.feature`: applying to a second brand leaves the first membership unchanged and shows the second as pending
- added `features/programme/memberships.feature`: a manager for a brand approves an application on that brand's default plan and a manager for another brand cannot see it
- added `features/programme/memberships.feature`: revenue read for one brand folds only that brand's facts
- added `features/programme/memberships.feature`: revenue read with no brand filter lists each brand separately with totals per currency
- added `features/programme/memberships.feature`: a suspended membership still records facts and writes no new payable fact while another brand is unaffected
- added `features/programme/memberships.feature`: a rejected applicant may apply again
- added `features/programme/memberships.feature`: an application from an email with a pending or active membership at that brand is refused as a duplicate

---

### User Story 2 - A brand's facts become commission (Priority: P1)

A brand emits attribution facts; the programme turns each into the commission the affiliate's plan at that brand says, folding the ledger fresh from facts every time so late and out-of-order facts correct themselves and nothing is ever counted twice.

**Why this priority**: It is the money.

**Independent Test**: Deliver a registration, a first deposit, three settled rounds, a bonus cost and a chargeback for one pseudonymous player at one brand, in and out of order and with one duplicate, and compare the ledger with the fold the original's `CommissionEngine` produces for the same inputs.

**Acceptance Scenarios**:

- added `features/programme/commission.feature`: a registration fact records the player as attributed to the affiliate at that brand
- added `features/programme/commission.feature`: a second registration fact for the same player at the same brand is ignored as a duplicate
- added `features/programme/commission.feature`: a first deposit at or above the plan's minimum deposit writes one CPA fact in the deposit's currency
- added `features/programme/commission.feature`: a later deposit in any period writes no second CPA fact and is still counted in the player's totals
- added `features/programme/commission.feature`: a first deposit below the minimum deposit writes no CPA fact and leaves the first-deposit guard unset
- added `features/programme/commission.feature`: GGR, bonus cost and chargeback facts fold into NGR and a RevShare fact for the delta
- added `features/programme/commission.feature`: without negative carryover a negative period floors RevShare at zero and the next period starts fresh
- added `features/programme/commission.feature`: with negative carryover a deficit persists until positive NGR covers it
- added `features/programme/commission.feature`: a fact whose id was consumed before writes nothing and is counted as a duplicate
- added `features/programme/commission.feature`: a fact for a player without attribution at that brand is recorded as unattributed and writes no commission
- added `features/programme/commission.feature`: a completed withdrawal then its reversal raise and lower the player's withdrawal total, never below zero
- added `features/programme/commission.feature`: a withdrawal requested or failed changes no total
- added `features/programme/commission.feature`: a reversal without a matching completed withdrawal is ignored

---

### User Story 3 - A sub-affiliate network earns override (Priority: P1)

A parent affiliate invites sub-affiliates, approves, suspends and reactivates them, edits their plan and override percentage, and earns override on what every affiliate below them earns at each brand. Authority flows downward only.

**Why this priority**: Networks are the affiliates that move volume; spec 193's behaviour is live.

**Independent Test**: Build a three-tier network, deliver facts for a tier-3 player at one brand, and verify each ancestor's override fact independently against the original's `emitOverrideDelta`.

**Acceptance Scenarios**:

- added `features/programme/network.feature`: each ancestor is written an override fact for its percentage of the source's earnings, recomputed as a delta
- added `features/programme/network.feature`: a parent earns override at a brand where both hold a membership
- added `features/programme/network.feature`: a parent earns nothing at a brand where the sub has no membership
- added `features/programme/network.feature`: an invitation creates the sub pending under the parent with the parent's plan unless it says otherwise
- added `features/programme/network.feature`: a parent's action on a sub applies within their own subtree and is audited as the parent's
- added `features/programme/network.feature`: an operator's suspension of a sub stands over the parent
- added `features/programme/network.feature`: an affiliate sees every descendant and nothing above or beside them
- added `features/programme/network.feature`: a request about an affiliate outside the subtree is refused
- added `features/programme/network.feature`: a parent chain that forms a cycle is visited once and the computation ends

---

### User Story 4 - A period closes and affiliates are paid (Priority: P1)

A manager closes a period for a brand. Every unsettled payable fact that occurred on or before the period's end is claimed by that affiliate's payout for the period, in that currency; the payout is approved, rejected or paid; and finance exports the approved ones.

**Why this priority**: Exactly-once settlement is the regulatory and commercial core of ADR 0042.

**Independent Test**: Close a period twice, approve, deliver a late fact, close again, and verify the payout reverted to pending with the fact included; mark paid, deliver another late fact, close the next period, and verify it lands there.

**Acceptance Scenarios**:

- added `features/programme/settlement.feature`: a close claims every unclaimed payable fact up to the period's end for a pending payout
- added `features/programme/settlement.feature`: a close below the affiliate's minimum payout threshold claims nothing and carries the facts forward
- added `features/programme/settlement.feature`: approving a pending payout records the override figure the manager chose
- added `features/programme/settlement.feature`: a late fact swept into an approved payout reverts it to pending and supersedes the approval
- added `features/programme/settlement.feature`: a late fact for a paid payout leaves it unchanged and is claimed by the next close
- added `features/programme/settlement.feature`: facts in two currencies close as two payouts
- added `features/programme/settlement.feature`: an export of approved payouts lists every figure and is audited as a read of payment details
- added `features/programme/settlement.feature`: a payout transition the status machine does not allow is refused naming the current status

---

### User Story 5 - Links, bonus codes and invite links carry the programme's tag (Priority: P2)

An affiliate creates tracking links, bonus codes and invite links; each carries a tag the programme signs, and a brand verifies without asking the programme. Clicks land at the brand and reach the programme as facts.

**Why this priority**: Attribution starts here, but the flow runs on legacy UUIDs until 012's transition ends.

**Independent Test**: Create a link, click it, register a player at the brand with its tag, and verify the registration fact carries the link, campaign and click.

**Acceptance Scenarios**:

- added `features/programme/links.feature`: a new link carries a tag signed by the programme naming the affiliate, the link and the sub-ids
- added `features/programme/links.feature`: a click fact from a brand is recorded against its link and raises the link's click count
- added `features/programme/links.feature`: a brand holding the programme's key document verifies a tag without calling the programme
- added `features/programme/links.feature`: a tag for a link on the programme's revocation list binds no one and the programme receives no fact for the affiliate
- added `features/programme/links.feature`: a bonus code is unique at a brand regardless of case
- added `features/programme/links.feature`: two concurrent creations of the same bonus code text yield one code
- added `features/programme/links.feature`: revoking a bonus code frees its text for later reuse
- added `features/programme/links.feature`: the legacy tag document maps each imported legacy tag to its affiliate and link during the transition period
- added `features/programme/links.feature`: the legacy tag document is withdrawn after the transition period
- added `features/programme/links.feature`: a link's click count comes from click facts and never from a counter on the account

---

### User Story 6 - Managers run the programme per brand and across brands (Priority: P2)

A programme manager applies, approves and rejects applications, manages plans, settings, managers, adjustments, fraud flags, notes and creatives for the brands their roles cover, sees the full ledger, closes periods, and reads a read-only view of what an affiliate sees.

**Why this priority**: Everything a manager does today must still be possible; most of it is unchanged.

**Independent Test**: Replay the operator-route recordings from ankka-port against the programme with the brand carried as a filter.

**Acceptance Scenarios**:

- added `features/programme/managers.feature`: a manager for one brand lists only that brand's memberships and facts
- added `features/programme/managers.feature`: a manager's request naming a brand their roles do not cover is refused
- added `features/programme/managers.feature`: a manager for several brands reads revenue per brand and per currency
- added `features/programme/managers.feature`: a plan created or changed applies to the memberships that name it from the next fact onward and is audited
- added `features/programme/managers.feature`: a manager's adjustment is a payable fact, negative for a debit, swept by the next close
- added `features/programme/managers.feature`: a fraud flag is raised once per player, brand and reason when a brand's thresholds are crossed
- added `features/programme/managers.feature`: dismissing a fraud flag tells the affiliate it was reviewed and dismissed
- added `features/programme/managers.feature`: a manager's read-only view of an affiliate shows what the affiliate sees and changes nothing
- added `features/programme/managers.feature`: adding, resetting or revoking a manager for a brand provisions them with the manager role and the brand's role and is audited
- added `features/programme/managers.feature`: a change to a brand's programme page is what new applicants see

---

### User Story 7 - The programme talks to affiliates (Priority: P2)

Affiliates are told what happened to them: application received, approved, rejected, payment processed, a sub applied, a flag dismissed, an invitation, a password reset; by in-portal notification, by email, and by postback to their own system.

**Why this priority**: Nothing else owns affiliate communications once the programme leaves the brand.

**Independent Test**: Approve an application, close and pay a period, and verify a notification, an email send and a postback for each.

**Acceptance Scenarios**:

- added `features/programme/communications.feature`: a postback is sent for a registration, first deposit, deposit or commission with the click id, payout amount and status substituted
- added `features/programme/communications.feature`: a failed postback is retried with backoff for up to a day and its last outcome is visible
- added `features/programme/communications.feature`: an affiliate is told what happened to them by notification and by email, and a failed send never blocks the action
- added `features/programme/communications.feature`: a parent is told when a sub's application is pending approval
- added `features/programme/communications.feature`: reading notifications or marking all read marks them read for that affiliate only

---

### User Story 8 - An affiliate is a data subject (Priority: P2)

An affiliate's name, email, company, website and payment details are personal data under the affiliate's subject; a player exists in the programme only as a pseudonym. An affiliate who leaves can be erased once the records finance must keep have aged out.

**Why this priority**: Constitution V, and the ADR 0040 exception this closes.

**Independent Test**: Erase a closed affiliate with a hold, let it lapse, and verify payouts still total and the identity fields read as erased.

**Acceptance Scenarios**:

- added `features/programme/data-subjects.feature`: an affiliate's identity and payment fields are personal data under the affiliate
- added `features/programme/data-subjects.feature`: a closed account's erasure waits for the retention hold and then erases identity while facts, payouts and audit remain
- added `features/programme/data-subjects.feature`: a player read in the programme shows a pseudonym, a country and figures, and nothing that names the player
- added `features/programme/data-subjects.feature`: a player-closed fact makes the programme forget the pseudonym's country and click details and keep the amounts

---

### User Story 9 - Spinvibe's affiliates move in (Priority: P3)

Today's single-brand affiliates, their plans, links, bonus codes, invite links, creatives, notes, flags, audit and the whole commission ledger become the programme's, with Spinvibe as their one membership, and nothing owed changes.

**Why this priority**: Needed for the cutover (019), not for the pilot brand.

**Independent Test**: Import a recorded Spinvibe affiliate data set and compare every affiliate's revenue, breakdown and payouts for every period with the original's answers.

**Acceptance Scenarios**:

- added `features/programme/import.feature`: each imported account becomes one account with one membership at the brand on its plan
- added `features/programme/import.feature`: the imported ledger keeps every fact and payout and folds to the original's revenue
- added `features/programme/import.feature`: imported links and bonus codes keep their legacy tag for the transition period and gain a signed tag
- added `features/programme/import.feature`: a fact for a player attributed before the import is attributed because the import carried the pseudonym and attribution
- added `features/programme/import.feature`: running the import again duplicates nothing

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
- **FR-023a**: The programme MUST publish, beside its key document and as a public document a
  brand fetches and caches the same way (spec 001 FR-016), a *code document* per brand: every live
  affiliate bonus code at that brand with its text, the brand offer it names and its signed tag; a
  revoked or deleted code MUST leave the document on the next publication, and the document MUST
  carry a version and a time so a brand's `rewards` (spec 011 FR-021) can report staleness.
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
- From spec 012 (2026-10-25, [spec 012 research](../012-affiliate-protocol/research.md), *Findings for other specs*, finding 3): a refund arrives as a `chargeback` with a `/refund` reference and a closed dispute emits nothing; FR-014 must fold either the `ggr-round` facts or the day's `ggr-daily`, never both; FR-023a's code document and FR-026's legacy tag document take the shapes of 012's contracts/protocol.md.
