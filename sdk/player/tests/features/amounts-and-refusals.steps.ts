import { expect } from "vitest";
import { amount, format, type Amount } from "../../src/amount.ts";
import type { Result } from "../../src/client.ts";
import { routes as coreRoutes } from "../../src/core/routes.ts";
import { catalogue, chooseLanguage } from "../../src/language.ts";
import type { Refusal } from "../../src/refusal.ts";
import { bind, world, type World } from "./setup.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
let w: World;
let currency = "EUR";
let read: Result<{ real: Amount }> | undefined;
let refused: Refusal | undefined;
let language = "fr";

const signedIn = async () => {
  w = await world();
  w.site.session.actions.signIn(ana);
  await w.clock.settle();
};
const writes = (written: string) => {
  w.doubles.brand.balance = { real: written, bonus: "0" };
};
const readBalance = async () => {
  read = await w.site.core.balance(currency);
  await w.site.funds.read();
};
/** A call refused by the scripted reply, read as the page reads it. */
const refuse = async (body: unknown, status = 403) => {
  await signedIn();
  w.doubles.script("profile", { status, body });
  const r = await w.transport.call(coreRoutes.find((x) => x.id === "profile")!, { path: { brand: "guyanabet", player: "p-ana" } });
  refused = r.ok ? undefined : r.refusal;
};
/** Intl writes narrow and no-break spaces; a shown amount is compared with every kind of space alike. */
const spaces = (s: string) => s.replace(/[\s  ]/g, " ");

const writers: Record<string, { status: number; body: unknown; code: string; fields: Record<string, unknown> }> = {
  "the platform": { status: 403, body: { status: 403, error: "player_id_mismatch: the token is another player's", details: { player: "p-other" } }, code: "player_id_mismatch", fields: { player: "p-other" } },
  "a service that writes words beside its code": { status: 403, body: { error: "cooling_off_active", message: "You are on a break until Friday", coolingOffUntil: "2026-11-25T12:00:00Z" }, code: "cooling_off_active", fields: { until: "2026-11-25T12:00:00Z" } },
  "a service that writes only its code": { status: 429, body: { error: "RATE_LIMITED", retryAfterSeconds: 30 }, code: "RATE_LIMITED", fields: { retryAfterSeconds: 30 } },
};

await bind("amounts-and-refusals.feature", ({ Scenario, ScenarioOutline }) => {
  ScenarioOutline("a balance the wallet writes in major units is read exactly as minor units", ({ Given, When, Then }, v) => {
    Given('the wallet writes a real balance of "<written>" in "<currency>"', async () => {
      await signedIn();
      currency = v.currency as string;
      writes(v.written as string);
    });
    When("the player SDK reads the balance", readBalance);
    Then('the page is given "<minor>" minor units of "<currency>"', () => {
      expect(read).toEqual({ ok: true, value: expect.objectContaining({ real: amount(BigInt(v.minor as string), currency) }) });
      if (currency === "EUR") expect(w.site.funds.current().real).toEqual(amount(BigInt(v.minor as string), "EUR"));
    });
  });

  ScenarioOutline("a balance that does not fit its currency is refused as malformed and never rounded", ({ Given, When, Then, And }, v) => {
    Given('the wallet writes a real balance of "<written>" in "EUR"', async () => {
      await signedIn();
      currency = "EUR";
      writes(v.written as string);
      w.site.funds.player(null); // forget the balance read at sign-in: only this one is read
      w.site.funds.player("EUR");
      await w.clock.settle();
    });
    When("the player SDK reads the balance", readBalance);
    Then('the read is refused with the code "malformed_amount"', () => {
      expect(read).toEqual({ ok: false, refusal: { status: 200, code: "malformed_amount", fields: {}, service: "wallet" } });
    });
    And("the page is given no balance", () => expect(w.site.funds.current().real).toBeNull());
  });

  ScenarioOutline("an amount is shown by the page's language and the currency's own decimals", ({ Given, When, Then }, v) => {
    let shown = "";
    Given('the page\'s language is "<language>"', () => {
      language = v.language as string;
    });
    When('"<minor>" minor units of "<currency>" are shown', () => {
      shown = format(amount(BigInt(v.minor as string), v.currency as string), language);
    });
    Then('the page shows "<shown>"', () => expect(spaces(shown)).toBe(spaces(v.shown as string)));
  });

  ScenarioOutline("a refusal reaches the page in one form whichever service wrote it", ({ Given, When, Then }, v) => {
    const wr = () => writers[v.writer as string]!;
    Given("a service refuses a call in the form of <writer>", () => {});
    When("the refusal reaches the page", () => refuse(wr().body, wr().status));
    Then("it has a status, a code and named fields", () => {
      expect(refused).toEqual({ status: wr().status, code: wr().code, fields: wr().fields });
      expect(JSON.stringify(refused)).not.toMatch(/break until Friday|another player's/);
    });
  });

  Scenario("a refusal the platform writes as a code and an explanation is read as its code", ({ Given, When, Then }) => {
    Given('the platform refuses a call as "wrong_brand: this is brand guyanabet"', () => {});
    When("the refusal reaches the page", () => refuse({ status: 403, error: "wrong_brand: this is brand guyanabet" }));
    Then('its code is "wrong_brand"', () => expect(refused).toMatchObject({ status: 403, code: "wrong_brand" }));
  });

  Scenario("a refusal with a code the player SDK does not know is the unknown refusal and the service's text is not the player's", ({ Given, When, Then, And }) => {
    Given("a service refuses a call with a code the player SDK does not know", () => {});
    When("the refusal reaches the page", () => refuse({ error: "QUANTUM_FLUX", message: "the flux capacitor is cold" }, 418));
    Then('its code is "unknown" with the status the service answered', () => expect(refused).toEqual({ status: 418, code: "unknown", fields: {} }));
    And("the page is given no text of the service's", () => expect(JSON.stringify(refused)).not.toMatch(/QUANTUM|flux/));
  });

  ScenarioOutline("the page's language is the visitor's choice, else the browser's, else the site's first", ({ Given, And, When, Then }, v) => {
    let chosen = "";
    Given('a player site offering "fr" then "en"', () => {});
    And('a visitor whose browser asks for "<browser>" and whose kept choice is "<choice>"', () => {});
    When("they arrive", () => {
      chosen = chooseLanguage(["fr", "en"], v.choice === "none" ? null : (v.choice as string), [v.browser as string]);
    });
    Then('the page\'s language is "<language>"', () => expect(chosen).toBe(v.language));
  });

  Scenario("a text missing in a language is shown in the site's first language", ({ Given, When, Then }) => {
    let words: ReturnType<typeof catalogue<{ deposit: string; play: string }>>;
    let shown = "";
    Given('a player site offering "fr" then "en" whose "en" words lack one text', () => {
      words = catalogue({ language: "fr", words: { deposit: "Déposer", play: "Jouer" } }, { en: { deposit: "Deposit" } });
      expect(words.missing("en")).toEqual(["play"]);
    });
    When('the page shows that text in "en"', () => {
      shown = words.in("en").play;
    });
    Then('the "fr" words are shown', () => expect(shown).toBe("Jouer"));
  });
});
