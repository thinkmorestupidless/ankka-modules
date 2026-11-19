import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import type { StreamEvent } from "../eventStream.ts";
import { readRefusal, refusal } from "../refusal.ts";
import { pathNames, type Route } from "../route.ts";
import type { CallOptions, Called, SessionApi, SignedInPlayer, SignedOutReason, SiteFacts, Streamed, Transport } from "../transport.ts";
import type { Clock } from "../clock.ts";

/**
 * Scripted doubles of the services a player site calls (`players`, the wallet, responsible
 * gambling, the cashier, `game-provider`, `rewards`, `engagement`, `support-agent`), as one fake
 * brand. Each route of contracts/module-clients.md answers by default as the brand would; a test
 * scripts any route by id to refuse, answer late, stay silent, or stream events it pushes.
 * The same doubles answer at the level of a `Transport` (the behaviours' suites) and as an HTTP
 * server at the calling address (the site server's and the site's suites).
 */

/** A request a double was asked. */
export interface Asked {
  readonly id: string;
  readonly service: string;
  readonly method: string;
  /** The path's values, `{brand}` among them. */
  readonly path: Readonly<Record<string, string>>;
  readonly query: Readonly<Record<string, string>>;
  readonly body: unknown;
  readonly headers: Readonly<Record<string, string>>;
}

/** What a double answers. */
export interface Reply {
  readonly status: number;
  readonly body?: unknown;
  /** Answer as `text/event-stream`: these events, or a channel the test pushes into. */
  readonly stream?: readonly StreamEvent[] | Channel;
  /** Answer after this long, on the doubles' clock. */
  readonly afterMs?: number;
  /** Never answer. At the transport level the site server's 15 s bound answers instead. */
  readonly silent?: boolean;
}

export type Script = Reply | ((asked: Asked, brand: FakeBrand) => Reply | Promise<Reply>);

/** A stream a test writes to. */
export interface Channel extends AsyncIterable<StreamEvent> {
  push(event: string, data: unknown): void;
  end(): void;
  readonly ended: boolean;
}

export function channel(): Channel {
  const queue: StreamEvent[] = [];
  let waiting: ((r: IteratorResult<StreamEvent>) => void) | null = null;
  let ended = false;
  return {
    get ended() {
      return ended;
    },
    push(event, data) {
      if (ended) return;
      const e = { event, data: typeof data === "string" ? data : JSON.stringify(data) };
      if (waiting) {
        const w = waiting;
        waiting = null;
        w({ value: e, done: false });
      } else queue.push(e);
    },
    end() {
      ended = true;
      if (waiting) {
        const w = waiting;
        waiting = null;
        w({ value: undefined, done: true });
      }
    },
    [Symbol.asyncIterator]() {
      return {
        next: () => {
          const e = queue.shift();
          if (e) return Promise.resolve({ value: e, done: false });
          if (ended) return Promise.resolve({ value: undefined, done: true } as IteratorResult<StreamEvent>);
          return new Promise<IteratorResult<StreamEvent>>((resolve) => (waiting = resolve));
        },
        return: () => {
          ended = true;
          return Promise.resolve({ value: undefined, done: true } as IteratorResult<StreamEvent>);
        },
      };
    },
  };
}

export interface FakePlayer extends SignedInPlayer {
  email: string;
  password: string;
  verified: boolean;
  profile: Record<string, unknown>;
}

export interface FakeTerminal {
  terminalEntityId: string;
  name: string;
  scheme: string;
  gateway?: string;
  supportedCurrencies?: string[];
}

export interface FakePayment {
  paymentId: string;
  status: string;
  amountCents: number;
  currency: string;
  redirectUrl?: string;
  paymentAddress?: string;
  cryptoAmount?: string;
  cryptoCurrency?: string;
  validUntil?: string;
  failureCode?: string;
  failureReason?: string;
}

export interface FakeGame {
  gameId: string;
  name: string;
  provider: string;
  restrictedTerritories?: string[];
  prohibitedTerritories?: string[];
  demo?: boolean;
}

/** The fake brand's state, which the default answers read and change. */
export interface FakeBrand {
  brand: string;
  languages: string[];
  currency: string;
  modules: string[];
  players: FakePlayer[];
  /** Who the site server holds signed in, at the transport level. */
  signedIn: FakePlayer | null;
  /** The real and bonus balance as the wallet writes them, in major units. */
  balance: { real: string; bonus: string };
  terminals: FakeTerminal[];
  /** The cashier sessions opened, by ticket. */
  cashierSessions: Map<string, { requestId: string; amount: number; currency: string }>;
  payments: Map<string, FakePayment>;
  games: FakeGame[];
  gameSessions: Map<string, { sessionId: string; gameId: string; status: string; lastBetAt: string | null }>;
  freeRounds: Array<{ id: string; gameId: string; spinsRemaining: number }>;
  protection: { realityCheckIntervalMinutes: number | null; isCoolingOff: boolean; coolingOffUntil: string | null };
  country: string;
  unread: number;
  /** Each push connection the page opened, in order: a test pushes into one or ends it. */
  pushes: Channel[];
  next: number;
}

export function fakeBrand(overrides: Partial<FakeBrand> = {}): FakeBrand {
  const ana: FakePlayer = {
    playerId: "p-ana",
    brand: "guyanabet",
    currency: "EUR",
    country: "GF",
    email: "ana@example.com",
    password: "correct horse battery 1!",
    verified: true,
    profile: { firstName: "Ana", lastName: "Silva" },
  };
  return {
    brand: "guyanabet",
    languages: ["fr", "en"],
    currency: "EUR",
    modules: ["core", "casino", "engagement", "support", "player-site"],
    players: [ana],
    signedIn: null,
    balance: { real: "50.00", bonus: "0.00" },
    terminals: [
      { terminalEntityId: "t-card", name: "Carte", scheme: "VISA", gateway: "stub", supportedCurrencies: ["EUR"] },
      { terminalEntityId: "t-skrill", name: "Skrill", scheme: "SKRILL", gateway: "stub" },
      { terminalEntityId: "t-crypto", name: "Crypto", scheme: "CRYPTO", gateway: "stub", supportedCurrencies: ["BTC", "USDT"] },
      { terminalEntityId: "t-redirect", name: "Virement", scheme: "BANK", gateway: "stub" },
    ],
    cashierSessions: new Map(),
    payments: new Map(),
    games: [
      { gameId: "pools", name: "Pools", provider: "eitheror", demo: true },
      { gameId: "wheel", name: "Wheel", provider: "st8", demo: true },
    ],
    gameSessions: new Map(),
    freeRounds: [],
    protection: { realityCheckIntervalMinutes: null, isCoolingOff: false, coolingOffUntil: null },
    country: "GF",
    unread: 0,
    pushes: [],
    next: 1,
    ...overrides,
  };
}

/** A game as the catalogue answers it: the fields every game has, which a fake game need not say. */
const asCatalogued = (g: FakeGame) => ({ category: "slots", developerId: g.provider, developerName: g.provider, enabled: true, funMode: g.demo === true, ...g });

const id = (b: FakeBrand, prefix: string) => `${prefix}-${b.next++}`;

/** The default answer of each route, by id. */
const defaults: Record<string, (a: Asked, b: FakeBrand) => Reply> = {
  register(a, b) {
    const body = (a.body ?? {}) as Record<string, string>;
    const missing = ["email", "password", "currency"].filter((f) => !body[f]);
    if (missing.length) return { status: 400, body: { error: "missing_fields", fields: missing } };
    if (b.players.some((p) => p.email === body.email)) return { status: 409, body: { error: "email_already_exists", message: "Email already registered" } };
    const p: FakePlayer = { playerId: id(b, "p"), brand: b.brand, currency: body.currency!, country: b.country, email: body.email!, password: body.password!, verified: false, profile: {} };
    b.players.push(p);
    return { status: 201, body: { playerId: p.playerId, status: "pending_verification" } };
  },
  verifyEmail(a, b) {
    const token = (a.body as { token?: string } | undefined)?.token;
    const p = b.players.find((x) => `verify-${x.playerId}` === token);
    if (!p) return { status: 400, body: { error: "invalid_or_expired_token" } };
    p.verified = true;
    return { status: 200, body: { status: "verified" } };
  },
  resendVerification: () => ({ status: 200, body: { result: "sent" } }),
  requestPasswordReset: () => ({ status: 200, body: { status: "ok" } }),
  signIn(a, b) {
    const body = (a.body ?? {}) as { email?: string; password?: string };
    const p = b.players.find((x) => x.email === body.email);
    if (!p || p.password !== body.password) return { status: 401, body: { error: "invalid_credentials", message: "Invalid email or password" } };
    if (!p.verified) return { status: 401, body: { error: "email_not_verified", message: "Please verify your email" } };
    return {
      status: 200,
      body: {
        token: fakeToken(p.playerId, 300),
        refreshToken: `refresh-${p.playerId}-${b.next++}`,
        brandId: b.brand,
        playerId: p.playerId,
        currency: p.currency,
        country: p.country,
        pendingBonusCode: "",
      },
    };
  },
  refresh(a, b) {
    const token = (a.body as { refreshToken?: string } | undefined)?.refreshToken ?? "";
    const p = b.players.find((x) => token.startsWith(`refresh-${x.playerId}-`));
    if (!p) return { status: 401, body: { error: "refresh_expired" } };
    return { status: 200, body: { accessToken: fakeToken(p.playerId, 300), refreshToken: `refresh-${p.playerId}-${b.next++}`, expiresIn: 300 } };
  },
  signOut: () => ({ status: 200, body: { status: "logged_out" } }),
  profile(a, b) {
    const p = b.players.find((x) => x.playerId === a.path.player);
    if (!p) return { status: 404, body: { error: "player_not_found" } };
    return { status: 200, body: { playerId: p.playerId, status: "active", email: p.email, emailVerified: p.verified, currency: p.currency, profileComplete: false, missingProfileFields: [], ...p.profile } };
  },
  changeProfile(a, b) {
    const p = b.players.find((x) => x.playerId === a.path.player);
    if (p) Object.assign(p.profile, a.body);
    return { status: 200, body: { updated: true } };
  },
  startDeposit(a, b) {
    const body = (a.body ?? {}) as { amount?: number; currency?: string; requestId?: string };
    if (!Number.isSafeInteger(body.amount) || (body.amount ?? 0) <= 0) return { status: 400, body: { error: "invalid_amount", message: "amount must be a whole number above zero" } };
    const requestId = body.requestId ?? id(b, "req");
    for (const [ticket, s] of b.cashierSessions) {
      if (s.requestId !== requestId) continue;
      if (s.amount !== body.amount) return { status: 409, body: { error: "REQUEST_ID_REUSED", message: "transactionId reused", service: "ingest", transactionId: requestId } };
      return { status: 200, body: { redirectUrl: "", paymentId: requestId, cashierTicket: ticket, terminals: b.terminals, requestId } };
    }
    const ticket = id(b, "ticket");
    b.cashierSessions.set(ticket, { requestId, amount: body.amount!, currency: body.currency ?? b.currency });
    return { status: 200, body: { redirectUrl: "", paymentId: requestId, cashierTicket: ticket, terminals: b.terminals, requestId } };
  },
  unread: (_a, b) => ({ status: 200, body: { items: [], total: b.unread, unreadCount: b.unread, page: 1, pageSize: 1 } }),
  gameSession(a, b) {
    const s = b.gameSessions.get(a.path.sessionId ?? "");
    if (!s) return { status: 404, body: { error: "session_not_found" } };
    return { status: 200, body: { sessionId: s.sessionId, gameId: s.gameId, status: s.status, launchedAt: "2026-11-01T00:00:00Z", lastBetAt: s.lastBetAt } };
  },
  endGameSession(a, b) {
    const s = b.gameSessions.get(a.path.sessionId ?? "");
    if (!s) return { status: 404, body: { error: "session_not_found" } };
    if (s.status === "active") s.status = "completed";
    return { status: 200, body: { status: "completed", sessionId: s.sessionId, durationMinutes: 1 } };
  },
  country: (_a, b) => ({ status: 200, body: { country: b.country, dialCode: "+594" } }),
  balance: (a, b) => ({
    status: 200,
    body: { realBalance: b.balance.real, bonusBalance: b.balance.bonus, currency: a.path.currency ?? b.currency, primary: null, depositPoolBalance: "0.00", convertedPoolBalance: "0.00", reserved: "0.00", available: b.balance.real },
  }),
  protection: (_a, b) => ({
    status: 200,
    body: { limits: [], pendingChanges: [], usedAmounts: [], activeRestrictions: [], ...b.protection },
  }),
  acknowledgeRealityCheck: () => ({ status: 200, body: { acknowledged: true } }),
  submitCashier(a, b) {
    const s = b.cashierSessions.get(a.path.ticket ?? "");
    if (!s) return { status: 404, body: { error: "SESSION_NOT_FOUND" } };
    const body = (a.body ?? {}) as { selectedTerminalEntityId?: string; cryptoCurrency?: string };
    const terminal = b.terminals.find((t) => t.terminalEntityId === body.selectedTerminalEntityId);
    if (!terminal) return { status: 400, body: { error: "terminal_not_found" } };
    const paymentId = id(b, "pay");
    const payment: FakePayment =
      terminal.scheme === "CRYPTO"
        ? { paymentId, status: "AWAITING_CRYPTO", amountCents: s.amount, currency: s.currency, paymentAddress: "bc1qexampleaddress", cryptoAmount: "0.00071234", cryptoCurrency: body.cryptoCurrency ?? "BTC", validUntil: "2026-11-18T12:30:00Z" }
        : { paymentId, status: "AWAITING_REDIRECT", amountCents: s.amount, currency: s.currency, redirectUrl: `https://psp.example/pay/${paymentId}` };
    b.payments.set(paymentId, payment);
    return { status: 200, body: { paymentId, status: payment.status } };
  },
  abandonCashier: () => ({ status: 200, body: { status: "FAILED", failureCode: "player_abandoned" } }),
  payment(a, b) {
    const p = b.payments.get(a.path.paymentId ?? "");
    if (!p) return { status: 404, body: { error: "PAYMENT_NOT_FOUND" } };
    return { status: 200, body: { ...p, amount: (p.amountCents / 100).toFixed(2) } };
  },
  launch(a, b) {
    const body = (a.body ?? {}) as { gameId?: string; demo?: boolean };
    if (!body.gameId) return { status: 400, body: { error: "gameId required" } };
    const sessionId = id(b, "gs");
    b.gameSessions.set(sessionId, { sessionId, gameId: body.gameId, status: "active", lastBetAt: null });
    return { status: 200, body: { launchToken: "launch-secret", gameUrl: `https://games.example/${body.gameId}?s=${sessionId}`, sessionId } };
  },
  games: (_a, b) => ({ status: 200, body: { games: b.games.map(asCatalogued) } }),
  game(a, b) {
    const g = b.games.find((x) => x.gameId === a.path.gameId);
    return g ? { status: 200, body: asCatalogued(g) } : { status: 404, body: { error: "game_not_found" } };
  },
  freeRounds: (_a, b) => ({ status: 200, body: b.freeRounds.map((f) => ({ ...f, gameName: f.gameId, expiresAt: "2026-12-01T00:00:00Z", providerStatus: "active" })) }),
  // A push connection stays open until the test ends it, as engagement's does, beginning with `ready`.
  push: (_a, b) => {
    const c = channel();
    c.push("ready", { subjectId: b.signedIn?.playerId ?? "p-ana", serverTime: "2026-11-18T12:00:00Z" });
    b.pushes.push(c);
    return { status: 200, stream: c };
  },
  turn: () => ({ status: 200, stream: [{ event: "delta", data: JSON.stringify({ token: "Bonjour" }) }, { event: "done", data: "{}" }] }),
  decide: () => ({ status: 200, stream: [{ event: "done", data: "{}" }] }),
};

/** A token-shaped value: three parts, the middle a JSON payload with `sub` and `exp`. */
export function fakeToken(sub: string, lifeSeconds: number, now = Date.now()): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "none" })}.${b64({ sub, exp: Math.floor(now / 1000) + lifeSeconds, eitheror_twin_id: sub })}.sig-${sub}-${now}`;
}

export interface Doubles {
  readonly brand: FakeBrand;
  /** Every request asked, in order. */
  readonly asked: Asked[];
  /** Answers `id` with `script` from now on (or `times` times). */
  script(id: string, script: Script, times?: number): void;
  /** The answer a route gives now. */
  answer(id: string, asked: Asked): Promise<Reply>;
  /** The requests asked of one route. */
  askedOf(id: string): Asked[];
  readonly clock: Clock | undefined;
}

export function doubles(options: { brand?: Partial<FakeBrand>; clock?: Clock } = {}): Doubles {
  const brand = fakeBrand(options.brand);
  const scripts = new Map<string, Array<{ script: Script; times: number }>>();
  const asked: Asked[] = [];
  return {
    brand,
    asked,
    clock: options.clock,
    script(routeId, script, times = Infinity) {
      const list = scripts.get(routeId) ?? [];
      list.unshift({ script, times });
      scripts.set(routeId, list);
    },
    askedOf: (routeId) => asked.filter((a) => a.id === routeId),
    async answer(routeId, a) {
      asked.push(a);
      const list = scripts.get(routeId) ?? [];
      const entry = list.find((e) => e.times > 0);
      if (entry) {
        entry.times -= 1;
        return typeof entry.script === "function" ? entry.script(a, brand) : entry.script;
      }
      const d = defaults[routeId];
      return d ? d(a, brand) : { status: 404, body: { error: "not_named" } };
    },
  };
}

const later = (clock: Clock | undefined, ms: number) => new Promise<void>((resolve) => (clock ? clock.after(ms, resolve) : setTimeout(resolve, ms)));

const values = (_route: Route, options: CallOptions, brand: string) => {
  const path: Record<string, string> = { brand, ...(options.path ?? {}) };
  delete path.ticket;
  return path;
};

const toEvents = async function* (stream: readonly StreamEvent[] | Channel): AsyncGenerator<StreamEvent> {
  for await (const e of stream as AsyncIterable<StreamEvent>) yield e;
};

/**
 * A `Transport` over the doubles, as the site server would answer a page: a `player` route with
 * nobody signed in is `signed_out`; a silent service is `service_unavailable` after the site
 * server's 15 s bound, on the doubles' clock.
 */
export function doubleTransport(d: Doubles, options: { boundMs?: number } = {}): Transport {
  const bound = options.boundMs ?? 15_000;
  const listeners = new Set<(reason: SignedOutReason) => void>();
  const ask = async (route: Route, o: CallOptions): Promise<Reply> => {
    const path = values(route, o, d.brand.brand);
    // As the site server: a ticket row's `{ticket}` comes from the header `cashierTicket` alone.
    const ticket = o.headers?.cashierTicket;
    if (route.audience === "ticket" && ticket) path.ticket = ticket;
    const query = Object.fromEntries(Object.entries(o.query ?? {}).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]));
    const a: Asked = { id: route.id, service: route.service, method: route.method, path, query, body: o.body, headers: { ...(o.headers ?? {}) } };
    const reply = await d.answer(route.id, a);
    if (reply.afterMs) await later(d.clock, reply.afterMs);
    if (reply.silent) {
      await later(d.clock, bound);
      return { status: 503, body: { error: "service_unavailable", service: route.service } };
    }
    return reply;
  };
  // Whether this page held a sign-in: a player route finding none then is the lapsed sign-in the
  // page transport's one refresh could not renew, and its listeners are told.
  let held = false;
  const guard = (route: Route): Called | null => {
    if (route.audience === "public") return null;
    if (!d.brand.signedIn) {
      if (held) {
        held = false;
        for (const l of [...listeners]) l("refresh_expired");
      }
      return { ok: false, refusal: refusal("signed_out", 401) };
    }
    return null;
  };
  const session: SessionApi = {
    async signIn(credentials) {
      const reply = await d.answer("signIn", { id: "signIn", service: "players", method: "POST", path: { brand: d.brand.brand }, query: {}, body: credentials, headers: {} });
      if (reply.afterMs) await later(d.clock, reply.afterMs);
      if (reply.status !== 200) return { ok: false, refusal: readRefusal(reply.status, reply.body) };
      const p = d.brand.players.find((x) => x.playerId === (reply.body as { playerId: string }).playerId) ?? null;
      d.brand.signedIn = p;
      held = p !== null;
      const b = reply.body as { playerId: string; brandId: string; currency: string; country: string };
      return { ok: true, player: { playerId: b.playerId, brand: b.brandId, currency: b.currency, country: b.country } };
    },
    async refresh() {
      return d.brand.signedIn ? { ok: true } : { ok: false, refusal: refusal("refresh_expired", 401) };
    },
    async signOut() {
      await d.answer("signOut", { id: "signOut", service: "players", method: "POST", path: { brand: d.brand.brand, player: d.brand.signedIn?.playerId ?? "" }, query: {}, body: undefined, headers: {} });
      d.brand.signedIn = null;
      held = false;
    },
    async read() {
      const p = d.brand.signedIn;
      held = p !== null;
      return { ok: true, player: p ? { playerId: p.playerId, brand: p.brand, currency: p.currency, country: p.country } : null };
    },
    async site() {
      const site: SiteFacts = { brand: d.brand.brand, languages: d.brand.languages, currency: d.brand.currency, modules: d.brand.modules };
      return { ok: true, site };
    },
  };
  return {
    session,
    onSignedOut(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    async call(route, o = {}) {
      const refused = guard(route);
      if (refused) return refused;
      const reply = await ask(route, o);
      if (reply.status >= 200 && reply.status < 300) return { ok: true, status: reply.status, body: reply.body ?? null };
      return { ok: false, refusal: readRefusal(reply.status, reply.body, { words: o.words === true }) };
    },
    async stream(route, o = {}): Promise<Streamed> {
      const refused = guard(route);
      if (refused && !refused.ok) return refused;
      const reply = await ask(route, o);
      if (reply.status < 200 || reply.status >= 300) return { ok: false, refusal: readRefusal(reply.status, reply.body, { words: o.words === true }) };
      const source = reply.stream ?? [];
      return { ok: true, events: toEvents(source), close: () => (source as Channel).end?.() };
    },
  };
}

/**
 * The doubles as one HTTP server at a calling address: `/<service>/<path>`, as the platform's
 * `ANKKA_SERVICES_URL` is, and the cashier at `/ingest/<path>`. A request is matched to a row of the
 * route tables given; its `Authorization` and other headers are kept in `asked`.
 */
export async function serveDoubles(d: Doubles, routes: readonly Route[]): Promise<{ url: string; close(): Promise<void>; server: Server }> {
  const matchers = routes.map((route) => {
    const names = pathNames(route.path);
    const bare = route.path.split("?")[0]!;
    const escaped = bare.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\{[A-Za-z]+\}/g, "([^/]+)");
    const pattern = new RegExp(`^/${route.service}${escaped}$`);
    return { route, names, pattern };
  });
  const readBody = (req: IncomingMessage) =>
    new Promise<unknown>((resolve) => {
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        if (!text) return resolve(undefined);
        try {
          resolve(JSON.parse(text));
        } catch {
          resolve(text);
        }
      });
    });
  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://doubles");
    const found = matchers.find((m) => m.route.method === req.method && m.pattern.test(url.pathname));
    if (!found) {
      res.writeHead(404, { "content-type": "application/json" }).end(JSON.stringify({ error: "no_such_route", path: url.pathname }));
      return;
    }
    const groups = found.pattern.exec(url.pathname)!.slice(1);
    const path = Object.fromEntries(found.names.map((n, i) => [n, decodeURIComponent(groups[i] ?? "")]));
    const headers = Object.fromEntries(Object.entries(req.headers).map(([k, v]) => [k, Array.isArray(v) ? v.join(",") : (v ?? "")]));
    const body = await readBody(req);
    const reply = await d.answer(found.route.id, { id: found.route.id, service: found.route.service, method: req.method ?? "GET", path, query: Object.fromEntries(url.searchParams), body, headers });
    if (reply.afterMs) await later(undefined, reply.afterMs);
    if (reply.silent) return; // never answered; the caller's bound decides
    if (reply.stream) {
      res.writeHead(reply.status, { "content-type": "text/event-stream", "cache-control": "no-store" });
      res.flushHeaders();
      for await (const e of reply.stream as AsyncIterable<StreamEvent>) {
        if (res.destroyed) break;
        res.write(`event: ${e.event}\ndata: ${e.data}\n\n`);
      }
      res.end();
      return;
    }
    res.writeHead(reply.status, { "content-type": "application/json" });
    res.end(reply.body === undefined ? "" : JSON.stringify(reply.body));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    server,
    url: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}
