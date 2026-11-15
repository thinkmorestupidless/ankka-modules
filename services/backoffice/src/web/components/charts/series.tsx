// Line and candle series on a faint grid (contracts/design.md). The marks are drawn for the eye only
// (`aria-hidden`); the same values are a table beside them that a screen reader reads, each cell the
// owner's own text for it (an amount as Money shows it), never a value rounded for drawing.
import type { ReactNode } from "react";
import "./charts.css";
import { t } from "../../../shared/messages/index.ts";

export interface Point {
  /** The x axis' label: an hour, a day, a week. */
  label: string;
  /** The value drawn; a float is fine here, it only places the mark. */
  value: number;
  /** What a person reads for it. */
  text: ReactNode;
}

export interface Series {
  name: string;
  points: Point[];
}

const W = 1000;
const H = 260;
const Pad = { top: 12, right: 12, bottom: 28, left: 12 };

/** The y extent of `values`, zero always in it, and a little room above and below. */
export function extent(values: number[]): [number, number] {
  const lo = Math.min(0, ...values);
  const hi = Math.max(0, ...values);
  if (lo === hi) return [lo - 1, hi + 1];
  const room = (hi - lo) * 0.08;
  return [lo < 0 ? lo - room : lo, hi + room];
}

export function scaleY([lo, hi]: [number, number]) {
  return (v: number) => Pad.top + (H - Pad.top - Pad.bottom) * (1 - (v - lo) / (hi - lo));
}

export function scaleX(n: number) {
  const inner = W - Pad.left - Pad.right;
  return (i: number) => Pad.left + (n <= 1 ? inner / 2 : (inner * i) / (n - 1));
}

/** Every `step`th label, so the axis is not crowded. */
const every = (n: number) => Math.max(1, Math.ceil(n / 8));

function Grid({ y, ticks }: { y: (v: number) => number; ticks: number[] }) {
  return (
    <g className="bo-chart-grid">
      {ticks.map((v) => (
        <line key={v} x1={Pad.left} x2={W - Pad.right} y1={y(v)} y2={y(v)} />
      ))}
    </g>
  );
}

function ticksOf([lo, hi]: [number, number]): number[] {
  return [0, 1, 2, 3, 4].map((i) => lo + ((hi - lo) * i) / 4);
}

function XAxis({ labels, x }: { labels: string[]; x: (i: number) => number }) {
  const step = every(labels.length);
  return (
    <g className="bo-chart-axis">
      {labels.map((l, i) =>
        i % step === 0 ? (
          <text key={`${l}-${i}`} x={x(i)} y={H - 8} textAnchor="middle">
            {l}
          </text>
        ) : null,
      )}
    </g>
  );
}

function Swatch({ n }: { n: number }) {
  return (
    <svg viewBox="0 0 24 8" aria-hidden="true">
      <line className="bo-chart-line" data-series={n} x1="0" x2="24" y1="4" y2="4" />
    </svg>
  );
}

/** One line per series (at most three, each in its own token and dash), and the table of them all. */
export function LineChart({ title, series }: { title: string; series: Series[] }) {
  const labels = series[0]?.points.map((p) => p.label) ?? [];
  const domain = extent(series.flatMap((s) => s.points.map((p) => p.value)));
  const y = scaleY(domain);
  const x = scaleX(labels.length);
  return (
    <figure className="bo-chart">
      <figcaption>{title}</figcaption>
      <ul className="bo-chart-legend" aria-hidden="true">
        {series.map((s, i) => (
          <li key={s.name}>
            <Swatch n={i + 1} />
            {s.name}
          </li>
        ))}
      </ul>
      <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" data-chart="line">
        <Grid y={y} ticks={ticksOf(domain)} />
        {series.map((s, i) => (
          <polyline
            key={s.name}
            className="bo-chart-line"
            data-series={i + 1}
            points={s.points.map((p, j) => `${x(j).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ")}
          />
        ))}
        <XAxis labels={labels} x={x} />
      </svg>
      <table className="bo-sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">{t("chart.when")}</th>
            {series.map((s) => (
              <th key={s.name} scope="col">
                {s.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {labels.map((l, j) => (
            <tr key={`${l}-${j}`}>
              <th scope="row">{l}</th>
              {series.map((s) => (
                <td key={s.name} data-numeric>
                  {s.points[j]?.text}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

export interface Candle {
  label: string;
  open: number;
  high: number;
  low: number;
  close: number;
  /** What a person reads for each of the four. */
  text: { open: ReactNode; high: ReactNode; low: ReactNode; close: ReactNode };
}

/** Candles: the body from open to close, the wick from low to high; rising hollow, falling filled. */
export function CandleChart({ title, candles }: { title: string; candles: Candle[] }) {
  const domain = extent(candles.flatMap((c) => [c.high, c.low]));
  const y = scaleY(domain);
  const x = scaleX(candles.length);
  const width = Math.max(3, Math.min(18, ((W - Pad.left - Pad.right) / Math.max(1, candles.length)) * 0.6));
  return (
    <figure className="bo-chart">
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" data-chart="candle">
        <Grid y={y} ticks={ticksOf(domain)} />
        {candles.map((c, i) => {
          const up = c.close >= c.open;
          const top = y(Math.max(c.open, c.close));
          const bottom = y(Math.min(c.open, c.close));
          return (
            <g key={`${c.label}-${i}`} className="bo-candle" data-direction={up ? "up" : "down"}>
              <line x1={x(i)} x2={x(i)} y1={y(c.high)} y2={y(c.low)} />
              <rect x={x(i) - width / 2} width={width} y={top} height={Math.max(1, bottom - top)} />
            </g>
          );
        })}
        <XAxis labels={candles.map((c) => c.label)} x={x} />
      </svg>
      <table className="bo-sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">{t("chart.when")}</th>
            <th scope="col">{t("chart.open")}</th>
            <th scope="col">{t("chart.high")}</th>
            <th scope="col">{t("chart.low")}</th>
            <th scope="col">{t("chart.close")}</th>
            <th scope="col">{t("chart.direction")}</th>
          </tr>
        </thead>
        <tbody>
          {candles.map((c, i) => (
            <tr key={`${c.label}-${i}`}>
              <th scope="row">{c.label}</th>
              <td data-numeric>{c.text.open}</td>
              <td data-numeric>{c.text.high}</td>
              <td data-numeric>{c.text.low}</td>
              <td data-numeric>{c.text.close}</td>
              <td>{t(c.close >= c.open ? "chart.rose" : "chart.fell")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
