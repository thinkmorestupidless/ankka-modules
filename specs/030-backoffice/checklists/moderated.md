# Moderated test: finding a player and approving a withdrawal

**Purpose**: The script and result sheet for SC-002 and SC-003, which need people (research,
*Design finished*; quickstart §7). Run at the end of phase 1 (SC-002) and phase 2 (SC-003), and
again before a brand goes live.
**Feature**: [spec.md](../spec.md)
**Results**: left blank here; the product owner fills them in and signs.

## The criteria

- **SC-002**: an operator finds a player from any of the reference kinds of FR-019 in under ten
  seconds and one search, in nine of ten tries, with three operators.
- **SC-003**: a finance operator reviews and approves a withdrawal request in under one minute
  without leaving its page, using only what the page shows.

## Setting up

- [ ] A local installation with the brand applied and seeded: `just modules apply goldvibe
  --installation dev-kind`, then `just backoffice-seed goldvibe` (quickstart §6).
- [ ] Three operators, none of whom built the backoffice; people who ran the original's console if
  possible. Each has an account in the `eitheror` realm holding `brand_goldvibe` and, for SC-002,
  `brand_support`; for SC-003, `brand_finance`.
- [ ] A desktop browser at 1440 by 900 (at least 1280 wide: narrower is read-only, FR-041), signed
  in, the worklist open, nothing else open.
- [ ] A stopwatch, or a screen recording from which the times are read afterwards.
- [ ] The references below chosen from the seeded brand and written on cards, one per card, before
  the first operator arrives. Each reference names exactly one player; choose them so that no two
  operators are given the same card.
- [ ] For SC-003, three withdrawal requests per operator in `pending review` that the brand's rules
  allow to be approved (identity verified, no open case, the balance covers the hold), chosen and
  written on cards.

## The script (read to each operator)

> "We are testing the backoffice, not you. I will hand you a card with something a player or a
> payment might be known by. Find that player's page. Use the backoffice only. Say 'there' when you
> are looking at the player's page. I can't help during a try. You can stop any try and we move on."

For SC-003:

> "I will hand you a card with a withdrawal request. Open it, decide, and approve it if the page
> tells you it is right to. Stay on the request's page. Say 'done' when the approval is confirmed."

## How a try is counted

- **SC-002**: the clock starts when the card is face up and stops when the player's page is shown.
  A try passes when the page is the right player's, in under 10 seconds, after one search (one
  submission in the search field; choosing a match from its answer is part of that search). A
  second search, a wrong player, a browse through a list instead, or 10 seconds passed is a fail.
- **SC-003**: the clock starts when the request's page is shown and stops when the approval is
  confirmed. A try passes in under 60 seconds with the operator never leaving the page (no other
  page, tab or tool) and asking nothing. Approving a request the page shows should not be approved
  is a fail whatever the time.
- A try spoiled by the test itself (the installation down, a wrong card) is noted and repeated with
  a fresh card; it is not counted.
- SC-002 passes when every operator passes at least nine of their ten tries. SC-003 passes when
  every try of every operator passes.

## Result sheet: SC-002, finding a player

Ten tries per operator: each of the nine reference kinds once, and a name a second time (the kind
most likely to match several players). Times in seconds.

| # | Reference kind (FR-019) | Operator 1: reference · time · searches · pass | Operator 2: reference · time · searches · pass | Operator 3: reference · time · searches · pass |
|---|---|---|---|---|
| 1 | player id | | | |
| 2 | email | | | |
| 3 | name | | | |
| 4 | provider round id | | | |
| 5 | provider transaction id | | | |
| 6 | payment reference | | | |
| 7 | withdrawal request id | | | |
| 8 | reward id | | | |
| 9 | reference number (an export's or a report's) | | | |
| 10 | name (a second one) | | | |
| | **Tries passed, of 10** | | | |

## Result sheet: SC-003, approving a withdrawal request

| # | Operator 1: request · time · stayed on the page · pass | Operator 2: request · time · stayed on the page · pass | Operator 3: request · time · stayed on the page · pass |
|---|---|---|---|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| **Tries passed, of 3** | | | |

## What the operators said

After each operator's tries, ask: "What slowed you down?" and, for SC-003, "What on the page told
you it was right to approve?" Write down the answers as said.

| Operator | SC-002 | SC-003 |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |

## Outcome

- [ ] SC-002 passed
- [ ] SC-003 passed
- Findings raised (each as a task or an amendment):
- Run on (date), at (installation and backoffice version):
- Moderator:
- Signed by the product owner:
