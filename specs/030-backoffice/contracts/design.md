# Design tokens and the screen frame

The spec's *Visual direction*, as values the build is checked against. Colours are OKLCH; each pair
named below must meet WCAG 2.2 AA (4.5:1 for text, 3:1 for marks and focus); a unit test over this
table checks it, and a value that fails is changed here, not excused.

## Tokens (`services/backoffice/src/web/tokens.css`)

| Token | Dark (default) | Light | Use |
|---|---|---|---|
| `--surface-0` | `oklch(0.16 0.012 155)` | `oklch(0.98 0.004 155)` | the page |
| `--surface-1` | `oklch(0.20 0.014 155)` | `oklch(1 0 0)` | cards |
| `--surface-2` | `oklch(0.25 0.016 155)` | `oklch(0.95 0.006 155)` | raised, hover, table header |
| `--text` | `oklch(0.95 0.01 155)` | `oklch(0.22 0.02 155)` | |
| `--text-muted` | `oklch(0.72 0.02 155)` | `oklch(0.45 0.02 155)` | |
| `--primary` | `oklch(0.84 0.22 145)` | `oklch(0.52 0.17 145)` | primary action, positive, the active pill |
| `--on-primary` | `oklch(0.18 0.03 145)` | `oklch(0.99 0 0)` | text on primary |
| `--series-2` | `oklch(0.68 0.25 340)` | `oklch(0.52 0.22 340)` | magenta, second series |
| `--series-3` | `oklch(0.88 0.17 95)` | `oklch(0.53 0.12 85)` | yellow, third series; the warning tone's text |
| `--danger` | `oklch(0.66 0.22 25)` | `oklch(0.52 0.21 25)` | loss, failure, danger only |
| `--focus` | `oklch(0.90 0.15 145)` | `oklch(0.45 0.17 145)` | 2 px ring, 2 px offset |
| `--radius-card` | `20px` | | cards have no border; 16 px gap between |
| `--radius-pill` | `999px` | | tabs, range toggles, chips |

Type: one variable sans for text, tabular figures everywhere an amount or a count is shown; figures
for balances and totals at 28 to 40 px with a change chip (arrow and sign, not colour alone).

## Frame

- A 64 px icon rail at the left; the current area a filled circle; labels on hover and focus, and
  always for a screen reader.
- A top bar: the brand (always visible, changed in one step), the one search, freshness, jobs and
  notices, the operator.
- Density: tables at 36 px rows, 13 px text; the player page's standing fits 1440 by 900 above the
  timeline (FR-020). Below 1280 px the rail collapses and editing is not offered (FR-041).

## State is never colour alone (FR-040)

Every status has a word and an icon; a provisional figure has a dashed underline and the word; a
disabled action shows the role it needs in text beside it.

## Charts

Line and candle series on a faint grid; donut breakdowns with labels outside; a dot grid for
activity by hour; the world map in `--surface-2` with countries filled by a single-hue scale of
`--primary`, each with its figure in a table beside it for keyboard and screen-reader use.
