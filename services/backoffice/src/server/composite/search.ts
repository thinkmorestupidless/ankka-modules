// `GET /api/search?q=` (contracts/sources.md, *Search*; research R11). What was pasted is classified by
// shape and asked only of the resolvers that shape can match, in parallel, each bounded; every match is
// listed with its kind and the player it belongs to. Nothing not shaped like an id is ever sent as one:
// the original sent free text as a player id and answered 760 errors for it.
//
// Each resolver is a row of the route table: its upstream, its project and the permission it needs come
// from there, so the search reaches nothing the table does not hold, and a resolver whose row is not in
// the table yet (or whose permission the operator lacks) is not asked.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";

export type Shape = "uuid" | "email" | "document" | "reference" | "text";

const Uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const Email = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const Document = /^R-[A-Za-z0-9-]{1,64}$/;
/** A token a payment provider or a provider's system could have issued: no spaces, a digit in it. */
const Reference = /^(?=.*\d)[A-Za-z0-9][A-Za-z0-9._:-]{5,63}$/;

/** Every shape the value has. A UUID is also a payment reference; free text is only text. */
export function shapes(raw: string): Shape[] {
  const q = raw.trim();
  if (q === "") return [];
  if (Uuid.test(q)) return ["uuid", "reference"];
  if (Email.test(q)) return ["email"];
  if (Document.test(q)) return ["document", "reference"];
  if (Reference.test(q)) return ["reference", "text"];
  return ["text"];
}

export interface Match {
  kind: string;
  id: string;
  player?: string;
  summary: Record<string, string | number | boolean | null>;
}

interface Resolver {
  kind: string;
  shape: Shape;
  service: string;
  /** The table's row this resolver is, by its path template: a row of another shape that happens to match is not it. */
  template: string;
  /** The page-side path the table holds, with the value in place. */
  path: (q: string) => string;
  /** Matches in the owner's answer. */
  read: (body: unknown, q: string) => Match[];
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : {});
const str = (v: unknown): string | undefined => (typeof v === "string" ? v : typeof v === "number" ? String(v) : undefined);
const pick = (o: Obj, keys: string[]): Match["summary"] => {
  const s: Match["summary"] = {};
  for (const k of keys) {
    const v = o[k];
    if (v === null || typeof v === "string" || typeof v === "number" || typeof v === "boolean") s[k] = v;
  }
  return s;
};
const one = (kind: string, idKey: string, summary: string[], playerKey = "playerId") => (body: unknown): Match[] => {
  const o = obj(body);
  const id = str(o[idKey]);
  if (!id) return [];
  return [{ kind, id, player: str(o[playerKey]), summary: pick(o, summary) }];
};
const list = (kind: string, field: string, idKey: string, summary: string[], playerKey = "playerId") => (body: unknown): Match[] => {
  const items = obj(body)[field];
  return Array.isArray(items) ? items.flatMap((i) => one(kind, idKey, summary, playerKey)(i)) : [];
};
const enc = encodeURIComponent;

export const Resolvers: readonly Resolver[] = [
  { kind: "player", shape: "uuid", service: "players", template: "/players/{playerId}/profile", path: (q) => `/players/${enc(q)}/profile`, read: one("player", "playerId", ["email", "status", "currency"]) },
  { kind: "withdrawal", shape: "uuid", service: "payouts", template: "/withdrawal-requests/{requestId}", path: (q) => `/withdrawal-requests/${enc(q)}`, read: one("withdrawal", "requestId", ["status", "amount", "currency"]) },
  { kind: "reward", shape: "uuid", service: "rewards", template: "/reward-grants/{rewardId}", path: (q) => `/reward-grants/${enc(q)}`, read: one("reward", "rewardId", ["status", "offerName", "kind"]) },
  { kind: "payment", shape: "uuid", service: "merchant", template: "/{paymentId}", path: (q) => `/${enc(q)}`, read: one("payment", "paymentId", ["status", "amountCents", "currency", "psp"], "customerRef") },
  { kind: "round", shape: "uuid", service: "game-provider", template: "/rounds/by-round/{roundId}", path: (q) => `/rounds/by-round/${enc(q)}`, read: one("round", "roundId", ["gameCode", "gameName", "status", "currency"]) },
  { kind: "round", shape: "uuid", service: "game-provider", template: "/rounds/by-transaction/{transactionId}", path: (q) => `/rounds/by-transaction/${enc(q)}`, read: one("round", "roundId", ["gameCode", "gameName", "status", "currency"]) },
  { kind: "player", shape: "email", service: "players", template: "/players", path: (q) => `/players?search=${enc(q)}`, read: list("player", "items", "playerId", ["email", "username", "accountStatus", "currency"]) },
  { kind: "document", shape: "document", service: "reporting", template: "/documents/{reference}", path: (q) => `/documents/${enc(q)}`, read: one("document", "reference", ["kind", "state"]) },
  { kind: "payment", shape: "reference", service: "merchant", template: "/cashier", path: (q) => `/cashier?reference=${enc(q)}`, read: list("payment", "payments", "paymentId", ["status", "amountCents", "currency", "psp"], "customerRef") },
  { kind: "player", shape: "text", service: "players", template: "/players", path: (q) => `/players?search=${enc(q)}`, read: list("player", "items", "playerId", ["email", "username", "accountStatus", "currency"]) },
];

export interface SearchDeps extends ProxyDeps {
  table: readonly Row[];
  /** The bound on each resolver. */
  timeoutMs: number;
}

export interface Answer {
  matches: Match[];
  /** The kinds asked, so the page can say where it looked. */
  asked: string[];
  /** The services that did not answer within the bound, or failed. */
  unavailable: string[];
}

export async function search(deps: SearchDeps, raw: string, accessToken: string, holds: (permission: string) => boolean): Promise<Answer> {
  const q = raw.trim();
  const wanted = new Set(shapes(q));
  const asking = Resolvers.flatMap((r) => {
    if (!wanted.has(r.shape)) return [];
    const path = r.path(q);
    const [pathname] = path.split("?");
    const m = match(deps.table, "GET", r.service, pathname!);
    if (m === undefined || m.row.path !== r.template || !holds(m.row.permission)) return [];
    return [{ r, m, path }];
  });
  // The same call asked by two shapes (players' search for an email-like reference) is asked once.
  const unique = [...new Map(asking.map((a) => [`${a.r.service} ${a.path}`, a])).values()];
  const results = await Promise.all(
    unique.map(async ({ r, m, path }) => {
      const request = new Request(`http://backoffice${path}`, { headers: { Accept: "application/json" } });
      try {
        const { response, why } = await pass(deps, m, request, accessToken);
        if (why !== undefined || response.status >= 500) return { r, failed: true, matches: [] as Match[] };
        if (!response.ok) return { r, failed: false, matches: [] as Match[] };
        return { r, failed: false, matches: r.read(await response.json(), q) };
      } catch {
        return { r, failed: true, matches: [] as Match[] };
      }
    }),
  );
  const seen = new Set<string>();
  const matches = results
    .flatMap((x) => x.matches)
    .filter((m) => {
      const k = `${m.kind}/${m.id}`;
      return seen.has(k) ? false : (seen.add(k), true);
    });
  return {
    matches,
    asked: [...new Set(unique.map((a) => a.r.kind))].sort(),
    unavailable: [...new Set(results.filter((x) => x.failed).map((x) => x.r.service))].sort(),
  };
}
