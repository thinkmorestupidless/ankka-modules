// The route table (research R4): what the browser may ask for under `/api/s/<service>/…`, and where
// the server sends it. A route an owner lacks is an amendment to that owner, never a workaround here
// (FR-007). Each owner still decides admission; `permission` is what that owner requires, so the page
// can say which role would give it.

/** The brand's own project, or one of the shared projects (their addresses are the brand's values). */
export type Where = "brand" | "payments" | "programme" | "reasoner" | "games";
export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface Row {
  method: Method;
  /** The owning service's name in its project. */
  service: string;
  /** The browser's path under `/api/s/<service>`, without the brand. */
  path: string;
  /** The owner's path; `{brand}` is put in by the server. */
  upstream: string;
  where: Where;
  permission: string;
}

export interface Matched {
  row: Row;
  params: Record<string, string>;
}

/** A segment the browser may name: not empty, no traversal, no encoded separator. */
function safe(segment: string): boolean {
  return segment !== "" && segment !== "." && segment !== ".." && !/%2f|%5c|\\/i.test(segment);
}

export function match(rows: readonly Row[], method: string, service: string, rest: string): Matched | undefined {
  if (!rest.startsWith("/")) return undefined;
  const asked = rest.slice(1).split("/");
  if (!asked.every(safe)) return undefined;
  for (const row of rows) {
    if (row.method !== method || row.service !== service) continue;
    const template = row.path.slice(1).split("/");
    if (template.length !== asked.length) continue;
    const params: Record<string, string> = {};
    const fits = template.every((t, i) => {
      const a = asked[i]!;
      const name = /^\{(\w+)\}$/.exec(t)?.[1];
      if (!name) return t === a;
      params[name] = a;
      return true;
    });
    if (fits) return { row, params };
  }
  return undefined;
}

/** The owner's path for a matched row: the brand put in, the browser's parameters as it sent them. */
export function upstreamPath(m: Matched, brand: string): string {
  return m.row.upstream.replace(/\{(\w+)\}/g, (_, name: string) =>
    name === "brand" ? encodeURIComponent(brand) : (m.params[name] ?? ""),
  );
}

/** Filled by T038, owner by owner. */
export const table: readonly Row[] = [];
