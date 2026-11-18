// SC-003: no step of any behaviour opens a cashier session or gives a game's address after the
// brand refused, and no action a step does not allow sends anything.
import { describe, expect, it } from "vitest";
import { playBehaviour, type PlayBehaviour } from "../../src/casino/play.ts";
import type { DepositBehaviour } from "../../src/core/deposit.ts";
import { playerSite, type PlayerSite } from "../../src/site.ts";
import { handClock, type HandClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport, type Doubles, type FakeTerminal } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const terminals: FakeTerminal[] = [
  { terminalEntityId: "t-pix", name: "PIX", scheme: "PIX", gateway: "stub" },
  { terminalEntityId: "t-skrill", name: "Skrill", scheme: "SKRILL", gateway: "stub" },
  { terminalEntityId: "t-crypto", name: "Crypto", scheme: "CRYPTO", gateway: "stub", supportedCurrencies: ["BTC", "USDT"] },
];

interface Setup {
  clock: HandClock;
  d: Doubles;
  site: PlayerSite;
}
async function setup(signIn = true): Promise<Setup> {
  const clock = handClock();
  const d = doubles({ clock, brand: { terminals } });
  d.brand.players[0]!.profile = { firstName: "", lastName: "" };
  const site = playerSite({ transport: doubleTransport(d), clock, idleMinutes: 24 * 60 });
  await site.ready;
  if (signIn) {
    site.session.actions.signIn(ana);
    await clock.settle();
  }
  return { clock, d, site };
}
const drain = async (s: Setup) => {
  await s.clock.settle();
  await s.clock.advance(16_000);
};

// ---- the deposit ----

type DepositAct = [name: string, run: (dep: DepositBehaviour) => unknown];
const depositActs: DepositAct[] = [
  ["confirm", (x) => x.actions.confirm(5000n)],
  ["retry", (x) => x.actions.retry()],
  ["choose", (x) => x.actions.choose("t-pix")],
  ["chooseCoin", (x) => x.actions.chooseCoin("USDT")],
  ["giveDetails", (x) => x.actions.giveDetails({ firstName: "Ana", lastName: "Silva" })],
  ["back", (x) => x.actions.back()],
  ["close", (x) => x.actions.close()],
];

/** A way to each step of the deposit, from a fresh signed-in site. */
const depositRecipes: Record<string, (s: Setup, dep: DepositBehaviour) => Promise<void>> = {
  amount: async () => {},
  starting: async (s, dep) => {
    s.d.script("startDeposit", { status: 200, silent: true });
    dep.actions.confirm(5000n);
    await s.clock.settle();
  },
  refused: async (s, dep) => {
    s.d.script("startDeposit", { status: 403, body: { error: "excluded", service: "responsible-gambling" } });
    dep.actions.confirm(5000n);
    await s.clock.settle();
  },
  choosing_terminal: async (s, dep) => {
    dep.actions.confirm(5000n);
    await s.clock.settle();
  },
  choosing_coin: async (s, dep) => {
    await depositRecipes.choosing_terminal!(s, dep);
    dep.actions.choose("t-crypto");
    await s.clock.settle();
  },
  details: async (s, dep) => {
    await depositRecipes.choosing_terminal!(s, dep);
    dep.actions.choose("t-skrill");
    await s.clock.settle();
  },
  submitting: async (s, dep) => {
    await depositRecipes.choosing_terminal!(s, dep);
    s.d.script("submitCashier", { status: 200, silent: true });
    dep.actions.choose("t-pix");
    await s.clock.settle();
  },
  waiting_at_provider: async (s, dep) => {
    await depositRecipes.choosing_terminal!(s, dep);
    dep.actions.choose("t-pix");
    await s.clock.advance(1500);
  },
  waiting_on_crypto: async (s, dep) => {
    await depositRecipes.choosing_coin!(s, dep);
    dep.actions.chooseCoin("USDT");
    await s.clock.advance(1500);
  },
  waiting: async (s, dep) => {
    await depositRecipes.choosing_terminal!(s, dep);
    dep.actions.choose("t-pix");
    await s.clock.settle();
    for (const p of s.d.brand.payments.values()) Object.assign(p, { status: "AWAITING_PAYMENT" });
    await s.clock.advance(1500);
  },
  succeeded: async (s, dep) => {
    await depositRecipes.waiting_at_provider!(s, dep);
    for (const p of s.d.brand.payments.values()) p.status = "COMPLETED";
    await s.clock.advance(3000);
  },
  failed: async (s) => {
    s.d.brand.terminals = [];
    s.site.currentDeposit!.actions.confirm(5000n);
    await s.clock.settle();
  },
  closed: async (_s, dep) => void dep.actions.close(),
};

describe("the deposit: an action its step does not allow sends nothing", () => {
  for (const [kind, reach] of Object.entries(depositRecipes)) {
    it(kind, async () => {
      for (const [name, run] of depositActs) {
        const s = await setup();
        const dep = s.site.deposit()!;
        await reach(s, dep);
        const at = dep.step();
        expect(at.kind, `the recipe for ${kind}`).toBe(kind);
        if ((at.allowed as readonly string[]).includes(name)) continue;
        const before = s.d.asked.length;
        expect(run(dep), name).toEqual({ ok: false, notAllowedAt: kind });
        await s.clock.settle();
        expect(s.d.asked.length, `${name} at ${kind} sent something`).toBe(before);
        expect(dep.step(), `${name} at ${kind} moved the step`).toBe(at);
      }
    });
  }
});

/**
 * From a refused step, every path of allowed actions, the brand refusing each start again: none
 * reaches a step holding the cashier session's terminals, and nothing submits a cashier session.
 */
describe("the deposit: no way round a refusal", () => {
  for (const [refusal, reply] of [
    ["a refusal", { status: 403, body: { error: "excluded", service: "responsible-gambling" } }],
    ["no answer", { status: 200, silent: true }],
  ] as const) {
    it(`after ${refusal}`, async () => {
      const seen = new Set<string>();
      let frontier: string[][] = [[]];
      for (let depth = 0; depth < 4 && frontier.length; depth++) {
        const next: string[][] = [];
        for (const path of frontier) {
          const s = await setup();
          s.d.script("startDeposit", reply);
          const dep = s.site.deposit()!;
          dep.actions.confirm(5000n);
          await drain(s);
          expect(dep.step().kind).toBe("refused");
          for (const name of path) {
            depositActs.find(([n]) => n === name)![1](dep);
            await drain(s);
          }
          const at = dep.step();
          expect(["refused", "amount", "starting", "closed"], `${path.join(" → ")} reached ${at.kind}`).toContain(at.kind);
          expect(s.d.askedOf("submitCashier"), path.join(" → ")).toEqual([]);
          const key = at.kind + ":" + path.at(-1);
          if (seen.has(key)) continue;
          seen.add(key);
          for (const name of at.allowed) next.push([...path, name]);
        }
        frontier = next;
      }
      expect([...seen].some((k) => k.startsWith("amount"))).toBe(true);
    });
  }
});

// ---- play ----

type PlayAct = [name: string, run: (p: PlayBehaviour) => unknown];
const playActs: PlayAct[] = [
  ["launch", (p) => p.actions.launch()],
  ["dismiss", (p) => p.actions.dismiss()],
  ["close", (p) => p.actions.close()],
  ["keepOn", (p) => p.actions.keepOn()],
  ["stop", (p) => p.actions.stop()],
];
const playOf = (s: Setup) =>
  playBehaviour({
    game: "pools",
    core: s.site.core,
    casino: s.site.casino,
    clock: s.clock,
    player: () => s.site.player,
    funds: () => s.site.funds.current(),
    needsDeposit: () => {},
    recentBet: () => {},
    keptOn: () => {},
  });
const playRecipes: Record<string, { signIn?: boolean; reach: (s: Setup, p: PlayBehaviour) => Promise<void> }> = {
  idle: { reach: async () => {} },
  needs_registration: { signIn: false, reach: async (s, p) => void p.actions.launch() },
  needs_sign_in: { signIn: false, reach: async (s, p) => void p.actions.launch({ funMode: true }) },
  needs_deposit: {
    reach: async (s, p) => {
      s.d.brand.balance = { real: "0.00", bonus: "0.00" };
      await s.site.funds.read();
      p.actions.launch();
    },
  },
  launching: {
    reach: async (s, p) => {
      s.d.script("launch", { status: 200, silent: true });
      p.actions.launch();
      await s.clock.settle();
    },
  },
  playing: {
    reach: async (s, p) => {
      p.actions.launch();
      await s.clock.settle();
    },
  },
  reality_check: {
    reach: async (s, p) => {
      await playRecipes.playing!.reach(s, p);
      p.realityCheck(1800);
    },
  },
  refused: {
    reach: async (s, p) => {
      s.d.script("launch", { status: 403, body: { error: "PLAY_BLOCKED_KYC", kycStatus: "PENDING" } });
      p.actions.launch();
      await s.clock.settle();
    },
  },
  ended: {
    reach: async (s, p) => {
      await playRecipes.playing!.reach(s, p);
      p.actions.close();
      await s.clock.settle();
    },
  },
};

describe("play: an action its step does not allow sends nothing", () => {
  for (const [kind, recipe] of Object.entries(playRecipes)) {
    it(kind, async () => {
      for (const [name, run] of playActs) {
        const s = await setup(recipe.signIn !== false);
        const p = playOf(s);
        await recipe.reach(s, p);
        const at = p.step();
        expect(at.kind, `the recipe for ${kind}`).toBe(kind);
        if ((at.allowed as readonly string[]).includes(name)) continue;
        const before = s.d.asked.length;
        expect(run(p), name).toEqual({ ok: false, notAllowedAt: kind });
        await s.clock.settle();
        expect(s.d.asked.length, `${name} at ${kind} sent something`).toBe(before);
        expect(p.step(), `${name} at ${kind} moved the step`).toBe(at);
      }
    });
  }

  it("with two games open, each game's not-allowed actions send nothing", async () => {
    const s = await setup();
    const games = s.site.games!;
    games.actions.pick("pools");
    games.actions.pick("wheel");
    await s.clock.settle();
    expect(games.step().open.map((g) => g.step().kind)).toEqual(["playing", "playing"]);
    for (const g of games.step().open)
      for (const [name, run] of playActs) {
        if ((g.step().allowed as readonly string[]).includes(name)) continue;
        const before = s.d.asked.length;
        expect(run(g)).toEqual({ ok: false, notAllowedAt: "playing" });
        await s.clock.settle();
        expect(s.d.asked.length, `${name} at ${g.game}`).toBe(before);
      }
  });
});

describe("play: no way round a refusal", () => {
  it("from refused, every path of allowed actions with the brand refusing again reaches no step with a game's address", async () => {
    const seen = new Set<string>();
    let frontier: string[][] = [[]];
    for (let depth = 0; depth < 5 && frontier.length; depth++) {
      const next: string[][] = [];
      for (const path of frontier) {
        const s = await setup();
        s.d.script("launch", { status: 403, body: { error: "PLAY_BLOCKED_KYC", kycStatus: "PENDING" } });
        const p = playOf(s);
        p.actions.launch();
        await drain(s);
        const steps = [p.step()];
        p.subscribe((x) => steps.push(x));
        for (const name of path) {
          playActs.find(([n]) => n === name)![1](p);
          await drain(s);
        }
        expect(JSON.stringify(steps), path.join(" → ")).not.toContain("games.example");
        expect(steps.some((x) => "address" in x)).toBe(false);
        const at = p.step();
        const key = `${at.kind}:${path.at(-1)}`;
        if (seen.has(key)) continue;
        seen.add(key);
        for (const name of at.allowed) next.push([...path, name]);
      }
      frontier = next;
    }
    expect(seen.size).toBeGreaterThan(1);
  });

  it("a refused game picked again is shown as it stands and launches nothing", async () => {
    const s = await setup();
    s.d.script("launch", { status: 403, body: { error: "PLAY_BLOCKED_KYC", kycStatus: "PENDING" } });
    const games = s.site.games!;
    games.actions.pick("pools");
    await s.clock.settle();
    games.actions.pick("pools");
    await s.clock.settle();
    expect(s.d.askedOf("launch")).toHaveLength(1);
    expect(games.step().shown?.step().kind).toBe("refused");
  });
});
