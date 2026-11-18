import { expect } from "vitest";
import { routes as casinoRoutes } from "../../src/casino/routes.ts";
import { coreClient, type CoreClient } from "../../src/core/client.ts";
import type { DepositBehaviour, DepositStep } from "../../src/core/deposit.ts";
import { routes as coreRoutes } from "../../src/core/routes.ts";
import { routes as engagementRoutes } from "../../src/engagement/routes.ts";
import type { Route } from "../../src/route.ts";
import { readConfig } from "../../src/server/config.ts";
import { playerSite, type PlayerSite, type PlayerSiteOptions } from "../../src/site.ts";
import { routes as supportRoutes } from "../../src/support/routes.ts";
import { handClock, type HandClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport, type Doubles } from "../../src/testing/doubles.ts";
import type { Transport } from "../../src/transport.ts";
import { bind } from "./setup.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
let clock: HandClock;
let d: Doubles;
let site: PlayerSite;
let kinds: DepositStep["kind"][] = [];

async function build(options: Omit<Partial<PlayerSiteOptions>, "transport"> & { modules?: string[]; transport?: (t: Transport) => Transport } = {}) {
  clock = handClock();
  d = doubles({ clock, brand: options.modules ? { modules: options.modules } : {} });
  const t = doubleTransport(d);
  site = playerSite({ ...options, transport: options.transport ? options.transport(t) : t, clock, idleMinutes: 24 * 60 });
  await site.ready;
  site.session.actions.signIn(ana);
  await clock.settle();
}

/** Drives a deposit from the amount to succeeded, with no component: only steps and actions. */
async function driveDeposit(): Promise<DepositStep["kind"][]> {
  const dep: DepositBehaviour = site.deposit()!;
  const seen: DepositStep["kind"][] = [dep.step().kind];
  dep.subscribe((s) => seen.push(s.kind));
  dep.actions.confirm(5000n);
  await clock.settle();
  dep.actions.choose("t-redirect");
  await clock.advance(1500);
  for (const p of d.brand.payments.values()) p.status = "COMPLETED";
  await clock.advance(3000);
  dep.actions.close();
  return seen;
}
const reference: DepositStep["kind"][] = ["amount", "starting", "choosing_terminal", "submitting", "waiting_at_provider", "succeeded", "closed"];

/**
 * Another implementation of `core`'s interface: the deposit's calls written again here over the
 * transport, by hand and in another style, the rest delegated.
 */
function otherCore(transport: Transport, player: () => string | null): CoreClient {
  const base = coreClient(transport, player);
  const row = (id: string) => coreRoutes.find((r) => r.id === id)!;
  const call = async (id: string, options: Parameters<Transport["call"]>[1]) => {
    const r = await transport.call(row(id), options);
    return r.ok ? ({ ok: true, value: r.body } as const) : r;
  };
  return {
    ...base,
    async startDeposit(amount, requestId) {
      const r = await call("startDeposit", { path: { player: player()! }, body: { amount: Number(amount.minor), currency: amount.currency, requestId } });
      if (!r.ok) return r;
      const b = r.value as { cashierTicket: string; terminals: { terminalEntityId: string; name: string; scheme: string; gateway?: string; supportedCurrencies?: string[] }[]; requestId: string };
      return { ok: true, value: { cashierTicket: b.cashierTicket, requestId: b.requestId, terminals: b.terminals.map((t) => ({ id: t.terminalEntityId, name: t.name, scheme: t.scheme, ...(t.gateway ? { gateway: t.gateway } : {}), ...(t.supportedCurrencies ? { currencies: t.supportedCurrencies } : {}) })) } };
    },
    async submitCashier(ticket, terminalId, extra = {}) {
      const r = await call("submitCashier", { headers: { cashierTicket: ticket }, body: { selectedTerminalEntityId: terminalId, ...(extra.coin ? { cryptoCurrency: extra.coin } : {}) }, words: true });
      if (!r.ok) return r;
      const b = r.value as { paymentId: string; status: string };
      return { ok: true, value: { paymentId: b.paymentId, status: b.status } };
    },
    async payment(ticket, paymentId) {
      const r = await call("payment", { headers: { cashierTicket: ticket }, path: { paymentId } });
      if (!r.ok) return r;
      const { failureReason: _dropped, ...p } = r.value as Record<string, unknown>;
      return { ok: true, value: p as never };
    },
  };
}

await bind("composing.feature", ({ Background, Scenario }) => {
  Background(({ Given }) => {
    Given('a player site of brand "guyanabet"', () => build());
  });

  Scenario("a component bound to a behaviour is told each step and the actions it allows", ({ Given, When, Then }) => {
    // the adapter's rendering is react.test.tsx's; here, what it is told: the step with its fields and allowed actions
    const told: DepositStep[] = [];
    Given("a component bound to the deposit behaviour through the adapter", () => {
      const dep = site.deposit()!;
      told.push(dep.step());
      dep.subscribe((s) => told.push(s));
    });
    When("the deposit behaviour moves from the amount to choosing a terminal", async () => {
      site.currentDeposit!.actions.confirm(5000n);
      await clock.settle();
    });
    Then("the component is told the step, its fields and the actions it allows", () => {
      expect(told.map((s) => s.kind)).toEqual(["amount", "starting", "choosing_terminal"]);
      expect(told[0]).toMatchObject({ allowed: ["confirm", "close"], presets: expect.any(Array), currency: "EUR" });
      expect(told.at(-1)).toMatchObject({ allowed: ["choose", "back", "close"], terminals: expect.arrayContaining([expect.objectContaining({ id: "t-card" })]) });
    });
  });

  Scenario("a site builder configures the brand, its languages and its preset amounts and no address of a service", ({ When, Then, And }) => {
    let builder: Record<string, string> = {};
    let presets: PlayerSiteOptions["presets"];
    When("a site builder configures a player site", async () => {
      // what a builder writes: the brand's values (the keys of the server's configuration) and the page's presets
      builder = { CASINO_BRAND: "guyanabet", PLAYER_SITE_LANGUAGES: "fr,en", PLAYER_SITE_CURRENCY: "EUR", CASINO_MODULES: "casino,engagement,support,player-site" };
      presets = { EUR: ["10", "20", "50"] };
      await build({ presets });
    });
    Then("the configuration names the brand, the site's languages and its preset amounts", () => {
      // the addresses of services are the platform's (ANKKA_SERVICES_URL) and the module's (INGEST_URL), not the builder's
      const config = readConfig({ ...builder, ANKKA_SERVICES_URL: "http://services.internal", INGEST_URL: "http://ingest.internal", PLAYER_SITE_BASE: "https://guyanabet.example" });
      expect(config).toMatchObject({ brand: "guyanabet", languages: ["fr", "en"], currency: "EUR" });
      const dep = site.deposit()!;
      expect((dep.step() as Extract<DepositStep, { kind: "amount" }>).presets.map((a) => a.minor)).toEqual([1000n, 2000n, 5000n]);
    });
    And("the configuration names no address of a service", () => {
      expect(JSON.stringify({ builder, presets })).not.toMatch(/https?:|\/\/|\.internal|localhost/);
      expect(() => readConfig(builder)).toThrow(/ANKKA_SERVICES_URL/);
    });
  });

  Scenario("replacing a module client with another of the same interface changes no behaviour", ({ Given, When, Then }) => {
    let first: DepositStep["kind"][] = [];
    let firstCalls: string[] = [];
    Given('the deposit behaviour over the module client of the module "core"', async () => {
      first = await driveDeposit();
      firstCalls = d.asked.map((a) => a.id);
    });
    When("the module client is replaced by another that meets the same interface", async () => {
      await build({ clients: { core: otherCore } });
      kinds = await driveDeposit();
    });
    Then("the deposit behaviour takes the same steps", () => {
      expect(first).toEqual(reference);
      expect(kinds).toEqual(first);
      expect(d.asked.map((a) => a.id)).toEqual(firstCalls);
    });
  });

  Scenario("a behaviour makes no call of the brand except through a module client", ({ When, Then }) => {
    const calls: { route: Route; stack: string }[] = [];
    When("the deposit behaviour and the play behaviour are each driven through every step", async () => {
      const recording = (t: Transport): Transport => ({
        ...t,
        call: (route, options) => {
          calls.push({ route, stack: new Error().stack ?? "" });
          return t.call(route, options);
        },
        stream: (route, options) => {
          calls.push({ route, stack: new Error().stack ?? "" });
          return t.stream(route, options);
        },
      });
      await build({ transport: recording });
      await driveDeposit();
      site.games!.actions.pick("pools");
      await clock.settle();
      await clock.advance(5 * 60_000);
      site.games!.step().shown!.realityCheck(60);
      site.games!.step().shown!.actions.keepOn();
      site.games!.step().shown!.actions.close();
      await clock.settle();
    });
    Then("every call made of the brand was made through a module client", () => {
      const rows = new Set<Route>([...coreRoutes, ...casinoRoutes, ...engagementRoutes, ...supportRoutes]);
      expect(calls.length).toBeGreaterThan(8);
      expect(new Set(calls.map((c) => c.route.id))).toEqual(new Set(["balance", "freeRounds", "startDeposit", "submitCashier", "payment", "launch", "game", "protection", "gameSession", "acknowledgeRealityCheck", "endGameSession"]));
      for (const c of calls) {
        expect(rows.has(c.route), c.route.id).toBe(true);
        expect(c.stack, c.route.id).toMatch(/src\/(core|casino|engagement|support)\/client\.ts/);
      }
    });
  });

  Scenario("a site offers only the behaviours of the modules its brand runs", ({ Given, When, Then, And }) => {
    Given('a brand "solo" running the module "core" and not the module "casino"', () => {});
    When('a player site of brand "solo" is built', () => build({ modules: ["core"] }));
    Then("the session behaviour and the deposit behaviour are offered", () => {
      expect(site.session.step().kind).toBe("signed_in");
      expect(site.deposit()?.step().kind).toBe("amount");
    });
    And("the play behaviour is not offered", () => expect(site.games).toBeUndefined());
  });
});
