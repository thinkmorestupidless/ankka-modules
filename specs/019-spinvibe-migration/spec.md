# Feature Specification: The Spinvibe Migration — One Cutover From the Twin Substrate to ankka

**Feature Branch**: `019-spinvibe-migration`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Migrate Spinvibe, the live real-money brand, from `eitheror-platform`
to its ankka project in one cutover window: drain rounds and payouts, import one opening event per
entity, keep the old journal read-only, reconcile to the cent, switch the addresses, and be able to
go back within the window."

## Context

Spinvibe runs today on `eitheror-platform`: every player, wallet, bonus grant, withdrawal request,
KYC record, RG profile, affiliate account and commission fact is a *twin* — an event sourced actor
whose persistence id is `twin|<domain>~<uuid>` in one `event_journal` table of one database,
`substrate`, with its current properties projected into a `twins` table, its relationships into a
graph in Neo4j (a projection, rebuildable from the journal), and its personal fields in the clear
on the player twin and in `twin_pii_lookup`. One CloudNativePG cluster per environment holds five
databases (`substrate`, `keycloak`, `unleash`, `casino_backoffice_bff`, `casino_cms`), backed up
nightly by Barman to `gs://eitheror-prod-backups/postgres/` and kept 30 days. KYC documents are
objects in MinIO. The player site, the affiliate portal and the operator console reach the services
through base URLs in their environment; ST8 and every payment provider reach the brand at hostnames
registered with them.

Specs 002–013 describe each of those twins' successors as ankka services, and each says what it
holds and, under *Changes from the original*, what it fixes. Spec 001 says a brand is one ankka
project, that the pilot brand is a new brand launched on ankka first (Phase 3), and that Spinvibe
follows in one cutover (Phase 4). This spec is that cutover: what must be true before it, what is
carried across and in what shape, the runbook, the proof, the way back, and what becomes of the old
journal.

Three facts of the original shape the whole plan:

- **The twin substrate has been migrated in place before, and rehearsed.** Rewards Phase 2 (the
  original's specs 222, 225, 228, 230) was rolled with a paged backfill, a rollback script, two
  full rehearsals against a production snapshot restored into pre-production (`specs/rewards-platform/
  phase2-rehearsal-runbook.md`), and a per-wallet balance parity check that reads the same routes
  the player sees (`scripts/balance-parity-check.sh`). Its findings — aggregate equality is blind to
  what a player's screen shows; the gap between a code roll and a backfill is player-visible — are
  this spec's rules.
- **The original can already erase a player physically.** `scripts/player-expunge.sh` deletes a
  player's events, snapshots, durable state, projection rows, PII lookup rows and graph nodes,
  retaining the audit projection under its own legal basis. That is how an erasure reaches the old
  journal during its retention.
- **Several defects the port fixes have left data behind** (specs 003, 008, 009, 010, 013): open
  wallet bets under rounds the provider settled, players stuck in a cooling-off that never ends,
  players refused every deposit because source of funds can never clear, realm users with no
  player, affiliates whose bonus-code signups were never attributed. The migration must decide what
  each of those becomes, because an opening event written from a wrong state carries the wrong state
  forever.

## Clarifications

### Session 2026-10-08

- Q: One cutover or a service-at-a-time run beside the original, as the plan's first draft had? →
  A: One cutover. Today's services read the wallet twins directly, so a brand cannot run half on
  each platform; and a long double-running period is the Phase 2 rehearsals' lesson in the other
  direction — every day of overlap is a day a player can see two answers.
- Q: Does Spinvibe keep its player ids? → A: Yes. A player's id today is the twin's UUID, which the
  realm user carries and which the affiliate ledger, the audit record and every export already
  name. Spec 008 mints new ids for new players and never derives one from an email; existing ids
  are carried as they are.
- Q: Does Spinvibe keep its Keycloak realm? → A: Yes, the same Keycloak and the same
  `casino-spinvibe` realm (spec 001 FR-019): players keep their passwords, sessions and consents,
  and the realm's user → player attribute is unchanged. The cutover changes only the two
  authenticators' and the audit listener's targets, from the twin routes to `players`' status route
  and the audit topic.
- Q: What happens to open rounds and in-flight payouts at cutover? → A: Rounds are drained: game
  launch is closed first, and the freeze does not begin until the wallet reports zero open bets
  that the provider has not settled. In-flight payouts are carried: a provider may take five days
  to answer (spec 007), so a request in `initiated` is imported with its provider reference and its
  workflow resumes in its await-outcome step.
- Q: Opening event or replayed history? → A: One opening event per entity, written in the
  successor's own event shape from the twin's current state, and never a transcription of the old
  journal. The old journal is not an ankka journal, its events carry twin property writes rather
  than domain events, and spec 042 encrypts personal fields from the first event a service writes.
  Where a successor's state is a fold of history (the commission ledger, spec 013), the history is
  imported as facts, which is what ADR 0042 made possible.
- Q: What does the migration do about data the fixed defects left behind? → A: Each has a rule
  (FR-020 to FR-026), applied before the final export, with a report; an opening event is never
  written from a state a rule says is wrong.
- Q: Where do the providers' and ST8's callbacks go after cutover? → A: To the same hostnames.
  The brand's public hostnames that ST8, the payment providers and the player site know are
  preserved and their DNS moved to the new installation's gateway at cutover, so no provider is
  asked to change anything. Only the frontends' environment changes.
- Q: How long is the old journal kept, and who may read it? → A: For the longest regulatory
  retention any record in it is under, as spec 009's policy per licence profile states, counted
  from the cutover; read-only, restorable from its final backup on request by a platform
  administrator under two-eyes, for a stated reason recorded in the shared audit. It holds
  plaintext personal data; an erasure applied in the brand during that time is applied to it with
  the original's own expunge script, unless the request's hold covers the retention period
  entirely.
- Q: What proves the migration? → A: For every player, the balances and bonus state the player
  would see, read from the new services' player routes and compared to the same read from the
  original at the freeze point; for every ledger, totals to the minor unit; for every entity, a
  count. One signed report, no `UNEXPLAINED` row, or the cutover does not switch.
- Q: Which brand goes first? → A: Not Spinvibe. Spec 001 requires the pilot brand to be live on
  ankka before this cutover is scheduled (FR-002); its open question on which brand that is stays
  spec 001's.

### Session 2026-10-08 (reconciliation 3)

Settled with specs 014–018 once they existed.

- Q: What do `partner-api` and `audit` import? → A: The opaque public-id registry in full and each
  partner grant, with no partner to serve yet (spec 016); and the brand's account-event twins as
  `audit_record` facts with the import marker, never the substrate's own `audit_records` (spec 018
  Assumptions). FR-006's two placeholder rows are filled.
- Q: Which frontend change goes beyond base URLs? → A: One: the player site's proxy forwards the
  realm token to the agent's routes instead of minting a hand-off token (spec 017); it is a runbook
  step (FR-016).
- Q: Which third-wave defects need a data rule? → A: Suppression (spec 014): the operator's flag and
  consents are imported so the first identify tells Customer.io (FR-026a). Reporting (spec 015) is
  a defect in figures, not in data: nothing is imported for it, and the report says historic
  dashboard figures are not comparable (FR-026b).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player's money and bonuses are exactly what they were (Priority: P1)

A Spinvibe player who logged out on Sunday evening logs in on Monday morning on the new platform.
Their real balance, their restricted funds and its wagering progress, their open withdrawal, their
deposit history and their favourites are what they were. They notice nothing but a maintenance page
overnight.

**Why this priority**: This is the whole point. A migration that changes one player's balance by
one cent has failed, whatever the aggregates say.

**Independent Test**: Against a production snapshot in a rehearsal, capture every player's balances
and bonus state from the original's player routes, run the migration, capture the same from the new
services' routes, and diff.

**Acceptance Scenarios**:

- added `features/migration/balances.feature`: a player's real balance and restricted funds record migrate with the same balance, requirement and progress, linked to a reward in the same state
- added `features/migration/balances.feature`: a player's wallets in two currencies migrate each with its own balances and records, neither naming the other
- added `features/migration/balances.feature`: a converted record awaiting its cap migrates with the same allowance and deposit counters and the reward's cap clock
- added `features/migration/balances.feature`: the parity report has every player's row OK or explained with evidence, and none unexplained
- added `features/migration/balances.feature`: a player's past deposit is listed in their transaction history with its date, amount and reference as before

---

### User Story 2 - Nothing in flight is lost (Priority: P1)

A withdrawal the operator approved on Friday is with the provider when the cutover happens. On
Monday the provider answers, the request completes, and the player's hold is confirmed. A bonus
claimed on Sunday with its claim window still open can still be activated on Monday.

**Why this priority**: The brand cannot wait for every provider to answer, and every payout in
flight is a player's money.

**Independent Test**: In a rehearsal, leave one payout `initiated` at `psp-stub` and one grant in
each of spec 011's non-terminal states, run the migration, then let the stub answer and the clocks
run.

**Acceptance Scenarios**:

- added `features/migration/in-flight.feature`: a withdrawal payment initiated at a provider before the cutover completes once the provider reports success
- added `features/migration/in-flight.feature`: a withdrawal payment initiated before the cutover fails and releases the hold when the provider reports failure
- added `features/migration/in-flight.feature`: a reward claimed before the cutover keeps its clocks at the same instants and expires or activates exactly when it would have
- added `features/migration/in-flight.feature`: the migration refuses to export while the wallet reports open bets, and shows the operator whose rounds are still open
- added `features/migration/in-flight.feature`: a withdrawal request pending review before the cutover is in the new queue with its time, amount and destination for the operator to decide

---

### User Story 3 - The operator can go back within the window (Priority: P1)

Reconciliation finds twelve wallets the migration cannot explain. The operator stops, puts the
addresses back, and the original serves Monday's players as if nothing happened. Nothing on the
original was written during the window.

**Why this priority**: A cutover without a rehearsed way back is a bet, not a plan.

**Independent Test**: In a rehearsal, inject one bad opening event, run the cutover to the
reconciliation step, take the rollback path, and prove the original answers every route as it did
at the freeze.

**Acceptance Scenarios**:

- added `features/migration/going-back.feature`: going back at the reconciliation step returns the addresses to the original, leaves its data unchanged since the freeze, and keeps the new brand for study
- added `features/migration/going-back.feature`: going back after the addresses switched is still possible while the window is open, with the players' actions since listed for the operator
- added `features/migration/going-back.feature`: the window closing after sign-off retires the original and makes going back a restore rather than a step

---

### User Story 4 - A player erased during retention is erased everywhere (Priority: P2)

Two years after the cutover a former player asks to be forgotten. The brand erases them under spec
008; the old journal, kept for the regulator, still holds their name in the clear. The operator runs
the original's expunge against the retained copy, and the certificate names both.

**Why this priority**: Spec 042 says plainly that the old journal is outside its erasure. Either the
migration plan covers it or the brand holds plaintext it cannot erase.

**Independent Test**: In a rehearsal, erase one player on the new platform, run the retained-journal
procedure, and search the retained copy for the player's personal fields.

**Acceptance Scenarios**:

- added `features/migration/retained-journal.feature`: an erasure applied for a player from before the cutover removes them from the retained journal while the audit projection keeps them
- added `features/migration/retained-journal.feature`: an erasure whose hold lasts the whole retention period needs no per-player step, because the retained journal is deleted as a whole
- added `features/migration/retained-journal.feature`: a read of the retained journal needs a second person and a stated reason and leaves a record in the shared audit

---

### User Story 5 - The affiliates and the partners see continuity (Priority: P2)

An affiliate who sent Spinvibe players last year logs in to the programme and sees the same revenue,
the same players and the same payouts, and their existing `btag` links still attribute. The
analytics team's consumer keeps reading.

**Why this priority**: Commission is money owed to third parties and is reconciled by them.

**Independent Test**: Import a recorded affiliate data set under spec 013's rules and compare every
affiliate's revenue and payouts per period with the original's answers; land a player through a
legacy link after cutover.

**Acceptance Scenarios**:

- added `features/migration/continuity.feature`: each imported affiliate is one account with one membership at the brand whose revenue and payouts fold to the original's figures
- added `features/migration/continuity.feature`: a player attributed before the cutover has a deposit afterwards attributed to the same affiliate
- added `features/migration/continuity.feature`: a legacy tag link landed on after the cutover resolves through the legacy tag document and binds
- added `features/migration/continuity.feature`: the analytics reader's last position on the old facts is recorded and the new facts begin at the cutover, with nothing missing or doubled

---

### User Story 6 - The cutover is rehearsed until it is boring (Priority: P2)

Before the real window, the team runs the whole runbook against a production snapshot in a
rehearsal installation, twice, and the second run's timings set the window.

**Why this priority**: Rewards Phase 2 needed three rehearsals to find a player-visible defect no
aggregate check saw.

**Independent Test**: The rehearsal reports exist, name their snapshot, their timings and their
findings, and the last one has none open.

**Acceptance Scenarios**:

- added `features/migration/rehearsals.feature`: a rehearsal run end to end records every step's elapsed time and lists every finding
- added `features/migration/rehearsals.feature`: a real cutover window proposed while a rehearsal has an open finding is refused until a later rehearsal closes it
- added `features/migration/rehearsals.feature`: production personal data in the rehearsal installation is removed when the rehearsal ends, and the removal recorded

---

### Edge Cases

- A player registered in the original between the final export and the freeze: impossible by
  construction — registration is closed with the maintenance page before the export begins; a
  registration that slipped through is listed by the count check and the export is run again.
- A wallet with restricted funds but no grant that can be found (the Phase 2 backfill's `unlinked`
  class): the record is imported with an empty rule snapshot as the backfill did, and the player is
  listed for review before sign-off.
- A twin whose properties the successor's opening event cannot represent (an unknown status word, a
  decimal amount, a negative balance): the export refuses, names the twin and the field, and the
  rule for it is added to this spec before the next rehearsal; never a best-effort default.
- A withdrawal request `initiated` whose provider never answers: it reaches spec 007's
  needs-review step at the provider's window as any new request would.
- A player suspended with a reason written in the clear on the twin: the reason is imported as a
  `Personal` value, as spec 008 holds it.
- A KYC document object that MinIO lists but cannot be read: the copy refuses, and the document's
  record is imported marked unreadable for the operator to ask the player again.
- An affiliate whose membership would fail spec 013's rules (no plan): imported on the brand's
  default plan and listed.
- A Keycloak user with no player twin (the orphan spec 008 found): removed from the realm before
  cutover and listed; the person may register again.
- The provider's DNS move is cached beyond the window at one provider: the old gateway keeps
  answering the preserved hostnames with a redirect to the new one for the DNS record's old
  time-to-live, and nothing else.

## Requirements *(mandatory)*

### Functional Requirements

**Pre-conditions**

- **FR-001**: The cutover MUST NOT be scheduled until every parity test every service spec names is
  green against its recordings (spec 001 FR-032), and the original's Playwright suite passes against
  a brand on the new installation with only base URLs changed (spec 001 FR-033).
- **FR-002**: The cutover MUST NOT be scheduled until the pilot brand has taken real money on the
  installation for at least four weeks with no open incident of severity one or two.
- **FR-003**: The installation MUST meet every condition of spec 001 FR-035 to FR-040 and have a
  rehearsed restore (ankka 041) of the Spinvibe project within the last 30 days.
- **FR-004**: The cutover MUST NOT be scheduled until the agreement between thinkmorestupidless and
  eitheror is signed and recorded in the eitheror brain, and the licence holder has given any notice
  the regulator requires. [NEEDS CLARIFICATION: does the Curaçao licence require notice of, or
  approval for, a change of platform before it happens, and with what lead time? Sheldon to confirm
  with compliance.]
- **FR-005**: Two full rehearsals of the runbook MUST have been completed against a production
  snapshot in a rehearsal installation, the last with no open finding, and the window length MUST
  be set from the last rehearsal's timings with a margin of one half.

**The inventory**

- **FR-006**: The migration MUST carry exactly the state the following table names, from the twin
  shape named to one opening event (or, where stated, imported facts) in the successor's own shape,
  and MUST carry nothing else; a twin domain, property or relationship not in the table is not
  migrated, and the export MUST list every one it finds so the omission is deliberate.

  | Successor | From the original | What the opening event carries |
  |---|---|---|
  | `wallet` (002) | `wallet` twins (`player_id`, `brand_id`, currency, `real_balance`, `deposit_pool_balance`, `converted_pool_balance`, `pools_stamped_at`, deposit records, `RestrictedFunds` records, open bets index), transactions | real balance with its two pools (spec 025 FR-011: a stamped wallet's pools; an unstamped one all converted); deposit turnover pending with its records; every restricted-funds record with status, balance, requirement, progress, cap fields, counters, clock and rule snapshot; no open bets (FR-012); the seen-key set empty |
  | `wallet` transactions view | `transaction` twins and `HAS_TRANSACTION` edges | imported as transaction facts in order, so history lists, sums and lifetime totals read as before; the balance-before and balance-after of each are checked to chain to the opening balance |
  | `payouts` (007) | `withdrawal-request` twins, `payout-destination` twins | each open request with status, payout status, amount, destination, provider reference, decisions, times; completed and failed requests of the last 24 months as history; destinations with status |
  | `players` (008) | `player` twins, `player-session` and `game-session` twins, operator notes, fraud flags, consents, favourites, VIP tier, `twin_pii_lookup` | the player with status, profile fields as `Personal`, consents, favourites, tier, flags and links, the account trail; no sessions (every session is ended at freeze); lookup tokens recomputed under spec 042 |
  | `kyc` (009) | the player's `kyc_documents` array, statuses, mode, required categories, deadline, cumulative deposits; MinIO objects | one record per player (009 FR-013); every object copied to the service's bucket under `subjects/<player id>/kyc/…`, content type and upload time preserved, checksum verified |
  | `responsible-gambling` (010) | the `rg_*` properties, RGCase twins | the profile with limits by type and currency, pending changes with their apply times, break and exclusion with end times, reality check; cases as history |
  | `rewards` (011) | `reward-grant` twins, offer twins, claim refs, free-rounds state | each offer; each grant in its spec 011 state with clocks, snapshot, baselines, source and code; a free-rounds grant with rounds used and winnings accumulated |
  | `game-provider` (003) | ST8 transaction, round-index and callback-failure stores; catalogue | known rounds of the last 90 days with their transactions and outcomes, so a provider's late cancel or dispute finds its round; the catalogue and brand catalogue configuration |
  | `attribution` (012) | `affiliate-registration-<brand>` twins, `ATTRIBUTED_TO` edges, `affiliate_id` on player twins, click twins | one attribution record per attributed player with its legacy tag, click id where recorded, bound-at and the pseudonym computed under the programme's network key; clicks of the last 90 days |
  | `affiliate-programme` (013) | `affiliates-<brand>`, plans, `affiliate-facts-<brand>-<aff>`, payouts, links, creatives, bonus codes, invite links, fraud flags, notes, audit | per spec 013's import: one account and one Spinvibe membership per affiliate; the ledger as facts; payouts; links and codes with their legacy tags; the legacy tag document |
  | `merchant` (004) | holding, company, sub-brand, merchant, terminal, operator twins; routing trees | the hierarchy with the Spinvibe sub-brand naming its project; merchant keys by digest; terminals with secrets moved to the secret store; live routing trees; operators |
  | `psp-gateway` (005) | deposit and payout session twins | in-flight sessions only; completed sessions of the last 13 months as history for disputes |
  | `partner-api` (016) | the opaque public-id registry (`PublicIdMinter`), the partner grants in Helm values, the `partner-<slug>` realm clients | the registry in full, so an id a partner stored resolves after the cutover (016 FR-010); each grant as a grant record (016 FR-020) less the two unenforced quota fields; the realm clients untouched. No partner exists today, and the service is not in the pilot (spec 001 FR-007); the registry is carried regardless |
  | `audit` (018) | the `casino-account-events-<brand>` twins and the wallet's `HAS_AUDIT_EVENT` edges | each account event as an `audit_record` on the brand's `platform.audit` with the import marker, the migration as actor and the original's time as occurrence, a `Personal` previous or new value recorded by field name only (018 FR-003); the shared audit pseudonymises them on ingest as any record. The substrate's `audit_records` and `pseudonym_registry` are not migrated: they say only that a twin changed, and stay read-only with the old journal (018 Assumptions) |
  | Keycloak | the `casino-spinvibe` realm | nothing migrated; the realm is kept (Clarifications) and its authenticators' targets changed |
  | Strapi, Unleash, Chatwoot | their own databases | nothing migrated; they stay external (constitution) |
  | Neo4j, the `twins` and `casino_read` projections | — | nothing migrated; they are projections of the journal and have no successor |

- **FR-007**: Every amount imported MUST be a whole number of minor units; a stored decimal string
  or 32-bit cent value MUST be converted at export and refused when it is not whole (spec 001
  FR-026).
- **FR-008**: Every personal field named in spec 001 FR-023 MUST be written as a `Personal` value
  under the player's id in the opening event, so the new journal holds no personal field in the
  clear from its first event (ankka 042).
- **FR-009**: Every opening event and imported fact MUST carry the import marker: the cutover's id,
  the freeze point, and the source twin id, so a fact can be traced to the twin it came from and an
  import can be told from live activity in every view.
- **FR-010**: The import MUST be idempotent: run twice against the same export it writes nothing the
  second time (spec 013 SC-008 for the programme; the same for every service).
- **FR-011**: The export MUST be taken at one recorded freeze point from a read-only copy of the
  `substrate` database restored from the final backup, never from the running original, so the
  export is repeatable and the original is untouched.
- **FR-011a**: Before the final backup, the original's `pools-backfill` (its spec 253) MUST be run
  and every wallet stamped (`pools_stamped_at`), so a wallet opening all converted (spec 025
  FR-011) is the exception; the export MUST count the wallets still unstamped, and the runbook
  MUST NOT continue while any is unstamped without a decision recorded for it. The export passes
  `deposit_pool_balance` and `converted_pool_balance` to `open-migrated` as `depositPool` and
  `convertedPool`; a pair that does not sum to the real balance is refused (`POOLS_INVARIANT`) and
  stops that player's import, as any refused opening does (spec 025 research R8).

**Draining**

- **FR-012**: Before the freeze, game launch MUST be closed, open game sessions ended, and the wallet
  polled until it reports zero open bets whose round the provider has not settled; the freeze MUST
  NOT begin while any remain, and the operator MUST see which players' rounds are open and for how
  long.
- **FR-013**: Deposits MUST be closed before the freeze; a payment captured by a provider after the
  freeze MUST be delivered by the gateway to the new `merchant`, which hands it to the new wallet
  under its grant — the original's cashier is stopped, not the providers' webhooks.
- **FR-014**: Withdrawal requests MUST NOT be drained: those `initiated` at a provider are carried
  (FR-006) and their workflows resume in await-outcome; those `pending review` are carried to the
  new queue.
- **FR-015**: Every player session MUST be ended at the freeze, and the maintenance page MUST be
  served at every player-facing address for the whole window.

**The runbook**

- **FR-016**: The cutover MUST follow a written runbook kept in this repository under
  `brands/spinvibe/cutover.md`, each step naming its owner, its command, its expected output, its
  elapsed time at the last rehearsal, and whether it is reversible; the steps MUST be, in order:
  announce; close registration, deposits and launch; drain (FR-012); end sessions and serve the
  maintenance page; take the final backup and record the freeze point; restore the export copy
  (FR-011); apply the data rules (FR-020 to FR-026) and record their report; export; import into
  every service of the project and the shared projects; reconcile (FR-017, FR-018); sign off
  (FR-030); change the realm's authenticator targets; move the preserved hostnames' DNS to the new
  gateway and redeploy the frontends with the new base URLs — and the player site's proxy forwarding
  the player's realm token to the agent's routes instead of minting a hand-off token (spec 017), the
  one frontend change beyond base URLs; unfreeze; watch (FR-019).
- **FR-017**: Reconciliation MUST compare, for every player, what the player's own routes answer on
  the new services with what the original's answered at the freeze — real balance, restricted
  balance and status, open withdrawal, deposit history count and sum, KYC status, RG status and
  limits, active grants — and classify every player as `OK`, an explained class with evidence, or
  `UNEXPLAINED`; the capture of the original MUST be made with the original's own parity tool
  against the restored copy.
- **FR-018**: Reconciliation MUST also compare totals to the minor unit: the sum of real balances per
  currency, the sum of restricted balances per currency, the count and sum of transactions per type,
  the count of players per status, the count of grants per state, the count of requests per status,
  the programme's revenue and payouts per affiliate per period; and counts per entity type between
  the export and the import.
- **FR-019**: For 72 hours after unfreeze the brand MUST be watched for: a money command refused
  as unknown key or unmigrated record, a provider callback for a round the service does not know,
  a login refused on account status, a withdrawal that cannot find its destination, and a fact a
  reader cannot parse; each MUST page the on-call.

**Data the fixed defects left behind**

- **FR-020**: Open wallet bets whose round the provider settled (spec 003's multi-debit leak) MUST be
  settled before export by the original's own reconciliation (`reconcile-st8-unsettled.py`,
  `mark-st8-credit-settled.py`) from the provider's recorded outcome; a bet that cannot be matched
  to an outcome MUST be listed, and the cutover MUST NOT proceed while any is older than the drain
  window.
- **FR-021**: A player whose `rg_is_cooling_off` is set with a break whose end has passed (spec 010)
  MUST be imported with no break and a closed case saying so; a break whose end has not passed is
  imported as a break; a period exclusion whose period has passed MUST be imported as ended with a
  case, never as active.
- **FR-022**: A player whose source-of-funds status blocks every deposit with no way to clear it
  (spec 009 FR-023) MUST be imported with the flag in the review queue and the gate answering as
  the new policy says, and listed for the operator.
- **FR-023**: A realm user with no player twin (spec 008) MUST be removed from the realm before the
  cutover and listed; a player twin with no realm user MUST be imported as `closed` with a reason
  and listed.
- **FR-024**: A player whose registration carried an affiliate bonus code but has no
  `affiliate-registration` record (spec 013 change 7) MUST be imported with an attribution record
  bound to that affiliate at the registration time, and the programme MUST NOT recompute commission
  for facts before the cutover from it — the imported ledger stands.
- **FR-025**: A withdrawal request the original left `payout_status` stamped `needs review` with
  no resolution MUST be imported into spec 007's needs-review state, never completed.
- **FR-026**: A player id that the original derived from brand and email (spec 008) is kept as the
  player's id; the derivation is not carried, and a later re-registration after erasure mints a new
  id.
- **FR-026a**: An operator's message-suppression flag and every consent on the player twin MUST be
  imported so that `engagement`'s first identify after the cutover carries `marketing_suppressed`
  for every self-excluded, cooling-off, closed or suppressed player (spec 014 FR-012); nothing in the
  original enforced suppression outbound, so the cutover is the first moment Customer.io is told.
- **FR-026b**: No dashboard or report figure of the original is imported or reproduced: the
  original's figures were sums over a twenty-player sample (spec 015), and the new `reporting`
  folds every figure from the imported transaction facts; the cutover report MUST say that historic
  dashboard figures are not comparable before and after.

**The old journal**

- **FR-027**: The final backup of the original's cluster, the restored export copy, and every
  earlier backup within its 30 days MUST be retained read-only for the longest regulatory retention
  any record in them is under, as spec 009's policy per licence profile states, counted from the
  cutover, in a bucket the brand's services cannot reach, under the installation's object store
  with versioning; its deletion at the end of that time MUST be a recorded action.
- **FR-028**: A read of the retained copy MUST be requested by a platform administrator with a
  stated reason, approved by a second person, performed on a throwaway restore that is removed
  afterwards, and recorded in the shared audit with who, when and why.
- **FR-029**: An erasure applied in the brand for a player who existed before the cutover MUST be
  applied to the retained copy with the original's expunge procedure (`player-expunge.sh`,
  retaining the audit projection) when the request has no hold covering the retention period, and
  the erasure certificate (ankka 042) MUST name the retained copy; when the hold covers the whole
  period, the copy's deletion at the end of retention is the erasure.

**Going back**

- **FR-030**: The cutover MUST NOT switch addresses until the reconciliation report has no
  `UNEXPLAINED` row and has been signed by Trevor and Sheldon; the signed report is kept in this
  repository beside the runbook.
- **FR-031**: Until sign-off the original MUST NOT be written: its services serve the maintenance
  page only, and going back is returning the addresses and removing the maintenance page.
- **FR-032**: After sign-off and within the window, going back MUST still be possible: the original
  is untouched, the addresses are returned, and every money command, registration and request the
  new platform served since the switch is listed from its facts for the operator to re-apply or
  refund by hand; the window MUST NOT close while that list is not empty and unreviewed.
- **FR-033**: After the window the original MUST be retired: its services scaled to zero, its
  cluster kept until the final backup is verified restorable (FR-027), and going back is a restore
  under ankka 041 of a decision, not a runbook step.
- **FR-034**: A defect in one rebuilt service after the window MUST be handled by ankka 033's
  rollback of that service, never by returning to the original.

**Evidence and sign-off**

- **FR-035**: The cutover MUST produce one report: the freeze point, the counts per entity type at
  export and import, the data rules' reports, the reconciliation classes and their evidence, the
  totals, the elapsed time per step, who did each step, and the signatures; kept under
  `brands/spinvibe/cutover-<date>.md`.
- **FR-036**: Every rehearsal MUST produce the same report marked as a rehearsal, naming its
  snapshot, and recording the removal of production personal data from the rehearsal installation
  afterwards.

**Timing**

- **FR-037**: The window MUST be set from the last rehearsal's elapsed time with a margin of one
  half, announced to players with the notice the brand's terms require, and placed at the brand's
  lowest-traffic hours; the target is a window of no more than four hours. [NEEDS CLARIFICATION:
  what notice does Spinvibe's terms and its regulator require for planned downtime, and is a
  four-hour overnight window acceptable to Sheldon?]

### Key Entities

- **Cutover**: one attempt, rehearsal or real, with its id, freeze point, window, runbook steps and
  report.
- **Freeze point**: the instant the final backup was taken; every opening event and import marker
  names it.
- **Export**: the read of the restored copy at the freeze point: one record per twin, by domain,
  with its properties and relationships, after the data rules.
- **Opening event**: the first event of a successor entity, written in the successor's own shape,
  carrying the import marker.
- **Import marker**: cutover id, freeze point, source twin id, on every opening event and imported
  fact.
- **Data rule**: one of FR-020 to FR-026: a condition on a twin's state, the state written instead,
  and a report row.
- **Reconciliation row**: one player's before and after, its class and its evidence.
- **Retained copy**: the original's final backup and export copy, read-only, under retention.
- **Preserved hostname**: a public hostname a provider or ST8 knows, whose DNS moves at cutover.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every player of the brand is classified `OK` or an explained class in the
  reconciliation report, and zero are `UNEXPLAINED`, before the switch; in rehearsal, a single
  injected wrong opening event is caught as `UNEXPLAINED`.
- **SC-002**: Every total of FR-018 matches to the minor unit between the original at the freeze and
  the new services after import.
- **SC-003**: The real cutover's elapsed time from freeze to unfreeze is within the window set from
  the last rehearsal, and no step is run that the last rehearsal did not run.
- **SC-004**: The import run twice against the same export writes nothing the second time, in every
  service.
- **SC-005**: Every in-flight payout carried across reaches a terminal state by the provider's
  answer or spec 007's needs-review step, with no `withdrawal_completed` or `withdrawal_failed`
  fact doubled.
- **SC-006**: Every KYC object listed in MinIO at the freeze exists in the bucket under the subject
  prefix with the same checksum, or is marked unreadable in the record.
- **SC-007**: No personal field of any player exists in the clear in any store, topic or log of the
  new installation after the import, checked as spec 001 SC-002 does.
- **SC-008**: The rollback path is exercised in every rehearsal and returns the original to service
  within 30 minutes of the decision.
- **SC-009**: An erasure of a pre-cutover player during retention removes every personal field from
  the retained copy except the audit projection, and the certificate names the copy.
- **SC-010**: Within 72 hours of unfreeze, zero money commands are refused as unknown key or
  unmigrated record, and every provider callback finds its round.

## Assumptions

- Spinvibe is described by its values, not a manifest (spec 021, research R9): the migration writes
  `brands/spinvibe/values.json` and the brand's modules come from its entitlements at ankka-vendor,
  `core`, `engagement`, `reporting` and `casino` from the first apply, at the versions their channel
  gives. Wherever this spec says the brand's manifest, read its values and those entitlements; the
  shared projects keep their manifests under `shared/`.
- The pilot brand (spec 001's open question) is live on ankka before this spec is planned; this spec
  is not the first real money on the installation.
- The original's production stores are as the Phase 2 rehearsal runbook recorded them on
  2026-09-24: one CNPG cluster, five databases, Barman backups nightly to
  `gs://eitheror-prod-backups/postgres/` kept 30 days, Neo4j as a rebuildable projection, MinIO for
  KYC objects. The plan's "pgvector" was not found among the casino's stores; spec 017 keeps no
  vector store (session memory is a journal), so nothing is migrated for it.
- The twin shapes named in FR-006 are those the service specs read from the code on 2026-10-08;
  the export's refusal (edge cases) is what catches a shape that changed before the cutover.
- Production carries two brand ids, `spinvibe` and `goldvibe`; this spec migrates `spinvibe` only,
  and a second brand on the original is a second cutover under the same runbook.
- The analytics team's Kafka export (the original's specs 146 and 149) continues from the new
  installation's export topics in the same envelope (spec 001 FR-014; spec 015 names them as spec
  014's or 016's if carried); the cutover records the consumer's last position and the new topics
  begin at the cutover point.
- The operator console, the affiliate portal and the player site change only their base URLs and
  are redeployed inside the window; custom hostnames of the player site are unchanged.
- The old journal's retention follows spec 009's per-licence policy; this spec does not set a
  number of years.
- The rehearsal installation is a separate ankka installation (or project set) that receives a
  production snapshot under the Phase 2 runbook's PII decision, and is wiped after each rehearsal.

## Dependencies

- Every service spec 002–018 and 020, for the shape of each opening event and the import each
  service accepts; spec 013 for the programme's import; spec 016 for the opaque-id registry; spec
  018 for the account-event import; spec 017 for the proxy change.
- Spec 001: the port method (its recordings prove the services before this spec applies), the
  installation conditions, the topic contract, the personal-data rules.
- ankka 041 (backups, the rehearsed restore, the line of history after any later restore), 042
  (personal fields from the first event; the retained copy outside its erasure), 033 (rollback of
  one service after the window), 040 (the grants the brand's manifest makes, in place before
  import).
- The original's tooling, run against the restored copy and never the live original:
  `balance-parity-check.sh` (capture at the freeze), `reconcile-st8-unsettled.py` and
  `mark-st8-credit-settled.py` (FR-020), `player-expunge.sh` (FR-029), the Phase 2 rehearsal runbook
  as the pattern for restoring a production snapshot into a rehearsal environment.
- The licence holder's compliance function for FR-004 and FR-037.

## Out of scope

- Migrating Goldvibe or any other brand: a second cutover under the same runbook.
- Rebuilding the old journal's history as ankka events: one opening event per entity, history as
  facts only where a service is a fold of history (the ledgers, transactions, audit).
- Frontend changes beyond base URLs; Keycloak realm changes beyond the authenticators' targets.
- Neo4j, the `twins` projection and `casino_read`: projections with no successor.
- The thinkmorestupidless ↔ eitheror agreement's content; FR-004 only requires it signed.
- A migration while the original keeps serving (dual running): rejected in Clarifications.

## Recordings

This spec names no end-to-end recordings; its parity evidence is the reconciliation report
(FR-017, FR-018) produced in each rehearsal and in the real cutover, kept under
`brands/spinvibe/`, and the original's own parity tool's capture at the freeze point as the
before-side of every row.
