// Research R20's targets (T133), measured on the seeded brand: the worklist (2 s p95, never over 3.5 s), a
// player's first standing (1.5 s p95), search (2 s p95), a proxied read (the owner's time plus 30 ms p95)
// and the token for a request when cached (under 5 ms p95). Each measure is printed and written to
// `test-results/it/perf-<slug>.json`, from which research.md records them.
//
// A proxied read's overhead needs the owner's own time, so the owner is called directly too, with a token
// for the same operator in the file BACKOFFICE_IT_OWNER_TOKEN (a person fetches it; no suite does). Without
// it that measure is skipped.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { TokenCache, type StateClient, type Token } from "../../src/server/session.ts";
import { installation, Operator, operators, percentile, readSeed, seeded, spread, type Seed } from "./support.ts";

const Runs = 40;
const measured: Record<string, { p50: number; p95: number; max: number; n: number; target: string }> = {};

function keep(name: string, samples: number[], target: string) {
  const m = { p50: percentile(samples, 50), p95: percentile(samples, 95), max: Math.max(...samples), n: samples.length, target };
  measured[name] = m;
  console.log(`${name}: p50 ${m.p50.toFixed(3)} ms, p95 ${m.p95.toFixed(3)} ms, max ${m.max.toFixed(3)} ms over ${m.n} (target ${target})`);
  return m;
}

describe("R20 on the seeded brand", () => {
  const inst = installation();
  let seed: Seed;
  let admin: Operator;

  beforeAll(async () => {
    seed = readSeed(inst);
    admin = await Operator.signIn(inst, operators(inst).brand_admin, "brand_admin");
    // one of each first, so a cold instance's first answers are not what is measured
    await admin.get("/api/worklist");
    await admin.get("/api/me");
  });

  afterAll(() => {
    const file = path.join(path.dirname(inst.seedFile), `perf-${inst.brand}.json`);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify({ brand: inst.brand, at: new Date().toISOString(), measured }, null, 1));
  });

  async function times(paths: string[]): Promise<number[]> {
    const out: number[] = [];
    for (const p of paths) {
      const a = await admin.get(p);
      expect(a.status, p).toBe(200);
      out.push(a.ms);
    }
    return out;
  }

  it("the worklist: 2 s p95, never more than 3.5 s", async () => {
    const m = keep("worklist", await times(Array.from({ length: Runs }, () => "/api/worklist")), "2000 ms p95, 3500 ms max");
    expect(m.p95).toBeLessThanOrEqual(2_000);
    expect(m.max).toBeLessThanOrEqual(3_500);
  });

  it("a player's first standing: 1.5 s p95", async () => {
    const players = spread(seeded(seed).players, Runs).map((p) => `/api/players/${encodeURIComponent(p.playerId!)}/standing`);
    const m = keep("standing", await times(players), "1500 ms p95");
    expect(m.p95).toBeLessThanOrEqual(1_500);
  });

  it("search: 2 s p95, for each kind of reference", async () => {
    const s = seeded(seed);
    const refs = [
      ...spread(s.players, 10).map((p) => p.playerId!),
      ...spread(s.players, 10).map((p) => p.email),
      ...spread(s.withdrawals, 10).map((p) => p.withdrawal!.requestId),
      ...spread(s.rewards, 10).map((p) => p.reward!.rewardId),
    ];
    const m = keep("search", await times(refs.map((q) => `/api/search?q=${encodeURIComponent(q)}`)), "2000 ms p95");
    expect(m.p95).toBeLessThanOrEqual(2_000);
  });

  it("a proxied read: the owner's time plus 30 ms p95", async (ctx) => {
    const file = process.env.BACKOFFICE_IT_OWNER_TOKEN;
    if (!file || !existsSync(file)) {
      console.log("a proxied read's overhead: not measured (BACKOFFICE_IT_OWNER_TOKEN names no file with the operator's token)");
      ctx.skip();
      return;
    }
    const token = readFileSync(file, "utf8").trim();
    const players = spread(seeded(seed).players, Runs).map((p) => p.playerId!);
    const proxied: number[] = [];
    const direct: number[] = [];
    const owner = `${inst.service("players")}/v1/casino/backoffice/${inst.brand}/players`;
    for (const p of players) {
      // alternated, so a slow moment of the owner lands on both
      const started = performance.now();
      const d = await fetch(`${owner}/${encodeURIComponent(p)}/profile`, { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" } });
      await d.text();
      direct.push(performance.now() - started);
      expect(d.status, "the owner answers the token").toBe(200);
      proxied.push((await admin.get(`/api/s/players/players/${encodeURIComponent(p)}/profile`)).ms);
    }
    const o = keep("owner read (direct)", direct, "—");
    const r = keep("owner read (through the backoffice)", proxied, "owner p95 + 30 ms");
    measured["proxy overhead"] = { p50: r.p50 - o.p50, p95: r.p95 - o.p95, max: r.max - o.max, n: Runs, target: "30 ms p95" };
    console.log(`proxy overhead: p95 ${(r.p95 - o.p95).toFixed(1)} ms`);
    expect(r.p95 - o.p95).toBeLessThanOrEqual(30);
  });
});

describe("R20 in the process", () => {
  it("the token for a request, when cached: under 5 ms p95", async () => {
    const token: Token = { accessToken: "x.y.z", expiresAt: Date.now() + 300_000, roles: ["brand_admin"] };
    let asked = 0;
    const state = {
      token: async () => {
        asked++;
        return token;
      },
    } as unknown as StateClient;
    const cache = new TokenCache(state, Date.now);
    await cache.token("s");
    const samples: number[] = [];
    for (let i = 0; i < 10_000; i++) {
      const started = performance.now();
      await cache.token("s");
      samples.push(performance.now() - started);
    }
    const m = keep("token, cached", samples, "5 ms p95");
    expect(asked, "the state service is asked once").toBe(1);
    expect(m.p95).toBeLessThan(5);
  });
});
