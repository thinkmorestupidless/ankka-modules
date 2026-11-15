// What the dashboard and the reports show beside reporting's figures: each amount by the one money
// rule, and each figure's freshness in reporting's own terms (FR-011). reporting cannot say when a
// view last applied a change; it says the first day each view's figures are complete from, and whether
// it is rebuilding (spec 015 FR-004). So a panel says "complete from <day>" for the views it reads, and
// is provisional while reporting rebuilds or its status cannot be read.
import { useQuery } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { Money } from "../../components/money.tsx";
import { Glyph } from "../../components/glyph.tsx";
import { minorOf } from "./read.ts";

/** A reporting day (`YYYY-MM-DD`, a calendar day of the brand's zone) as a person reads it: `3 Oct 2026`. */
export function formatDay(day: string): string {
  const at = Date.parse(`${day}T00:00:00Z`);
  if (Number.isNaN(at)) return day;
  return new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" }).format(at);
}

/** An amount reporting answered as its decimal text; shown as given, with its currency, if it is not one. */
export function Amount({ text, currency }: { text: string | null | undefined; currency: string }) {
  const minor = minorOf(text, currency);
  if (minor === undefined) return <span className="bo-money" data-figure>{t("dashboard.amountAsGiven", { amount: text ?? "", currency })}</span>;
  return <Money value={{ minor, currency }} />;
}

export interface ReportingView {
  name: string;
  completeFrom?: string | null;
  rebuilding: boolean;
}

export type ReportingStatus = { state: "ok"; views: ReportingView[] } | { state: "unavailable" };

export async function readReportingStatus(fetchFn: typeof fetch = fetch): Promise<ReportingStatus> {
  try {
    const r = await fetchFn("/api/freshness?services=reporting", { headers: { Accept: "application/json" } });
    if (!r.ok) return { state: "unavailable" };
    const b = (await r.json()) as { services: { service: string; state: string; views?: ReportingView[] }[] };
    const s = b.services.find((x) => x.service === "reporting");
    return s?.state === "ok" && Array.isArray(s.views) ? { state: "ok", views: s.views } : { state: "unavailable" };
  } catch {
    return { state: "unavailable" };
  }
}

export function useReportingStatus() {
  return useQuery({ queryKey: ["freshness", "reporting", "views"], queryFn: () => readReportingStatus(), refetchInterval: 30_000, staleTime: 10_000 });
}

/** The freshness of what reads `views`: the latest day they are complete from, and provisional or not. */
export function freshnessOf(status: ReportingStatus | undefined, views: readonly string[]): { completeFrom: string | null; provisional: boolean } | undefined {
  if (status === undefined) return undefined;
  if (status.state === "unavailable") return { completeFrom: null, provisional: true };
  const mine = status.views.filter((v) => views.includes(v.name));
  const days = mine.map((v) => v.completeFrom).filter((d): d is string => typeof d === "string").sort();
  return { completeFrom: days.at(-1) ?? null, provisional: mine.some((v) => v.rebuilding) };
}

export function ViewsFreshness({ status, views }: { status: ReportingStatus | undefined; views: readonly string[] }) {
  const f = freshnessOf(status, views);
  if (f === undefined) return null;
  return (
    <span className="bo-status bo-panel-freshness" data-freshness={f.provisional ? "provisional" : "current"}>
      <Glyph name={f.provisional ? "alert" : "clock"} />
      <span>{f.completeFrom !== null ? t("dashboard.completeFrom", { day: formatDay(f.completeFrom) }) : t("dashboard.completeUnknown")}</span>
      {f.provisional ? <span className="bo-provisional">{t("list.provisional")}</span> : null}
    </span>
  );
}
