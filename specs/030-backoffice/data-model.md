# Data model: the backoffice

A brand's backoffice stores two things, both in its `backoffice-state`, and neither about a player.
Everything else it shows is read from an owner and held only while a page is open. The brand's
roles are not the backoffice's: `players` keeps them (below).

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
| `worklistKinds` | `Set[String]` | kinds shown; default all the roles admit |
| `onlineNotices` | `Boolean` | |
| `jobs` | `List[Job]`, at most 20, newest first | `Job(reference, kind, startedAt)`; no player id |

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
| operators' names by subject | 5 minutes | from merchant's `/v1/operators` |
| the brand's roles and their bags | 10 seconds | from players' `roles` |
| a brand's enabled modules | 1 minute | from players' `modules` |
| the route table, the permissions' names, the currency table | the build | from `routes.ts` and the contract's published files |

## Shapes the page's server makes (not stored)

### Worklist

`Worklist { kinds: [WorklistKind] }`;
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

## The brand's roles (`services/players/`, amendment P12)

### `Role` (event sourced entity; id: the role's name)

| Field | Type | Rule |
|---|---|---|
| `name` | `String` | lower case letters, digits and `_`, at most 40; not `platform_admin`, not `brand_<slug>`, not a payments role |
| `description` | `String` | at most 200 characters |
| `permissions` | `Set[String]` | each a name the contract knows |
| `default` | `Boolean` | one of the role matrix's roles: resettable, not deletable |

Events: `Defined`, `PermissionsChanged(before, after, by)`, `Reset(by)`, `Deleted(by)`.

Rules: the last role carrying `roles.change` cannot lose it or be deleted; the default roles are
seeded once, when no role exists; a deleted role's name may be used again.

`Roles` (view): every role with its permissions, read by `GET roles`.

### In each other service of the brand

`RoleBags` (in memory, per instance): the roles as last read, when they were read, and whether
`players` answered the last time. Nothing stored.

## What each owning service gains

New stored data in the owners (manager tags, seen devices, deposit bounds, the adjustment's cause, the
balances at a withdrawal request) is described where it is specified:
[contracts/amendments.md](contracts/amendments.md) P7, P8, P12, H3, W2, Y3.
