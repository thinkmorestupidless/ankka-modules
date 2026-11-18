import { describe, expect, it } from "vitest";
import type { Route } from "../../src/route.ts";
import { handClock } from "../../src/testing/clock.ts";
import { channel, doubles, doubleTransport, serveDoubles } from "../../src/testing/doubles.ts";

const balance: Route = { module: "core", id: "balance", service: "wallet", method: "GET", path: "/v1/casino/players/{brand}/wallets/{currency}/balance", audience: "player" };
const games: Route = { module: "casino", id: "games", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games", audience: "public" };
const push: Route = { module: "engagement", id: "push", service: "engagement", method: "GET", path: "/v1/stream", audience: "player", stream: true };
const unread: Route = { module: "core", id: "unread", service: "players", method: "GET", path: "/v1/casino/players/{brand}/{player}/messages?status=unread&page=1&pageSize=1", audience: "player" };

describe("the service doubles", () => {
  it("answer a player route only with somebody signed in, and by default as the brand would", async () => {
    const d = doubles();
    const t = doubleTransport(d);
    expect(await t.call(balance, { path: { currency: "EUR" } })).toEqual({ ok: false, refusal: { status: 401, code: "signed_out", fields: {} } });
    const signedIn = await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    expect(signedIn).toEqual({ ok: true, player: { playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" } });
    const r = await t.call(balance, { path: { currency: "EUR" } });
    expect(r.ok && (r.body as { realBalance: string }).realBalance).toBe("50.00");
    expect(d.askedOf("balance")[0]?.path).toEqual({ brand: "guyanabet", currency: "EUR" });
  });

  it("refuse, answer late, or stay silent as scripted", async () => {
    const clock = handClock();
    const d = doubles({ clock });
    const t = doubleTransport(d);
    d.script("games", { status: 503, body: { error: "service_unavailable" } }, 1);
    expect((await t.call(games)).ok).toBe(false);
    expect((await t.call(games)).ok).toBe(true);
    d.script("games", { status: 200, body: { games: [] }, afterMs: 2000 }, 1);
    let late: unknown;
    void t.call(games).then((r) => (late = r));
    await clock.advance(1999);
    expect(late).toBeUndefined();
    await clock.advance(1);
    expect(late).toEqual({ ok: true, status: 200, body: { games: [] } });
    d.script("games", { status: 200, silent: true }, 1);
    let silent: unknown;
    void t.call(games).then((r) => (silent = r));
    await clock.advance(15_000);
    expect(silent).toMatchObject({ ok: false, refusal: { code: "service_unavailable" } });
  });

  it("stream events a test pushes", async () => {
    const d = doubles();
    const t = doubleTransport(d);
    await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    const c = channel();
    d.script("push", { status: 200, stream: c });
    const s = await t.stream(push);
    if (!s.ok) throw new Error("no stream");
    c.push("ready", { subjectId: "p-ana" });
    c.push("signal", { kind: "inbox.changed" });
    c.end();
    const seen: string[] = [];
    for await (const e of s.events) seen.push(e.event);
    expect(seen).toEqual(["ready", "signal"]);
  });

  it("answer over HTTP at a calling address, keeping the headers asked with", async () => {
    const d = doubles();
    const served = await serveDoubles(d, [balance, games, unread, push]);
    try {
      const r = await fetch(`${served.url}/wallet/v1/casino/players/guyanabet/wallets/EUR/balance`, { headers: { authorization: "Bearer x" } });
      expect(r.status).toBe(200);
      expect(((await r.json()) as { currency: string }).currency).toBe("EUR");
      expect(d.askedOf("balance")[0]?.headers.authorization).toBe("Bearer x");
      const u = await fetch(`${served.url}/players/v1/casino/players/guyanabet/p-ana/messages?status=unread&page=1&pageSize=1`);
      expect(((await u.json()) as { unreadCount: number }).unreadCount).toBe(0);
      expect(d.askedOf("unread")[0]?.query).toEqual({ status: "unread", page: "1", pageSize: "1" });
      const s = await fetch(`${served.url}/engagement/v1/stream`);
      expect(s.headers.get("content-type")).toBe("text/event-stream");
      expect(await s.text()).toContain("event: ready");
      expect((await fetch(`${served.url}/nowhere`)).status).toBe(404);
    } finally {
      await served.close();
    }
  });
});
