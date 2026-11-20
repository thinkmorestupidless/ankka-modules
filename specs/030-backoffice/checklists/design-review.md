# Design review: the original's screens against the built ones

**Purpose**: T131 (research R19). Each screen of the original's operator frontends, captured on
kind, set beside the built screen, with every difference that is meant (and why) and every one that
is not (a finding, to be fixed or decided). Run again at the end of each phase.
**Feature**: [spec.md](../spec.md), [contracts/design.md](../contracts/design.md), [parity.md](../parity.md)
**Before**: `tests/original/backoffice-screens/` (`index.json` lists every capture; the images stay
out of git, R19). **After**: the built page against the feature doubles with the a11y suite's seed
(`tests/a11y/seed.ts`), at 1440 by 900, dark, every route of `tests/a11y/routes.ts`, as
`platform_admin`, and the worklist, a player, configuration, payments and team as each default
role.

**Recorded 2026-11-16**: the original on kind (`eitheror-dev`), images
`eitheror/casino-backoffice-ui:latest` and `eitheror/payments-backoffice-ui:dev`, both built
2026-11-14, brand `goldvibe`, as `platform_admin` (the realm's `dev-operator`). The other roles
wait on their realm users (*What is left*).

## How to read it

- **Intended** names the requirement or decision that makes the difference. Nothing here asks for
  the original's look back.
- **Finding** is a difference no requirement explains, or a place where the built screen misses
  its own design (`contracts/design.md`, the *Visual direction*, an FR). Each has an id, the
  screen, what was seen, and what would close it. Findings are not fixed by T131.
- A screen's data differs between the two (the original's kind brand against the doubles' seed);
  data is not compared, only what the screen does with it.

## Intended differences, across every screen

- [x] **Frame**: the original's 200 px text sidebar (one long list: casino, affiliates, content,
  payments) and its top bar (scope selector, an orange "Viewing as" banner) become a 64 px icon
  rail with the current area a filled circle, and a top bar with the brand, the one search,
  freshness, jobs, notices and the operator (contracts/design.md *Frame*; FR-004: one backoffice
  per brand, so no scope selector and no impersonation banner).
- [x] **Look**: the original's shadcn grey cards with borders and its blue/green status pills
  become the tokens of contracts/design.md (near-black with a green cast, borderless cards, one
  green primary, pills filled when active); never the eitheror brand (FR-039).
- [x] **State never colour alone**: the original's coloured badges (`active`, `KYC: UNVERIFIED`,
  `available`) become a word with an icon (FR-040).
- [x] **Lists**: every list shows its total and whether it is complete, and its freshness where it
  is read from a view (FR-009, FR-011); the original showed "Showing 4 of 4" or a count only.
- [x] **Times** name their zone ("Times are in …", FR-015); the original showed bare local times.
- [x] **Amounts** are per currency and never summed (FR-013): the original's dashboard and
  reports showed one currency for the brand ("Default currency" on Brand Config); the built
  dashboard has a panel per currency.
- [x] **Actions**: the original's inline row actions (Revoke on a bonus, Approve/Reject in the
  withdrawal queue, Deactivate on an offer) move to the record's own page, where the action states
  its effect first and offers only what the state allows (FR-005, FR-006, FR-026).
- [x] **Dropped screens**: casino content (ten Strapi screens, FR-043), payments Risk (a stub,
  spec 004 FR-036), the Bonus Offers *System* tab (TL function status, the twin substrate's), and
  the sub-brand's *Concepts* panel (twin concepts) have no built screen.

## Intended differences, screen by screen

| Original screen (capture id) | Built screen | Intended difference |
|---|---|---|
| Dashboard (`casino.dashboard`) | `/dashboard` | figures per currency; the change chip names its direction in a word (FR-040); live activity, top games and top players as in the original; a world map in `--surface-2` with the table beside it (design.md *Charts*) |
| — | `/` worklist | new (stories 1, FR-016 to FR-018); the original opened on the dashboard |
| Players (`casino.players`) | `/players` | the facet sidebar becomes a row of filters above the list (FR-009, FR-010: kept in the URL); search moves to the top bar for references (FR-019) |
| Player detail (`casino.player`, tabs) | `/players/{p}` and tabs | the header and four KPI cards become the standing above the tabs (FR-020: fits 1440 by 900); Activity, Timeline, Bets become one timeline with kinds as checkboxes (FR-021, FR-022); RG becomes Protection, KYC Identity, CRM part of Profile and Messages; VIP tier and PotVIP move to *Tier and tags*; Bonuses become Rewards; new tabs Notes, Wallets, Rounds, Sessions, Games played (gaps closed, parity.md) |
| Wallets (`casino.wallets`) | the player's Wallets tab; `/adjustments/*` | no brand-wide wallets screen: balances are read per player; adjustments to many players and to accounts are new (FR-028) |
| Withdrawals (`casino.withdrawals`) | `/withdrawals`, `/withdrawals/{id}` | one queue for the casino's and the payments' payouts (parity.md *changed: one queue*); the decision is taken on the request's page, which tells its whole story (FR-014, story 4) |
| Sessions, player session, game session timeline (`casino.sessions`, `casino.player-session`, `casino.game-session-timeline`) | `/players/sessions`, the player's Sessions and Rounds tabs | the session timeline is FR-023's; force-end is on the session, effect stated |
| Transaction search (`casino.transaction-search`) | the player's Rounds tab; search | parity.md *changed*: search by transaction id, type, status with totals is a gap in the owner |
| Bonuses (`casino.bonuses`) | `/rewards` | no inline Revoke (FR-026); the restricted funds row is on the player's Rewards tab |
| Bonus offers (`casino.bonus-offers`) | `/offers` | the manual grant form moves to the player's Rewards tab, with refusal reasons (parity.md); reordering by arrows as well as by dragging (keyboard, FR-040) |
| RG cases (`casino.rg`) | `/protection` | the brand-wide open list (a gap closed); filter by status, not by type pills |
| Fraud cases (`casino.fraud-cases`) | `/fraud` | review and dismiss on the case, effect stated |
| Team (`casino.team`, `payments.team`) | `/team`, `/team/roles` | the roles of the brand and what each carries, and giving them, are new (FR-008b to FR-008d); payments operators by level stay as in merchant |
| Providers, Game catalog (`casino.providers`, `casino.game-catalog`) | `/configuration/games`, `/configuration/callbacks` | one configuration area; unacknowledged callbacks are new (worklist) |
| Reporting (`casino.reporting`, tabs) | `/reports/{report}` | one route per report; export from the server (FR-033); day-end, compliance and disputes are new |
| Brand config (`casino.brand-config`) | `/configuration/brand`, `currencies`, `withdrawals`, `rewards` | split by owner (players, payouts, wallet); each field says when it differs from the brand's default; the ST8 lobby and deposit URLs are with the games |
| KYC policy (`casino.kyc-policy`) | `/configuration/identity-policy` | thresholds in the currency's units, not minor units; the licence profile applied as one action |
| Payments transactions (`casino.payments.transactions`, `payments.transactions`) | `/payments/deposits` | deposits only (pay-outs are the withdrawals queue); analytics on the same page |
| Hierarchy, merchant, CRM config, routing rules, PSPs, analytics (`*.payments.*`, `payments.*`) | `/payments/{area}` | one payments area with "Where you work" (the level) in place of the scope selector; deposit bounds, undelivered outcomes and lost answers are new; CRM configuration is the brand's engagement configuration (`/configuration/crm`); the sub-brand page loses the twin's *Concepts* and gains its merchant keys and rate limit |
| Routing rules canvas (`*.payments.routing-rules`) | `/payments/routing` | the drag-and-drop canvas becomes the draft and the tree in force drawn one above the other, the draft edited in place as a tree of controls, with versions listed: reachable by keyboard and screen reader (FR-040) |
| Affiliates (`casino.affiliates.*`) | `/affiliates/*` | the same sections; *view as the affiliate* closes a gap; fraud flags dismissed with a reason recorded by the owner |
| Accept invitation, reset password, not authorised (`*.accept-invitation`, `casino.reset-password`, `casino.not-authorised`) | merchant's invitation pages; the frame's refusal | sign-in is server-held (FR-003); a refused page names the role that gives it (FR-005) |
| Wrong portal (`casino.wrong-portal`, role `affiliate`) | the sign-in refusal | an affiliate has no operator role and is refused at sign-in |

## Findings

| Id | Screen | Seen | To close it | Resolution |
|---|---|---|---|---|
| DR-01 | every screen with a date (43 files: `areas/**`, `frame/Frame.tsx`) | times are formatted in the browser's zone (`Intl.DateTimeFormat().resolvedOptions().timeZone`): the pages said "Times are in Europe/London" for goldvibe, whose zone is `America/Sao_Paulo` (players' `reportingZone`, the doubles' brand) | FR-015 says the brand's zone: take it from `/api/me` (the brand's currency config) once, in the frame, and pass it down; a unit test that renders a page with a browser zone unlike the brand's | Resolved: b582e7eb (the server reads players' `reportingZone` as itself and answers it as `/api/me`'s `brand.zone`, UTC while never read; `useZone()` in `components/date.tsx`; the lint refuses `resolvedOptions().timeZone` under `src/web`; a unit test renders with a browser zone unlike the brand's) |
| DR-02 | dashboard, *Now and today* and the currency panels | the change chip's parts run together ("Levelyesterday by this hour3"); in the currency panels the chip wraps onto two lines inside its pill | the chip's `gap` is not in effect (another `.bo-chip` rule, `players.css`, wins); give the dashboard's chip its own class or spaces between the parts, and keep it on one line | Resolved: caa64530 |
| DR-03 | dashboard, trend | the panel says "Trend in EUR" with EUR chosen while the chart's caption says "GGR and NGR in BRL" and draws the BRL series | the heading and the series must name the same currency: request the chosen currency and title from the answer's (may be the seed answering every currency with BRL; check the request) | Resolved: caa64530 (the request did name the chosen currency; the seed answered BRL for all. The panels now title from the answer's currency and the seed answers the trend per currency) |
| DR-04 | player, standing, *Account* | "Last sign-in" has no value and its label sits over the registered time, so "14:00 BST" reads struck through | show the last sign-in or "never", in its own cell | Resolved: df010ecc |
| DR-05 | player, standing, *Wallets*, *Lifetime* | balances are body-size text; *Won* wraps alone onto a third row | design.md: balances and totals at 28 to 40 px; a 2 by 2 grid for the lifetime figures | Resolved: df010ecc |
| DR-06 | worklist, withdrawals, rewards, deposits (player column) | the player shows as the bare id (`p-ana`) in monospace, not as a link and not by name; on `/protection` the same id is a link | a record links to the records it names (FR-021, `links.tsx`) everywhere, with the name or username where the owner gives it (the original showed the email) | Resolved: 06dc1996 (linked by id: the owners' rows carry no name or username; showing the email would be a read per row) |
| DR-07 | `/withdrawals` | two pill rows both offer "Pending review"; the active state pill is outlined, not filled with `--primary` | one control for the queue's state; the active pill filled (design.md *Tokens*, `--primary`: "the active pill"); the same on `/rewards`, `/players` filters | Resolved: 63b30914 (/rewards and /players filter with selects, no pills to fill) |
| DR-08 | `/players` | the original's facets showed a count beside each value (status, identity queue, birthday window, VIP, online, country); the built filters show none | parity.md lists the counts with the facets (a gap); show the owner's counts in the options, or record the drop in parity.md | Resolved: de6b630f. Not a gap: the list already asks players for `facets=true` and shows each value's count in its option; the doubles never answered facets, so the review saw none. The a11y seed answers them now. parity.md's row (*Player list facets … counts*, `gap`) is stale: players answers them |
| DR-09 | `/reports/revenue` | the large figures overflow their columns and touch ("R$1,000.00R$800.00"); the From and To fields show `dd/mm/yyyy` while the report shows 1 to 31 Oct | figures wrap to a grid that fits the widest amount; the fields show the period shown | Resolved: 3b469c10 (period fields) and caa64530 (`.bo-figures` columns at 240 px, wide enough for the amounts) |
| DR-10 | `/configuration/brand`, `identity-policy`, `/team` roles, the player's Identity tab | forms and tables in browser defaults: unstyled selects and inputs at full width, fieldsets with the browser's border, the roles table centred with no card, the identity panel as an indented definition list; checkboxes far from their labels | the shared field, table and definition-list styles of `components.css` on these screens | Resolved: 87f06c1c |
| DR-11 | `/configuration/brand`, `/payments/merchant` | "Differs from the brand's default (none)" in the warning tone under every filled field | say it only when a default exists and differs; not in the warning colour (it is not a warning) | Resolved: 87f06c1c |
| DR-12 | `/configuration/identity-policy` and other configuration screens | no field says what it does; the original explained each ("Whether a player must have an approved KYC status before depositing") | a hint per field from the catalogue | Resolved: 87f06c1c (every field of brand, currencies, withdrawals, rewards, reporting, identity policy, games, CRM, support agent and attribution) |
| DR-13 | `/payments/deposits` | the status `SUCCEEDED` and the analytics tiles `FAILED`, `SUCCEEDED` are shown as the owner's codes | `status.succeeded` (and the analytics tile labels) in the catalogue | Resolved: de6b630f (the seed's SUCCEEDED and FAILED are codes merchant never answers; merchant's are pending, captured, failed, expired) |
| DR-14 | `/protection` | the case kind shows as `cooling_off` | a catalogue word for each case kind | Resolved: de6b630f |
| DR-15 | `/team` | no page title: the first heading is "Payments operators"; on `/payments/*` and `/affiliates/*` the page title is smaller than its section headings | one `h1` per page at the title size the other areas use | Resolved: e8bedca0 |
| DR-16 | `/affiliates` dashboard | "NaN" for first deposits by campaign and for registrations and first deposits by country | the page reads the owner's `ftdCount` and `registrationCount` correctly; the a11y seed answers `ftds` and `registrations` (`tests/a11y/seed.json`), so the suite never looked at real figures there: correct the seed, and show a dash, never NaN, for a missing figure | Resolved: 76e81587 (and the axe suite refuses NaN on every route) |
| DR-17 | worklist as `brand_support` | "Your roles admit no kind of work in this brand" and, below it, an empty table with "Nothing matches" | show only the sentence | Resolved: 8afcdf19 |
| DR-18 | primary actions | most screens draw their main action (Save the brand, Invite an operator, New offer, Run) as an outlined button; Export on the reports is filled while Run is not | the one primary action per screen in `--primary` (design.md); the rest outlined | Resolved: 3b469c10, 87f06c1c (a screen with two forms, currencies and zone, turnover and limits, a network beside the windows, draws only the first save in --primary) |
| DR-19 | `/affiliates` dashboard | the original's programme overview had traffic (impressions, clicks, unique clicks, CTR), acquisition rates (QFTDs, conversion, registration to first deposit) and the change against the previous month, with Prev and Next; the built dashboard has the four counts and the month's figures only, and its counts are small text | parity.md keeps the programme dashboard: show what the owner's `revenue/*` answers of these, with the change chip, or record what is dropped and why in parity.md | Resolved, (b) (Trevor, 2026-11-16). The counts are figure-size (e8bedca0). The programme counts traffic per period, affiliate and link (amendment A3, 0dfb3a16): clicks, registrations, first deposits and qualified first deposits (the deposit that sets the first-deposit guard). The dashboard shows them and the original's three rates (click to first deposit, click to registration, registration to first deposit), each with the change on the month before (the page's commit). Impressions, unique clicks and CTR are dropped and the page says so: the original counted none of them (its page always showed `—`), no brand reports an impression, and a click fact carries no visitor's digest (parity.md). Prev and Next stay the month field |

## What is left

- [ ] **The other roles**: only `platform_admin` is recorded. `brand_admin`, `brand_compliance`,
  `resource_finance`, `support_agent`, `affiliate_manager` and `affiliate` need their realm users
  (`tests/original/backoffice-screens/personas.sh`, run by a person), then the recording again
  (README *Running it*). Until then the per-role rows of the table above (what each role sees in
  the original) are read from the original's code (`App.tsx`'s nav gates, `RoleGuard`), not from
  captures.
- [ ] Screens the recording could not reach on the kind brand's data, `not reached` in
  `index.json`: a payment's detail (both apps) and a withdrawal's detail (goldvibe has no payments
  in the original's merchant), an affiliate's page (goldvibe has no affiliates there), and the game
  session timeline (no player session with a game session was followed). Seed a deposit, a
  withdrawal and an affiliate through the original's own routes and record those screens again
  (`ONLY=…`, README).
- [ ] The findings above, each fixed or decided; then this review again at the end of each phase.
