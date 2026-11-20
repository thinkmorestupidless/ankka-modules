import { describe, expect, test } from "vitest";
import { TokenCache, type Token } from "../../src/server/session.ts";
import { FakeState, harness, Origin, write } from "./support.ts";

describe("a request without a session", () => {
  test.each(["/api/me", "/api/permissions", "/api/s/players/players/p-1/profile", "/api/worklist"])(
    "%s answers 401 signed_out",
    async (path) => {
      const h = harness();
      const r = await h.ask("GET", path);
      expect(r.status).toBe(401);
      expect(await r.json()).toEqual({ error: "signed_out" });
      expect(h.calls).toHaveLength(0);
    },
  );

  test("a session the state service no longer holds is signed out too", async () => {
    const h = harness();
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-gone" });
    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "signed_out" });
    expect(h.calls).toHaveLength(0);
  });
});

describe("a write's origin", () => {
  const path = "/api/s/players/players/p-1/suspend";

  test("without X-Backoffice it is 403 bad_origin, and no owner is asked", async () => {
    const h = harness();
    const r = await h.ask("PATCH", path, { session: "s-1", headers: { Origin }, body: "{}" });
    expect(r.status).toBe(403);
    expect(await r.json()).toEqual({ error: "bad_origin" });
    expect(h.calls).toHaveLength(0);
  });

  test("from a foreign Origin it is 403 bad_origin", async () => {
    const h = harness();
    const r = await h.ask("PATCH", path, { session: "s-1", headers: { ...write, Origin: "https://elsewhere.example" }, body: "{}" });
    expect(r.status).toBe(403);
    expect(await r.json()).toEqual({ error: "bad_origin" });
  });

  test("with no Origin at all it is 403 bad_origin", async () => {
    const h = harness();
    const r = await h.ask("PATCH", path, { session: "s-1", headers: { "X-Backoffice": "1" }, body: "{}" });
    expect(r.status).toBe(403);
  });

  test("sign-out is a write and needs the same", async () => {
    const h = harness();
    const r = await h.ask("POST", "/auth/sign-out", { session: "s-1" });
    expect(r.status).toBe(403);
    expect(h.state.ended).toEqual([]);
  });

  test("from this site with the header it reaches the owner", async () => {
    const h = harness();
    const r = await h.ask("PATCH", path, { session: "s-1", headers: write, body: "{}" });
    expect(r.status).toBe(200);
    expect(h.calls).toHaveLength(1);
  });

  test("is the configured address's, whatever the forwarded headers say or leave out", async () => {
    const h = harness();
    // an installation with no base domain sends no X-Forwarded-* headers: the write still reaches the owner
    const bare = await h.ask("PATCH", path, { session: "s-1", headers: { ...write, "X-Forwarded-Proto": "", "X-Forwarded-Host": "" }, body: "{}" });
    expect(bare.status).toBe(200);
    // a request whose forwarded host matches a foreign Origin is still refused
    const forged = await h.ask("PATCH", path, { session: "s-1", headers: { ...write, Origin: "https://elsewhere.example", "X-Forwarded-Host": "elsewhere.example" }, body: "{}" });
    expect(forged.status).toBe(403);
  });

  test("a read needs neither", async () => {
    const h = harness();
    const r = await h.ask("GET", "/api/s/players/players/p-1/profile", { session: "s-1" });
    expect(r.status).toBe(200);
  });
});

describe("the token cache", () => {
  const token = (inMs: number): Token => ({ accessToken: "t", expiresAt: now + inMs, roles: [] });
  let now = 1_000_000;

  test("asks the state service once while the token has more than 30 seconds left", async () => {
    const state = new FakeState();
    state.sessions.set("s", token(120_000));
    const cache = new TokenCache(state, () => now);
    await cache.token("s");
    await cache.token("s");
    expect(state.tokenCalls).toBe(1);
  });

  test("asks again once under 30 seconds remain, so the state service refreshes it", async () => {
    const state = new FakeState();
    state.sessions.set("s", token(60_000));
    const cache = new TokenCache(state, () => now);
    await cache.token("s");
    now += 31_000;
    await cache.token("s");
    expect(state.tokenCalls).toBe(2);
  });

  test("reads it again 10 seconds after it was read, so a sign-out at another instance is in force within that", async () => {
    const state = new FakeState();
    state.sessions.set("s", token(300_000));
    const cache = new TokenCache(state, () => now);
    await cache.token("s");
    now += 9_000;
    await cache.token("s");
    expect(state.tokenCalls).toBe(1);
    now += 1_000;
    state.sessions.delete("s");
    expect(await cache.token("s")).toBeUndefined();
    expect(state.tokenCalls).toBe(2);
  });

  test("parallel requests for a token to read again ask the state service once, and the roles' change is said once", async () => {
    const state = new FakeState();
    let answer: (t: Token) => void = () => undefined;
    state.token = async () => {
      state.tokenCalls++;
      return new Promise<Token>((resolve) => (answer = resolve));
    };
    const cache = new TokenCache(state, () => now);
    const asked = [cache.token("s"), cache.token("s"), cache.token("s")];
    answer({ ...token(300_000), rolesChanged: true });
    const got = await Promise.all(asked);
    expect(state.tokenCalls).toBe(1);
    expect(got.map((t) => t?.rolesChanged)).toEqual([true, false, false]);
  });

  test("keeps nothing for a session that ended, and forgets one on sign-out", async () => {
    const state = new FakeState();
    const cache = new TokenCache(state, () => now);
    expect(await cache.token("gone")).toBeUndefined();
    state.sessions.set("s", token(120_000));
    await cache.token("s");
    cache.forget("s");
    await cache.token("s");
    expect(state.tokenCalls).toBe(3);
  });
});
