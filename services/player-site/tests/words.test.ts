import { codesByBehaviour, refusalCodes } from "@eitheror/player-sdk";
import { describe, expect, it } from "vitest";
import { bind } from "../../../sdk/player/tests/features/setup.ts";
import { fill, languages, refusalWords, textWords, words } from "../src/words/index.ts";

const met = [...new Set(Object.values(codesByBehaviour).flat())];

// SC-006: amounts-and-refusals.feature's scenario on the reference site's words, bound here.
await bind(
  "amounts-and-refusals.feature",
  ({ Scenario }) => {
    Scenario("every refusal code a behaviour can meet has words in each language the reference site offers", ({ When, Then }) => {
      When("the reference site is built", () => expect(languages).toEqual(["fr", "en"]));
      Then('every refusal code the behaviours can meet has words in "fr" and in "en"', () => {
        for (const language of ["fr", "en"]) {
          const own = language === "fr" ? refusalWords.in("fr") : refusalWords.in("en");
          for (const code of met) expect(own[code]?.trim(), `${language}: ${code}`).toBeTruthy();
          expect(refusalWords.missing(language), language).toEqual([]);
        }
      });
    });
  },
  "every refusal code a behaviour can meet has words in each language the reference site offers",
);

describe("the reference site's words", () => {
  it("name every refusal code the SDK names, in both languages, each language's own", () => {
    for (const code of refusalCodes) {
      expect(refusalWords.in("fr")[code], code).toBeTruthy();
      expect(refusalWords.in("en")[code], code).toBeTruthy();
    }
    const same = refusalCodes.filter((c) => refusalWords.in("fr")[c] === refusalWords.in("en")[c]);
    expect(same).toEqual([]);
  });

  it("have every text in English as in French", () => expect(textWords.missing("en")).toEqual([]));

  it("write a refusal's fields in the page's language", () => {
    expect(words.refusal("over_limit", "fr", { remaining: 2000n })).toMatch(/^Ce dépôt dépasse votre limite : il vous reste 20,00\s€\.$/);
    expect(words.refusal("over_limit", "en", { remaining: 2000n })).toBe("This deposit is over your limit: you have €20.00 left.");
    expect(words.refusal("cooling_off_active", "en", { until: "2026-11-25T12:00:00Z" })).toMatch(/^You are taking a break until 25 November 2026/);
    expect(words.refusal("rate_limited", "fr", { retryAfterSeconds: 30 })).toBe("Trop de demandes. Réessayez dans 30 secondes.");
  });

  it("leave out a field that was not given, keeping French spacing", () => {
    expect(fill("Envoyez {cryptoAmount} {coin} à cette adresse :", { coin: "BTC" }, "fr")).toBe("Envoyez BTC à cette adresse :");
  });
});
