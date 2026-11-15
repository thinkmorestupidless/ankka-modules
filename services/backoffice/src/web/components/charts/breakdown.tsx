// A donut breakdown with its labels outside (contracts/design.md), and a dot grid of activity by hour.
// Both draw for the eye only; the table under each is what a screen reader reads.
import type { ReactNode } from "react";
import "./charts.css";
import { t } from "../../../shared/messages/index.ts";

export interface Slice {
  name: string;
  /** The value drawn: a share of the whole, in one unit (one currency's amounts, or counts). */
  value: number;
  text: ReactNode;
}

const Tones = [1, 2, 3];

/** The arc of a slice from `a0` to `a1` (radians from twelve o'clock), as a path. */
function arc(cx: number, cy: number, r: number, a0: number, a1: number): string {
  const p = (a: number) => [cx + r * Math.sin(a), cy - r * Math.cos(a)] as const;
  const [x0, y0] = p(a0);
  const [x1, y1] = p(a1);
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
}

/** Shares of one whole. Slices with nothing are listed in the table and not drawn. */
export function Donut({ title, slices, centre }: { title: string; slices: Slice[]; centre?: string }) {
  const total = slices.reduce((s, x) => s + Math.max(0, x.value), 0);
  const cx = 160;
  const cy = 110;
  const r = 64;
  let a = 0;
  const drawn = slices.flatMap((s, i) => {
    if (total <= 0 || s.value <= 0) return [];
    const a0 = a;
    const a1 = a + (2 * Math.PI * s.value) / total;
    a = a1;
    const mid = (a0 + a1) / 2;
    const out = (k: number) => [cx + k * Math.sin(mid), cy - k * Math.cos(mid)] as const;
    return [{ s, i, a0, a1: Math.min(a1, a0 + 2 * Math.PI - 1e-4), mid, out }];
  });
  return (
    <figure className="bo-chart">
      <figcaption>{title}</figcaption>
      <svg viewBox="0 0 320 220" aria-hidden="true" data-chart="donut">
        <circle className="bo-donut-track" cx={cx} cy={cy} r={r} strokeWidth={22} />
        {drawn.map(({ s, i, a0, a1 }) => (
          <path key={s.name} d={arc(cx, cy, r, a0, a1)} data-series={Tones[i % Tones.length]} fill="none" strokeWidth={22} style={{ fill: "none" }} />
        ))}
        {drawn.map(({ s, mid, out }) => {
          const [x0, y0] = out(r + 12);
          const [x1, y1] = out(r + 26);
          const right = Math.sin(mid) >= 0;
          return (
            <g key={`${s.name}-label`}>
              <polyline className="bo-donut-leader" points={`${x0},${y0} ${x1},${y1} ${x1 + (right ? 8 : -8)},${y1}`} />
              <text className="bo-chart-label" x={x1 + (right ? 11 : -11)} y={y1 + 4} textAnchor={right ? "start" : "end"}>
                {s.name}
              </text>
            </g>
          );
        })}
        {centre !== undefined ? (
          <text className="bo-donut-centre" x={cx} y={cy + 7} textAnchor="middle">
            {centre}
          </text>
        ) : null}
      </svg>
      <table className="bo-sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">{t("chart.part")}</th>
            <th scope="col">{t("chart.value")}</th>
            <th scope="col">{t("chart.share")}</th>
          </tr>
        </thead>
        <tbody>
          {slices.map((s) => (
            <tr key={s.name}>
              <th scope="row">{s.name}</th>
              <td data-numeric>{s.text}</td>
              <td data-numeric>{total > 0 ? t("chart.percent", { value: Math.round((Math.max(0, s.value) * 100) / total) }) : t("chart.none")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/** Counts by row and column (activity by kind and hour): a dot's area is its count. */
export function DotGrid({ title, rows, columns, counts }: { title: string; rows: string[]; columns: string[]; counts: number[][] }) {
  const max = Math.max(0, ...counts.flat());
  const cell = 22;
  const left = 120;
  const width = left + columns.length * cell + 8;
  const height = rows.length * cell + 24;
  const step = Math.max(1, Math.ceil(columns.length / 12));
  return (
    <figure className="bo-chart">
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${width} ${height}`} aria-hidden="true" data-chart="dots">
        {rows.map((r, i) => (
          <g key={r}>
            <text className="bo-chart-label" x={left - 8} y={i * cell + cell / 2 + 4} textAnchor="end">
              {r}
            </text>
            {columns.map((c, j) => {
              const n = counts[i]?.[j] ?? 0;
              const radius = n > 0 && max > 0 ? 2 + 7 * Math.sqrt(n / max) : 2;
              return <circle key={c} className="bo-dot" data-empty={n === 0 || undefined} cx={left + j * cell + cell / 2} cy={i * cell + cell / 2} r={radius} />;
            })}
          </g>
        ))}
        <g className="bo-chart-axis">
          {columns.map((c, j) =>
            j % step === 0 ? (
              <text key={c} x={left + j * cell + cell / 2} y={rows.length * cell + 16} textAnchor="middle">
                {c}
              </text>
            ) : null,
          )}
        </g>
      </svg>
      <table className="bo-sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">{t("chart.part")}</th>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r}>
              <th scope="row">{r}</th>
              {columns.map((c, j) => (
                <td key={c} data-numeric>
                  {counts[i]?.[j] ?? 0}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
