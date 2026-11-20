// The operator's jobs, as the page's server keeps them (`/api/jobs`; FR-029): each one an export the
// owner runs under a reference. The page starts one, reads their states (every few seconds while one
// is generating), and downloads a ready one from the owner through the server.
import { useQuery } from "@tanstack/react-query";
import { explain, type Refusal } from "../../../shared/refusals.ts";
import type { MessageKey } from "../../../shared/messages/index.ts";

export type JobKind =
  | "revenue" | "players" | "transactions" | "rewards" | "providers" | "rg_cases" | "kyc_decisions" | "day_end" | "dispute_report";

export type JobState =
  | { state: "generating"; progress: number }
  | { state: "ready"; rows: number | null; expiresAt: string | null }
  | { state: "failed" | "expired" }
  | { state: "unknown"; reason: string };

export type Job = { reference: string; kind: string; startedAt: string } & JobState;

export const KindLabel: Record<JobKind, MessageKey> = {
  revenue: "jobs.kind.revenue",
  players: "jobs.kind.players",
  transactions: "jobs.kind.transactions",
  rewards: "jobs.kind.rewards",
  providers: "jobs.kind.providers",
  rg_cases: "jobs.kind.rg_cases",
  kyc_decisions: "jobs.kind.kyc_decisions",
  day_end: "jobs.kind.day_end",
  dispute_report: "jobs.kind.dispute_report",
};

export function kindLabel(kind: string): MessageKey {
  return Object.hasOwn(KindLabel, kind) ? KindLabel[kind as JobKind] : "jobs.kind.other";
}

/** Where a ready document is downloaded: reporting's own route, as the operator, through the server. */
export function downloadHref(reference: string): string {
  return `/api/s/reporting/documents/${encodeURIComponent(reference)}`;
}

export type Started = { ok: true; job: Job; kept: boolean } | { ok: false; refusal: Refusal; body: string };

export async function startJob(kind: JobKind, params: Record<string, string>, fetchFn: typeof fetch = fetch): Promise<Started> {
  let r: Response;
  try {
    r = await fetchFn("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Backoffice": "1" },
      body: JSON.stringify({ kind, params }),
    });
  } catch {
    const body = JSON.stringify({ error: "source_unavailable", source: "backoffice" });
    return { ok: false, refusal: explain(503, body), body };
  }
  const text = await r.text();
  if (!r.ok) return { ok: false, refusal: explain(r.status, text), body: text };
  const b = JSON.parse(text) as { job: Job; kept: boolean };
  return { ok: true, job: b.job, kept: b.kept };
}

export async function readJobs(fetchFn: typeof fetch = fetch): Promise<Job[]> {
  const r = await fetchFn("/api/jobs", { headers: { Accept: "application/json" } });
  if (!r.ok) throw Object.assign(new Error(`jobs answered ${r.status}`), { refusal: explain(r.status, await r.text()) });
  return ((await r.json()) as { jobs: Job[] }).jobs;
}

/** The operator's jobs; read again every 3 s while one is generating, else every 30 s and on `job_done`. */
export function useJobs() {
  return useQuery({
    queryKey: ["jobs"],
    queryFn: () => readJobs(),
    refetchInterval: (q) => (q.state.data?.some((j) => j.state === "generating") ? 3_000 : 30_000),
  });
}
