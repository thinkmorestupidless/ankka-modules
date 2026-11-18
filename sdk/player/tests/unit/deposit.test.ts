import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { amount } from "../../src/amount.ts";
import type { DepositStep } from "../../src/core/deposit.ts";
import { followDepositAddress, playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

class MemoryStorage {
  readonly items = new Map<string, string>();
  getItem = (k: string) => this.items.get(k) ?? null;
  setItem = (k: string, v: string) => void this.items.set(k, v);
  removeItem = (k: string) => void this.items.delete(k);
  clear = () => this.items.clear();
  key = (i: number) => [...this.items.keys()][i] ?? null;
  get length() {
    return this.items.size;
  }
}
const g = globalThis as Record<string, unknown>;
beforeEach(() => {
  g.sessionStorage = new MemoryStorage();
  g.localStorage = new MemoryStorage();
});
afterEach(() => {
  delete g.sessionStorage;
  delete g.localStorage;
});

async function setup(options: { presets?: Record<string, (string | number)[]> } = {}) {
  const clock = handClock();
  const d = doubles({ clock });
  const transport = doubleTransport(d);
  const site = playerSite({ transport, clock, ...(options.presets ? { presets: options.presets } : {}) });
  await site.ready;
  site.session.actions.signIn(ana);
  await clock.settle();
  const dep = site.deposit()!;
  const steps: DepositStep[] = [dep.step()];
  dep.subscribe((s) => steps.push(s));
  return { clock, d, site, dep, steps };
}
const kinds = (steps: DepositStep[]) => steps.map((s) => s.kind);

describe("the deposit behaviour", () => {
  it("opens at the amount with the site's presets, the original's by default, as minor units", async () => {
    const { dep } = await setup();
    expect(dep.step()).toMatchObject({ kind: "amount", currency: "EUR" });
    expect((dep.step() as { presets?: unknown[] }).presets).toEqual([10, 25, 50, 100, 200, 500].map((x) => amount(BigInt(x * 100), "EUR")));
    const other = await setup({ presets: { EUR: ["5.50", 20] } });
    expect((other.dep.step() as { presets?: unknown[] }).presets).toEqual([amount(550n, "EUR"), amount(2000n, "EUR")]);
  });

  it("confirms once under a new request id and offers the terminals; a second confirm is not allowed", async () => {
    const { clock, d, dep } = await setup();
    expect(dep.actions.confirm(5000n)).toEqual({ ok: true });
    expect(dep.actions.confirm(5000n)).toEqual({ ok: false, notAllowedAt: "starting" });
    await clock.settle();
    expect(d.askedOf("startDeposit")).toHaveLength(1);
    expect(d.askedOf("startDeposit")[0]!.body).toMatchObject({ amount: 5000, currency: "EUR", requestId: expect.any(String) });
    expect(dep.step()).toMatchObject({ kind: "choosing_terminal", terminals: expect.arrayContaining([expect.objectContaining({ id: "t-card" })]) });
  });

  it("a refused start stands at refused with back and close only", async () => {
    const { clock, d, dep } = await setup();
    d.script("startDeposit", { status: 403, body: { error: "DEPOSIT_BLOCKED_KYC", message: "verify first", service: "kyc", kycStatus: "PENDING" } });
    dep.actions.confirm(5000n);
    await clock.settle();
    expect(dep.step()).toMatchObject({ kind: "refused", allowed: ["back", "close"], refusal: { code: "DEPOSIT_BLOCKED_KYC", fields: { kycStatus: "PENDING" }, service: "kyc" } });
    expect(dep.actions.retry()).toEqual({ ok: false, notAllowedAt: "refused" });
    dep.actions.back();
    expect(dep.step().kind).toBe("amount");
  });

  it("a start not answered may be tried again under the same request id, answered with the first session", async () => {
    const { clock, d, dep } = await setup();
    d.script("startDeposit", { status: 200, silent: true }, 1);
    dep.actions.confirm(5000n);
    await clock.advance(15_000);
    expect(dep.step()).toMatchObject({ kind: "refused", allowed: ["back", "close", "retry"], refusal: { code: "service_unavailable", service: "players" } });
    dep.actions.retry();
    await clock.settle();
    const [first, second] = d.askedOf("startDeposit");
    expect((second!.body as { requestId: string }).requestId).toBe((first!.body as { requestId: string }).requestId);
    expect(dep.step().kind).toBe("choosing_terminal");
    // a repeat after an answered start returns the first session: one cashier session in all
    dep.actions.back();
    expect(d.brand.cashierSessions.size).toBe(1);
  });

  it("a cashier session with no terminal fails as no_terminals", async () => {
    const { clock, d, dep } = await setup();
    d.script("startDeposit", { status: 200, body: { cashierTicket: "ticket-x", terminals: [], requestId: "r" } });
    dep.actions.confirm(5000n);
    await clock.settle();
    expect(dep.step()).toMatchObject({ kind: "failed", code: "no_terminals" });
  });

  async function atTerminals() {
    const s = await setup();
    s.dep.actions.confirm(5000n);
    await s.clock.settle();
    return s;
  }

  it("a card whose details the profile holds submits at once; the payment read moves it to waiting at the provider", async () => {
    const s = await atTerminals();
    Object.assign(s.d.brand.players[0]!.profile, { phone: "+594694000000", addressLine1: "1 rue", stateProv: "973" });
    s.dep.actions.choose("t-card");
    await s.clock.settle();
    expect(s.d.askedOf("submitCashier")).toHaveLength(1);
    expect(s.d.askedOf("submitCashier")[0]!.body).toEqual({ selectedTerminalEntityId: "t-card" });
    expect(s.dep.step().kind).toBe("submitting");
    await s.clock.advance(1500);
    expect(s.dep.step()).toMatchObject({ kind: "waiting_at_provider", address: expect.stringMatching(/^https:\/\/psp\.example\/pay\//) });
  });

  it("a wallet lacking the name asks for it, saves it and submits with it; a bad answer stays at details", async () => {
    const s = await atTerminals();
    s.d.brand.players[0]!.profile = {};
    s.dep.actions.choose("t-skrill");
    await s.clock.settle();
    expect(s.dep.step()).toMatchObject({ kind: "details", needs: ["firstName", "lastName"] });
    s.dep.actions.giveDetails({ firstName: "Ana" });
    expect(s.dep.step()).toMatchObject({ kind: "details", invalid: ["lastName"] });
    s.dep.actions.giveDetails({ firstName: "Ana", lastName: "Silva" });
    await s.clock.settle();
    expect(s.d.askedOf("changeProfile")[0]!.body).toEqual({ firstName: "Ana", lastName: "Silva" });
    expect(s.d.askedOf("submitCashier")[0]!.body).toEqual({ selectedTerminalEntityId: "t-skrill", customerFirstName: "Ana", customerLastName: "Silva" });
  });

  it("a profile that cannot be read asks for every detail", async () => {
    const s = await atTerminals();
    s.d.script("profile", { status: 503, body: { error: "service_unavailable" } });
    s.dep.actions.choose("t-skrill");
    await s.clock.settle();
    expect(s.dep.step()).toMatchObject({ kind: "details", needs: ["firstName", "lastName"] });
  });

  it("crypto asks for a coin of the terminal's, submits it, and waits with the cashier's text for the amount", async () => {
    const s = await atTerminals();
    s.dep.actions.choose("t-crypto");
    expect(s.dep.step()).toMatchObject({ kind: "choosing_coin", coins: ["BTC", "USDT"] });
    s.dep.actions.back();
    expect(s.dep.step().kind).toBe("choosing_terminal");
    s.dep.actions.choose("t-crypto");
    s.dep.actions.chooseCoin("USDT");
    await s.clock.advance(1500);
    expect(s.d.askedOf("submitCashier")[0]!.body).toMatchObject({ cryptoCurrency: "USDT" });
    expect(s.dep.step()).toEqual({ kind: "waiting_on_crypto", allowed: ["close"], paymentId: expect.any(String), address: "bc1qexampleaddress", cryptoAmount: "0.00071234", coin: "USDT", expiresAt: "2026-11-18T12:30:00Z" });
  });

  async function atProvider() {
    const s = await atTerminals();
    s.dep.actions.choose("t-redirect");
    await s.clock.advance(1500);
    expect(s.dep.step().kind).toBe("waiting_at_provider");
    const paymentId = (s.dep.step() as { paymentId: string }).paymentId;
    return { ...s, paymentId };
  }

  it("a waiting step reads every 3 s; only an outcome moves it; a capture succeeds and the funds are read", async () => {
    const s = await atProvider();
    const reads = s.d.askedOf("payment").length;
    await s.clock.advance(3000);
    expect(s.d.askedOf("payment")).toHaveLength(reads + 1);
    s.d.brand.payments.get(s.paymentId)!.status = "AWAITING_PAYMENT";
    await s.clock.advance(3000);
    expect(s.dep.step().kind).toBe("waiting_at_provider");
    const balances = s.d.askedOf("balance").length;
    s.d.brand.payments.get(s.paymentId)!.status = "COMPLETED";
    await s.clock.advance(3000);
    expect(s.dep.step()).toEqual({ kind: "succeeded", allowed: ["close"], amount: amount(5000n, "EUR") });
    expect(s.d.askedOf("balance").length).toBeGreaterThan(balances);
    expect(s.clock.pending()).toBeGreaterThan(0); // the funds' own reads go on
  });

  it("a failed or expired payment fails with its failure code", async () => {
    for (const [status, code] of [["FAILED", "payment_failed"], ["EXPIRED", "payment_expired"]] as const) {
      const s = await atProvider();
      Object.assign(s.d.brand.payments.get(s.paymentId)!, { status, failureCode: "05", failureReason: "Do not honour" });
      await s.clock.advance(3000);
      expect(s.dep.step()).toEqual({ kind: "failed", allowed: ["back", "close"], code, fields: { failureCode: "05" } });
    }
  });

  it("a read that fails is ignored and read again; twenty minutes from the submit with no outcome times out", async () => {
    const s = await atProvider();
    s.d.script("payment", { status: 503, body: { error: "TRY_AGAIN" } }, 1);
    await s.clock.advance(3000);
    expect(s.dep.step().kind).toBe("waiting_at_provider");
    const reads = s.d.askedOf("payment").length;
    await s.clock.advance(3000);
    expect(s.d.askedOf("payment")).toHaveLength(reads + 1);
    await s.clock.advance(20 * 60_000);
    expect(s.dep.step()).toMatchObject({ kind: "failed", code: "timed_out" });
    const after = s.d.askedOf("payment").length;
    await s.clock.advance(30_000);
    expect(s.d.askedOf("payment")).toHaveLength(after);
  });

  it("a submit the provider rejects fails with its words, and another refusal carries none", async () => {
    const s = await atTerminals();
    s.d.script("submitCashier", { status: 400, body: { error: "PAYMENT_REJECTED", message: "minimum deposit amount is 15.00" } }, 1);
    s.dep.actions.choose("t-redirect");
    await s.clock.settle();
    expect(s.dep.step()).toEqual({ kind: "failed", allowed: ["back", "close"], code: "PAYMENT_REJECTED", fields: {}, words: "minimum deposit amount is 15.00" });
    s.dep.actions.back();
    s.dep.actions.confirm(5000n);
    await s.clock.settle();
    s.d.script("submitCashier", { status: 502, body: { error: "psp_error", message: "decode response failed" } }, 1);
    s.dep.actions.choose("t-redirect");
    await s.clock.settle();
    expect(s.dep.step()).not.toHaveProperty("words");
  });

  it("closing a waiting deposit abandons the cashier session under its ticket, and ends the deposit", async () => {
    const s = await atProvider();
    expect(s.dep.actions.close()).toEqual({ ok: true });
    await s.clock.settle();
    const ticket = [...s.d.brand.cashierSessions.keys()][0]!;
    expect(s.d.askedOf("abandonCashier")[0]!.headers.cashierTicket).toBe(ticket);
    expect(s.site.currentDeposit).toBeUndefined();
    expect(s.dep.step().kind).toBe("closed");
  });

  it("the cashier ticket is in no step and in no page storage", async () => {
    const s = await atProvider();
    s.dep.actions.close();
    const ticket = [...s.d.brand.cashierSessions.keys()][0]!;
    expect(JSON.stringify(s.steps, (_k, v) => (typeof v === "bigint" ? v.toString() : v))).not.toContain(ticket);
    for (const store of [g.sessionStorage, g.localStorage] as MemoryStorage[]) {
      expect(store.length).toBe(0);
    }
  });

  it("an action the step does not allow sends nothing", async () => {
    const { d, dep } = await setup();
    const asked = d.asked.length;
    expect(dep.actions.chooseCoin("BTC")).toEqual({ ok: false, notAllowedAt: "amount" });
    expect(dep.actions.choose("t-card")).toEqual({ ok: false, notAllowedAt: "amount" });
    expect(d.asked.length).toBe(asked);
  });

  it("one deposit is open at a time, told through subscribe; sign-out closes it", async () => {
    const s = await setup();
    let told = 0;
    s.site.subscribe(() => told++);
    expect(s.site.deposit()).toBe(s.dep);
    s.site.session.actions.signOut();
    await s.clock.settle();
    expect(s.site.currentDeposit).toBeUndefined();
    expect(told).toBeGreaterThan(0);
    expect(kinds(s.steps)).toEqual(["amount"]);
  });
});

describe("the deposit address (FR-025)", () => {
  it("opens the deposit at once for a signed-in player, and after sign-in for a visitor", async () => {
    const clock = handClock();
    const d = doubles({ clock });
    const site = playerSite({ transport: doubleTransport(d), clock });
    await site.ready;
    expect(followDepositAddress(site, "/lobby").now).toBe("none");
    const visitor = followDepositAddress(site, "/?deposit=true");
    expect(visitor.now).toBe("sign_in");
    expect(site.currentDeposit).toBeUndefined();
    site.session.actions.signIn(ana);
    await clock.settle();
    expect(site.currentDeposit?.step().kind).toBe("amount");
    site.currentDeposit!.actions.close();
    expect(followDepositAddress(site, "https://x.example/game/pools?deposit=true").now).toBe("opened");
    expect(site.currentDeposit).toBeDefined();
  });
});
