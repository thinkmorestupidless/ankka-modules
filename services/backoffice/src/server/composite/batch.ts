// `POST /api/adjustment-batches` (contracts/routes.md; research R15, FR-028): one reason and cause
// applied as one wallet adjustment per row, each under its own key, `batch/<batch id>/<row id>`. The
// batch id is the SHA-256 of the reason, the category and the cause; a row's id is the SHA-256 of what
// it adjusts (player, currency, balance, direction, amount) and which of the list's identical rows it
// is. So a row sent again is the same row whatever else changed in the list: a list cut short, one
// row corrected and sent again, makes again only the row that was not made, and the wallet answers
// each other its first outcome. The backoffice keeps no state beyond the request (spec, Assumptions);
// the rows are asked eight at a time, and each row's outcome is streamed as a line of JSON as it is
// known, then a last line with the counts.
//
// Made or already made: the wallet answers a repeated key with its first outcome and `repeated: true`
// (spec 030 W2), `repeated: false` when it made the adjustment now. A row is asked once, its
// adjustment under its key; nothing is read before it.
import { createHash } from "node:crypto";
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";

export const MaxRows = 500;
export const AtATime = 8;
export const CauseKinds = ["round", "provider_transaction", "payment", "incident"] as const;

export interface BatchRow {
  player: string;
  currency: string;
  walletType: "real" | "restricted";
  direction: "credit" | "debit";
  /** Minor units, positive; the direction gives the sign. */
  amountMinor: number;
}

export interface Batch {
  /** The page's key for this composed batch: one per batch the operator makes, kept when a corrected list is sent again. */
  key: string;
  reason: string;
  category: string;
  cause?: { kind: (typeof CauseKinds)[number]; ref: string };
  rows: BatchRow[];
}

export interface RowOutcome {
  /** The row's number, from 1, as the page listed it. */
  row: number;
  player: string;
  currency: string;
  outcome: "made" | "refused" | "already_made";
  /** A refusal's code as the owner gave it, and its words and status. */
  reason?: string;
  message?: string;
  status?: number;
  /** The service that did not answer, for `source_unavailable`. */
  source?: string;
  /** The adjusted balance before and after, in minor units, as the wallet answered them. */
  before?: string;
  after?: string;
}

export interface Done {
  done: true;
  batch: string;
  made: number;
  refused: number;
  alreadyMade: number;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | undefined => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : undefined);
const Segment = /^[A-Za-z0-9._:-]{1,128}$/;
const Currency = /^[A-Z]{3}$/;
const Category = /^[a-z][a-z0-9_]{0,63}$/;
const BatchKey = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The batch as the page sent it, or what is wrong with it (row numbers from 1). */
export function readBatch(body: unknown): { batch: Batch } | { error: string; row?: number } {
  const b = obj(body);
  if (!b) return { error: "the body is not a JSON object" };
  const key = typeof b.key === "string" ? b.key.toLowerCase() : "";
  if (!BatchKey.test(key)) return { error: "a batch carries its key, a UUID the page makes for it" };
  const reason = typeof b.reason === "string" ? b.reason.trim() : "";
  if (reason === "" || reason.length > 1000) return { error: "a batch names its reason, at most 1000 characters" };
  const category = typeof b.category === "string" ? b.category : "";
  if (!Category.test(category)) return { error: "a batch names its category" };
  let cause: Batch["cause"];
  if (b.cause !== undefined && b.cause !== null) {
    const c = obj(b.cause);
    const ref = typeof c?.ref === "string" ? c.ref.trim() : "";
    if (!c || !(CauseKinds as readonly unknown[]).includes(c.kind) || ref === "" || ref.length > 200)
      return { error: "a cause is a round, provider_transaction, payment or incident, with its reference of at most 200 characters" };
    cause = { kind: c.kind as (typeof CauseKinds)[number], ref };
  }
  if (!Array.isArray(b.rows) || b.rows.length === 0) return { error: "a batch has at least one row" };
  if (b.rows.length > MaxRows) return { error: `a batch has at most ${MaxRows} rows` };
  const rows: BatchRow[] = [];
  for (const [i, raw] of b.rows.entries()) {
    const r = obj(raw);
    const n = i + 1;
    if (!r || typeof r.player !== "string" || !Segment.test(r.player) || r.player === "." || r.player === "..") return { error: "a row names its player", row: n };
    if (typeof r.currency !== "string" || !Currency.test(r.currency)) return { error: "a row names its currency", row: n };
    if (r.walletType !== "real" && r.walletType !== "restricted") return { error: "a row's balance is real or restricted", row: n };
    if (r.direction !== "credit" && r.direction !== "debit") return { error: "a row is a credit or a debit", row: n };
    const minor = typeof r.amountMinor === "string" && /^\d{1,16}$/.test(r.amountMinor) ? Number(r.amountMinor) : r.amountMinor;
    if (typeof minor !== "number" || !Number.isSafeInteger(minor) || minor <= 0) return { error: "a row's amount is a positive whole number of minor units", row: n };
    rows.push({ player: r.player, currency: r.currency, walletType: r.walletType, direction: r.direction, amountMinor: minor });
  }
  return { batch: { key, reason, category, ...(cause ? { cause } : {}), rows } };
}

const sha256 = (v: unknown) => createHash("sha256").update(JSON.stringify(v)).digest("hex");

/**
 * The batch's id: its key with its reason, category and cause, whichever rows it holds. The same key resent
 * with a corrected list is the same batch, so rows already made answer `already_made`; a batch made later
 * with the same reason and rows has another key, so it is paid.
 */
export function batchId(b: Pick<Batch, "key" | "reason" | "category" | "cause">): string {
  return sha256([b.key, b.reason, b.category, b.cause ? [b.cause.kind, b.cause.ref] : null]);
}

/**
 * Each row's id, in the list's order: what it adjusts, and which of the identical rows it is (the first,
 * the second, …), so a player credited twice the same is two rows, and moving a row keeps its id.
 */
export function rowIds(rows: readonly BatchRow[]): string[] {
  const seen = new Map<string, number>();
  return rows.map((r) => {
    const what = JSON.stringify([r.player, r.currency, r.walletType, r.direction, r.amountMinor]);
    const nth = (seen.get(what) ?? 0) + 1;
    seen.set(what, nth);
    return sha256([what, nth]);
  });
}

/** A row's key at the wallet: 135 characters, within its 255. */
export const keyOf = (id: string, row: string) => `batch/${id}/${row}`;

export interface BatchDeps extends ProxyDeps {
  table: readonly Row[];
}

const enc = encodeURIComponent;

async function call(deps: BatchDeps, token: string, method: "GET" | "POST", path: string, body?: unknown): Promise<{ status: number; json: Obj | undefined; unavailable: boolean }> {
  const m = match(deps.table, method, "wallet", path);
  if (m === undefined) return { status: 404, json: { error: "no_such_route" }, unavailable: false };
  const request = new Request(`http://backoffice${path}`, {
    method,
    headers: { Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  try {
    const { response, why } = await pass(deps, m, request, token);
    const text = await response.text();
    let json: Obj | undefined;
    try {
      json = obj(JSON.parse(text));
    } catch {
      json = undefined;
    }
    return { status: response.status, json, unavailable: why !== undefined || response.status === 503 };
  } catch {
    return { status: 503, json: undefined, unavailable: true };
  }
}

const str = (v: unknown) => (typeof v === "string" ? v : typeof v === "number" ? String(v) : undefined);

/** One row: its adjustment under its key. */
async function one(deps: BatchDeps, token: string, key: string, n: number, r: BatchRow, b: Batch): Promise<RowOutcome> {
  const base = { row: n, player: r.player, currency: r.currency };
  const wallet = `/wallets/${enc(r.player)}/${enc(r.currency)}`;
  const unavailable = (): RowOutcome => ({ ...base, outcome: "refused", reason: "source_unavailable", source: "wallet", status: 503 });
  const refused = (a: { status: number; json: Obj | undefined }): RowOutcome => ({
    ...base,
    outcome: "refused",
    reason: str(a.json?.error) ?? str(a.json?.code) ?? String(a.status),
    message: str(a.json?.message),
    status: a.status,
  });

  const signed = r.direction === "debit" ? -r.amountMinor : r.amountMinor;
  const made = await call(deps, token, "POST", `${wallet}/adjustments`, {
    key,
    walletType: r.walletType,
    amountMinor: signed,
    reason: b.reason,
    category: b.category,
    ...(b.cause ? { cause: b.cause } : {}),
  });
  if (made.unavailable) return unavailable();
  if (made.status !== 200 || !made.json) return refused(made);
  return { ...base, outcome: made.json.repeated === true ? "already_made" : "made", before: str(made.json.before), after: str(made.json.after) };
}

/**
 * Every row, eight at a time, each outcome given to `emit` as it is known; the counts at the end. Once
 * `stop` ends (the page went), no further row is asked; the rows already asked finish at the wallet.
 */
export async function runBatch(deps: BatchDeps, b: Batch, token: string, emit: (o: RowOutcome) => void, stop?: AbortSignal): Promise<Done> {
  const id = batchId(b);
  const keys = rowIds(b.rows).map((row) => keyOf(id, row));
  const done: Done = { done: true, batch: id, made: 0, refused: 0, alreadyMade: 0 };
  let next = 0;
  const worker = async () => {
    while (next < b.rows.length && !stop?.aborted) {
      const i = next++;
      const o = await one(deps, token, keys[i]!, i + 1, b.rows[i]!, b);
      if (o.outcome === "made") done.made++;
      else if (o.outcome === "refused") done.refused++;
      else done.alreadyMade++;
      emit(o);
    }
  };
  await Promise.all(Array.from({ length: Math.min(AtATime, b.rows.length) }, worker));
  return done;
}

/** The streamed answer: one line of JSON a row, then the counts. A page that goes stops the batch. */
export function batchStream(deps: BatchDeps, b: Batch, token: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  const gone = new AbortController();
  return new ReadableStream<Uint8Array>({
    async start(controller) {
      const line = (v: unknown) => {
        if (!gone.signal.aborted) controller.enqueue(encoder.encode(`${JSON.stringify(v)}\n`));
      };
      try {
        line(await runBatch(deps, b, token, line, gone.signal));
      } finally {
        if (!gone.signal.aborted) controller.close();
      }
    },
    cancel() {
      gone.abort();
    },
  });
}
