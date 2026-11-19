import { describe, expect, it } from "vitest";
import { amount } from "../../src/amount.ts";
import { coreClient } from "../../src/core/client.ts";
import { fundsStore, hasPlayableFunds, type Funds } from "../../src/core/funds.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const eur = (minor: number | null) => (minor === null ? null : amount(BigInt(minor), "EUR"));
const f = (real: number | null, restricted: number | null, freeRoundsGames?: string[] | null): Funds => ({ real: eur(real), restricted: eur(restricted), freeRoundsGames: freeRoundsGames ?? null });

// Ported from the original: context/DepositContext.test.ts (hasNoPlayableFunds, turned round).
describe("hasPlayableFunds", () => {
  it("is true while the wallet is not read (null)", () => {
    expect(hasPlayableFunds(f(null, null, []))).toBe(true);
  });
  it("is true with a real balance", () => {
    expect(hasPlayableFunds(f(1000, null, []))).toBe(true);
  });
  it("is true with no real balance but a restricted one", () => {
    expect(hasPlayableFunds(f(0, 500, []))).toBe(true);
  });
  it("is false with no real balance, no restricted balance and no free rounds", () => {
    expect(hasPlayableFunds(f(0, null, []))).toBe(false);
    expect(hasPlayableFunds(f(0, 0, []))).toBe(false);
  });
  it("is true while the free rounds are not read", () => {
    expect(hasPlayableFunds(f(0, null, null), "g1")).toBe(true);
  });
  it("lifts the gate only for the game the free rounds are on", () => {
    const funds = f(0, null, ["g1"]);
    expect(hasPlayableFunds(funds, "g1")).toBe(true);
    expect(hasPlayableFunds(funds, "g2")).toBe(false);
  });
  it("with no game named, any free rounds lift the gate", () => {
    expect(hasPlayableFunds(f(0, null, ["g1"]))).toBe(true);
    expect(hasPlayableFunds(f(0, null, []))).toBe(false);
  });
});

describe("the funds store (FR-026)", () => {
  async function setup() {
    const clock = handClock();
    const d = doubles({ clock });
    const t = doubleTransport(d);
    await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    const store = fundsStore(coreClient(t, () => "p-ana"), clock);
    return { clock, d, store };
  }

  it("reads on sign-in and every 5 seconds while visible, and not while hidden", async () => {
    const { clock, d, store } = await setup();
    const seen: Funds[] = [];
    store.subscribe((x) => seen.push(x));
    store.player("EUR");
    await clock.settle();
    expect(store.current()).toEqual({ real: amount(5000n, "EUR"), restricted: amount(0n, "EUR"), freeRoundsGames: null });
    await clock.advance(10_000);
    expect(d.askedOf("balance")).toHaveLength(3);
    store.visible(false);
    await clock.advance(60_000);
    expect(d.askedOf("balance")).toHaveLength(3);
    store.visible(true);
    await clock.settle();
    expect(d.askedOf("balance")).toHaveLength(4);
    expect(seen.length).toBeGreaterThan(1);
  });

  it("a failed read keeps what was known", async () => {
    const { clock, d, store } = await setup();
    store.player("EUR");
    await clock.settle();
    d.script("balance", { status: 503, body: { error: "service_unavailable" } });
    await store.read();
    expect(store.current().real).toEqual(amount(5000n, "EUR"));
  });

  it("free rounds come from the reader it is given; none given is not read", async () => {
    const { clock, store } = await setup();
    store.player("EUR");
    await clock.settle();
    expect(store.current().freeRoundsGames).toBeNull();
    store.freeRounds(async () => ["pools"]);
    await store.read();
    expect(store.current().freeRoundsGames).toEqual(["pools"]);
  });

  it("signing out forgets the funds and stops the reads; a read answered after it is dropped", async () => {
    const { clock, d, store } = await setup();
    store.player("EUR");
    d.script("balance", { status: 200, body: { realBalance: "9.00", bonusBalance: "0.00", currency: "EUR" }, afterMs: 1000 });
    const late = store.read();
    store.player(null);
    await clock.advance(1000);
    await late;
    expect(store.current()).toEqual({ real: null, restricted: null, freeRoundsGames: null });
    const before = d.askedOf("balance").length;
    await clock.advance(30_000);
    expect(d.askedOf("balance")).toHaveLength(before);
  });
});
