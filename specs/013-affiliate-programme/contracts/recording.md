# Recordings

Research R17. Constitution I: the plan names the recordings it replays and the parity tests it leaves.

## What exists

`tests/port/attribution/`: twelve recordings (four from the Go suite, eight from
`tests/original/attribution-driver/`), driving the original's click and `notify/*` routes and reading
its `affiliate-registration`, `affiliate-revenue` and `affiliate-facts` twins. The programme replays the
eight driver recordings' ledger reads as commission parity (the `notify/*` steps translated to the
facts spec 012 emits for them, as attribution's `Translate` does).

## What the driver records

`tests/original/programme-driver/` (Go, beside `attribution-driver/`, its recorder reused), against
the local kind stack with the affiliate service running, never a shared or production one. One
scenario per feature scenario the original can show:

| Driver scenario | Feature scenario(s) | Routes driven |
|---|---|---|
| `apply, approve, reject, reapply` | memberships: approve on default plan; rejected may reapply; duplicate refused | P7, O14, O15 |
| `plans` | managers: plan created or changed | O4–O6 |
| `cpa once` | commission: first deposit at or above minimum; later deposit; below minimum | notify/registration, notify/deposit, M1, M2 |
| `revshare and carryover` | commission: NGR fold; with and without carryover | notify/ggr, notify/bonus-cost, M1, M4 |
| `network override` | network: each ancestor's override; sees descendants; outside refused | M20, O8, M14, M15, M1 |
| `invite and join` | network: email invitation active at once; invite link pending | M20, M32, P3, P4, M17 |
| `close and pay` | settlement: close claims; below threshold; approve with figure; late fact reverts; paid unchanged; transition refused | O28, O40, O41, O42 |
| `links and codes` | links: new link; code unique; concurrent; revoke frees | M11, M12, M28–M30 |
| `managers` | managers: add, reset | O1–O3 |
| `fraud flags` | managers: flag raised once; dismiss tells the affiliate | notify/bonus-cost ×3, O48, O49, M24 |
| `notes, messages, creatives, settings, programme page` | managers: page change seen by applicants | O23–O25, O43–O47, O50–O52, P7 |
| `read-only view` | managers: read-only view | O29–O34 |
| `notifications` | communications: mark all read | M24, M25 |

Each recording is one parity test in `src/test/.../port/ParitySuite`: the requests are replayed with
the brand as a filter (portal reads gain `?brand=<slug>`, operator paths keep `{brand}`), the reshaped
routes (routes.md) translated, and each answer compared field by field after the translation of money
(major-unit strings to minor units), ids (twin UUIDs to the programme's ids) and player ids (to
pseudonyms).

## Marked divergences

A parity test that differs by design names the change it demonstrates: 2 (lifetime guard, `cpa once`
with deposits in two months), 6 (per-currency payouts), 11 (CPA in the plan's currency), 13 (no raw
status), 14 (override figure as an adjustment, `close and pay`), 15 (one clock). A field the programme adds (brand, currency) is ignored in comparison; a field it drops
(a player id) is a divergence of FR-038.
