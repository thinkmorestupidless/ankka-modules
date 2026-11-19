import { describe, expect, it } from "vitest";
import type { Route } from "../../src/route.ts";
import { pageTransport, passThroughUrl } from "../../src/transport.ts";

const balance: Route = { module: "core", id: "balance", service: "wallet", method: "GET", path: "/v1/casino/players/{brand}/wallets/{currency}/balance", audience: "player" };
const payment: Route = { module: "core", id: "payment", service: "ingest", method: "GET", path: "/v1/payments/{paymentId}", audience: "ticket" };
const change: Route = { module: "core", id: "changeProfile", service: "players", method: "PATCH", path: "/v1/casino/players/{brand}/{player}", audience: "player" };

interface Seen {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: string;
}

/** A site server whose answers are scripted by path. */
function site(script: (seen: Seen, count: number) => Response | Promise<Response>) {
  const seen: Seen[] = [];
  const counts = new Map<string, number>();
  const fetchImpl = async (url: string, init?: RequestInit) => {
    const s: Seen = { method: init?.method ?? "GET", url, headers: (init?.headers ?? {}) as Record<string, string>, ...(init?.body ? { body: String(init.body) } : {}) };
    seen.push(s);
    const key = `${s.method} ${url.split("?")[0]}`;
    const n = (counts.get(key) ?? 0) + 1;
    counts.set(key, n);
    return script(s, n);
  };
  return { seen, transport: pageTransport(fetchImpl), count: (key: string) => counts.get(key) ?? 0 };
}

const answer = (status: number, body?: unknown) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("the page's transport (FR-001, FR-005)", () => {
  it("calls the site server's pass-through with the row's own method, the path's values in the query", async () => {
    expect(passThroughUrl(balance, { path: { currency: "EUR" } })).toBe("/api/c/core/balance?currency=EUR");
    const { seen, transport } = site(() => answer(200, { updated: true }));
    const r = await transport.call(change, { path: { player: "p-1" }, body: { firstName: "Ana" } });
    expect(r).toEqual({ ok: true, status: 200, body: { updated: true } });
    expect(seen[0]).toMatchObject({ method: "PATCH", url: "/api/c/core/changeProfile?player=p-1", body: '{"firstName":"Ana"}' });
    expect(seen[0]!.headers["content-type"]).toBe("application/json");
  });

  it("three calls answered signed out at once refresh once, and each is made once more", async () => {
    let refreshed = false;
    const { transport, count } = site(async (s) => {
      if (s.url === "/api/session/refresh") {
        await new Promise((r) => setTimeout(r, 5));
        refreshed = true;
        return answer(204);
      }
      return refreshed ? answer(200, { ok: 1 }) : answer(401, { error: "signed_out" });
    });
    const answers = await Promise.all([transport.call(balance), transport.call(balance), transport.call(balance)]);
    expect(answers.every((a) => a.ok)).toBe(true);
    expect(count("POST /api/session/refresh")).toBe(1);
    expect(count("GET /api/c/core/balance")).toBe(6);
  });

  it("a refused refresh reads the session again, and goes on when another tab has refreshed", async () => {
    let tabRefreshed = false;
    const { transport, count } = site((s) => {
      if (s.url === "/api/session/refresh") {
        tabRefreshed = true;
        return answer(401, { error: "refresh_expired" });
      }
      if (s.url === "/api/session") return answer(200, { playerId: "p-1", brand: "guyanabet", currency: "EUR", country: "GF" });
      return tabRefreshed ? answer(200, {}) : answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    expect((await transport.call(balance)).ok).toBe(true);
    expect(count("GET /api/session")).toBe(1);
    expect(signedOut).toEqual([]);
  });

  it("a refused refresh with no session signs the player out, once", async () => {
    const { transport } = site((s) => {
      if (s.url === "/api/session/refresh") return answer(401, { error: "refresh_expired" });
      if (s.url === "/api/session") return answer(200, { signedOut: true });
      return answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    const r = await transport.call(balance);
    expect(r).toEqual({ ok: false, refusal: { status: 401, code: "signed_out", fields: {} } });
    expect(signedOut).toEqual(["refresh_expired"]);
  });

  it("a call still signed out after a refresh that worked signs the player out (review)", async () => {
    const { transport, count } = site((s) => {
      if (s.url === "/api/session/refresh") return new Response(null, { status: 204 });
      return answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    const r = await transport.call(balance);
    expect(r).toEqual({ ok: false, refusal: { status: 401, code: "signed_out", fields: {} } });
    expect(signedOut).toEqual(["refresh_expired"]);
    expect(count("POST /api/session/refresh")).toBe(1);
  });

  it("a refresh refused because another tab won the race goes on with that tab's sign-in (review)", async () => {
    // The losing tab's refresh is refused, but the cookies now hold the winner's sign-in.
    const { transport, count } = site((s, n) => {
      if (s.url === "/api/session/refresh") return answer(401, { error: "refresh_expired" });
      if (s.url === "/api/session") return answer(200, { playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" });
      return n === 1 ? answer(401, { error: "signed_out" }) : answer(200, { real: "1.00" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    const r = await transport.call(balance);
    expect(r.ok).toBe(true);
    expect(signedOut).toEqual([]);
    expect(count("POST /api/session/sign-out")).toBe(0);
  });

  it("a refused refresh whose call is still refused signs out, which removes the cookies, and tells the page", async () => {
    const { transport, count } = site((s) => {
      if (s.url === "/api/session/refresh") return answer(401, { error: "refresh_expired" });
      if (s.url === "/api/session") return answer(200, { playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" });
      if (s.url === "/api/session/sign-out") return new Response(null, { status: 204 });
      return answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    const r = await transport.call(balance);
    expect(r).toEqual({ ok: false, refusal: { status: 401, code: "signed_out", fields: {} } });
    expect(count("POST /api/session/sign-out")).toBe(1);
    expect(signedOut).toEqual(["refresh_expired"]);
  });

  it("calls still refused after the refresh share one sign-out, and the page is told once (review)", async () => {
    const { transport, count } = site((s) => {
      if (s.url === "/api/session/refresh") return answer(401, { error: "refresh_expired" });
      if (s.url === "/api/session") return answer(200, { playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" });
      if (s.url === "/api/session/sign-out") return new Response(null, { status: 204 });
      return answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    await Promise.all([transport.call(balance), transport.call(balance), transport.call(balance)]);
    expect(count("POST /api/session/sign-out")).toBe(1);
    expect(signedOut).toEqual(["refresh_expired"]);
  });

  it("a refresh the brand did not answer keeps the player signed in and refuses the call as unavailable", async () => {
    const { transport } = site((s) => {
      if (s.url === "/api/session/refresh") return answer(503, { error: "service_unavailable", service: "players" });
      return answer(401, { error: "signed_out" });
    });
    const signedOut: string[] = [];
    transport.onSignedOut((r) => signedOut.push(r));
    const r = await transport.call(balance);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.refusal).toMatchObject({ code: "service_unavailable", service: "players" });
    expect(signedOut).toEqual([]);
  });

  it("a cashier's 401 refreshes nothing", async () => {
    const { transport, count } = site(() => answer(401, { error: "UNAUTHENTICATED" }));
    const r = await transport.call(payment, { path: { paymentId: "pay-1" }, headers: { cashierTicket: "t" } });
    expect(r.ok).toBe(false);
    expect(count("POST /api/session/refresh")).toBe(0);
  });

  it("a site server that cannot be reached is unavailable", async () => {
    const transport = pageTransport(() => Promise.reject(new TypeError("network")));
    const r = await transport.call(balance);
    expect(r).toEqual({ ok: false, refusal: { status: 503, code: "service_unavailable", fields: {}, service: "site" } });
  });

  it("passes a service's words on only for a call that asks", async () => {
    const { transport } = site(() => answer(400, { error: "PAYMENT_REJECTED", message: "Carte refusée" }));
    const quiet = await transport.call(payment);
    const told = await transport.call(payment, { words: true });
    expect(!quiet.ok && quiet.refusal.words).toBeUndefined();
    expect(!told.ok && told.refusal.words).toBe("Carte refusée");
  });
});
