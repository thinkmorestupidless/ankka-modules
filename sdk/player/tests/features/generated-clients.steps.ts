import { cpSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, expect } from "vitest";
import { descriptionsIn, generate, GenerationError, modules, run, selectionsIn, type Generated } from "../../scripts/generate.ts";
import { amount } from "../../src/amount.ts";
import { casinoClient } from "../../src/casino/client.ts";
import { coreClient } from "../../src/core/client.ts";
import type { DepositStep } from "../../src/core/deposit.ts";
import { engagementClient } from "../../src/engagement/client.ts";
import type { ModuleName, Route } from "../../src/route.ts";
import { supportClient } from "../../src/support/client.ts";
import { wholeSite } from "../server/helpers.ts";
import * as hand from "../unit/fixtures/routes-027.ts";
import { bind, world, type World } from "./setup.ts";

const sdk = path.resolve(import.meta.dirname, "../..");
const services = path.resolve(sdk, "../../services");
const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

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
let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  delete g.sessionStorage;
  delete g.localStorage;
  await close?.();
  close = undefined;
});

/** A copy of the SDK's sources and the kept descriptions, to change without touching the repository's. */
function copy() {
  const dir = mkdtempSync(path.join(tmpdir(), "generated-clients-"));
  cpSync(path.join(sdk, "src"), path.join(dir, "sdk/src"), { recursive: true });
  for (const service of Object.keys(descriptionsIn(services))) cpSync(path.join(services, service, "openapi.player.json"), path.join(dir, "services", service, "openapi.player.json"));
  return { sdk: path.join(dir, "sdk"), services: path.join(dir, "services") };
}

const read = (r: Route) => ({ ...r, ...(r.query ? { query: [...r.query].sort() } : {}) });
const selections = await selectionsIn(path.join(sdk, "src"));

await bind("generated-clients.feature", ({ Background, Scenario, ScenarioOutline }) => {
  let descriptions = descriptionsIn(services);

  Background(({ Given }) => {
    Given("the kept service descriptions of the services a player site calls", () => {
      descriptions = descriptionsIn(services);
      const called = new Set(modules.flatMap((m) => selections[m].map((row) => row.service)));
      for (const service of called) expect(descriptions[service], service).toBeDefined();
    });
  });

  ScenarioOutline("a generated module client holds the routes its module client held when written by hand", ({ When, Then }, v) => {
    let made: Generated;
    When('the module client of the module "<module>" is generated', () => {
      made = generate(descriptions, selections);
    });
    Then("it holds the same routes, each with the same service, the same callers and the same query parameters", () => {
      const module = v.module as ModuleName;
      // The fixture is the table of spec 027 as it was written by hand; a row is its service, its
      // method and path, its audience (the callers), its query parameters and what the server does with it.
      expect(made.tables[module].map(read)).toEqual(hand[module].map(read));
      expect(made.tables[module].length).toBeGreaterThan(0);
    });
  });

  Scenario("generating the module clients changes no behaviour", ({ Given, When, Then }) => {
    let w: World;
    const kinds: string[] = [];
    Given('the deposit behaviour over the generated module client of the module "core"', async () => {
      w = await world();
      w.site.session.actions.signIn(ana);
      await w.clock.settle();
    });
    When("a deposit is driven from the amount to succeeded", async () => {
      const dep = w.site.deposit()!;
      const seen: DepositStep[] = [dep.step()];
      dep.subscribe((s) => seen.push(s));
      dep.actions.confirm(5000n);
      await w.clock.settle();
      dep.actions.choose("t-redirect");
      await w.clock.advance(1500);
      [...w.doubles.brand.payments.values()][0]!.status = "COMPLETED";
      await w.clock.advance(3000);
      for (const s of seen) if (kinds.at(-1) !== s.kind) kinds.push(s.kind);
    });
    Then("the deposit behaviour takes the same steps", () => {
      // The steps spec 027's deposit takes over the hand-written client, by its own scenarios
      // (deposit.feature, bound unchanged in deposit.steps.ts).
      expect(kinds).toEqual(["amount", "starting", "choosing_terminal", "submitting", "waiting_at_provider", "succeeded"]);
      expect(w.doubles.asked.map((a) => a.id).filter((id) => ["startDeposit", "submitCashier", "payment"].includes(id)).slice(0, 3)).toEqual(["startDeposit", "submitCashier", "payment"]);
    });
  });

  Scenario("a service description that changed leaves the generated module clients out of date and the build says so", ({ Given, When, Then }) => {
    let at: ReturnType<typeof copy>;
    let built: Awaited<ReturnType<typeof run>>;
    Given("a kept service description that changed after the module clients were generated", async () => {
      at = copy();
      expect(await run({ ...at, check: true })).toEqual({ code: 0, lines: [] });
      const file = path.join(at.services, "responsible-gambling/openapi.player.json");
      const d = JSON.parse(readFileSync(file, "utf8"));
      d.components.schemas.Protection.properties.sessionLimitMinutes = { type: ["integer", "null"] };
      writeFileSync(file, JSON.stringify(d));
    });
    When("the player SDK is built", async () => {
      // `just player-sdk-test` and CI's `player-sdk` job run this check before anything else.
      built = await run({ ...at, check: true });
    });
    Then("the build fails, naming the generated module clients as out of date", () => {
      expect(built).toEqual({ code: 1, lines: ["src/core/shapes.ts is out of date: run npm run generate -w sdk/player"] });
    });
  });

  Scenario("an answer that does not fit its generated shape is refused as a malformed answer naming the service", ({ Given, When, Then, And }) => {
    let w: World;
    let answer: unknown;
    Given("a service whose answer does not fit its service description", async () => {
      w = await world();
      w.site.session.actions.signIn(ana);
      await w.clock.settle();
      expect(w.site.funds.current().real).toEqual(amount(5000n, "EUR"));
      // The wallet's description says `bonusBalance` and the rest; this answer holds a balance and nothing else.
      w.doubles.script("balance", { status: 200, body: { realBalance: "999.00" } });
    });
    When("a generated module client reads the answer", async () => {
      answer = await coreClient(w.transport, () => "p-ana").balance("EUR");
      await w.site.funds.read();
    });
    Then("the answer is refused as a malformed answer, naming the service", () => {
      expect(answer).toEqual({ ok: false, refusal: expect.objectContaining({ code: "malformed_answer", service: "wallet" }) });
    });
    And("no behaviour is given the answer", () => {
      expect(w.site.funds.current().real).toEqual(amount(5000n, "EUR"));
    });
  });

  Scenario("a selection naming a route that no service description holds fails the generation naming the route", ({ Given, When, Then }) => {
    const route = "GET /v1/casino/players/the-brand/{playerId}/loyalty";
    let failure: unknown;
    let withIt = selections;
    Given("a selection that names a route no kept service description holds", () => {
      withIt = { ...selections, core: [...selections.core, { id: "loyalty", service: "players", operation: route, rename: { playerId: "player" }, answers: "Loyalty" }] };
    });
    When("the module clients are generated", () => {
      try {
        generate(descriptions, withIt);
      } catch (e) {
        failure = e;
      }
    });
    Then("the generation fails, naming the route", () => {
      expect(failure).toBeInstanceOf(GenerationError);
      expect((failure as GenerationError).problems).toEqual([`core/selection.ts: loyalty names ${route} which services/players/openapi.player.json does not hold`]);
    });
  });

  Scenario("a player route no selection names has no call in a module client and is refused by the site server", ({ Given, When, Then, And }) => {
    const servicePath = "/v1/casino/kyc/the-brand/{playerId}/documents";
    let made: Generated;
    Given('a player route of the service "kyc" that no selection names', () => {
      expect(descriptions.kyc?.paths?.[servicePath]?.get?.tags).toContain("player");
      expect(modules.flatMap((m) => selections[m]).some((row) => row.service === "kyc")).toBe(false);
    });
    When("the module clients are generated", () => {
      made = generate(descriptions, selections);
    });
    Then("no module client has a call of the route", async () => {
      const rows = modules.flatMap((m) => made.tables[m]);
      expect(rows.filter((r) => r.service === "kyc" || r.path.includes("/kyc/"))).toEqual([]);
      // A client's functions are its table's rows: none is the documents'.
      const w = await world();
      const clients = { ...coreClient(w.transport), ...casinoClient(w.transport), ...engagementClient(w.transport), ...supportClient(w.transport, () => "guyanabet") };
      const pageRows = rows.filter((r) => !r.serverOnly).map((r) => r.id).sort();
      expect(Object.keys(clients).sort()).toEqual(pageRows);
      expect(pageRows).not.toContain("documents");
    });
    And("the site server refuses a page's call of the route", async () => {
      const s = await wholeSite();
      close = s.close;
      await s.signIn();
      const before = s.doubles.asked.length;
      for (const call of ["/api/c/core/documents", "/api/c/kyc/documents", "/api/c/core/kyc/documents"]) {
        const r = await s.request("GET", call);
        expect(r.status, call).toBe(404);
        expect(await r.json(), call).toEqual({ error: "not_named" });
      }
      expect(s.doubles.asked.length).toBe(before);
    });
  });
});
