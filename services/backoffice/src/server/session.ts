// The operator's sign-in, as the page's server holds it (research R2): only the session id, in the
// `__Host-bo` cookie, and the session's access token in memory for no longer than its life. The tokens
// themselves are `backoffice-state`'s; this file is its client.
import type { Context } from "hono";
import { deleteCookie, getCookie, setCookie } from "hono/cookie";

export const CookieName = "__Host-bo";

/** An access token with what the realm put in it; `expiresAt` in epoch milliseconds. */
export interface Token {
  accessToken: string;
  expiresAt: number;
  roles: string[];
  /** The roles differ from the last refresh's (data-model.md), said once. */
  rolesChanged?: boolean;
}

export interface Opened {
  session: string;
  subject: string;
  expiresAt: number;
}

/** What the page's server asks of `backoffice-state` (contracts/routes.md). */
export interface StateClient {
  open(code: string, verifier: string, redirectUri: string): Promise<Opened>;
  /** The session's token, refreshed by the state service when under 30 seconds remain; none once it ended. */
  token(session: string): Promise<Token | undefined>;
  end(session: string): Promise<void>;
  /** The operator's preferences (data-model.md), as the state service keeps them. */
  preferences(subject: string): Promise<Preferences>;
  /** A write of some preferences; the state service's answer as it gave it (200 with them all, or 400 with its refusal). */
  writePreferences(subject: string, body: string): Promise<{ status: number; body: string }>;
}

export interface Preferences {
  theme: "dark" | "light";
  /** `null` while the operator keeps the default: every kind their roles admit. */
  worklistKinds: string[] | null;
  onlineNotices: boolean;
  jobs: { reference: string; kind: string; startedAt: string }[];
}

export class StateUnavailable extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`backoffice-state answered ${status}`);
    this.status = status;
    this.name = "StateUnavailable";
  }
}

const instant = (v: unknown): number => (typeof v === "number" ? v : Date.parse(String(v)));

/** The client of `backoffice-state` at the calling address. No token is put in an error it raises. */
export function stateClient(servicesUrl: string, fetchFn: typeof fetch, timeoutMs = 5_000): StateClient {
  const base = `${servicesUrl}/backoffice-state`;
  const call = (method: string, path: string, body?: unknown) =>
    fetchFn(`${base}${path}`, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  return {
    async open(code, verifier, redirectUri) {
      const r = await call("POST", "/sessions", { code, verifier, redirectUri });
      if (!r.ok) throw new StateUnavailable(r.status);
      const b = (await r.json()) as { session: string; subject: string; expiresAt: unknown };
      return { session: b.session, subject: b.subject, expiresAt: instant(b.expiresAt) };
    },
    async token(session) {
      const r = await call("POST", `/sessions/${encodeURIComponent(session)}/token`);
      if (r.status === 404) return undefined;
      if (!r.ok) throw new StateUnavailable(r.status);
      const b = (await r.json()) as { accessToken: string; expiresAt: unknown; roles?: string[]; rolesChanged?: boolean };
      return { accessToken: b.accessToken, expiresAt: instant(b.expiresAt), roles: b.roles ?? [], rolesChanged: b.rolesChanged === true };
    },
    async end(session) {
      const r = await call("DELETE", `/sessions/${encodeURIComponent(session)}`);
      if (!r.ok && r.status !== 404) throw new StateUnavailable(r.status);
    },
    async preferences(subject) {
      const r = await call("GET", `/preferences/${encodeURIComponent(subject)}`);
      if (!r.ok) throw new StateUnavailable(r.status);
      return (await r.json()) as Preferences;
    },
    async writePreferences(subject, body) {
      const r = await fetchFn(`${base}/preferences/${encodeURIComponent(subject)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body,
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (r.status !== 200 && r.status !== 400) throw new StateUnavailable(r.status);
      return { status: r.status, body: await r.text() };
    },
  };
}

/** A token kept until 30 seconds before its expiry, so the state service is asked to refresh it in time. */
export class TokenCache {
  static readonly Margin = 30_000;
  private readonly kept = new Map<string, Token>();

  private readonly state: StateClient;
  private readonly now: () => number;

  constructor(state: StateClient, now: () => number) {
    this.state = state;
    this.now = now;
  }

  async token(session: string): Promise<Token | undefined> {
    const held = this.kept.get(session);
    if (held && held.expiresAt - this.now() > TokenCache.Margin) return { ...held, rolesChanged: false };
    const fresh = await this.state.token(session);
    if (fresh === undefined) this.kept.delete(session);
    else this.keep(session, fresh);
    return fresh;
  }

  forget(session: string): void {
    this.kept.delete(session);
  }

  private keep(session: string, token: Token): void {
    if (this.kept.size > 10_000) for (const [s, t] of this.kept) if (t.expiresAt <= this.now()) this.kept.delete(s);
    this.kept.set(session, token);
  }
}

export function sessionOf(c: Context): string | undefined {
  const id = getCookie(c, CookieName);
  return id && /^[A-Za-z0-9_-]{1,128}$/.test(id) ? id : undefined;
}

/** `HttpOnly`, `Secure`, `SameSite=Lax`, path `/`, no domain: what a `__Host-` cookie must be. */
export function keepSession(c: Context, opened: Opened, now: number): void {
  setCookie(c, CookieName, opened.session, {
    httpOnly: true,
    secure: true,
    sameSite: "Lax",
    path: "/",
    maxAge: Math.max(0, Math.floor((opened.expiresAt - now) / 1000)),
  });
}

export function dropSession(c: Context): void {
  deleteCookie(c, CookieName, { path: "/", secure: true });
}
