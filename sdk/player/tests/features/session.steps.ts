import { afterAll, expect } from "vitest";
import { routes as core } from "../../src/core/routes.ts";
import type { Refusal } from "../../src/refusal.ts";
import { bind } from "./setup.ts";
import { stack, type Stack } from "./stack.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const balance = core.find((r) => r.id === "balance")!;
const issued = (w: Stack) => ["__Host-eo_session", "__Secure-eo_refresh"].map((n) => w.browser.get(n)).filter((v): v is string => !!v);

let w: Stack;
const opened: Stack[] = [];
afterAll(async () => {
  for (const s of opened) await s.close();
});
const fresh = async (options: Parameters<typeof stack>[0] = {}) => {
  w = await stack(options);
  opened.push(w);
};
const signIn = async () => {
  w.site.session.actions.signIn(ana);
  await until(() => w.site.session.step().kind !== "signing_in");
};
/** Waits for real HTTP over the hand clock. */
async function until(done: () => boolean, ms = 2000) {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("timed out waiting");
    await new Promise((r) => setTimeout(r, 5));
  }
}
const call = () => w.transport.call(balance, { path: { currency: "EUR" } });

await bind("session.feature", ({ Background, Scenario, ScenarioOutline }) => {
  Background(({ Given, And }) => {
    Given('a player site of brand "guyanabet"', async () => {
      await fresh();
    });
    And('a player of brand "guyanabet"', () => {
      expect(w.doubles.brand.players.some((p) => p.brand === "guyanabet")).toBe(true);
    });
  });

  Scenario("a player who signs in at the player site is signed in at the brand and the page is never given the sign-in", ({ Given, When, Then, And }) => {
    Given("the player is active with a verified email address", () => {
      expect(w.doubles.brand.players[0]!.verified).toBe(true);
    });
    When("they sign in at the player site with the right password", signIn);
    Then("a player session opens at the brand", () => {
      expect(w.doubles.askedOf("signIn")).toHaveLength(1);
      expect(issued(w)).toHaveLength(2);
    });
    And("the page is told the player id, the currency and the country", () => {
      expect(w.site.session.step()).toMatchObject({ kind: "signed_in", player: { playerId: "p-ana", currency: "EUR", country: "GF" } });
    });
    And("the sign-in is in no answer to the page, in nothing the page keeps and in nothing the site server logged", () => {
      const values = issued(w);
      for (const a of w.answers) {
        for (const v of values) {
          expect(a.body).not.toContain(v);
          expect(a.headers.filter(([k]) => k !== "set-cookie").map(([, x]) => x).join()).not.toContain(v);
        }
      }
      expect(JSON.stringify(w.site.session.step())).not.toMatch(/token/i);
      for (const v of values) expect(JSON.stringify(w.logs)).not.toContain(v);
    });
  });

  const fieldsOf: Record<string, Record<string, unknown>> = {
    "no fields": {},
    "the player status": { reason: "closed" },
    "the end of the break": { until: "2026-11-25T12:00:00Z" },
  };
  const refusals: Record<string, { status: number; body: Record<string, unknown> }> = {
    invalid_credentials: { status: 401, body: { error: "invalid_credentials", message: "the address or the password is wrong" } },
    email_not_verified: { status: 401, body: { error: "email_not_verified", message: "the address is not verified" } },
    account_restricted: { status: 403, body: { error: "account_restricted", message: "the account is restricted", reason: "closed" } },
    cooling_off: { status: 403, body: { error: "cooling_off", message: "the player is cooling off", until: "2026-11-25T12:00:00Z" } },
  };
  ScenarioOutline("a sign-in the brand refuses puts the session behaviour at refused with the brand's code and fields", ({ Given, When, Then, And }, variables) => {
    Given('the brand refuses the player\'s sign-in as "<code>"', () => {
      w.doubles.script("signIn", refusals[variables.code as string]!);
    });
    When("they sign in at the player site", signIn);
    Then('the session behaviour stands at refused with the code "<code>" and <fields>', () => {
      const step = w.site.session.step();
      expect(step).toMatchObject({ kind: "refused", refusal: { code: variables.code, fields: fieldsOf[variables.fields as string] } });
      expect((step as { refusal: Refusal }).refusal.fields).toEqual(fieldsOf[variables.fields as string]);
    });
    And("the page is given no text of the brand's", () => {
      expect(JSON.stringify(w.site.session.step())).not.toContain(refusals[variables.code as string]!.body.message as string);
    });
  });

  Scenario("calls refused because the sign-in lapsed refresh it once and are each made once more", ({ Given, And, When, Then }) => {
    let answers: Awaited<ReturnType<typeof call>>[] = [];
    let before = { asked: 0, answered: 0 };
    Given("the player is signed in at the player site", signIn);
    And("the sign-in has lapsed", async () => {
      // the funds' own reads on sign-in are not the page's three calls: let them answer, then stop them
      await until(() => w.answers.some((a) => a.path.startsWith("/api/c/core/balance")) && w.answers.some((a) => a.path.startsWith("/api/c/casino/freeRounds")));
      w.site.funds.stop();
      w.browser.delete("__Host-eo_session");
    });
    When("the page makes three calls at once", async () => {
      before = { asked: w.doubles.askedOf("balance").length, answered: w.answers.filter((a) => a.path.startsWith("/api/c/core/balance")).length };
      answers = await Promise.all([call(), call(), call()]);
    });
    Then("the sign-in is refreshed once", () => {
      expect(w.doubles.askedOf("refresh")).toHaveLength(1);
    });
    And("each of the three calls is made once more and answered", () => {
      expect(answers.every((a) => a.ok)).toBe(true);
      expect(w.doubles.askedOf("balance").length - before.asked).toBe(3);
      expect(w.answers.filter((a) => a.path.startsWith("/api/c/core/balance")).length - before.answered).toBe(6);
    });
  });

  Scenario("a sign-in the realm will not refresh signs the player out and the page is told why", ({ Given, And, When, Then }) => {
    Given("the player is signed in at the player site", signIn);
    And("the realm refuses to refresh the sign-in", () => {
      w.browser.delete("__Host-eo_session");
      w.doubles.script("refresh", { status: 401, body: { error: "refresh_expired" } });
    });
    When("the page makes a call", async () => {
      await call();
    });
    Then("the player is signed out at the player site", () => {
      expect(w.browser.names()).toEqual([]);
    });
    And('the session behaviour stands at signed out with the reason "refresh_expired"', () => {
      expect(w.site.session.step()).toMatchObject({ kind: "signed_out", reason: "refresh_expired" });
    });
  });

  Scenario("a refresh that fails for another cause keeps the sign-in", ({ Given, And, When, Then }) => {
    let answer: Awaited<ReturnType<typeof call>> | undefined;
    Given("the player is signed in at the player site", signIn);
    And("the brand does not answer a refresh", () => {
      w.doubles.script("refresh", { status: 200, silent: true });
    });
    When("the page makes a call whose sign-in has lapsed", async () => {
      w.browser.delete("__Host-eo_session");
      answer = await call();
    });
    Then("the call is refused as unavailable naming the service", () => {
      expect(answer).toMatchObject({ ok: false, refusal: { code: "service_unavailable", service: "players" } });
    });
    And("the player is still signed in at the player site", () => {
      expect(w.browser.names()).toEqual(["__Host-eo_player", "__Secure-eo_refresh"]);
      expect(w.site.session.step().kind).toBe("signed_in");
    });
  });

  Scenario("a player who signs out ends the player session and the site server keeps nothing of the sign-in", ({ Given, When, Then, And }) => {
    Given("the player is signed in at the player site", signIn);
    When("they sign out", async () => {
      w.site.session.actions.signOut();
      await until(() => w.site.session.step().kind === "signed_out");
    });
    Then("the player session is ended at the brand", () => {
      expect(w.doubles.askedOf("signOut")).toHaveLength(1);
      expect(w.doubles.askedOf("signOut")[0]!.path.player).toBe("p-ana");
    });
    And("the site server keeps nothing of the sign-in", () => {
      expect(w.browser.names()).toEqual([]);
    });
  });

  Scenario("an idle player is warned and then signed out", ({ Given, When, Then }) => {
    Given("the player is signed in at the player site", signIn);
    When("they do nothing for thirty minutes less thirty seconds", async () => {
      await w.clock.advance(29 * 60_000 + 30_000);
    });
    Then("the session behaviour stands at the idle warning", () => {
      expect(w.site.session.step().kind).toBe("idle_warning");
    });
    When("they do nothing for thirty seconds more", async () => {
      await w.clock.advance(30_000);
      await until(() => w.site.session.step().kind === "signed_out");
    });
    Then("the player is signed out at the player site", () => {
      expect(w.site.session.step()).toMatchObject({ kind: "signed_out", reason: "idle" });
      expect(w.browser.names()).toEqual([]);
    });
  });

  Scenario("a warned player who chooses to stay has their sign-in refreshed", ({ Given, When, Then, And }) => {
    Given("the session behaviour stands at the idle warning", async () => {
      await signIn();
      await w.clock.advance(29 * 60_000 + 30_000);
      expect(w.site.session.step().kind).toBe("idle_warning");
    });
    When("the player chooses to stay", async () => {
      w.site.session.actions.stay();
      await until(() => w.site.session.step().kind !== "idle_warning");
    });
    Then("the sign-in is refreshed", () => {
      expect(w.doubles.askedOf("refresh")).toHaveLength(1);
    });
    And("the session behaviour stands at signed in", () => {
      expect(w.site.session.step().kind).toBe("signed_in");
    });
  });

  Scenario("a visitor who registers is told to verify their email address and is not signed in", ({ Given, When, Then, And }) => {
    Given("a visitor of the player site", () => {
      expect(w.site.session.step().kind).toBe("signed_out");
    });
    When("they register with an email address, a password and a currency", async () => {
      w.site.session.actions.register({ email: "new@example.com", password: "correct horse battery 1!", currency: "EUR" });
      await until(() => w.site.session.step().kind !== "registering");
    });
    Then("the session behaviour stands at awaiting verification", () => {
      expect(w.site.session.step()).toMatchObject({ kind: "awaiting_verification", email: "new@example.com" });
    });
    And("no player session opens", () => {
      expect(w.doubles.askedOf("signIn")).toEqual([]);
      expect(w.browser.names()).toEqual([]);
    });
  });

  Scenario("a page loaded by a signed-in player knows the player before it asks anything", ({ Given, When, Then, And }) => {
    let asked = 0;
    Given("the player is signed in at the player site", signIn);
    When("the page is loaded again", async () => {
      asked = w.doubles.asked.length;
      await w.reload();
    });
    Then("the page knows the player id, the brand, the currency and the country", () => {
      expect(w.site.session.step()).toMatchObject({ kind: "signed_in", player: { playerId: "p-ana", brand: "guyanabet", currency: "EUR", country: "GF" } });
    });
    And("no service was asked", () => {
      expect(w.doubles.asked.length).toBe(asked);
    });
  });

  Scenario("a request for anything the player SDK does not name is refused by the site server", ({ Given, When, Then, And }) => {
    let status = 0;
    Given("the player is signed in at the player site", signIn);
    When("the page asks the site server for an operator's route", async () => {
      const operator = { module: "core", id: "players", service: "players", method: "GET", path: "/v1/casino/backoffice/{brand}/players", audience: "player" } as const;
      const r = await w.transport.call(operator);
      status = r.ok ? 200 : r.refusal.status;
    });
    Then("the site server refuses the request", () => {
      expect(status).toBe(404);
      expect(w.answers.at(-1)!.body).toBe(JSON.stringify({ error: "not_named" }));
    });
    And("no service is asked", () => {
      expect(w.doubles.askedOf("players")).toEqual([]);
      // nothing but the sign-in and the funds' reads that follow it
      expect(w.doubles.asked.filter((a) => !["signIn", "balance", "freeRounds"].includes(a.id))).toEqual([]);
    });
  });

  Scenario('a site server refuses a request naming another brand', ({ When, Then, And }) => {
    let status = 0;
    When('the page asks the site server for a route of brand "goldvibe"', async () => {
      const games = { module: "casino", id: "games", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games", audience: "public", query: ["brand"] } as const;
      const r = await w.transport.call(games, { query: { brand: "goldvibe" } });
      status = r.ok ? 200 : r.refusal.status;
    });
    Then("the site server refuses the request", () => {
      expect(status).toBe(403);
    });
    And("no service is asked", () => {
      expect(w.doubles.asked).toEqual([]);
    });
  });
});
