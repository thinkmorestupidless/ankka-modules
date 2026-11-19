import { describe, expect, expectTypeOf, it } from "vitest";
import { catalogue, chooseLanguage, type RefusalWords } from "../../src/language.ts";

describe("the page's language (FR-041, FR-042)", () => {
  it.each([
    ["en", null, "en"],
    ["de", null, "fr"],
    ["en", "fr", "fr"],
    ["fr", "en", "en"],
    ["en-GB", null, "en"],
    ["de", "es", "fr"],
  ])("browser %s, kept %s: %s", (browser, kept, language) => {
    expect(chooseLanguage(["fr", "en"], kept, browser)).toBe(language);
  });

  it("takes the first of the browser's languages the site offers", () => {
    expect(chooseLanguage(["fr", "en"], undefined, ["de-DE", "en-US", "fr"])).toBe("en");
  });

  it("a text missing in a language is shown in the site's first", () => {
    const words = catalogue({ language: "fr", words: { hello: "Bonjour", bye: "Au revoir" } }, { en: { hello: "Hello" } });
    expect(words.in("en")).toEqual({ hello: "Hello", bye: "Au revoir" });
    expect(words.in("fr").bye).toBe("Au revoir");
    expect(words.in("pt")).toEqual({ hello: "Bonjour", bye: "Au revoir" });
    expect(words.missing("en")).toEqual(["bye"]);
    expect(words.languages).toEqual(["fr", "en"]);
  });

  it("words for refusals are typed by every code", () => {
    expectTypeOf<RefusalWords["DEPOSIT_BLOCKED_KYC"]>().toEqualTypeOf<string>();
    // @ts-expect-error: a code the SDK does not know has no words
    expectTypeOf<RefusalWords["no_such_code"]>().toEqualTypeOf<string>();
  });
});
