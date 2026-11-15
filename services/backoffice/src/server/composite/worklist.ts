// `GET /api/worklist?kinds=` and `GET /api/worklist/{kind}?page=` (contracts/routes.md; contracts/sources.md,
// *Worklist*; research R9; FR-016, FR-017). Each kind the operator's roles admit, and only those, is asked
// of its owner's own queue as the operator, in parallel, each within its bound; a source that fails, is
// late or answers what cannot be read is `unavailable` with its reason, never an empty kind, and the
// others are shown. Each kind's count is its source's `total`; nothing is stored.
//
// A kind is asked through its row of the route table, as search and freshness are: a kind whose row the
// table does not hold yet is `unavailable` with `no_source`, except undelivered outcomes, which no brand
// permission admits (a payments role at its level does, and the payments project decides): those are
// left out when the table cannot reach them, or when the payments project refuses the operator.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";
import { Kinds, type Kind } from "../../shared/worklist.ts";

export interface WorklistItem {
  /** The record's id at its owner (a request, a case, a player for the identity queue). */
  id: string;
  kind: string;
  subject: { player?: string };
  /** Since when the item has waited, by its owner's time for it. */
  since: string;
  /** Minor units and their currency, for a kind about money. */
  amount?: { minor: number; currency: string };
  /** What the owner says about the item, by its own words (a status, a signal, a reason): never prose. */
  detail: Record<string, string | number | null>;
}

export type WorklistKind =
  | { kind: string; state: "ok"; total: number; page: number; items: WorklistItem[] }
  | { kind: string; state: "unavailable"; reason: string };

export interface WorklistDeps extends ProxyDeps {
  table: readonly Row[];
}

export const PageSize = 50;

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : {});
const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : typeof v === "number" ? String(v) : undefined);
const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
const detailOf = (o: Obj, keys: readonly string[]): WorklistItem["detail"] => {
  const d: WorklistItem["detail"] = {};
  for (const k of keys) {
    const v = o[k];
    if (typeof v === "string" || typeof v === "number") d[k] = v;
    else if (Array.isArray(v)) d[k] = v.filter((x) => typeof x === "string").join(",");
    else if (v === null) d[k] = null;
  }
  return d;
};

/** One call to a kind's source: the table's row by its template, the query, and how to read its answer. */
interface Ask {
  service: string;
  path: string;
  query: (page: number) => Record<string, string>;
}

interface Source {
  kind: string;
  asks: readonly Ask[];
  /** The rows of one answer. */
  rows: (body: Obj) => unknown[];
  /** The source's count of the kind; the rows answered when it gives none. */
  total: (body: Obj, rows: number) => number;
  item: (row: Obj) => Omit<WorklistItem, "kind"> | undefined;
}

const paged = (page: number) => ({ page: String(page), pageSize: String(PageSize) });
const listOf = (field: string) => (b: Obj) => (Array.isArray(b[field]) ? (b[field] as unknown[]) : []);
const totalOr = (b: Obj, rows: number) => num(b.total) ?? rows;

const withdrawal = (row: Obj): Omit<WorklistItem, "kind"> | undefined => {
  const id = str(row.requestId);
  const since = str(row.createdAt);
  if (!id || !since) return undefined;
  const minor = num(row.amount);
  const currency = str(row.currency);
  return {
    id,
    subject: { player: str(row.playerId) },
    since,
    ...(minor !== undefined && currency ? { amount: { minor, currency } } : {}),
    detail: detailOf(row, ["status", "payoutStatus", "kycStatus", "method", "terminalName"]),
  };
};

const identity = (sinceKey: string) => (row: Obj): Omit<WorklistItem, "kind"> | undefined => {
  const id = str(row.playerId);
  const since = str(row[sinceKey]) ?? str(row.updatedAt);
  if (!id || !since) return undefined;
  return { id, subject: { player: id }, since, detail: detailOf(row, ["status", "awaiting", "flags", "mode"]) };
};

/** kyc counts each filter but `flagged` (KycQueue.counts); `total` once its queue is paged (K2). */
const kycTotal = (count: string | undefined) => (b: Obj, rows: number) => num(b.total) ?? (count ? num(obj(b.counts)[count]) : undefined) ?? rows;

export const Sources: readonly Source[] = [
  {
    kind: "withdrawals_pending",
    asks: [{ service: "payouts", path: "/withdrawal-requests", query: (p) => ({ status: "PENDING_REVIEW", sort: "oldest", ...paged(p) }) }],
    rows: listOf("requests"),
    total: totalOr,
    item: withdrawal,
  },
  {
    // payouts defaults `status` to pending review, so the payments needing review are asked across every status
    kind: "payouts_needing_review",
    asks: [{ service: "payouts", path: "/withdrawal-requests", query: (p) => ({ status: "ALL", payoutStatus: "NEEDS_REVIEW", sort: "oldest", ...paged(p) }) }],
    rows: listOf("requests"),
    total: totalOr,
    item: withdrawal,
  },
  {
    kind: "documents_awaiting",
    asks: [{ service: "kyc", path: "/queue", query: (p) => ({ filter: "awaiting", ...paged(p) }) }],
    rows: listOf("rows"),
    total: kycTotal("awaiting"),
    item: identity("oldestAwaitingAt"),
  },
  {
    kind: "players_flagged",
    asks: [{ service: "kyc", path: "/queue", query: (p) => ({ filter: "flagged", ...paged(p) }) }],
    rows: listOf("rows"),
    total: kycTotal(undefined),
    item: identity("flaggedAt"),
  },
  {
    kind: "protection_cases",
    asks: [{ service: "responsible-gambling", path: "/cases", query: (p) => ({ status: "open", ...paged(p) }) }],
    rows: (b) => listOf("cases")(b).concat(listOf("items")(b)),
    total: totalOr,
    item: (row) => {
      const id = str(row.caseId);
      const since = str(row.openedAt);
      if (!id || !since) return undefined;
      return { id, subject: { player: str(row.playerId) ?? str(row.player) }, since, detail: detailOf(row, ["caseType", "trigger"]) };
    },
  },
  {
    kind: "fraud_cases",
    asks: [{ service: "players", path: "/fraud-cases", query: (p) => ({ status: "OPEN", ...paged(p) }) }],
    rows: listOf("cases"),
    total: totalOr,
    item: (row) => {
      const id = str(row.caseId);
      const since = str(row.detectedAt);
      if (!id || !since) return undefined;
      const players = Array.isArray(row.playerIds) ? row.playerIds.filter((p): p is string => typeof p === "string") : [];
      return { id, subject: { player: players[0] }, since, detail: detailOf(row, ["signals", "playerIds"]) };
    },
  },
  {
    kind: "unacknowledged_calls",
    asks: [{ service: "game-provider", path: "/unacknowledged", query: (p) => paged(p) }],
    rows: listOf("entries"),
    total: totalOr,
    item: (row) => {
      const id = str(row.transactionId);
      const since = str(row.recordedAt);
      if (!id || !since) return undefined;
      const minor = num(row.amount);
      const currency = str(row.currency);
      return {
        id,
        subject: { player: str(row.playerId) },
        since,
        ...(minor !== undefined && currency ? { amount: { minor, currency } } : {}),
        detail: detailOf(row, ["provider", "callback", "kind", "error"]),
      };
    },
  },
  {
    // attribution lists a network's undelivered facts only (`network=` required): no row until the worklist can name them
    kind: "undelivered_facts",
    asks: [{ service: "attribution", path: "/attribution-webhooks/undelivered", query: (p) => paged(p) }],
    rows: listOf("undelivered"),
    total: totalOr,
    item: (row) => {
      const id = str(row.delivery);
      const since = str(row.firstAttemptAt);
      if (!id || !since) return undefined;
      return { id, subject: {}, since, detail: detailOf(row, ["network", "kind", "attempts", "lastStatus"]) };
    },
  },
  {
    kind: "parked_sends",
    asks: [{ service: "engagement", path: "/parked-sends", query: (p) => ({ page: String(p) }) }],
    rows: listOf("sends"),
    total: totalOr,
    item: (row) => {
      const id = str(row.sendId);
      const since = str(row.time);
      if (!id || !since) return undefined;
      return { id, subject: {}, since, detail: detailOf(row, ["trigger", "channel", "reason"]) };
    },
  },
  {
    // psp-gateway answers the oldest `limit`, unpaged: the first page only
    kind: "undelivered_outcomes",
    asks: [
      { service: "psp-gateway", path: "/outcomes/undelivered", query: () => ({ limit: String(PageSize) }) },
      { service: "psp-gateway", path: "/outcomes/refused", query: () => ({ limit: String(PageSize) }) },
    ],
    rows: listOf("outcomes"),
    total: totalOr,
    item: (row) => {
      const id = str(row.reference);
      const since = str(row.since);
      if (!id || !since) return undefined;
      return { id, subject: {}, since, detail: detailOf(row, ["provider", "outcome", "attempts", "lastAnswer"]) };
    },
  },
];

const byKind = new Map(Sources.map((s) => [s.kind, s]));
const kindOf = new Map(Kinds.map((k) => [k.id, k]));

/** The kinds asked: those named (all when none is) that the roles admit, in the order of `Kinds`. */
export function wanted(raw: string | undefined, admitted: readonly Kind[]): Kind[] {
  const named = new Set((raw ?? "").split(",").map((s) => s.trim()).filter((s) => s !== ""));
  return admitted.filter((k) => named.size === 0 || named.has(k.id));
}

type Answer = { ok: Obj } | { unavailable: string };

async function askOne(deps: WorklistDeps, ask: Ask, page: number, accessToken: string): Promise<Answer> {
  const m = match(deps.table, "GET", ask.service, ask.path);
  if (m === undefined) return { unavailable: "no_source" };
  const query = new URLSearchParams(ask.query(page)).toString();
  const request = new Request(`http://backoffice${ask.path}?${query}`, { headers: { Accept: "application/json" } });
  try {
    const { response, why } = await pass(deps, m, request, accessToken);
    if (why !== undefined) return { unavailable: why === "TimeoutError" ? "timeout" : "source_unavailable" };
    if (!response.ok) {
      await response.body?.cancel();
      return { unavailable: response.status === 403 ? "forbidden" : response.status === 401 ? "signed_out" : `status_${response.status}` };
    }
    const body = (await response.json()) as unknown;
    return body !== null && typeof body === "object" && !Array.isArray(body) ? { ok: body as Obj } : { unavailable: "unreadable" };
  } catch (e) {
    return { unavailable: e instanceof Error && e.name === "TimeoutError" ? "timeout" : "unreadable" };
  }
}

/** One kind's page from its source; `undefined` when the kind is left out. */
export async function readKind(deps: WorklistDeps, kind: Kind, page: number, accessToken: string): Promise<WorklistKind | undefined> {
  const source = byKind.get(kind.id);
  if (source === undefined) return { kind: kind.id, state: "unavailable", reason: "no_source" };
  const answers = await Promise.all(source.asks.map((a) => askOne(deps, a, page, accessToken)));
  const failed = answers.flatMap((a) => ("unavailable" in a ? [a.unavailable] : []));
  // the payments project decides: a row the table does not hold, or its refusal of the operator, leaves the kind out
  if (kind.permission === null && failed.some((r) => r === "no_source" || r === "forbidden" || r === "signed_out")) return undefined;
  if (failed.length > 0) return { kind: kind.id, state: "unavailable", reason: failed[0]! };
  let total = 0;
  const items: WorklistItem[] = [];
  for (const a of answers) {
    if (!("ok" in a)) continue;
    const rows = source.rows(a.ok);
    total += source.total(a.ok, rows.length);
    for (const row of rows) {
      const it = source.item(obj(row));
      if (it) items.push({ kind: kind.id, ...it });
    }
  }
  // each owner orders its queue oldest first; two sources of one kind are merged in that order
  items.sort((x, y) => Date.parse(x.since) - Date.parse(y.since));
  return { kind: kind.id, state: "ok", total, page, items };
}

/** Every kind wanted, in parallel, each within the bound `deps.timeoutMs` gives its calls. */
export async function worklist(deps: WorklistDeps, kinds: readonly Kind[], accessToken: string): Promise<WorklistKind[]> {
  const read = await Promise.all(kinds.map((k) => readKind(deps, k, 1, accessToken)));
  return read.filter((k): k is WorklistKind => k !== undefined);
}

export const knownKind = (id: string): Kind | undefined => kindOf.get(id);
