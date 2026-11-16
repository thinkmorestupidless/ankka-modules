// `GET /api/withdrawals?…&q=` (T080; contracts/amendments.md Y2): payouts' queue, with a name or an email
// typed in the queue's search resolved to player ids through players' search first. payouts holds no
// name: its `playerSearch` takes exact player ids, so the server asks players for the ids and gives payouts
// those. A UUID is a player id as typed. Every other parameter goes to payouts as the page sent it.
//
// Both owners are asked as the operator, through their rows of the route table, each bounded. players'
// search answers at most `MaxPlayers` ids here; when it matched more, the answer says so, so the page
// can ask for a narrower search rather than show a part as the whole.
import { match, type Row } from "../routes.ts";
import { pass, unavailable, type ProxyDeps } from "../proxy.ts";

export interface WithdrawalsDeps extends ProxyDeps {
  table: readonly Row[];
}

/** The most player ids one search is resolved to. */
export const MaxPlayers = 100;

const Uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const ask = (deps: WithdrawalsDeps, service: string, path: string, query: URLSearchParams, accessToken: string) => {
  const m = match(deps.table, "GET", service, path)!;
  const search = query.toString();
  return pass(deps, m, new Request(`http://backoffice${path}${search ? `?${search}` : ""}`, { headers: { Accept: "application/json" } }), accessToken);
};

/** The player ids players' search gives for what was typed, and whether it matched more than were taken. */
async function resolve(deps: WithdrawalsDeps, q: string, accessToken: string): Promise<{ ids: string[]; more: boolean } | Response> {
  if (Uuid.test(q)) return { ids: [q], more: false };
  const { response, why } = await ask(deps, "players", "/players", new URLSearchParams({ search: q, pageSize: String(MaxPlayers) }), accessToken);
  if (why !== undefined || response.status >= 500) {
    await response.body?.cancel();
    return unavailable("players");
  }
  if (!response.ok) return response;
  const body = (await response.json()) as { items?: { playerId?: unknown }[]; total?: unknown };
  const ids = [...new Set((body.items ?? []).map((i) => i.playerId).filter((p): p is string => typeof p === "string" && p !== ""))];
  return { ids, more: typeof body.total === "number" && body.total > ids.length };
}

export async function withdrawals(deps: WithdrawalsDeps, query: URLSearchParams, accessToken: string): Promise<Response> {
  const forwarded = new URLSearchParams(query);
  const q = (forwarded.get("q") ?? "").trim();
  forwarded.delete("q");
  forwarded.delete("playerSearch");
  let resolved: { ids: string[]; more: boolean } | undefined;
  if (q !== "") {
    const r = await resolve(deps, q, accessToken);
    if (r instanceof Response) return r;
    resolved = r;
    // nobody matched: payouts is asked only for the counts, and the list is empty
    if (r.ids.length === 0) {
      forwarded.set("pageSize", "1");
      forwarded.set("page", "1");
    } else forwarded.set("playerSearch", r.ids.join(","));
  }
  const { response } = await ask(deps, "payouts", "/withdrawal-requests", forwarded, accessToken);
  if (!response.ok || resolved === undefined) return response;
  const body = (await response.json()) as Record<string, unknown>;
  const players = { matched: resolved.ids.length, more: resolved.more };
  if (resolved.ids.length === 0) {
    const whole = (name: string, otherwise: number) => {
      const n = Number(query.get(name) ?? otherwise);
      return Number.isInteger(n) && n >= 1 ? n : otherwise;
    };
    const page = whole("page", 1);
    const pageSize = whole("pageSize", 50);
    return Response.json({ ...body, requests: [], total: 0, page, pageSize, players });
  }
  return Response.json({ ...body, players });
}
