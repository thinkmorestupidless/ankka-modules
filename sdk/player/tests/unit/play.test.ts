import { describe, expect, it } from "vitest";
import { amount } from "../../src/amount.ts";
import type { PlayBehaviour, PlayStep } from "../../src/casino/play.ts";
import { playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport, type FakeBrand } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const MIN = 60_000;

async function setup(options: { brand?: Partial<FakeBrand>; signIn?: boolean; idleMinutes?: number } = {}) {
  const clock = handClock();
  const d = doubles({ clock, brand: options.brand ?? {} });
  // a long idle limit, so the idle sign-out does not close the games of a long test
  const site = playerSite({ transport: doubleTransport(d), clock, idleMinutes: options.idleMinutes ?? 24 * 60 });
  await site.ready;
  if (options.signIn !== false) {
    site.session.actions.signIn(ana);
    await clock.settle();
  }
  const games = site.games!;
  const play = (game: string) => games.step().open.find((g) => g.game === game)!;
  const pick = async (game: string, o: { funMode?: boolean } = {}) => {
    games.actions.pick(game, o);
    await clock.settle();
    return play(game);
  };
  return { clock, d, site, games, play, pick };
}
const kind = (g: PlayBehaviour | undefined) => g?.step().kind;
const zero = { real: "0.00", bonus: "0.00" };

// Ported from the original: hooks/useGuardedPlay.test.ts (guardPlay's routing) and the gate cases
// of components/game/GameLaunchModal.test.tsx, as what the play behaviour does with each.
describe("the order: visitor, funds, launch (FR-028)", () => {
  it("asks a visitor to register and launches nothing", async () => {
    const s = await setup({ signIn: false });
    expect(kind(await s.pick("pools"))).toBe("needs_registration");
    expect(s.d.askedOf("launch")).toEqual([]);
    expect(s.site.currentDeposit).toBeUndefined();
  });
  it("opens the deposit when the real and restricted balances are 0", async () => {
    const s = await setup({ brand: { balance: zero } });
    expect(kind(await s.pick("pools"))).toBe("needs_deposit");
    expect(s.site.currentDeposit?.step().kind).toBe("amount");
    expect(s.d.askedOf("launch")).toEqual([]);
  });
  it("launches when the real balance is 0 but the restricted balance is not", async () => {
    const s = await setup({ brand: { balance: { real: "0.00", bonus: "0.50" } } });
    expect(kind(await s.pick("pools"))).toBe("playing");
  });
  it("launches when the balances are 0 but the player holds free rounds for this game, and not for another", async () => {
    const s = await setup({ brand: { balance: zero, freeRounds: [{ id: "f", gameId: "pools", spinsRemaining: 5 }] } });
    expect(kind(await s.pick("pools"))).toBe("playing");
    expect(kind(await s.pick("wheel"))).toBe("needs_deposit");
  });
  it("launches when the balance is positive, and when it is not read yet", async () => {
    const s = await setup();
    expect(kind(await s.pick("pools"))).toBe("playing");
    const t = await setup();
    t.site.funds.player(null); // nothing read
    t.site.funds.player("EUR");
    t.games.actions.pick("pools");
    expect(kind(t.play("pools"))).toBe("launching");
  });
  it("fun mode needs the sign-in and no funds", async () => {
    const visitor = await setup({ signIn: false });
    expect(kind(await visitor.pick("pools", { funMode: true }))).toBe("needs_sign_in");
    const s = await setup({ brand: { balance: zero } });
    expect(kind(await s.pick("pools", { funMode: true }))).toBe("playing");
    expect(s.d.askedOf("launch")[0]!.body).toMatchObject({ gameId: "pools", currency: "EUR", demo: true });
  });
});

describe("the launch (FR-029 to FR-031)", () => {
  it("is asked once and stands at playing with the address and the game session", async () => {
    const s = await setup();
    const g = await s.pick("pools");
    expect(s.d.askedOf("launch")).toHaveLength(1);
    expect(g.step()).toMatchObject({ kind: "playing", address: expect.stringContaining("pools"), gameSession: expect.stringMatching(/^gs-/), hidden: false });
  });
  it("a refusal stands at refused with its fields and no address; dismiss leaves the open games", async () => {
    const s = await setup();
    s.d.script("launch", { status: 403, body: { error: "PLAY_BLOCKED_KYC", message: "verify", kycStatus: "PENDING" } });
    const g = await s.pick("pools");
    expect(g.step()).toEqual({ kind: "refused", allowed: ["dismiss"], game: "pools", refusal: { status: 403, code: "PLAY_BLOCKED_KYC", fields: { kycStatus: "PENDING" } } });
    expect(JSON.stringify(g.step())).not.toContain("games.example");
    g.actions.dismiss();
    expect(s.games.step().open).toEqual([]);
  });
  it("a game prohibited or restricted in the player's country is refused and its session ended", async () => {
    for (const field of ["prohibitedTerritories", "restrictedTerritories"] as const) {
      const s = await setup();
      s.d.brand.games[0]![field] = ["gf"];
      const g = await s.pick("pools");
      expect(g.step()).toMatchObject({ kind: "refused", refusal: { code: "blocked_in_territory" } });
      expect(s.d.askedOf("endGameSession")).toHaveLength(1);
    }
  });
  it("an unknown country, or a catalogue that cannot be read, blocks nothing", async () => {
    const s = await setup();
    (s.d.brand.players[0] as { country: string }).country = "";
    s.site.session.actions.signOut();
    await s.clock.settle();
    s.site.session.actions.signIn(ana);
    await s.clock.settle();
    s.d.brand.games[0]!.prohibitedTerritories = ["GF"];
    expect(kind(await s.pick("pools"))).toBe("playing");
    const t = await setup();
    t.d.script("game", { status: 503, body: { error: "service_unavailable" } });
    expect(kind(await t.pick("pools"))).toBe("playing");
  });
});

describe("while playing", () => {
  it("every 5 minutes reads the game session; a bet within 6 minutes refreshes the sign-in and is not idle", async () => {
    const s = await setup({ idleMinutes: 30 });
    const g = await s.pick("pools");
    const sessionId = (g.step() as { gameSession: string }).gameSession;
    await s.clock.advance(24 * MIN);
    s.d.brand.gameSessions.get(sessionId)!.lastBetAt = new Date(s.clock.now()).toISOString();
    await s.clock.advance(6 * MIN);
    expect(s.d.askedOf("gameSession")).toHaveLength(6);
    // the idle warning would have fallen at 29:30 without the bet
    expect(s.site.session.step().kind).toBe("signed_in");
  });
  it("a game session the brand ended stands at ended and asks no end", async () => {
    const s = await setup();
    const g = await s.pick("pools");
    s.d.brand.gameSessions.get((g.step() as { gameSession: string }).gameSession)!.status = "ended";
    await s.clock.advance(5 * MIN);
    expect(g.step().kind).toBe("ended");
    expect(s.d.askedOf("endGameSession")).toEqual([]);
    expect(s.games.step().open).toEqual([]);
  });
  it("close, the page leaving and sign-out each end the game session, sent to outlive the page", async () => {
    const closing = await setup();
    (await closing.pick("pools")).actions.close();
    expect(closing.d.askedOf("endGameSession")).toHaveLength(1);
    const leaving = await setup();
    await leaving.pick("pools");
    leaving.site.stop();
    expect(leaving.d.askedOf("endGameSession")).toHaveLength(1);
    const out = await setup();
    await out.pick("pools");
    await out.pick("wheel");
    out.site.session.actions.signOut();
    await out.clock.settle();
    expect(out.d.askedOf("endGameSession")).toHaveLength(2);
    expect(out.games.step().open).toEqual([]);
  });
});

describe("the reality check: one count of the time any game is shown (FR-033, FR-059)", () => {
  const withCheck = (minutes: number | null) => ({ brand: { protection: { realityCheckIntervalMinutes: minutes, isCoolingOff: false, coolingOffUntil: null } } });
  it("falls due after the interval shown, with the time played; keepOn acknowledges and schedules the next", async () => {
    const s = await setup(withCheck(30));
    const g = await s.pick("pools");
    await s.clock.advance(30 * MIN - 1000);
    expect(kind(g)).toBe("playing");
    await s.clock.advance(1000);
    expect(g.step()).toMatchObject({ kind: "reality_check", played: 1800 });
    g.actions.keepOn();
    await s.clock.settle();
    expect(s.d.askedOf("acknowledgeRealityCheck")[0]!.body).toEqual({ sessionId: (g.step() as { gameSession: string }).gameSession });
    expect(kind(g)).toBe("playing");
    await s.clock.advance(30 * MIN);
    expect(g.step()).toMatchObject({ kind: "reality_check", played: 3600 });
    g.actions.stop();
    expect(s.d.askedOf("endGameSession")).toHaveLength(1);
  });
  it("time hidden is not counted, and hiding starts nothing again", async () => {
    const s = await setup(withCheck(30));
    const g = await s.pick("pools");
    await s.clock.advance(20 * MIN);
    s.games.actions.hideAll();
    await s.clock.advance(40 * MIN);
    expect(kind(g)).toBe("playing");
    s.games.actions.show("pools");
    await s.clock.advance(10 * MIN);
    expect(g.step()).toMatchObject({ kind: "reality_check", played: 1800 });
  });
  it("a hidden page counts as no game shown", async () => {
    const s = await setup(withCheck(30));
    const g = await s.pick("pools");
    await s.clock.advance(29 * MIN);
    s.site.visible(false);
    await s.clock.advance(60 * MIN);
    expect(kind(g)).toBe("playing");
    s.site.visible(true);
    await s.clock.advance(1 * MIN);
    expect(kind(g)).toBe("reality_check");
  });
  it("counts across the open games and falls due on the game shown", async () => {
    const s = await setup(withCheck(30));
    const pools = await s.pick("pools");
    await s.clock.advance(20 * MIN);
    const wheel = await s.pick("wheel");
    await s.clock.advance(10 * MIN);
    expect(kind(wheel)).toBe("reality_check");
    expect(kind(pools)).toBe("playing");
  });
  it("no interval, or a status that cannot be read, fires none", async () => {
    for (const s of [await setup(withCheck(null)), await setup(withCheck(30))]) {
      if (s.d.brand.protection.realityCheckIntervalMinutes) s.d.script("protection", { status: 503, body: { error: "service_unavailable" } });
      const g = await s.pick("pools");
      await s.clock.advance(120 * MIN);
      expect(kind(g)).toBe("playing");
    }
  });
});

describe("site.games: the open games (FR-059)", () => {
  it("a second game launches under its own session; the first is hidden with its session open", async () => {
    const s = await setup();
    const pools = await s.pick("pools");
    const wheel = await s.pick("wheel");
    expect((pools.step() as { gameSession: string }).gameSession).not.toBe((wheel.step() as { gameSession: string }).gameSession);
    expect(s.games.step().shown).toBe(wheel);
    expect(pools.step()).toMatchObject({ kind: "playing", hidden: true });
    expect(s.d.askedOf("endGameSession")).toEqual([]);
  });
  it("picking an open game shows it and launches nothing", async () => {
    const s = await setup();
    await s.pick("pools");
    await s.pick("wheel");
    await s.pick("pools");
    expect(s.games.step().shown?.game).toBe("pools");
    expect(s.d.askedOf("launch")).toHaveLength(2);
  });
  it("a pick after a deposit launches the game that stood at needs_deposit", async () => {
    const s = await setup({ brand: { balance: zero } });
    expect(kind(await s.pick("pools"))).toBe("needs_deposit");
    s.d.brand.balance = { real: "50.00", bonus: "0.00" };
    await s.site.funds.read();
    expect(kind(await s.pick("pools"))).toBe("playing");
  });
  it("closing the shown game shows the most recently opened still open", async () => {
    const s = await setup();
    await s.pick("pools");
    await s.pick("wheel");
    await s.pick("pools");
    s.play("pools").actions.close();
    expect(s.games.step().shown?.game).toBe("wheel");
    s.play("wheel").actions.close();
    expect(s.games.step()).toMatchObject({ open: [], shown: undefined });
  });
  it("a brand that does not run casino has no games", async () => {
    const s = playerSite({ transport: doubleTransport(doubles({ brand: { modules: ["core"] } })) });
    await s.ready;
    expect(s.games).toBeUndefined();
  });
  it("a play's steps are told to whoever subscribed", async () => {
    const s = await setup();
    const g = await s.pick("pools");
    const seen: PlayStep["kind"][] = [];
    g.subscribe((x) => seen.push(x.kind));
    s.games.actions.hideAll();
    expect(seen).toEqual(["playing"]);
    expect(amount(1n, "EUR").minor).toBe(1n);
  });
});
