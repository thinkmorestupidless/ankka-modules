import { afterEach, beforeEach, expect } from "vitest";
import type { DepositBehaviour, DepositStep } from "../../src/core/deposit.ts";
import { followDepositAddress } from "../../src/site.ts";
import type { FakeTerminal } from "../../src/testing/doubles.ts";
import { bind, world, type World } from "./setup.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const fullProfile = { firstName: "Ana", lastName: "Silva", phone: "+594694000000", addressLine1: "1 rue des Palmistes", stateProv: "973", mobile: "4165550123", province: "ON" };
const schemes = ["SKRILL", "VISA", "APPLE_PAY", "INTERAC_ETRANSFER", "PIX", "BANK"];
const terminals: FakeTerminal[] = [
  { terminalEntityId: "card-eur", name: "Carte", scheme: "VISA", gateway: "stub", supportedCurrencies: ["EUR"] },
  ...schemes.map((scheme) => ({ terminalEntityId: `t-${scheme}`, name: scheme, scheme, gateway: "stub" })),
  { terminalEntityId: "t-CRYPTO", name: "Crypto", scheme: "CRYPTO", gateway: "stub", supportedCurrencies: ["BTC", "USDT"] },
];

class MemoryStorage {
  readonly items = new Map<string, string>();
  getItem = (k: string) => this.items.get(k) ?? null;
  setItem = (k: string, v: string) => void this.items.set(k, v);
  removeItem = (k: string) => void this.items.delete(k);
  clear = () => this.items.clear();
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

let w: World;
let dep: DepositBehaviour;
let seen: DepositStep[] = [];
const step = () => dep.step();
const open = () => {
  dep = w.site.deposit()!;
  seen = [dep.step()];
  dep.subscribe((s) => seen.push(s));
};
const confirm = async (minor = 5000n) => {
  dep.actions.confirm(minor);
  await w.clock.settle();
};
const atTerminals = async () => {
  open();
  await confirm();
  expect(step().kind).toBe("choosing_terminal");
};
const atProvider = async () => {
  await atTerminals();
  dep.actions.choose("t-BANK");
  await w.clock.advance(1500);
  expect(step().kind).toBe("waiting_at_provider");
};
const paymentOf = () => w.doubles.brand.payments.get((step() as { paymentId: string }).paymentId)!;
const ticket = () => [...w.doubles.brand.cashierSessions.keys()].at(-1)!;
const big = (v: unknown) => JSON.stringify(v, (_k, x) => (typeof x === "bigint" ? x.toString() : x));

const refusals: Record<string, { status: number; body: Record<string, unknown> }> = {
  DEPOSIT_BLOCKED_KYC: { status: 403, body: { error: "DEPOSIT_BLOCKED_KYC", message: "verify", service: "kyc", kycStatus: "PENDING" } },
  DEPOSIT_BLOCKED_KYC_THRESHOLD: { status: 403, body: { error: "DEPOSIT_BLOCKED_KYC_THRESHOLD", message: "verify", service: "kyc", kycStatus: "BASIC" } },
  DEPOSIT_BLOCKED_KYC_CURRENCY: { status: 403, body: { error: "DEPOSIT_BLOCKED_KYC_CURRENCY", message: "verify", service: "kyc", kycStatus: "BASIC" } },
  DEPOSIT_BLOCKED_SOF: { status: 403, body: { error: "DEPOSIT_BLOCKED_SOF", message: "source of funds", service: "kyc" } },
  over_limit: { status: 403, body: { error: "over_limit", message: "limit", service: "responsible-gambling", period: "DAILY", limit: 50000, used: 48000, remaining: 2000 } },
  cooling_off: { status: 403, body: { error: "cooling_off", message: "break", service: "responsible-gambling", endsAt: "2026-11-25T12:00:00Z" } },
  excluded: { status: 403, body: { error: "excluded", message: "excluded", service: "responsible-gambling" } },
  account_restricted: { status: 403, body: { error: "account_restricted", message: "restricted", reason: "suspended" } },
};
const fieldsOf: Record<string, Record<string, unknown>> = {
  "the KYC status": { kycStatus: expect.any(String) },
  "no fields": {},
  "the period of the deposit limit, its value, what was used and what remains": { period: "DAILY", limit: 50000n, used: 48000n, remaining: 2000n },
  "the end of the break": { until: "2026-11-25T12:00:00Z" },
  "the player status": { reason: "suspended" },
};
const missingOf: Record<string, { profile: string[]; needs: string[]; given: Record<string, string> }> = {
  "the first and last name": { profile: ["firstName", "lastName"], needs: ["firstName", "lastName"], given: { firstName: "Ana", lastName: "Silva" } },
  "the telephone number and the address": { profile: ["phone", "addressLine1"], needs: ["phone", "address"], given: { phone: "0694 00 00 00", dialCode: "+594", address: "1 rue des Palmistes" } },
  "the telephone number": { profile: ["phone"], needs: ["phone"], given: { phone: "+594694000001" } },
  "the mobile number and the province": { profile: ["mobile", "province"], needs: ["mobile", "province"], given: { mobile: "(416) 555-0199", province: "qc" } },
};

await bind("deposit.feature", ({ Background, Scenario, ScenarioOutline }) => {
  Background(({ Given, And }) => {
    Given('a player site of brand "guyanabet"', async () => {
      w = await world({ terminals });
    });
    And('a player signed in at the player site whose currency is "EUR"', async () => {
      w.site.session.actions.signIn(ana);
      await w.clock.settle();
      expect(w.site.player?.currency).toBe("EUR");
    });
  });

  Scenario("a deposit opens at the amount with the site's preset amounts in the player's currency", ({ When, Then, And }) => {
    When("the player opens the deposit", open);
    Then("the deposit behaviour stands at the amount", () => expect(step().kind).toBe("amount"));
    And('it offers the site\'s preset amounts in "EUR" as minor units', () => {
      const s = step() as Extract<DepositStep, { kind: "amount" }>;
      expect(s.presets.map((a) => [a.minor, a.currency])).toEqual([1000n, 2500n, 5000n, 10000n, 20000n, 50000n].map((m) => [m, "EUR"]));
    });
  });

  Scenario("confirming an amount has the brand start the deposit and offers the cashier session's terminals", ({ Given, When, Then, And }) => {
    Given("the deposit behaviour stands at the amount", open);
    When('the player confirms "5000" minor units', () => confirm(5000n));
    Then('the brand is asked once to start a deposit of "5000" minor units of "EUR" under a new request id', () => {
      expect(w.doubles.askedOf("startDeposit")).toHaveLength(1);
      expect(w.doubles.askedOf("startDeposit")[0]!.body).toEqual({ amount: 5000, currency: "EUR", requestId: expect.stringMatching(/^[A-Za-z0-9_-]{1,64}$/) });
    });
    And("the deposit behaviour stands at choosing a terminal with the terminals the cashier session offers", () => {
      expect(step()).toMatchObject({ kind: "choosing_terminal" });
      expect((step() as { terminals?: { id: string }[] }).terminals!.map((t) => t.id)).toEqual(terminals.map((t) => t.terminalEntityId));
    });
  });

  ScenarioOutline("a deposit the brand refuses stands at refused naming the reason and no cashier session is opened", ({ Given, When, Then, And }, v) => {
    Given('the brand refuses to start the deposit as "<code>"', () => {
      w.doubles.script("startDeposit", refusals[v.code as string]!);
    });
    When("the player confirms an amount", async () => {
      open();
      await confirm();
    });
    Then('the deposit behaviour stands at refused with the code "<code>" and <fields>', () => {
      const s = step() as Extract<DepositStep, { kind: "refused" }>;
      expect(s.kind).toBe("refused");
      expect(s.refusal.code).toBe(v.code);
      expect(s.refusal.fields).toEqual(fieldsOf[v.fields as string]);
    });
    And("no cashier session is opened", () => expect(w.doubles.brand.cashierSessions.size).toBe(0));
    And("the only actions allowed are going back to the amount and closing", () => expect(step().allowed).toEqual(["back", "close"]));
  });

  Scenario("a start that was not answered stands at refused naming the service and may be tried again under the same request id", ({ Given, When, Then }) => {
    Given("the brand does not answer the start of the deposit", () => {
      w.doubles.script("startDeposit", { status: 200, silent: true }, 1);
      open();
    });
    When('the player confirms "5000" minor units', async () => {
      dep.actions.confirm(5000n);
      await w.clock.advance(15_000);
    });
    Then('the deposit behaviour stands at refused with the code "service_unavailable" and the service that did not answer', () => {
      expect(step()).toMatchObject({ kind: "refused", refusal: { code: "service_unavailable", service: "players" } });
    });
    When("the player tries again", async () => {
      expect(dep.actions.retry()).toEqual({ ok: true });
      await w.clock.settle();
    });
    Then('the brand is asked to start a deposit of "5000" minor units of "EUR" under the same request id', () => {
      const [first, again] = w.doubles.askedOf("startDeposit").map((a) => a.body as { amount: number; currency: string; requestId: string });
      expect(again).toEqual({ amount: 5000, currency: "EUR", requestId: first!.requestId });
    });
  });

  Scenario("a deposit the cashier refuses stands at refused with the cashier's code", ({ Given, When, Then }) => {
    Given('the cashier refuses the cashier session as "TOO_MANY_REQUESTS"', () => {
      w.doubles.script("startDeposit", { status: 429, body: { error: "TOO_MANY_REQUESTS", message: "slow down", service: "ingest", retryAfterSeconds: 30 } });
    });
    When("the player confirms an amount", async () => {
      open();
      await confirm();
    });
    Then('the deposit behaviour stands at refused with the code "TOO_MANY_REQUESTS" and the service "ingest"', () => {
      expect(step()).toMatchObject({ kind: "refused", refusal: { code: "TOO_MANY_REQUESTS", service: "ingest", fields: { retryAfterSeconds: 30 } } });
    });
  });

  Scenario("going back from a refused deposit returns to the amount", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at refused", async () => {
      w.doubles.script("startDeposit", refusals.excluded!);
      open();
      await confirm();
      expect(step().kind).toBe("refused");
    });
    When("the player goes back", () => void dep.actions.back());
    Then("the deposit behaviour stands at the amount", () => expect(step().kind).toBe("amount"));
  });

  Scenario("a cashier session offering no terminal ends the deposit as failed", ({ Given, When, Then }) => {
    Given("the brand starts the deposit with a cashier session that offers no terminal", () => {
      w.doubles.brand.terminals = [];
    });
    When("the player confirms an amount", async () => {
      open();
      await confirm();
    });
    Then('the deposit behaviour stands at failed with the code "no_terminals"', () => expect(step()).toMatchObject({ kind: "failed", code: "no_terminals" }));
  });

  Scenario("choosing a terminal whose details the player profile holds submits the cashier session at once", ({ Given, And, When, Then }) => {
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    And('the player profile holds the details the terminal "card-eur" needs', () => {
      Object.assign(w.doubles.brand.players[0]!.profile, fullProfile);
    });
    When('the player chooses the terminal "card-eur"', async () => {
      dep.actions.choose("card-eur");
      await w.clock.settle();
    });
    Then('the cashier session is submitted once naming "card-eur" under its cashier ticket', () => {
      const asked = w.doubles.askedOf("submitCashier");
      expect(asked).toHaveLength(1);
      expect(asked[0]!.body).toEqual({ selectedTerminalEntityId: "card-eur" });
      expect(asked[0]!.headers.cashierTicket).toBe(ticket());
    });
  });

  ScenarioOutline("choosing a terminal whose scheme needs details the player profile lacks asks for them, saves them and submits with them", ({ Given, And, When, Then }, v) => {
    const m = () => missingOf[v.missing as string]!;
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    And("the player profile lacks <missing>", () => {
      const p = { ...fullProfile } as Record<string, string>;
      for (const f of m().profile) delete p[f];
      w.doubles.brand.players[0]!.profile = p;
    });
    When('the player chooses a terminal of the scheme "<scheme>"', async () => {
      dep.actions.choose(`t-${v.scheme}`);
      await w.clock.settle();
    });
    Then("the deposit behaviour stands at details asking for <missing>", () => {
      expect(step()).toMatchObject({ kind: "details", needs: m().needs });
    });
    When("the player gives the details", async () => {
      expect(dep.actions.giveDetails(m().given)).toEqual({ ok: true });
      await w.clock.settle();
    });
    Then("the details are saved to the player profile", () => {
      const saved = w.doubles.askedOf("changeProfile")[0]!.body as Record<string, string>;
      for (const f of m().profile) expect(saved[f], f).toBeTruthy();
    });
    And("the cashier session is submitted with the details", () => {
      const body = w.doubles.askedOf("submitCashier")[0]!.body as Record<string, string>;
      expect(body.selectedTerminalEntityId).toBe(`t-${v.scheme}`);
      const sent: Record<string, string> = { firstName: "customerFirstName", lastName: "customerLastName", phone: "customerPhone", address: "customerAddress", mobile: "customerMobile", province: "customerProvince" };
      for (const n of m().needs) expect(body[sent[n]!], n).toBeTruthy();
    });
  });

  Scenario("a terminal of a scheme that needs no details submits at once", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    When('the player chooses a terminal of the scheme "PIX"', async () => {
      dep.actions.choose("t-PIX");
      await w.clock.settle();
    });
    Then("the cashier session is submitted once", () => {
      expect(w.doubles.askedOf("submitCashier")).toHaveLength(1);
      expect(w.doubles.askedOf("profile")).toEqual([]);
    });
  });

  Scenario("a crypto terminal asks for the coin and then shows where to pay", ({ Given, When, Then, And }) => {
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    When('the player chooses a terminal of the scheme "CRYPTO"', () => void dep.actions.choose("t-CRYPTO"));
    Then("the deposit behaviour stands at choosing a coin", () => expect(step()).toMatchObject({ kind: "choosing_coin", coins: ["BTC", "USDT"] }));
    When('the player chooses the coin "USDT"', async () => {
      dep.actions.chooseCoin("USDT");
      await w.clock.advance(1500);
    });
    Then('the cashier session is submitted naming the coin "USDT"', () => {
      expect(w.doubles.askedOf("submitCashier")[0]!.body).toMatchObject({ selectedTerminalEntityId: "t-CRYPTO", cryptoCurrency: "USDT" });
    });
    And("the deposit behaviour stands at waiting on a crypto payment with the address, the amount, the coin and the expiry", () => {
      expect(step()).toMatchObject({ kind: "waiting_on_crypto", address: "bc1qexampleaddress", cryptoAmount: "0.00071234", coin: "USDT", expiresAt: "2026-11-18T12:30:00Z" });
    });
  });

  Scenario("a payment awaiting the player at the payment provider gives the page the provider's address and waits", ({ Given, When, Then }) => {
    Given("the cashier session was submitted", async () => {
      await atTerminals();
      dep.actions.choose("t-BANK");
      await w.clock.settle();
      expect(step().kind).toBe("submitting");
    });
    When("the payment is read as awaiting the player with a redirect address", () => w.clock.advance(1500));
    Then("the deposit behaviour stands at waiting at the payment provider with that address", () => {
      const p = [...w.doubles.brand.payments.values()][0]!;
      expect(step()).toMatchObject({ kind: "waiting_at_provider", address: p.redirectUrl });
    });
  });

  Scenario("a captured payment ends the deposit as succeeded and the balance is read again", ({ Given, When, Then, And }) => {
    let balances = 0;
    Given("the deposit behaviour stands at waiting at the payment provider", atProvider);
    When("the payment is read as captured", async () => {
      balances = w.doubles.askedOf("balance").length;
      paymentOf().status = "COMPLETED";
      await w.clock.advance(3000);
    });
    Then("the deposit behaviour stands at succeeded", () => expect(step()).toMatchObject({ kind: "succeeded", amount: { minor: 5000n, currency: "EUR" } }));
    And("the player's balance is read again", () => expect(w.doubles.askedOf("balance").length).toBeGreaterThan(balances));
  });

  ScenarioOutline("a payment that fails or expires ends the deposit as failed with the payment's failure code", ({ Given, When, Then }, v) => {
    Given("the deposit behaviour stands at waiting at the payment provider", atProvider);
    When("the payment is read as <outcome>", async () => {
      Object.assign(paymentOf(), { status: v.outcome === "failed" ? "FAILED" : "EXPIRED", failureCode: "05" });
      await w.clock.advance(3000);
    });
    Then("the deposit behaviour stands at failed with the payment's failure code", () => {
      expect(step()).toEqual({ kind: "failed", allowed: ["back", "close"], code: v.outcome === "failed" ? "payment_failed" : "payment_expired", fields: { failureCode: "05" } });
    });
  });

  Scenario("a submit the payment provider rejects ends the deposit as failed with the provider's words", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    When("the player chooses a terminal and the payment provider rejects the submit with words for the player", async () => {
      w.doubles.script("submitCashier", { status: 400, body: { error: "PAYMENT_REJECTED", message: "montant minimum 15,00 €" } });
      dep.actions.choose("t-PIX");
      await w.clock.settle();
    });
    Then('the deposit behaviour stands at failed with the code "PAYMENT_REJECTED" and the provider\'s words', () => {
      expect(step()).toMatchObject({ kind: "failed", code: "PAYMENT_REJECTED", words: "montant minimum 15,00 €" });
    });
  });

  Scenario("the cashier ticket is in no step and in nothing the page keeps", ({ Given, When, Then }) => {
    let read = "";
    Given("the deposit behaviour stands at choosing a terminal", atTerminals);
    When("the page reads the step and everything the page keeps", () => {
      const stores = [g.sessionStorage, g.localStorage] as MemoryStorage[];
      read = big({ steps: seen, now: step(), stores: stores.map((s) => [...s.items]) });
    });
    Then("the cashier ticket is in neither", () => {
      expect(ticket()).toMatch(/^ticket-/);
      expect(read).not.toContain(ticket());
    });
  });

  Scenario("a payment with no outcome in twenty minutes ends the deposit as timed out", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at waiting at the payment provider", atProvider);
    When("twenty minutes pass and the payment has no outcome", () => w.clock.advance(20 * 60_000));
    Then('the deposit behaviour stands at failed with the code "timed_out"', () => expect(step()).toMatchObject({ kind: "failed", code: "timed_out" }));
  });

  Scenario("a payment read that fails while waiting is ignored", ({ Given, When, Then, And }) => {
    let reads = 0;
    Given("the deposit behaviour stands at waiting at the payment provider", atProvider);
    When("a read of the payment is not answered", async () => {
      w.doubles.script("payment", { status: 200, silent: true }, 1);
      reads = w.doubles.askedOf("payment").length;
      await w.clock.advance(3000 + 15_000);
    });
    Then("the deposit behaviour still stands at waiting at the payment provider", () => expect(step().kind).toBe("waiting_at_provider"));
    And("the payment is read again", async () => {
      await w.clock.advance(3000);
      expect(w.doubles.askedOf("payment").length).toBeGreaterThanOrEqual(reads + 2);
    });
  });

  Scenario("closing a waiting deposit abandons the cashier session", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at waiting at the payment provider", atProvider);
    When("the player closes the deposit", async () => {
      dep.actions.close();
      await w.clock.settle();
    });
    Then("the cashier session is abandoned under its cashier ticket", () => {
      expect(w.doubles.askedOf("abandonCashier")[0]!.headers.cashierTicket).toBe(ticket());
    });
  });

  Scenario("an action the step does not allow sends nothing and is answered as not allowed", ({ Given, When, Then, And }) => {
    let answer: unknown;
    let asked = 0;
    Given("the deposit behaviour stands at the amount", open);
    When("the page asks the deposit behaviour to submit", () => {
      asked = w.doubles.asked.length;
      answer = dep.actions.choose("card-eur");
    });
    Then("nothing is sent to the brand", () => expect(w.doubles.asked.length).toBe(asked));
    And("the page is told the action is not allowed at the amount", () => expect(answer).toEqual({ ok: false, notAllowedAt: "amount" }));
  });

  Scenario("an amount confirmed twice starts one deposit", ({ Given, When, Then }) => {
    Given("the deposit behaviour stands at the amount", open);
    When("the player confirms an amount twice", async () => {
      dep.actions.confirm(5000n);
      dep.actions.confirm(5000n);
      await w.clock.settle();
    });
    Then("the brand is asked once to start a deposit", () => expect(w.doubles.askedOf("startDeposit")).toHaveLength(1));
  });

  Scenario("a visitor arriving at the site's deposit address is asked to sign in and the deposit opens once they have", ({ Given, When, Then }) => {
    let now = "";
    Given("a visitor of the player site", async () => {
      w.site.session.actions.signOut();
      await w.clock.settle();
      expect(w.site.player).toBeNull();
    });
    When("they arrive at the site's deposit address", () => {
      now = followDepositAddress(w.site, "/?deposit=true").now;
    });
    Then("they are asked to sign in", () => {
      expect(now).toBe("sign_in");
      expect(w.site.currentDeposit).toBeUndefined();
    });
    When("they sign in", async () => {
      w.site.session.actions.signIn(ana);
      await w.clock.settle();
    });
    Then("the deposit behaviour stands at the amount", () => expect(w.site.currentDeposit?.step().kind).toBe("amount"));
  });
});
