// What the programme's pages share: a period chosen as a month, a read's loading and refusal, and the
// figures a revenue read answers, one row per currency (never summed across currencies, FR-013).
import { useId, type ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { Money } from "../../components/money.tsx";
import { refusalOf, type Figure } from "./programme.ts";

/** A month as the programme names a period (`yyyy-MM`). */
/**
 * A table wider than the page scrolls across inside this region, which the keyboard can reach and focus to
 * scroll (axe: scrollable-region-focusable), named by the table it holds.
 */
export function ScrollRegion({ label, children }: { label: MessageKey; children: ReactNode }) {
  return (
    <div className="bo-aff-scroll" role="region" aria-label={t(label)} tabIndex={0}>
      {children}
    </div>
  );
}

export function PeriodField({ value, onChange, label = "affiliates.period" }: { value: string; onChange(period: string): void; label?: MessageKey }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(label)}</label>
      <input id={id} type="month" value={value} onChange={(e) => e.target.value !== "" && onChange(e.target.value)} />
    </div>
  );
}

/** A read's answer, its refusal, or that it is being read. */
export function Read<T>({ q, children }: { q: UseQueryResult<T>; children(data: T): ReactNode }) {
  if (q.isError) return <RefusalNotice refusal={refusalOf(q.error)} />;
  if (q.data === undefined) return <p role="status">{t("list.loading")}</p>;
  return <>{children(q.data)}</>;
}

/** The figures' columns a table shows: money in the row's currency, counts as numbers. */
export type FigureColumn = { key: keyof Figure; label: MessageKey; money?: boolean };

export const RevenueColumns: readonly FigureColumn[] = [
  { key: "registrationCount", label: "affiliates.figure.registrations" },
  { key: "ftdCount", label: "affiliates.figure.ftds" },
  { key: "depositCount", label: "affiliates.figure.deposits" },
  { key: "totalDepositAmount", label: "affiliates.figure.depositAmount", money: true },
  { key: "ggr", label: "affiliates.figure.ggr", money: true },
  { key: "ngr", label: "affiliates.figure.ngr", money: true },
  { key: "cpaCommission", label: "affiliates.figure.cpa", money: true },
  { key: "revShareCommission", label: "affiliates.figure.revShare", money: true },
  { key: "overrideCommission", label: "affiliates.figure.override", money: true },
  { key: "adjustments", label: "affiliates.figure.adjustments", money: true },
  { key: "totalCommission", label: "affiliates.figure.totalCommission", money: true },
  { key: "fraudFlagCount", label: "affiliates.figure.fraudFlags" },
];

const counts = new Intl.NumberFormat("en-GB");

/** A count as a number, or a dash when the owner's answer has none: never "NaN" (DR-16). */
export function CountText({ value }: { value: unknown }) {
  return typeof value === "number" && Number.isFinite(value) ? <>{counts.format(value)}</> : <span className="bo-muted">—</span>;
}

/** A figure's cell: money in its currency, a count; a money figure of a row with no currency has none to show. */
export function FigureCell({ f, column }: { f: Figure; column: FigureColumn }) {
  const v = f[column.key] as number;
  if (!column.money) return <CountText value={v} />;
  return f.currency ? <Money value={{ minor: v, currency: f.currency }} /> : <span className="bo-muted">{t("affiliates.noCurrency")}</span>;
}

export function FiguresTable({ figures, caption, columns = RevenueColumns }: { figures: readonly Figure[]; caption: MessageKey; columns?: readonly FigureColumn[] }) {
  if (figures.length === 0) return <p className="bo-muted">{t("affiliates.noFigures")}</p>;
  return (
    <ScrollRegion label={caption}>
      <table className="bo-aff-table">
        <caption className="bo-sr-only">{t(caption)}</caption>
        <thead>
          <tr>
            <th scope="col">{t("affiliates.figure.currency")}</th>
            {columns.map((c) => (
              <th key={c.key} scope="col" data-numeric>
                {t(c.label)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {figures.map((f) => (
            <tr key={`${f.brandId}/${f.currency ?? ""}`}>
              <th scope="row">{f.currency ?? t("affiliates.noCurrency")}</th>
              {columns.map((c) => (
                <td key={c.key} data-numeric data-figure={c.key}>
                  <FigureCell f={f} column={c} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}
