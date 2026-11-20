// Signing in at the `eitheror` realm (contracts/routes.md, research R2): the code flow with PKCE, the
// brand's own confidential client `backoffice-<slug>`, the exchange made by `backoffice-state`, which
// alone holds the client's secret. The verifier and the state ride in a short-lived `__Host-` cookie, so
// any instance can finish a sign-in another began.
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import type { Context, Hono } from "hono";
import { deleteCookie, getCookie, setCookie } from "hono/cookie";
import { dropSession, keepSession, sessionOf, StateUnavailable, type StateClient, type TokenCache } from "./session.ts";
import type { Env } from "./main.ts";

export interface Realm {
  /** `ANKKA_AUTH_STAFF_ISSUER`: `<keycloak>/realms/eitheror`. */
  issuer: string;
  clientId: string;
}

const SignIn = "__Host-bo-signin";
const b64url = (b: Buffer) => b.toString("base64url");

/** A path of this site, never another's: `/x`, not `//x`, `/\x` or an absolute address. */
export function returnPath(asked: string | undefined): string {
  if (!asked || !/^\/(?![\/\\])[\x21-\x7e]*$/.test(asked) || asked.startsWith("/auth/")) return "/";
  return asked;
}

function same(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function page(c: Context, status: 400 | 503, text: string): Response {
  return c.html(`<!doctype html><meta charset="utf-8"><title>Sign-in</title><p>${text}</p><p><a href="/auth/sign-in">Sign in again</a></p>`, status);
}

export function authRoutes(app: Hono<Env>, deps: { realm: Realm; address: string; state: StateClient; tokens: TokenCache; now: () => number }): void {
  const callback = `${deps.address}/auth/callback`;

  app.get("/auth/sign-in", (c) => {
    const verifier = b64url(randomBytes(32));
    const state = b64url(randomBytes(16));
    const back = returnPath(c.req.query("return"));
    setCookie(c, SignIn, `${state}.${verifier}.${b64url(Buffer.from(back))}`, {
      httpOnly: true,
      secure: true,
      sameSite: "Lax",
      path: "/",
      maxAge: 600,
    });
    const url = new URL(`${deps.realm.issuer}/protocol/openid-connect/auth`);
    url.search = new URLSearchParams({
      client_id: deps.realm.clientId,
      response_type: "code",
      scope: "openid",
      redirect_uri: callback,
      state,
      code_challenge: b64url(createHash("sha256").update(verifier).digest()),
      code_challenge_method: "S256",
    }).toString();
    return c.redirect(url.toString(), 302);
  });

  app.get("/auth/callback", async (c) => {
    const kept = getCookie(c, SignIn)?.split(".");
    deleteCookie(c, SignIn, { path: "/", secure: true });
    const error = c.req.query("error");
    if (error) return page(c, 400, `The realm refused the sign-in (${error.replace(/[^a-z_]/g, "")}).`);
    const code = c.req.query("code");
    const state = c.req.query("state");
    if (!kept || kept.length !== 3 || !code || !state || !same(state, kept[0]!))
      return page(c, 400, "This sign-in expired or was started in another browser.");
    try {
      const opened = await deps.state.open(code, kept[1]!, callback);
      keepSession(c, opened, deps.now());
    } catch (e) {
      if (e instanceof StateUnavailable && e.status < 500) return page(c, 400, "The realm refused the sign-in.");
      return page(c, 503, "The sign-in cannot be finished now.");
    }
    return c.redirect(returnPath(Buffer.from(kept[2]!, "base64url").toString()), 302);
  });

  // Ends the session here and at the realm (`backoffice-state` revokes the refresh token and logs out).
  app.post("/auth/sign-out", async (c) => {
    const session = sessionOf(c);
    if (session !== undefined) {
      try {
        await deps.state.end(session);
      } catch {
        return c.json({ error: "source_unavailable", source: "backoffice-state" }, 503);
      }
      deps.tokens.forget(session);
    }
    dropSession(c);
    return c.json({ signedOut: true });
  });
}
