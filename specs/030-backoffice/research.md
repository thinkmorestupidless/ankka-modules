# Research: how the original backoffice is used

Sources for the findings in [spec.md](spec.md), *Context*. Paths are in
`~/Documents/projects/eitheror/eitheror-platform` unless stated. **[E]** is what a source says;
**[I]** is an inference from it. Gathered 2026-11-11 from specs, commit history (258 commits touch
`casino/frontend` and `payments/frontend`), incident reports,
`docs/operator-assistant/reference/known-issues.md`, `scripts/`, `casino/operator-mcp`, the
eitheror brain and Claude project memory.

## 1. "Why did this happen?" cannot be answered in the backoffice

- [E] Original spec 258 (operator MCP server): "every non-trivial customer question ('why was this
  withdrawal capped', 'where did this bonus go', 'is this a bug') ends with an engineer reading twin
  history, wallet transactions, graph edges, and logs by hand." Its phase 2 is an assistant drawer
  inside the backoffice.
- [E] The withdrawal investigation recipe (project memory) uses substrate REST through a
  port-forward, twin history, `kubectl exec` with cypher-shell, Loki, and nginx access-log byte
  deltas.
- [E] `casino/operator-mcp/src/tools/` is the investigation surface operators lack: find_player,
  player_overview, player_timeline, wallet_transactions, withdrawal_requests, payment_attempts,
  rewards_and_restricted_funds, kyc_status, rg_status_and_history, player_sessions_and_rounds,
  resolve_reference, twin_history, search_logs, projection_status.
- [E] Original spec 128: the activity tab "silently drops event categories when any one data
  source fails. Operators make support and compliance decisions on incomplete information."
- [E] Original spec 194: before it, "an operator has no way to discover which rounds exist for a
  player"; disputes start from ids pasted from tickets and provider reports.
- [E] Known issue: player search sent free text as a player id, producing 760 HTTP 500s.

## 2. Lists silently lost rows

- [E] Generic list reads returned at most 500 rows, oldest first, or a default of 20. Fixes:
  db4076735, 5dfad1946, 91fe3e62e (pool exhaustion, empty Players and Wallets), original spec 242
  (Wallets), 246 (Bonuses: "operators could not find or revoke most bonuses", 542 grants with 20
  shown), 248 (Transactions and Analytics), cb4e8dab6 (duplicate rows).
- [E] Known issue `graphql-twins-oldest-first-cap`, still open: "If a row is not there but the
  detail page loads, suspect the cap."
- [E] Original spec 180: the Players page fetched 500 players and filtered eight filters in memory.
- [E] Known issue `twin-projection-hourly-lag`: lists 190 to 380 seconds stale between about :11
  and :20 every hour; the graph froze for eight hours on 2026-10-29. Nothing on screen says so.

## 3. Money on screen could not be trusted

- [E] Withdrawal amounts shown in minor units: 16c373b66, 094856b92. Inconsistent currency
  formatting: 7f056ccc4.
- [E] Known issue, open: the timeline shows €0.05 for a €5 withdrawal.
- [E] A refund a hundred times too large (a228241cf), corrected afterwards by an adjustment.
- [E] Known issue `silent-withdrawal-cap`, open: lists show `amount` only and hide the requested
  and voided amounts.

## 4. Reward states opaque, actions confused

- [E] Incident 2026-09-30 (free rounds reward not cancellable): support emptied a reward with an
  adjustment, which left it active; the right tool was a forfeit. "The agent was working without
  the information or tools they needed"; "Support had no guidance on … the difference between
  emptying a bonus and forfeiting it."
- [E] Original spec 174: after a suspected-duplicate flag was cleared, manual grants failed with a
  generic error; the revoke button called a route that did not exist.
- [E] Free rounds shown PAUSED and 0.00 "by construction", 100/100 while the player saw 95/100,
  two games of the same name indistinguishable: 9f6c90ed6, d5f6e07fe. "Every 30 s poll blanked or
  dimmed the list": 6393149d4.
- [E] Original spec 251: €750 of a player's deposits voided and "refunded by hand".
- [I] About 35 commits touch reward operations: the area most confusing to operators.

## 5. A withdrawal's story was scattered

- [E] Original spec 250: "Operators currently have no single place to see the complete story of a
  withdrawal"; the player tab showed only the pending request; no balance context, reward impact
  or approver. Reviewing a withdrawal is "the most common review action".
- [E] 2abe0b30b: the screen told operators to confirm or mark failed a payout needing review, but
  the buttons never rendered and the routes answered 409.

## 6. Corrections happened outside the backoffice

- [E] Original spec 170: the adjust button reused the deposit action; corrections inflated total
  deposited, GGR, provider and RG figures, and had no actor or reason.
- [E] Incident 2026-08-19: support made six adjustments totalling €100 by hand. Incident
  2026-08-14: "Someone with backoffice operator access needs to be assigned to apply the
  admin_adjustment credits".
- [E] `scripts/mark-st8-credit-settled.py`: a win paid by hand through an adjustment is not linked
  to the provider's credit, so the provider's retry pays it again.
- [E] Repairs by script, not screen: `backfill-forfeited-grants.sh` (383 grants),
  `repair-migrated-dead-records.py`, `dedupe-duplicate-deposit-twins.sh`,
  `reconcile-st8-unsettled.py`, `bonus-conversion-reconciliation.py`.

## 7. Roles and brand selection broke pages

- [E] 26d84ae02: an operator narrowed to a sub-brand got 403 on Transactions. de6162171: a
  holding-level operator with no selection got 403 on Players, the KYC queue, Wallets and RG.
  Also 23f5b95d7, d36d756cc, 12b9b1b93, 8df14f055, ac6ba02cc; brain decision 0045.
- [E] Inventory of `casino/frontend` (2026-11-11): `brand_finance` and `casino_admin` are checked
  by the screen and the backend but are not realm roles; the withdrawal queue shows approve and
  reject to every operator; the screen admits `resource_admin` to KYC actions the backend refuses
  it; the KYC policy is read-only on screen for `brand_compliance`, which the backend lets write.
- [E] Original spec 135 (from a security audit): support reads only; compliance handles RG and
  KYC; finance approves withdrawals; brand_admin configures. Not carried into the backend for
  adjustments, limits, suspension, self-exclusion or closing an account.
- [E] Inventory: the scope is loaded from browser storage and never checked against the token.

## 8. Daily friction

- [E] bf0da23de: KYC reviewers "lost their filter, page and scroll position every time they opened
  a player"; first-in first-out queues asked for.
- [E] 4487c2bba: clicking month by month to a birth year "was unusable".
- [E] 065e751ec: the "until" date filter excluded the chosen day.
- [E] 4ecd72483: debits silently clamped.
- [E] Original spec 182: agents asked to be told when a player comes online, to message them by
  hand.

## Most frequent operator tasks

[E] where a source ranks them, [I] otherwise: look up a player and read their standing; review
and settle withdrawals ([E] spec 250); work the identity queue first-in first-out; grant, forfeit
or reclaim rewards and find why a grant failed; adjust a balance; investigate a dispute through
the timeline, rounds and payments; set limits, breaks and self-exclusions; reach out to players
online; configure the brand; run the affiliate programme.

## Security findings not to port

- `casino/backoffice-bff` `GET /kyc-doc-view?url=` fetches any address it is given, unauthenticated.
- The content system's write token is compiled into the backoffice bundle.
- The working scope is read from browser storage unchecked.

## The inspiration

Four images supplied by the product owner on 2026-11-15 (dark wallet and trading dashboards; kept
outside the repository). *Visual direction* in the spec states what was taken from them.
