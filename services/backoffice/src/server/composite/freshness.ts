// `GET /api/freshness?services=` (contracts/routes.md; research R13, FR-011): each named service's
// `status`, asked in parallel as the operator through its row of the route table.
//
// What provisional can mean here is bounded by V2 (research, *Found during implementation*): a
// service's `asOf` is when its view's follower last handled a change, not how far the view is
// behind its facts, and only reporting can say it is rebuilding. So a list is provisional when a view
// says it is rebuilding or when its service's status cannot be read; it is never made provisional
// by the age of `asOf`, which on a quiet brand is old and right.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";

export interface View {
  name: string;
  asOf: string | null;
  rebuilding: boolean;
}

export type ServiceFreshness =
  | { service: string; state: "ok"; views: View[]; asOf: string | null; provisional: boolean }
  | { service: string; state: "unavailable"; reason: string; provisional: true };

export interface FreshnessDeps extends ProxyDeps {
  table: readonly Row[];
}

const Name = /^[a-z][a-z0-9-]{0,40}$/;

/** The services named, each at most once, that the table holds a status row for. */
export function named(raw: string | undefined, table: readonly Row[]): string[] | undefined {
  const asked = [...new Set((raw ?? "").split(",").map((s) => s.trim()).filter((s) => s !== ""))];
  if (asked.length === 0 || asked.length > 12 || asked.some((s) => !Name.test(s))) return undefined;
  return asked.filter((s) => match(table, "GET", s, "/status") !== undefined);
}

function viewsOf(body: unknown): View[] | undefined {
  const views = (body as { views?: unknown })?.views;
  if (!Array.isArray(views)) return undefined;
  return views.flatMap((v) => {
    const o = v as { name?: unknown; asOf?: unknown; rebuilding?: unknown };
    if (typeof o?.name !== "string") return [];
    return [{ name: o.name, asOf: typeof o.asOf === "string" ? o.asOf : null, rebuilding: o.rebuilding === true }];
  });
}

/** The oldest time any view of the service last applied a change; null while none has. */
function oldest(views: View[]): string | null {
  const times = views.map((v) => v.asOf).filter((t): t is string => t !== null).sort();
  return times[0] ?? null;
}

export async function freshness(deps: FreshnessDeps, services: string[], accessToken: string): Promise<ServiceFreshness[]> {
  return Promise.all(
    services.map(async (service): Promise<ServiceFreshness> => {
      const m = match(deps.table, "GET", service, "/status")!;
      try {
        const { response, why } = await pass(deps, m, new Request("http://backoffice/status", { headers: { Accept: "application/json" } }), accessToken);
        if (why !== undefined) return { service, state: "unavailable", reason: "source_unavailable", provisional: true };
        if (!response.ok) {
          await response.body?.cancel();
          return { service, state: "unavailable", reason: response.status === 403 ? "forbidden" : `status_${response.status}`, provisional: true };
        }
        const views = viewsOf(await response.json());
        if (views === undefined) return { service, state: "unavailable", reason: "unreadable", provisional: true };
        return { service, state: "ok", views, asOf: oldest(views), provisional: views.some((v) => v.rebuilding) };
      } catch {
        return { service, state: "unavailable", reason: "unreadable", provisional: true };
      }
    }),
  );
}
