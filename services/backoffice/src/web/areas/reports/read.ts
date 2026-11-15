// Reading reporting (spec 015) from the page: every answer through the server's route table as the
// operator, a refusal kept with its body (an incomplete period names its first complete day), and
// reporting's amounts, which are decimal strings in the currency's own places, read back to minor
// units so the one money rule shows them (FR-013).
import { useQuery } from "@tanstack/react-query";
import { parseMoney } from "../../../shared/money.ts";
import { explain, type Refusal } from "../../../shared/refusals.ts";

export class Refused extends Error {
  readonly status: number;
  readonly body: string;
  readonly refusal: Refusal;
  constructor(status: number, body: string) {
    super(`reporting answered ${status}`);
    this.name = "Refused";
    this.status = status;
    this.body = body;
    this.refusal = explain(status, body);
  }

  /** `409 incomplete_period {completeFrom}` (spec 015 FR-004): the first day the figures are complete. */
  get completeFrom(): string | undefined {
    try {
      const b = JSON.parse(this.body) as { error?: string; completeFrom?: string };
      return b.error === "incomplete_period" && typeof b.completeFrom === "string" ? b.completeFrom : undefined;
    } catch {
      return undefined;
    }
  }
}

/** A reporting path under `/api/s/reporting`, its query without empty values. */
export function reportingUrl(path: string, query: Record<string, string | undefined> = {}): string {
  const q = new URLSearchParams(Object.entries(query).filter((e): e is [string, string] => e[1] !== undefined && e[1] !== ""));
  const s = q.toString();
  return `/api/s/reporting${path}${s ? `?${s}` : ""}`;
}

export async function readJson<T>(url: string, fetchFn: typeof fetch = fetch): Promise<T> {
  let r: Response;
  try {
    r = await fetchFn(url, { headers: { Accept: "application/json" } });
  } catch {
    throw new Refused(503, JSON.stringify({ error: "source_unavailable", source: "reporting" }));
  }
  if (!r.ok) throw new Refused(r.status, await r.text());
  return (await r.json()) as T;
}

/** One reporting read, kept by its URL. */
export function useReporting<T>(path: string, query: Record<string, string | undefined> = {}, enabled = true) {
  const url = reportingUrl(path, query);
  return useQuery<T, Refused>({ queryKey: ["reporting", url], queryFn: () => readJson<T>(url), enabled, retry: false, staleTime: 15_000 });
}

/** reporting's amount (`"-12.50"` in EUR, `"1200"` in JPY) as minor units; undefined when it is not one. */
export function minorOf(text: string | null | undefined, currency: string): bigint | undefined {
  if (typeof text !== "string") return undefined;
  try {
    return parseMoney(text, currency);
  } catch {
    return undefined;
  }
}

/** The amount as a number, only to place a mark on a chart. */
export function drawn(text: string | null | undefined): number {
  const n = Number(text);
  return Number.isFinite(n) ? n : 0;
}
