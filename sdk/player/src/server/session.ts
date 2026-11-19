import { Hono } from "hono";
import { routes as core } from "../core/routes.ts";
import type { Route } from "../route.ts";
import { clearCookie, cookieName, readCookies, secondsLeft, setCookie, ThirtyDays } from "./cookies.ts";
import { traceHeaders, visitorHeaders, type ServerDeps } from "./context.ts";
import { callService } from "./services.ts";

const row = (id: string): Route => core.find((r) => r.id === id)!;

/** The player as the server keeps them in `eo_player`: `{playerId, currency, country}`. */
export interface HeldPlayer {
  readonly playerId: string;
  readonly currency: string;
  readonly country: string;
}

/** What a request's cookies hold of the sign-in. */
export interface Held {
  readonly session?: string;
  readonly refresh?: string;
  readonly player?: HeldPlayer;
}

export function held(deps: ServerDeps, request: Request): Held {
  const cookies = readCookies(request.headers.get("cookie"));
  const session = cookies.get(cookieName(deps.config, "session")) || undefined;
  const refresh = cookies.get(cookieName(deps.config, "refresh")) || undefined;
  let player: HeldPlayer | undefined;
  const raw = cookies.get(cookieName(deps.config, "player"));
  if (raw) {
    try {
      const p = JSON.parse(Buffer.from(raw, "base64url").toString("utf8")) as Partial<HeldPlayer>;
      if (typeof p.playerId === "string" && typeof p.currency === "string" && typeof p.country === "string") player = { playerId: p.playerId, currency: p.currency, country: p.country };
    } catch {
      // a cookie the server did not write is no player
    }
  }
  return { ...(session ? { session } : {}), ...(refresh ? { refresh } : {}), ...(player ? { player } : {}) };
}

const json = (status: number, body: unknown, headers: Array<[string, string]> = []) => {
  const h = new Headers({ "content-type": "application/json", "cache-control": "no-store" });
  for (const [k, v] of headers) h.append(k, v);
  return new Response(JSON.stringify(body), { status, headers: h });
};

const noContent = (headers: Array<[string, string]>) => {
  const h = new Headers({ "cache-control": "no-store" });
  for (const [k, v] of headers) h.append(k, v);
  return new Response(null, { status: 204, headers: h });
};

const readJson = async (r: Response): Promise<unknown> => {
  const text = await r.text();
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
};

/** The cookies a sign-in or a refresh leaves (research R3). */
export function issued(deps: ServerDeps, access: string, refresh: string, player?: HeldPlayer): Array<[string, string]> {
  const out: Array<[string, string]> = [
    ["set-cookie", setCookie(deps.config, "session", access, secondsLeft(access, deps.now()))],
    ["set-cookie", setCookie(deps.config, "refresh", refresh, ThirtyDays)],
  ];
  if (player) out.push(["set-cookie", setCookie(deps.config, "player", Buffer.from(JSON.stringify(player)).toString("base64url"), ThirtyDays)]);
  return out;
}

export function cleared(deps: ServerDeps): Array<[string, string]> {
  return (["session", "refresh", "player"] as const).map((role) => ["set-cookie", clearCookie(deps.config, role)]);
}

/** Asks `players` for a new sign-in. `undefined`: `players` did not answer. */
export async function refreshWith(deps: ServerDeps, request: Request, token: string): Promise<{ access: string; refresh: string } | "expired" | undefined> {
  const answer = await callService(deps.config, row("refresh"), {
    body: JSON.stringify({ refreshToken: token }),
    headers: { "content-type": "application/json", ...traceHeaders(request) },
  }, deps.fetch);
  if (!answer.answered || answer.response.status >= 500) return undefined;
  if (answer.response.status !== 200) return "expired";
  const body = (await readJson(answer.response)) as { accessToken?: unknown; refreshToken?: unknown } | null;
  if (typeof body?.accessToken !== "string" || typeof body.refreshToken !== "string") return undefined;
  return { access: body.accessToken, refresh: body.refreshToken };
}

/** The site server's session routes (contracts/site-server.md, *Session routes*). */
export function sessionRoutes(deps: ServerDeps): Hono {
  const app = new Hono();
  const { config } = deps;

  app.post("/api/session/sign-in", async (c) => {
    const given = (await c.req.json().catch(() => null)) as { email?: unknown; password?: unknown; deviceFingerprint?: unknown } | null;
    const body = {
      email: typeof given?.email === "string" ? given.email : "",
      password: typeof given?.password === "string" ? given.password : "",
      ...(typeof given?.deviceFingerprint === "string" ? { deviceFingerprint: given.deviceFingerprint } : {}),
    };
    const answer = await callService(config, row("signIn"), {
      body: JSON.stringify(body),
      headers: { "content-type": "application/json", ...visitorHeaders(c.req.raw), ...traceHeaders(c.req.raw) },
    }, deps.fetch);
    if (!answer.answered) return json(503, { error: "service_unavailable", service: "players" });
    const read = await readJson(answer.response);
    if (answer.response.status !== 200) return json(answer.response.status, read);
    const b = read as { token?: unknown; refreshToken?: unknown; playerId?: unknown; brandId?: unknown; currency?: unknown; country?: unknown } | null;
    if (typeof b?.token !== "string" || typeof b.refreshToken !== "string" || typeof b.playerId !== "string") return json(502, { error: "malformed_answer", service: "players" });
    const player = { playerId: b.playerId, currency: String(b.currency ?? ""), country: String(b.country ?? "") };
    return json(200, { playerId: player.playerId, brand: typeof b.brandId === "string" ? b.brandId : config.brand, currency: player.currency, country: player.country }, issued(deps, b.token, b.refreshToken, player));
  });

  app.post("/api/session/refresh", async (c) => {
    const h = held(deps, c.req.raw);
    if (!h.refresh) return json(401, { error: "refresh_expired" }, cleared(deps));
    const r = await refreshWith(deps, c.req.raw, h.refresh);
    if (r === undefined) return json(503, { error: "service_unavailable", service: "players" });
    if (r === "expired") return json(401, { error: "refresh_expired" }, cleared(deps));
    // The player cookie is renewed with the refresh cookie, or it lapses 30 days after the sign-in
    // while the refresh cookie lives on (review).
    return noContent(issued(deps, r.access, r.refresh, h.player));
  });

  app.post("/api/session/sign-out", async (c) => {
    const h = held(deps, c.req.raw);
    let access = h.session;
    if (!access && h.refresh) {
      const r = await refreshWith(deps, c.req.raw, h.refresh);
      if (r && r !== "expired") access = r.access;
    }
    if (access && h.player) {
      await callService(config, row("signOut"), {
        path: { player: h.player.playerId },
        headers: { authorization: `Bearer ${access}`, ...traceHeaders(c.req.raw) },
      }, deps.fetch);
    }
    return noContent(cleared(deps));
  });

  app.get("/api/session", (c) => {
    const h = held(deps, c.req.raw);
    if (!h.player || (!h.session && !h.refresh)) return json(200, { signedOut: true });
    return json(200, { playerId: h.player.playerId, brand: config.brand, currency: h.player.currency, country: h.player.country });
  });

  app.get("/api/site", () => json(200, { brand: config.brand, languages: config.languages, currency: config.currency, modules: config.modules }));

  return app;
}
