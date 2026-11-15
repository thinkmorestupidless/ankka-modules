// `/api/jobs` (contracts/routes.md; research R16, FR-029): the exports an operator started, which stay
// the owner's jobs. reporting renders each export as a document under a reference: at once when it is
// small (`200`, the attachment named by its reference), as a job otherwise (`202 {reference}`). The
// server keeps only `{reference, kind, startedAt}` in the operator's preferences (at most 20, newest
// first, no player id) and reads each job's state from reporting as the operator when asked.
//
// A job's state is read from reporting's `GET /documents` (its records, by status), and its progress
// from `GET /documents/{reference}` only while it is generating: once ready, that route serves the file
// and records the serving, so the server never asks it of a ready document.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";
import type { Preferences, StateClient } from "../session.ts";

export interface JobsDeps extends ProxyDeps {
  table: readonly Row[];
  state: StateClient;
  now: () => number;
}

/** What an operator may start: the reporting route each kind is, the parameters it takes. */
interface Kind {
  path: string;
  /** Path parameters, put in `{name}`. */
  params?: string[];
  /** Query parameters handed on as given. */
  query: string[];
  /** `format=csv` added: the route answers its export rather than its figures. */
  csv: boolean;
}

const Period = ["from", "to"];

export const Kinds: Record<string, Kind> = {
  revenue: { path: "/reports/ggr", query: [...Period, "currency"], csv: true },
  players: { path: "/reports/players", query: [...Period, "currency", "country", "status"], csv: true },
  transactions: { path: "/reports/transactions/export", query: [...Period, "type", "playerEmail"], csv: true },
  rewards: { path: "/reports/bonus", query: [...Period, "currency", "offerId"], csv: true },
  providers: { path: "/reports/psp", query: [...Period, "currency"], csv: true },
  rg_cases: { path: "/compliance/rg-cases", query: Period, csv: true },
  kyc_decisions: { path: "/compliance/kyc-decisions", query: Period, csv: true },
  day_end: { path: "/day-end/{day}/export", params: ["day"], query: [], csv: false },
  dispute_report: { path: "/players/{playerId}/dispute-report", params: ["playerId"], query: ["sessionId", "fromDate", "toDate", "format"], csv: false },
};

export const MaxJobs = 20;

export interface Job {
  reference: string;
  kind: string;
  startedAt: string;
}

export type JobState = Job & (
  | { state: "generating"; progress: number }
  | { state: "ready"; rows: number | null; expiresAt: string | null }
  | { state: "failed" | "expired" }
  /** reporting does not list it (gone, or not the operator's to see), or could not be asked. */
  | { state: "unknown"; reason: string }
);

export type Started =
  | { ok: true; job: JobState; kept: boolean }
  | { ok: false; response: Response };

const refused = (status: number, error: string, message: string) => ({ ok: false as const, response: Response.json({ error, message }, { status }) });

/** The reference reporting put in the attachment's file name: `<report>-report-<reference>.<ext>`. */
export function referenceOf(disposition: string | null): string | undefined {
  const name = /filename="?([^";]+)"?/i.exec(disposition ?? "")?.[1];
  const ref = name ? /-report-([A-Za-z0-9][A-Za-z0-9._:-]*?)\.[a-z0-9]+$/i.exec(name)?.[1] : undefined;
  return ref && ref.length <= 128 ? ref : undefined;
}

function asked(body: unknown): { kind: string; values: Record<string, string> } | string {
  if (body === null || typeof body !== "object" || Array.isArray(body)) return "the body is a JSON object";
  const { kind, params } = body as { kind?: unknown; params?: unknown };
  if (typeof kind !== "string" || !Object.hasOwn(Kinds, kind)) return `kind is one of ${Object.keys(Kinds).join(", ")}`;
  const given = params ?? {};
  if (typeof given !== "object" || given === null || Array.isArray(given)) return "params is an object of strings";
  const k = Kinds[kind]!;
  const allowed = new Set([...(k.params ?? []), ...k.query]);
  const values: Record<string, string> = {};
  for (const [name, v] of Object.entries(given)) {
    if (!allowed.has(name)) return `${kind} takes no '${name}'`;
    if (typeof v !== "string" || v.length > 256) return `${name} is a string of at most 256 characters`;
    if (v.trim() !== "") values[name] = v.trim();
  }
  const missing = (k.params ?? []).find((p) => values[p] === undefined);
  return missing ? `${kind} needs ${missing}` : { kind, values };
}

async function keep(deps: JobsDeps, subject: string, job: Job): Promise<boolean> {
  try {
    const held = (await deps.state.preferences(subject)).jobs ?? [];
    const jobs = [job, ...held.filter((j) => j.reference !== job.reference)].slice(0, MaxJobs);
    const r = await deps.state.writePreferences(subject, JSON.stringify({ jobs }));
    return r.status === 200;
  } catch {
    return false;
  }
}

/** Starts the owner's export as the operator, and keeps its reference in their preferences. */
export async function start(deps: JobsDeps, subject: string, accessToken: string, body: unknown): Promise<Started> {
  const a = asked(body);
  if (typeof a === "string") return refused(400, "invalid_job", a);
  const kind = Kinds[a.kind]!;
  const path = kind.path.replace(/\{(\w+)\}/g, (_, n: string) => encodeURIComponent(a.values[n] ?? ""));
  const m = match(deps.table, "GET", "reporting", path);
  if (m === undefined) return refused(400, "invalid_job", "a path parameter is not a single segment");
  const query = new URLSearchParams();
  for (const q of kind.query) if (a.values[q] !== undefined) query.set(q, a.values[q]);
  if (kind.csv) query.set("format", "csv");
  const req = new Request(`http://backoffice${path}?${query}`, { headers: { Accept: "application/json, text/csv, */*" } });
  const { response } = await pass(deps, m, req, accessToken);
  const startedAt = new Date(deps.now()).toISOString();
  let job: JobState;
  if (response.status === 202) {
    const b = (await response.json().catch(() => ({}))) as { reference?: unknown };
    if (typeof b.reference !== "string") return refused(502, "unreadable", "reporting started a job and named no reference");
    job = { reference: b.reference, kind: a.kind, startedAt, state: "generating", progress: 0 };
  } else if (response.status === 200) {
    const reference = referenceOf(response.headers.get("content-disposition"));
    await response.body?.cancel();
    if (reference === undefined) return refused(502, "unreadable", "reporting's export named no reference");
    job = { reference, kind: a.kind, startedAt, state: "ready", rows: null, expiresAt: null };
  } else {
    return { ok: false, response };
  }
  const kept = await keep(deps, subject, { reference: job.reference, kind: job.kind, startedAt });
  return { ok: true, job, kept };
}

interface Line {
  reference: string;
  status?: string;
  rows?: number;
  expiresAt?: string | null;
}

const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** Each kept job's state now, as reporting says it to this operator. */
export async function states(deps: JobsDeps, jobs: readonly Job[], accessToken: string): Promise<JobState[]> {
  if (jobs.length === 0) return [];
  const unknown = (j: Job, reason: string): JobState => ({ ...j, state: "unknown", reason });
  const list = match(deps.table, "GET", "reporting", "/documents");
  if (list === undefined) return jobs.map((j) => unknown(j, "no_route"));
  // reporting's days are the brand's; a day either side of the jobs' UTC days covers every zone.
  const earliest = Math.min(...jobs.map((j) => Date.parse(j.startedAt)).filter((t) => !Number.isNaN(t)), deps.now());
  const query = new URLSearchParams({ from: day(earliest - 86_400_000), to: day(deps.now() + 86_400_000) });
  let lines: Map<string, Line>;
  try {
    const { response, why } = await pass(deps, list, new Request(`http://backoffice/documents?${query}`, { headers: { Accept: "application/json" } }), accessToken);
    if (why !== undefined || !response.ok) {
      await response.body?.cancel();
      return jobs.map((j) => unknown(j, why !== undefined ? "source_unavailable" : `status_${response.status}`));
    }
    const b = (await response.json()) as { documents?: Line[] };
    lines = new Map((b.documents ?? []).filter((d) => typeof d?.reference === "string").map((d) => [d.reference, d]));
  } catch {
    return jobs.map((j) => unknown(j, "unreadable"));
  }
  return Promise.all(
    jobs.map(async (j): Promise<JobState> => {
      const line = lines.get(j.reference);
      if (line === undefined) return unknown(j, "not_listed");
      if (line.status === "ready") return { ...j, state: "ready", rows: typeof line.rows === "number" ? line.rows : null, expiresAt: line.expiresAt ?? null };
      if (line.status === "failed" || line.status === "expired") return { ...j, state: line.status };
      if (line.status !== "generating") return unknown(j, "unreadable");
      return { ...j, state: "generating", progress: await progress(deps, j.reference, accessToken) };
    }),
  );
}

/** A generating document's progress, a whole percent; 0 when reporting does not say. */
async function progress(deps: JobsDeps, reference: string, accessToken: string): Promise<number> {
  const m = match(deps.table, "GET", "reporting", `/documents/${encodeURIComponent(reference)}`);
  if (m === undefined) return 0;
  try {
    const { response } = await pass(deps, m, new Request("http://backoffice/document", { headers: { Accept: "application/json" } }), accessToken);
    if (response.status !== 202) {
      // It became ready (or failed) between the list and this read: the file is not taken here.
      await response.body?.cancel();
      return response.ok ? 100 : 0;
    }
    const p = ((await response.json()) as { progress?: unknown }).progress;
    return typeof p === "number" && p >= 0 && p <= 100 ? Math.floor(p) : 0;
  } catch {
    return 0;
  }
}

export function kept(preferences: Preferences | null | undefined): Job[] {
  return (preferences?.jobs ?? []).filter((j) => typeof j?.reference === "string" && typeof j?.kind === "string");
}

/** Whether a job is over: what `/api/events` announces once as `job_done`. */
export const ended = (s: JobState) => s.state === "ready" || s.state === "failed";
