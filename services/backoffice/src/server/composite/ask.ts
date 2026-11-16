// One read of an owner by its row of the route table, as the operator, bounded: what the standing and the
// timeline ask each of their sources. A row the table does not hold, or whose permission the operator
// lacks, is not asked (`no_source`, `forbidden`); a failure, a late answer or an unreadable body is named.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";

export interface AskDeps extends ProxyDeps {
  table: readonly Row[];
}

export type Asked = { ok: true; body: unknown } | { ok: false; reason: string; status?: number };

export async function ask(
  deps: AskDeps,
  service: string,
  path: string,
  query: Record<string, string | undefined>,
  accessToken: string,
  holds: (permission: string) => boolean,
): Promise<Asked> {
  const m = match(deps.table, "GET", service, path);
  if (m === undefined) return { ok: false, reason: "no_source" };
  if (!holds(m.row.permission)) return { ok: false, reason: "forbidden" };
  const q = new URLSearchParams(Object.entries(query).filter((e): e is [string, string] => e[1] !== undefined && e[1] !== ""));
  const search = q.size > 0 ? `?${q}` : "";
  try {
    const { response, why } = await pass(deps, m, new Request(`http://backoffice${path}${search}`, { headers: { Accept: "application/json" } }), accessToken);
    if (why !== undefined) return { ok: false, reason: why === "TimeoutError" ? "late" : "unavailable", status: response.status };
    const text = await response.text();
    if (!response.ok) {
      let code = String(response.status);
      try {
        const j = JSON.parse(text) as { error?: unknown };
        if (typeof j.error === "string") code = j.error;
      } catch {
        // the status is the reason
      }
      return { ok: false, reason: response.status >= 500 ? "unavailable" : code.toLowerCase(), status: response.status };
    }
    return { ok: true, body: JSON.parse(text) };
  } catch (e) {
    return { ok: false, reason: e instanceof Error && e.name === "TimeoutError" ? "late" : "unreadable" };
  }
}

export type Obj = Record<string, unknown>;
export const obj = (v: unknown): Obj => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : {});
export const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
export const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : typeof v === "number" ? String(v) : undefined);
export const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
