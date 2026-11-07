# programme's recordings (spec 013, T006)

Recorded 2026-11-07 on the original at `7630feb0`, both runs passing. Thirteen recordings, one per driver scenario of
`specs/013-affiliate-programme/contracts/recording.md`, made by `tests/original/programme-driver/` on the
original's KinD stack with `affiliate-service` installed, twice, on fresh run tokens, and merged with
`tests/original/merge`. `services/affiliate-programme`'s
`src/test/scala/io/eitheror/casino/programme/port/ParitySuite` replays each as one parity test.

| Recording (the feature scenario it shows first) | Driver scenario | Requirement |
|---|---|---|
| a manager for a brand approves an application on that brand's default plan and a manager for another brand cannot see it | `apply, approve, reject, reapply` | 013/FR-003 |
| a plan created or changed applies to the memberships that name it from the next fact onward and is audited | `plans` | 013/FR-006 |
| a first deposit at or above the plan's minimum deposit writes one CPA fact in the plan's currency | `cpa once` | 013/FR-013 |
| GGR, bonus cost and chargeback facts fold into NGR and a RevShare fact for the delta | `revshare and carryover` | 013/FR-014 |
| each ancestor is written an override fact for its percentage of the source's earnings, recomputed as a delta | `network override` | 013/FR-015 |
| an email invitation makes the sub active at once on the parent's plan unless it says otherwise | `invite and join` | 013/FR-027 |
| a close claims every unclaimed payable fact up to the period's end for a pending payout | `close and pay` | 013/FR-018 |
| a bonus code is unique at a brand regardless of case | `links and codes` | 013/FR-025 |
| adding, resetting or revoking a manager for a brand provisions them with the manager role and the brand's role and is audited | `managers` | 013/FR-031 |
| a fraud flag is raised once per player, brand and reason when a brand's thresholds are crossed | `fraud flags` | 013/FR-032 |
| a change to a brand's programme page is what new applicants see | `notes, messages, creatives, settings, programme page` | 013/FR-034 |
| a manager's read-only view of an affiliate shows what the affiliate sees and changes nothing | `read-only view` | 013/FR-033 |
| reading notifications or marking all read marks them read for that affiliate only | `notifications` | 013/FR-035 |

A recording is named by one feature scenario; the other feature scenarios it shows are its `Def`'s
`Covers` in `tests/original/programme-driver/scenarios.go`. The driver's `NotDriven` and
`NotDrivenFeatures` say why every other scenario of `features/programme/` is not recorded; its offline
test holds both lists to the features and the thirteen names to the contract.

## How they are made

`tests/original/programme-driver/doc.go` has the command, the flags and the environment. In short:

- the original's KinD stack, its affiliate service on `localhost:8093` and Keycloak on `localhost:8180`,
  with the service's Customer.io settings empty so no email leaves the machine;
- a staff member of the realm `eitheror` holding `brand_admin` and `brand_spinvibe`, signed in through
  `casino-backoffice`; the managers routes take no `affiliate_manager`;
- each affiliate a scenario makes (by the backoffice's account creation, an approval, or a parent's
  invitation) is given a password through the master realm's admin API and signed in through
  `affiliate-portal`. The original's own way, the accept-invitation route, needs the token it only emails.

Every scenario runs on the seeded brand `spinvibe` (an affiliate's login takes `brand_spinvibe`, a role
that exists only for a seeded brand), so brand-wide lists (plans, payouts, flags, creatives, managers,
the close's answer) hold earlier runs' rows: the driver checks only its own and marks the rest as
varying. The run also changes the brand: its settings are put at the original's defaults with no fees,
its programme page is changed, and each close closes the period for every affiliate of the brand.

Requests carry placeholders, never a run's values: `{brand}`, `{run}` (in emails, plan names and codes),
`{period}`, `{previousPeriod}` and `{nextPeriod}`, ids the driver mints (`{player}`, `{txn2}`), ids the
original names deterministically and the driver computes (`{plan}`, `{payout}`, `{flag}`), and ids the
original mints, captured from the answer that gave them (`{affiliate}`, `{affiliateBtag}`, `{codeId}`).
A bearer token is recorded as `Bearer {staffToken}` or `Bearer {<who>Token}`; the internal secret, the
admin's token and every password are not recorded.

## How they are replayed

As contracts/recording.md says: the brand is a filter (portal reads gain `?brand=<slug>`, operator
paths keep `{brand}`), the reshaped routes are translated, a `notify/*` step becomes the fact spec 012
emits for it, a `setup:` step becomes kit setup, and each answer is compared field by field after the
translation of money (major-unit strings to minor units), ids (the original's UUIDs to the programme's)
and player ids (to pseudonyms). A field the programme adds (brand, currency) is ignored; a field it
drops (a player id) is a divergence of FR-038.

## Marked divergences

A parity test that differs by design names the change of the spec it demonstrates:

| Change | Where it shows |
|---|---|
| 2, the lifetime first-deposit guard | not in `cpa once`: the original's period is its server's month, which a run cannot move, so its deposits are in one month; `CommissionSuite` holds the rule |
| 6, per-currency payouts | `close and pay`: the original names a payout by affiliate and period only |
| 11, CPA in the plan's currency | `cpa once`: the original's CPA fact takes the deposit's currency |
| 13, no raw status | not driven: the original's `PATCH accounts/{aff}/status` is not served by the programme |
| 14, the override figure as an adjustment | `close and pay`: the original rewrites the payout's override and total with no fact |
| 15, one clock | every recording with a period: the original's fact period is its server's month, a period ends at a UTC boundary |

Two answers of the original differ from their feature and are recorded as they are: a payout moved
from paid to pending is refused `400` before the payout is read, so the refusal does not name the
current status (`close and pay`); and the original's application reads nothing of the programme page.

## The import's export (T088)

`import/` will hold `bundle.json` and `revenue.json`, written from the original's Spinvibe twins by
`tests/original/programme-export` (`-out` and `-expected`) on the local KinD stack, with the brand's
pseudonym key read from its `attribution`'s store at run time. `services/affiliate-programme`'s
`port/ImportParitySuite` imports the bundle and holds every affiliate's revenue, period by period and
field by field, to the original's; it fails until both files are here.

## What the first runs taught the driver

- The original's reads answer a twin, `{"properties": {...}, "twinId": ...}`; the driver reads its
  fields inside `properties`.
- Its lists, its duplicate-email check and the join route scan a namespace that catches up some time
  after a write is answered. The driver reads a list until the row it wants is there (`StaffRow`,
  `Settled`) before a step that depends on it; a join spends its one attempt per email before the scan,
  so the preview is read first.
- An internal deposit below the minimum answers `{"ok":true}`; a first deposit at the minimum or above
  answers the affiliate it pays.
- The read-only view's dashboard and stats take `?period=`.
- An invited sub's realm user has no name, which the realm's profile requires; the driver names it
  before signing in, as accepting the invitation would.
