import { afterEach, describe, expect, it } from "vitest";
import { routes as core } from "../../src/core/routes.ts";
import { MissingConfiguration, readConfig } from "../../src/server/config.ts";
import { secondsLeft } from "../../src/server/cookies.ts";
import { sessionRoutes } from "../../src/server/session.ts";
import { fakeToken } from "../../src/testing/doubles.ts";
import { jar, served, setCookies, SITE } from "./helpers.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});

async function site(options: Parameters<typeof served>[1] = {}) {
  const s = await served(core, options);
  close = s.close;
  const app = sessionRoutes(s.deps);
  const post = (path: string, body: unknown, cookie = "", headers: Record<string, string> = {}) =>
    app.request(path, { method: "POST", headers: { "content-type": "application/json", origin: SITE, cookie, ...headers }, body: JSON.stringify(body) });
  const get = (path: string, cookie = "") => app.request(path, { headers: { cookie } });
  return { ...s, app, post, get };
}

describe("the site server holds the sign-in (FR-002, FR-003, FR-005 to FR-007)", () => {
  it("a sign-in answers the player and keeps what players issued in cookies the page cannot read", async () => {
    const s = await site();
    const r = await s.post("/api/session/sign-in", ana);
    expect(r.status).toBe(200);
    const body = await r.text();
    expect(JSON.parse(body)).toEqual({ playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" });
    expect(body).not.toMatch(/token|refresh/i);
    const set = setCookies(r);
    expect([...set.keys()].sort()).toEqual(["__Host-eo_player", "__Host-eo_session", "__Secure-eo_refresh"]);
    for (const [, c] of set) {
      expect(c.attributes).toEqual(expect.arrayContaining(["HttpOnly", "Secure", "SameSite=Lax"]));
    }
    expect(set.get("__Secure-eo_refresh")!.attributes).toContain("Path=/api/session");
    expect(set.get("__Host-eo_session")!.attributes).toContain("Path=/");
    // the session cookie lives as long as the token it holds (300 s)
    const age = Number(set.get("__Host-eo_session")!.attributes.find((a) => a.startsWith("Max-Age="))!.slice(8));
    expect(age).toBeGreaterThan(290);
    expect(age).toBeLessThanOrEqual(300);
  });

  it("a refused sign-in is players' refusal, unchanged, and sets no cookie", async () => {
    const s = await site();
    const r = await s.post("/api/session/sign-in", { ...ana, password: "wrong" });
    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "invalid_credentials", message: "Invalid email or password" });
    expect(r.headers.getSetCookie()).toEqual([]);
  });

  it("forwards the visitor's address and user agent to players with the sign-in", async () => {
    const s = await site();
    await s.post("/api/session/sign-in", ana, "", { "x-forwarded-for": "203.0.113.9, 10.0.0.1", "user-agent": "Firefox/140" });
    const asked = s.doubles.askedOf("signIn")[0]!;
    expect(asked.headers["x-forwarded-for"]).toBe("10.0.0.1");
    expect(asked.headers["x-real-ip"]).toBe("10.0.0.1");
    expect(asked.headers["user-agent"]).toBe("Firefox/140");
  });

  it("a refresh issues new cookies, and a refused one keeps them, for another tab may have refreshed (review)", async () => {
    const s = await site();
    const browser = jar();
    browser.take(await s.post("/api/session/sign-in", ana));
    const before = browser.get("__Host-eo_session");
    const r = browser.take(await s.post("/api/session/refresh", {}, browser.header()));
    expect(r.status).toBe(204);
    expect(browser.get("__Host-eo_session")).not.toBe(before);
    // The player cookie is renewed with the refresh cookie, for as long (review).
    const renewed = setCookies(r);
    expect(renewed.get("__Host-eo_player")!.attributes.find((a) => a.startsWith("Max-Age"))).toBe(renewed.get("__Secure-eo_refresh")!.attributes.find((a) => a.startsWith("Max-Age")));
    s.doubles.script("refresh", { status: 401, body: { error: "refresh_expired" } });
    const refused = browser.take(await s.post("/api/session/refresh", {}, browser.header()));
    expect(refused.status).toBe(401);
    expect(await refused.json()).toEqual({ error: "refresh_expired" });
    expect(setCookies(refused).size).toBe(0);
    expect(browser.names()).toEqual(["__Host-eo_player", "__Host-eo_session", "__Secure-eo_refresh"]);
  });

  it("a refresh players does not answer keeps the cookies and says players was unavailable", async () => {
    const s = await site({ boundMs: 200 });
    const browser = jar();
    browser.take(await s.post("/api/session/sign-in", ana));
    s.doubles.script("refresh", { status: 200, silent: true });
    const r = browser.take(await s.post("/api/session/refresh", {}, browser.header()));
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "service_unavailable", service: "players" });
    expect(browser.names()).toEqual(["__Host-eo_player", "__Host-eo_session", "__Secure-eo_refresh"]);
  });

  it("a sign-out ends the player session at players and removes the cookies, whatever players answered", async () => {
    const s = await site();
    const browser = jar();
    browser.take(await s.post("/api/session/sign-in", ana));
    s.doubles.script("signOut", { status: 503, body: { error: "service_unavailable" } });
    const r = browser.take(await s.post("/api/session/sign-out", {}, browser.header()));
    expect(r.status).toBe(204);
    expect(browser.names()).toEqual([]);
    const asked = s.doubles.askedOf("signOut")[0]!;
    expect(asked.path.player).toBe("p-ana");
    expect(asked.headers.authorization).toMatch(/^Bearer /);
  });

  it("a sign-out whose session cookie lapsed refreshes first, so players is still told", async () => {
    const s = await site();
    const browser = jar();
    browser.take(await s.post("/api/session/sign-in", ana));
    browser.delete("__Host-eo_session");
    await s.post("/api/session/sign-out", {}, browser.header());
    expect(s.doubles.askedOf("refresh")).toHaveLength(1);
    expect(s.doubles.askedOf("signOut")).toHaveLength(1);
  });

  it("the session read answers the player or signed out, and asks no service", async () => {
    const s = await site();
    const browser = jar();
    expect(await (await s.get("/api/session")).json()).toEqual({ signedOut: true });
    browser.take(await s.post("/api/session/sign-in", ana));
    const asked = s.doubles.asked.length;
    expect(await (await s.get("/api/session", browser.header())).json()).toEqual({ playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" });
    expect(s.doubles.asked.length).toBe(asked);
  });

  it("the site read names the brand, its languages, its currency and its modules, never cached", async () => {
    const s = await site();
    const r = await s.get("/api/site");
    expect(r.headers.get("cache-control")).toBe("no-store");
    expect(await r.json()).toEqual({ brand: "guyanabet", languages: ["fr", "en"], currency: "EUR", modules: ["core", "casino", "engagement", "support", "player-site"] });
  });

  it("on a developer's machine the cookies have plain names and no Secure, by configuration alone", async () => {
    const s = await site({ config: { local: true } });
    const r = await s.post("/api/session/sign-in", ana, "", { "x-ankka-caller": "internet" });
    const set = setCookies(r);
    expect([...set.keys()].sort()).toEqual(["eo_player", "eo_refresh", "eo_session"]);
    for (const [, c] of set) expect(c.attributes).not.toContain("Secure");
    const notLocal = await site();
    const r2 = await notLocal.post("/api/session/sign-in", ana, "", { "x-ankka-caller": "local" });
    expect([...setCookies(r2).keys()].sort()).toEqual(["__Host-eo_player", "__Host-eo_session", "__Secure-eo_refresh"]);
  });

  it("the session cookie lives by the token's exp, and five minutes for a token that carries none", () => {
    const now = Date.UTC(2026, 10, 18);
    expect(secondsLeft(fakeToken("p", 60, now), now)).toBe(60);
    expect(secondsLeft("opaque-token", now)).toBe(300);
  });
});

describe("the site server's configuration", () => {
  it("refuses a missing field by its name", () => {
    expect(() => readConfig({ CASINO_BRAND: "guyanabet" })).toThrow(MissingConfiguration);
    try {
      readConfig({ CASINO_BRAND: "guyanabet", CASINO_MODULES: "casino" });
    } catch (e) {
      expect((e as MissingConfiguration).names).toEqual(["PLAYER_SITE_LANGUAGES", "PLAYER_SITE_CURRENCY", "PLAYER_SITE_BASE", "ANKKA_SERVICES_URL", "INGEST_URL"]);
    }
  });

  it("the brand always runs core, and local is only what the configuration says", () => {
    const c = readConfig({ CASINO_BRAND: "b", CASINO_MODULES: "casino", PLAYER_SITE_LANGUAGES: "fr", PLAYER_SITE_CURRENCY: "EUR", PLAYER_SITE_BASE: "https://x.example/", ANKKA_SERVICES_URL: "http://p/", INGEST_URL: "https://i/" });
    expect(c.modules).toEqual(["core", "casino"]);
    expect(c.local).toBe(false);
    expect(c.siteBase).toBe("https://x.example");
    expect(c.servicesUrl).toBe("http://p");
  });
});
