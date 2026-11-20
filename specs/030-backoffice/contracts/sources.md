# Sources: where the worklist, the timeline and the search read

Paths are after each service's operator prefix; `{b}` is the brand. *(new)* marks a route or field
[amendments.md](amendments.md) adds.

## Worklist (FR-016 to FR-018; research R9)

| Kind | Permission needed | Source | Order, count |
|---|---|---|---|
| Withdrawal requests pending review | `withdrawal.review` | payouts `withdrawal-requests?status=PENDING_REVIEW&sort=oldest` *(sort new)* | oldest first, `total` |
| Withdrawal payments needing review | `withdrawal.review` | payouts `withdrawal-requests?status=ALL&payoutStatus=NEEDS_REVIEW&sort=oldest` | oldest first, `total` |
| Identity documents awaiting review | `identity.review` | kyc `queue?filter=awaiting` | oldest first, `counts`, paged *(new)* |
| Players flagged for review | `identity.review` | kyc `queue?filter=flagged` | oldest first |
| Open cases | `protection.change` | responsible-gambling `cases?status=open` *(new)* | oldest first, `total` |
| Open fraud cases | `fraud.review` | players `fraud-cases?status=open` | oldest first, `total` *(new)* |
| Unacknowledged calls | `callback.resolve` | game-provider `unacknowledged` | oldest first, `total` *(new)* |
| Undelivered facts | `fact.redeliver` | attribution `attribution-webhooks/undelivered` | oldest first, `total` *(new)* |
| Parked sends | `send.release` | engagement `parked-sends` | `total` |
| Undelivered outcomes | a payments role, at its level | psp-gateway `/v1/psp/outcomes/undelivered`, `/refused` | `total` *(new)* |

A kind the operator's roles do not admit is not asked for. Each source has 3 seconds; a failure or a
late answer makes the kind `unavailable` with the reason, and the others are shown.

An item acted on by someone else: the action's refusal from its owner (`NotPending`,
`still_unacknowledged`, …) is followed by a read of the item, which answers its state and actor.

## Timeline (FR-021, FR-022; research R10)

| Source | Lines | Read |
|---|---|---|
| players `players/{p}/activity` | deposits, withdrawals, rounds, rewards, account status, sessions | `from`, `to`, `after` *(new)*, `category` |
| players `players/{p}/notes` *(list new)*, `messages` | notes, messages | all, by time |
| wallet `wallets/{p}/{c}/transactions?type=adjustment` | adjustments with reason, category, cause *(new)*, actor *(new)* | `from`, `to`, page |
| payouts `players/{p}/withdrawal-history` | each request's states with actors | paged *(new)* |
| kyc `{p}/decisions` *(new)* | document reviews, mode, status and final decisions, with actor | `from`, `to` |
| responsible-gambling `{p}/history` *(operator route new)* | limits, breaks, exclusions, cases | `from`, `to` |

Every line: `at`, `kind`, `source`, `actor {kind: player | operator | system | provider, id, name?}`,
`cause {kind, ref}?`, `amount {minor, currency}?`, `links []`, `summary` (a message key and its
values, not prose: FR-040a).

The merge emits lines down to the latest of the sources' unread cursors, so a page is never missing
a line that a slower source would have put in it. Lines at the cut's own instant wait for the next
page, which takes lines at or before its `after` (the page before's `next`), so a line at that
instant the holding source did not give is not lost. A source that fails is listed in
`gaps [{source, from, to, reason}]` and the page says the rest is incomplete. So is one that takes
no period (messages, withdrawals) when it holds more and every line it gave is after the period:
reason `beyond_page`.

## Search (FR-019; research R11)

| What was pasted looks like | Asked |
|---|---|
| a UUID | players `players/{id}`; payouts `withdrawal-requests/{id}`; rewards `reward-grants/{id}`; merchant `/v1/payments/{id}` |
| an email | players `players?search=` |
| a document reference (`R-…`) | reporting `documents/{reference}` |
| a provider's round or transaction id (the brand's providers' formats) | game-provider `rounds/by-round/{roundId}` *(new)*, `rounds/by-transaction/{txId}` *(new)* |
| a payment reference | merchant `/v1/payments/cashier?reference=` *(new)* |
| anything else | players `players?search=` only |

A value that matches more than one shape is asked of each; every match is listed with its kind and
the player it belongs to. Nothing that is not shaped like an id is ever sent as one.
