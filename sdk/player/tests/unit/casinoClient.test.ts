import { describe, expect, it } from "vitest";
import { casinoClient } from "../../src/casino/client.ts";
import { coreClient } from "../../src/core/client.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

async function setup() {
  const d = doubles();
  const t = doubleTransport(d);
  await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
  return { d, casino: casinoClient(t, () => "p-ana"), core: coreClient(t, () => "p-ana") };
}

describe("casino's client", () => {
  it("launch sends the game, the currency and fun mode, and answers the address and game session without the launch token", async () => {
    const { d, casino } = await setup();
    const r = await casino.launch("pools", { currency: "EUR" });
    expect(d.askedOf("launch")[0]!.body).toEqual({ playerId: "p-ana", gameId: "pools", currency: "EUR", demo: false });
    expect(r).toEqual({ ok: true, value: { address: expect.stringContaining("games.example/pools"), gameSession: expect.stringMatching(/^gs-/) } });
    expect(JSON.stringify(r)).not.toContain("launch-secret");
    await casino.launch("pools", { currency: "EUR", funMode: true });
    expect(d.askedOf("launch")[1]!.body).toMatchObject({ demo: true });
  });

  it("a refused launch is one refusal with its fields", async () => {
    const { d, casino } = await setup();
    d.script("launch", { status: 403, body: { error: "cooling_off_active", message: "break", coolingOffUntil: "2026-11-20T00:00:00Z" } }, 1);
    expect(await casino.launch("pools", { currency: "EUR" })).toEqual({ ok: false, refusal: { status: 403, code: "cooling_off_active", fields: { until: "2026-11-20T00:00:00Z" } } });
    d.script("launch", { status: 403, body: { error: "session_limit_reached", limitMinutes: 60, usedMinutes: 61 } }, 1);
    expect(await casino.launch("pools", { currency: "EUR" })).toMatchObject({ ok: false, refusal: { code: "session_limit_reached", fields: { limitMinutes: 60, usedMinutes: 61 } } });
  });

  it("games and game read the public catalogue with the territories", async () => {
    const { d, casino } = await setup();
    d.brand.games[0]!.prohibitedTerritories = ["GF"];
    const all = await casino.games({ search: "po", limit: 20 });
    expect(all.ok && all.value.games.map((g) => g.gameCode)).toEqual(["pools", "wheel"]);
    expect(d.askedOf("games")[0]!.query).toEqual({ search: "po", limit: "20" });
    expect(await casino.game("pools")).toMatchObject({ ok: true, value: { gameCode: "pools", prohibitedTerritories: ["GF"] } });
    expect(await casino.game("nope")).toMatchObject({ ok: false, refusal: { status: 404 } });
  });

  it("freeRounds answers the games grants with spins left are for", async () => {
    const { d, casino } = await setup();
    d.brand.freeRounds = [{ id: "fr1", gameId: "pools", spinsRemaining: 10 }, { id: "fr2", gameId: "wheel", spinsRemaining: 0 }, { id: "fr3", gameId: "pools", spinsRemaining: 2 }];
    expect(await casino.freeRounds("EUR")).toEqual({ ok: true, value: ["pools"] });
    expect(d.askedOf("freeRounds")[0]).toMatchObject({ path: { player: "p-ana" }, query: { currency: "EUR" } });
  });
});

describe("core's rows for play", () => {
  it("gameSession answers the status and the last bet; endGameSession sends no body", async () => {
    const { d, casino, core } = await setup();
    const l = await casino.launch("pools", { currency: "EUR" });
    if (!l.ok) throw new Error("refused");
    expect(await core.gameSession(l.value.gameSession)).toEqual({ ok: true, value: { status: "active", lastBetAt: null } });
    await core.endGameSession(l.value.gameSession);
    expect(d.askedOf("endGameSession")[0]!.body).toBeUndefined();
    expect(await core.gameSession(l.value.gameSession)).toMatchObject({ ok: true, value: { status: "completed" } });
  });

  it("country answers the country and the dial code, null when unknown", async () => {
    const { d, core } = await setup();
    expect(await core.country()).toEqual({ ok: true, value: { country: "GF", dialCode: "+594" } });
    d.script("country", { status: 200, body: { country: null, dialCode: null } });
    expect(await core.country()).toEqual({ ok: true, value: { country: null, dialCode: null } });
  });

  // In the spirit of the original's api/rgApi.test.ts (normaliseRgStatus): a status field read off
  // the wire never yields a figure the rule cannot use. This slice reads the reality check's interval
  // and the break, not the usage figures those cases are about.
  describe("protection", () => {
    it("reads the reality check interval, the break and its end", async () => {
      const { d, core } = await setup();
      d.brand.protection = { realityCheckIntervalMinutes: 30, isCoolingOff: true, coolingOffUntil: "2026-11-20T00:00:00Z" };
      expect(await core.protection("EUR")).toEqual({ ok: true, value: { realityCheckIntervalMinutes: 30, isCoolingOff: true, coolingOffUntil: "2026-11-20T00:00:00Z" } });
      expect(d.askedOf("protection")[0]).toMatchObject({ path: { player: "p-ana" }, query: { currency: "EUR" } });
    });
    it("an interval missing, zero, negative, fractional or not a number is none", async () => {
      const { d, core } = await setup();
      for (const v of [null, 0, -5, 2.5, "30", undefined]) {
        d.script("protection", { status: 200, body: { realityCheckIntervalMinutes: v, isCoolingOff: false } }, 1);
        expect(await core.protection("EUR"), String(v)).toMatchObject({ ok: true, value: { realityCheckIntervalMinutes: null } });
      }
    });
    it("acknowledges the reality check against the game session", async () => {
      const { d, core } = await setup();
      await core.acknowledgeRealityCheck("gs-1");
      expect(d.askedOf("acknowledgeRealityCheck")[0]).toMatchObject({ path: { player: "p-ana" }, body: { sessionId: "gs-1" } });
    });
  });
});
