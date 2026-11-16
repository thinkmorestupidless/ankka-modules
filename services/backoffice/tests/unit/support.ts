// What the server's unit tests share: an app over a scripted state service and scripted owners, and a
// record of every log line, so a test can say no line carried a token.
import { createApp, type Deps } from "../../src/server/main.ts";
import type { Preferences, StateClient, Token } from "../../src/server/session.ts";
import { Kept, type Bags } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import type { Row } from "../../src/server/routes.ts";

export const Brand = "goldvibe";
export const Host = "goldvibe-backoffice.example.test";
export const Origin = `https://${Host}`;
export const SecretToken = "eyJ.secret-access-token.sig";

export const rows: Row[] = [
  { method: "GET", service: "players", path: "/players/{p}/profile", upstream: "/v1/casino/backoffice/{brand}/players/{p}/profile", where: "brand", permission: "player.read" },
  { method: "PATCH", service: "players", path: "/players/{p}/suspend", upstream: "/v1/casino/backoffice/{brand}/players/{p}/suspend", where: "brand", permission: "standing.change" },
  { method: "GET", service: "reporting", path: "/exports/{e}", upstream: "/v1/casino/backoffice/{brand}/reporting/exports/{e}", where: "brand", permission: "report.read" },
  { method: "GET", service: "merchant", path: "/deposits", upstream: "/v1/merchants/{brand}/deposits", where: "payments", permission: "wallet.read" },
];

export interface Call { url: string; method: string; headers: Headers; body: string }

/** A state service that knows the sessions it was given, and counts its token calls. */
export class FakeState implements StateClient {
  sessions = new Map<string, Token>();
  tokenCalls = 0;
  ended: string[] = [];
  async open(_code: string, _verifier: string, _redirectUri: string): Promise<{ session: string; subject: string; expiresAt: number }> {
    throw new Error("not scripted");
  }
  async token(session: string): Promise<Token | undefined> {
    this.tokenCalls++;
    return this.sessions.get(session);
  }
  async end(session: string): Promise<void> {
    this.ended.push(session);
    this.sessions.delete(session);
  }
  prefs: Preferences = { theme: "dark", worklistKinds: null, onlineNotices: false, jobs: [] };
  async preferences(): Promise<Preferences> {
    return this.prefs;
  }
  written: { subject: string; body: string }[] = [];
  /** What the state service answers a write; by default it merges it in. */
  writeAnswer: ((body: string) => { status: number; body: string }) | Error | undefined;
  async writePreferences(subject: string, body: string) {
    this.written.push({ subject, body });
    if (this.writeAnswer instanceof Error) throw this.writeAnswer;
    if (this.writeAnswer) return this.writeAnswer(body);
    this.prefs = { ...this.prefs, ...(JSON.parse(body) as Partial<Preferences>) };
    return { status: 200, body: JSON.stringify(this.prefs) };
  }
}

export function harness(over: Partial<Deps> = {}) {
  const logs: string[] = [];
  const calls: Call[] = [];
  const state = new FakeState();
  state.sessions.set("s-1", { accessToken: SecretToken, expiresAt: Date.now() + 5 * 60_000, roles: [`brand_${Brand}`, "brand_admin"] });
  let answer: (call: Call) => Response | Promise<Response> = () => Response.json({ ok: true });
  let bags: () => Bags = () => OperatorBags;
  let modules: () => string[] = () => ["casino", "core"];
  let zone: () => string = () => "America/Sao_Paulo";
  const fetchFn: typeof fetch = async (input, init) => {
    const req = new Request(input, init);
    const call = { url: req.url, method: req.method, headers: req.headers, body: req.body ? await req.text() : "" };
    calls.push(call);
    // as a real fetch does: the request's signal ends the wait
    const signal = init?.signal;
    return new Promise<Response>((resolve, reject) => {
      signal?.addEventListener("abort", () => reject(signal.reason), { once: true });
      Promise.resolve().then(() => answer(call)).then(resolve, reject);
    });
  };
  const deps: Deps = {
    brand: Brand,
    servicesUrl: "http://services.local",
    sharedProjects: { payments: "payments", programme: "programme", reasoner: "reasoner", games: "games" },
    state,
    fetch: fetchFn,
    log: (line) => void logs.push(line),
    now: () => Date.now(),
    table: rows,
    timeoutMs: 15_000,
    realm: { issuer: "https://realm.example.test/realms/eitheror", clientId: `backoffice-${Brand}` },
    address: Origin,
    reads: {
      bags: new Kept<Bags>(async () => bags(), 10_000, () => Date.now()),
      modules: new Kept<string[]>(async () => modules(), 60_000, () => Date.now()),
      zone: new Kept<string>(async () => zone(), 60_000, () => Date.now()),
    },
    ...over,
  };
  const app = createApp(deps);
  return {
    app,
    state,
    logs,
    calls,
    answer: (f: typeof answer) => void (answer = f),
    bagsAre: (f: () => Bags) => void (bags = f),
    modulesAre: (f: () => string[]) => void (modules = f),
    zoneIs: (f: () => string) => void (zone = f),
    /** A request as the platform's proxy passes it: its host stated, the session's cookie when given. */
    ask: (method: string, path: string, opts: { session?: string; headers?: Record<string, string>; body?: string } = {}) => {
      const headers: Record<string, string> = { "X-Forwarded-Host": Host, "X-Forwarded-Proto": "https", ...opts.headers };
      if (opts.session) headers.Cookie = `__Host-bo=${opts.session}`;
      return app.request(`http://${Host}${path}`, { method, headers, body: opts.body });
    },
  };
}

/** The headers a page's own write carries. */
export const write = { "X-Backoffice": "1", Origin, "Content-Type": "application/json" };

/** Every header and the body of an answer, as one text, to search for a token. */
export async function whole(r: Response): Promise<string> {
  return [...r.headers.entries()].map(([k, v]) => `${k}: ${v}`).join("\n") + "\n" + (await r.text());
}
