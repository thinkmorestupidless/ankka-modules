# Data model: the backoffice

The backoffice stores two things, both in `backoffice-state`, and neither about a player. Everything
else it shows is read from an owner and held only while a page is open.

## Stored (`services/backoffice-state/`)

### `Session` (event sourced entity; id: a random 256-bit session id)

| Field | Type | Note |
|---|---|---|
| `subject` | `Personal[String]` under `operator/<subject>` | the realm's subject |
| `name`, `email` | `Personal[String]` | from the id token, for display |
| `accessToken`, `refreshToken` | `Secret` (never logged, never in an event's `toString`) | the realm's |
| `accessExpiresAt`, `refreshExpiresAt` | `Instant` | |
| `roles` | `Set[String]` | from the access token, as last refreshed |
| `endedAt` | `Option[Instant]` | |

Events: `Opened`, `Refreshed`, `Ended(reason: signed_out | expired | revoked | erased)`.

Rules:

- `token` answers the access token when 30 seconds or more remain; else it refreshes, one refresh
  at a time (the entity is the one writer), records `Refreshed`, and answers the new one.
- a refresh the realm refuses ends the session (`revoked`); every later `token` is `404`.
- a session ends at the refresh token's expiry by a timer; an ended session's tokens are cleared
  from its state by the `Ended` event, and the entity is deleted after 24 hours.
- `roles` changing between two refreshes is answered with `rolesChanged: true`, once.

### `Preferences` (key value entity; id: the operator's subject)

| Field | Type | Rule |
|---|---|---|
| `theme` | `dark` or `light` | default `dark` |
| `lastBrand` | `Option[String]` | a slug; ignored when the roles no longer name it |
| `worklistKinds` | `Set[String]` | kinds shown; default all the roles admit |
| `onlineNotices` | `Map[String, Boolean]` | per brand |
| `jobs` | `List[Job]`, at most 20, newest first | `Job(reference, kind, brand, startedAt)`; no player id |

A write that names a player id, or any key not above, is refused: `400 unknown_preference`.

### `SessionsBySubject` (view)

Session ids by subject, for sign-out everywhere and erasure.

### Erasure

`DELETE /subjects/{subject}` ends the subject's sessions (`erased`) and deletes the preferences;
the erasure handler does the same for the `operator/<subject>` prefix.

## Held in the page's server, in memory only

| What | Life | Note |
|---|---|---|
| a session's access token | to its expiry | keyed by session id; never written anywhere |
| operators' names by subject | 5 minutes, per brand | from merchant's `/v1/operators` |
| a brand's enabled modules | 1 minute | from players' `modules` |
| the route table, the matrix, the currency table | the build | from `routes.ts` and the contract's published files |

## Shapes the page's server makes (not stored)

### Worklist

`Worklist { brand, kinds: [WorklistKind] }`;
`WorklistKind { kind, state: ok | unavailable, reason?, total, items: [WorklistItem] }`;
`WorklistItem { id, kind, subject: {player?, name?}, since: Instant, summary: Message, actedBy?: Actor, actedWhat?: Message }`.

### Timeline

`Timeline { lines: [Line], next?: Cursor, gaps: [Gap] }`;
`Line { at, kind, source, actor: Actor, cause?: {kind, ref}, amount?: Money, links: [Link], summary: Message }`;
`Actor { kind: player | operator | system | provider, id?, name? }`;
`Gap { source, from, to, reason }`;
`Cursor`: one position per source, opaque to the page.

Ordering: by `at` descending, then `source`, then the source's own id, so two reads agree.

### Reference

`Match { kind: player | withdrawal_request | reward | round | provider_transaction | payment | document, id, player?, summary: Message }`.

### Adjustment batch

`Batch { id: sha256(reason, category, cause, rows), reason, category, cause?, rows: [Row] }`;
`Row { n, player, currency, walletType: real | restricted, direction: credit | debit, amountMinor: Long }`;
`RowOutcome { n, outcome: made | refused | already_made, reason?, before?, after? }`.

The wallet's key for row `n` is `batch/<id>/<n>`. A row appearing twice in a list is two rows with
two keys (the spec's edge case), and the result says so.

### Money, dates, messages

- `Money { minor: bigint as a string in JSON, currency }`; shown by the contract's currency table.
  The page never parses a decimal amount an owner answers when `…Minor` is present.
- instants are shown in the brand's zone (players' `currency-config`), with the zone named; a date
  filter's end is the last instant of its day in that zone.
- `Message { key, values }`: the catalogue's key, never prose, so another language is a
  translation (FR-040a).

## What each owning service gains

New stored data in the owners (manager tags, seen devices, deposit bounds, the adjustment's cause, the
balances at a withdrawal request) is described where it is specified:
[contracts/amendments.md](contracts/amendments.md) P7, P8, H3, W2, Y3.
