import { readEvents, type StreamEvent } from "./eventStream.ts";
import { readRefusal, refusal, type Refusal } from "./refusal.ts";
import type { Route } from "./route.ts";

/** What a call is given: the path's values, the service's query, the body, extra headers. */
export interface CallOptions {
  readonly path?: Readonly<Record<string, string>>;
  readonly query?: Readonly<Record<string, string | number | boolean | undefined>>;
  readonly body?: unknown;
  readonly headers?: Readonly<Record<string, string>>;
  /** The call outlives the page (an ended game session on `pagehide`). */
  readonly keepalive?: boolean;
  readonly signal?: AbortSignal;
  /** Whether this service's words reach the page (the support agent's, a payment provider's rejection). */
  readonly words?: boolean;
}

export type Called = { ok: true; status: number; body: unknown } | { ok: false; refusal: Refusal };
export type Streamed = { ok: true; events: AsyncIterable<StreamEvent>; close(): void } | { ok: false; refusal: Refusal };

/** The player as the site server holds them. */
export interface SignedInPlayer {
  readonly playerId: string;
  readonly brand: string;
  readonly currency: string;
  readonly country: string;
}

export interface SiteFacts {
  readonly brand: string;
  readonly languages: readonly string[];
  readonly currency: string;
  readonly modules: readonly string[];
}

export type SignedOutReason = "refresh_expired" | "idle" | "signed_out";

/** The site server's own routes (contracts/site-server.md, *Session routes*). */
export interface SessionApi {
  signIn(credentials: { email: string; password: string; deviceFingerprint?: string }): Promise<{ ok: true; player: SignedInPlayer } | { ok: false; refusal: Refusal }>;
  refresh(): Promise<{ ok: true } | { ok: false; refusal: Refusal }>;
  signOut(): Promise<void>;
  read(): Promise<{ ok: true; player: SignedInPlayer | null } | { ok: false; refusal: Refusal }>;
  site(): Promise<{ ok: true; site: SiteFacts } | { ok: false; refusal: Refusal }>;
}

/** Every call a page makes passes through one transport (FR-001, FR-005). */
export interface Transport {
  call(route: Route, options?: CallOptions): Promise<Called>;
  stream(route: Route, options?: CallOptions): Promise<Streamed>;
  readonly session: SessionApi;
  /** Told when a call found the player signed out and the one shared refresh could not sign them in again. */
  onSignedOut(listener: (reason: SignedOutReason) => void): () => void;
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

const json = async (response: Response): Promise<unknown> => {
  const text = await response.text();
  if (text === "") return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
};

/** The address of a module route at the site server: `/api/c/<module>/<id>?<path values>&<query>`. */
export function passThroughUrl(route: Route, options: CallOptions = {}, base = ""): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(options.path ?? {})) params.set(name, value);
  for (const [name, value] of Object.entries(options.query ?? {})) if (value !== undefined) params.set(name, String(value));
  const q = params.toString();
  return `${base}/api/c/${route.module}/${route.id}${q ? `?${q}` : ""}`;
}

/**
 * The page's transport: every call to `/api/c/<module>/<id>` at the site server, with the row's
 * own method. A `player` route answered `signed_out` is refreshed once, shared by every call that
 * meets it at the same time, and made once more; a refused refresh reads the session again before
 * signing the player out, since another tab may have refreshed (research R3). A `ticket` route's
 * `401` is the cashier's and refreshes nothing.
 */
export function pageTransport(fetchImpl: Fetch = (input, init) => fetch(input, init), base = ""): Transport {
  const listeners = new Set<(reason: SignedOutReason) => void>();
  let refreshing: Promise<"again" | "signed_out" | Refusal> | null = null;

  const unavailable = (service: string) => refusal("service_unavailable", 503, undefined, service);

  const send = async (url: string, method: string, options: CallOptions, accept?: string): Promise<Response | Refusal> => {
    const headers: Record<string, string> = { ...(options.headers ?? {}) };
    if (accept) headers.accept = accept;
    if (options.body !== undefined) headers["content-type"] = "application/json";
    try {
      return await fetchImpl(url, {
        method,
        headers,
        credentials: "same-origin",
        ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
        ...(options.keepalive ? { keepalive: true } : {}),
        ...(options.signal ? { signal: options.signal } : {}),
      });
    } catch {
      return unavailable("site");
    }
  };

  const session: SessionApi = {
    async signIn(credentials) {
      const r = await send(`${base}/api/session/sign-in`, "POST", { body: credentials });
      if (!(r instanceof Response)) return { ok: false, refusal: r };
      const body = await json(r);
      if (r.ok) return { ok: true, player: body as SignedInPlayer };
      return { ok: false, refusal: readRefusal(r.status, body) };
    },
    async refresh() {
      const r = await send(`${base}/api/session/refresh`, "POST", {});
      if (!(r instanceof Response)) return { ok: false, refusal: r };
      if (r.ok) return { ok: true };
      return { ok: false, refusal: readRefusal(r.status, await json(r)) };
    },
    async signOut() {
      await send(`${base}/api/session/sign-out`, "POST", {});
    },
    async read() {
      const r = await send(`${base}/api/session`, "GET", {});
      if (!(r instanceof Response)) return { ok: false, refusal: r };
      const body = (await json(r)) as Record<string, unknown> | null;
      if (!r.ok) return { ok: false, refusal: readRefusal(r.status, body) };
      return { ok: true, player: body && body.signedOut !== true ? (body as unknown as SignedInPlayer) : null };
    },
    async site() {
      const r = await send(`${base}/api/site`, "GET", {});
      if (!(r instanceof Response)) return { ok: false, refusal: r };
      const body = await json(r);
      if (!r.ok) return { ok: false, refusal: readRefusal(r.status, body) };
      return { ok: true, site: body as SiteFacts };
    },
  };

  /** The one refresh every call meeting `signed_out` at once shares. */
  const refreshOnce = () => {
    refreshing ??= (async () => {
      const r = await session.refresh();
      if (r.ok) return "again" as const;
      if (r.refusal.code === "service_unavailable") return r.refusal;
      const now = await session.read();
      if (now.ok && now.player) return "again" as const;
      for (const listener of [...listeners]) listener("refresh_expired");
      return "signed_out" as const;
    })().finally(() => {
      refreshing = null;
    });
    return refreshing;
  };

  const signedOut = (r: Response, route: Route, body: unknown) =>
    r.status === 401 && route.audience === "player" && typeof body === "object" && body !== null && (body as { error?: unknown }).error === "signed_out";

  const once = async <T>(route: Route, words: boolean, attempt: () => Promise<Response | Refusal>, done: (r: Response) => Promise<T>, refused: (r: Refusal) => T): Promise<T> => {
    for (let tries = 0; ; tries++) {
      const r = await attempt();
      if (!(r instanceof Response)) return refused(r);
      if (r.ok) return done(r);
      const body = await json(r);
      if (tries === 0 && signedOut(r, route, body)) {
        const after = await refreshOnce();
        if (after === "again") continue;
        if (after === "signed_out") return refused(refusal("signed_out", 401));
        return refused(after);
      }
      // Still signed out after a refresh that worked: the sign-in is gone all the same (review).
      if (tries > 0 && signedOut(r, route, body)) {
        for (const listener of [...listeners]) listener("refresh_expired");
        return refused(refusal("signed_out", 401));
      }
      return refused(readRefusal(r.status, body, { words }));
    }
  };

  return {
    session,
    onSignedOut(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    call(route, options = {}) {
      const url = passThroughUrl(route, options, base);
      return once<Called>(
        route,
        options.words === true,
        () => send(url, route.method, options),
        async (r) => ({ ok: true, status: r.status, body: await json(r) }),
        (refusal) => ({ ok: false, refusal }),
      );
    },
    stream(route, options = {}) {
      const url = passThroughUrl(route, options, base);
      const controller = new AbortController();
      const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal;
      return once<Streamed>(
        route,
        options.words === true,
        () => send(url, route.method, { ...options, signal }, "text/event-stream"),
        async (r) => {
          if (!r.body) return { ok: false, refusal: refusal("malformed_answer", r.status) };
          return { ok: true, events: readEvents(r.body, signal), close: () => controller.abort() };
        },
        (refusal) => ({ ok: false, refusal }),
      );
    },
  };
}
