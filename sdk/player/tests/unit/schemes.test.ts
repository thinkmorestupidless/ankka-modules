import { describe, expect, it } from "vitest";
import { amount } from "../../src/amount.ts";
import { CANADIAN_PROVINCES, checkDetails, coinNetwork, composePhone, need, presetAmounts, sharingScheme, splitPhone, toCanadianProvinceCode } from "../../src/core/schemes.ts";

// Ported from the original: components/cashier/DepositModal.test.ts (the phone cases).
describe("composePhone", () => {
  it("joins dial code and national number into E.164", () => {
    expect(composePhone("+44", "7700 900000")).toBe("+447700900000");
    expect(composePhone("44", "7700-900-000")).toBe("+447700900000");
  });

  it("drops the national trunk zero", () => {
    // Players type their number the way they dial it locally; UF needs the international form.
    expect(composePhone("+44", "07700 900000")).toBe("+447700900000");
  });

  it("rejects a missing dial code or an implausible number", () => {
    expect(composePhone("", "7700900000")).toBeNull();
    expect(composePhone("+44", "12")).toBeNull();
    expect(composePhone("+4412", "7700900000")).toBeNull();
    expect(composePhone("+44", "77009000001234567")).toBeNull();
    // 16 digits is E.164-legal in total but no real number: a prefix we failed to recognise.
    expect(composePhone("+351", "1234966209255")).toBeNull();
  });

  it("drops a dial code the player typed again in the national box", () => {
    // Production had +351351966209255 and +420420704249940 stored this way, and UnityFinance
    // rejected every deposit for those players.
    expect(composePhone("+351", "351 966 209 255")).toBe("+351966209255");
    expect(composePhone("+351", "+351966209255")).toBe("+351966209255");
    expect(composePhone("+420", "420704249940")).toBe("+420704249940");
    // A national number that merely starts with the same digits is left alone.
    expect(composePhone("+1", "1234567")).toBe("+11234567");
  });
});

describe("splitPhone", () => {
  it("takes the dial code from the number, not from the IP lookup", () => {
    expect(splitPhone("+351966209255", "+44")).toEqual({ dialCode: "+351", national: "966209255" });
    expect(splitPhone("0035796620925", "+44")).toEqual({ dialCode: "+357", national: "96620925" });
  });

  it("repairs a stored doubled dial code on the way into the form", () => {
    expect(splitPhone("+351351966209255", "+351")).toEqual({ dialCode: "+351", national: "966209255" });
  });

  it("recognises an older digits-only save that already carries the player's dial code", () => {
    expect(splitPhone("420704249940", "+420")).toEqual({ dialCode: "+420", national: "704249940" });
    // Without a usable hint the value is shown as-is for the player to fix.
    expect(splitPhone("704249940", "+420")).toEqual({ dialCode: "+420", national: "704249940" });
    expect(splitPhone("", "+420")).toEqual({ dialCode: "+420", national: "" });
  });

  it("recognises a foreign international number saved without a marker", () => {
    expect(splitPhone("447402355827", "+358")).toEqual({ dialCode: "+44", national: "7402355827" });
    // Too short to be international: left for the player.
    expect(splitPhone("99303125", "+357")).toEqual({ dialCode: "+357", national: "99303125" });
  });

  it("handles a trunk zero followed by the dial code again", () => {
    expect(splitPhone("+490491791627745", "+49")).toEqual({ dialCode: "+49", national: "1791627745" });
    expect(composePhone("+49", "0491791627745")).toBe("+491791627745");
  });
});

// Ported from the original: lib/canadianProvinces.test.ts.
describe("toCanadianProvinceCode", () => {
  it("accepts a code in any case", () => {
    expect(toCanadianProvinceCode("on")).toBe("ON");
    expect(toCanadianProvinceCode(" bc ")).toBe("BC");
  });

  it("maps the English name a player typed into the old free-text field", () => {
    // Production 2026-11-12: a Canadian player's stored stateProv went to EuPaymentz verbatim
    // and was refused with "must have 2 or 3 caracters".
    expect(toCanadianProvinceCode("Ontario")).toBe("ON");
    expect(toCanadianProvinceCode("british  columbia")).toBe("BC");
    expect(toCanadianProvinceCode("Québec")).toBe("QC");
  });

  it("is null for anything that is not a Canadian province", () => {
    expect(toCanadianProvinceCode("")).toBeNull();
    expect(toCanadianProvinceCode(null)).toBeNull();
    expect(toCanadianProvinceCode("England")).toBe(null);
    expect(toCanadianProvinceCode("CA")).toBeNull();
  });

  it("has every code exactly once and all 13 subdivisions", () => {
    const codes = CANADIAN_PROVINCES.map((p) => p.code);
    expect(new Set(codes).size).toBe(13);
    expect(codes).toHaveLength(13);
  });
});

// Ported from the original: lib/schemeUtils.test.ts, the cases that are rules and not words.
describe("coinNetwork", () => {
  it("returns the network for multi-network stablecoins", () => {
    expect(coinNetwork("USDT")).toBe("ERC-20");
    expect(coinNetwork("USDTRX")).toBe("TRC-20");
    expect(coinNetwork("USDC")).toBe("ERC-20");
  });
  it("returns undefined for single-network coins and for what is no coin", () => {
    for (const c of ["BTC", "ETH", "LTC", "SOL", "VISA", "UNKNOWN"]) expect(coinNetwork(c)).toBeUndefined();
  });
});

describe("sharingScheme (disambiguatedTerminalLabels)", () => {
  it("names no terminal when every terminal's scheme is unique", () => {
    expect(sharingScheme([{ id: "t1", scheme: "CARD_VISA" }, { id: "t2", scheme: "OPEN_BANKING" }])).toEqual(new Set());
  });
  it("names only the terminals sharing a scheme with another terminal", () => {
    expect(sharingScheme([{ id: "t1", scheme: "OPEN_BANKING" }, { id: "t2", scheme: "OPEN_BANKING" }, { id: "t3", scheme: "CARD_VISA" }])).toEqual(new Set(["t1", "t2"]));
  });
  it("names none whose scheme is missing", () => {
    expect(sharingScheme([{ id: "t1", scheme: "" }, { id: "t2", scheme: "" }])).toEqual(new Set());
  });
});

// Ported from the original: components/cashier/DepositModal.test.ts (resolvePresetAmounts), in minor units.
describe("presetAmounts", () => {
  const defaults = [10, 25, 50, 100, 200, 500];
  const eur = (...xs: number[]) => xs.map((x) => amount(BigInt(x) * 100n, "EUR"));
  it("returns the site's presets", () => {
    expect(presetAmounts([50, 100, 200], "EUR")).toEqual(eur(50, 100, 200));
  });
  it("drops presets outside the minimum and maximum", () => {
    expect(presetAmounts(defaults, "EUR", { minimum: 50, maximum: 200 })).toEqual(eur(50, 100, 200));
  });
  it("drops presets below the minimum", () => {
    expect(presetAmounts([10, 50, 100], "EUR", { minimum: 20 })).toEqual(eur(50, 100));
  });
  it("falls back to the minimum when no preset is within the bounds", () => {
    expect(presetAmounts(defaults, "EUR", { minimum: 1000 })).toEqual(eur(1000));
  });
  it("reads presets by the currency's exponent", () => {
    expect(presetAmounts(["12.50", 1000], "JPY")).toEqual([amount(1000n, "JPY")]);
    expect(presetAmounts(["12.50"], "EUR")).toEqual([amount(1250n, "EUR")]);
  });
});

// The table of contracts/behaviours.md, from handleTerminalSelect and handleDetailsSubmit.
describe("need", () => {
  const t = (scheme: string, currencies?: string[]) => ({ id: "t", name: "", scheme, ...(currencies ? { currencies } : {}) });
  const full = { firstName: "Ana", lastName: "Silva", phone: "+594694000000", addressLine1: "1 rue", stateProv: "973", country: "GF", mobile: "4165550123", province: "ON" };
  it("a crypto terminal asks for a coin of the terminal's own", () => {
    expect(need(t("CRYPTO", ["BTC", "USDT"]), null, "GF")).toEqual({ kind: "coin", coins: ["BTC", "USDT"] });
    expect(need(t("CRYPTO"), null, "GF")).toEqual({ kind: "coin", coins: [] });
  });
  it("any other scheme needs nothing", () => {
    expect(need(t("PIX"), null, "BR")).toEqual({ kind: "none" });
  });
  it("a wallet needs the names the profile lacks", () => {
    expect(need(t("SKRILL"), { firstName: "Ana" }, "GF")).toEqual({ kind: "details", needs: ["lastName"], known: { firstName: "Ana" } });
    expect(need(t("NETELLER"), full, "GF")).toEqual({ kind: "none" });
  });
  it("Apple Pay needs a telephone in international form", () => {
    expect(need(t("APPLE_PAY"), { ...full, phone: "0694000000" }, "GF")).toMatchObject({ kind: "details", needs: ["phone"] });
    expect(need(t("GOOGLE_PAY"), full, "GF")).toEqual({ kind: "none" });
  });
  it("a card needs the telephone, the address and the state, a Canadian's as a province code", () => {
    expect(need(t("VISA"), { firstName: "Ana", lastName: "Silva", stateProv: "973" }, "GF")).toMatchObject({ kind: "details", needs: ["phone", "address"] });
    expect(need(t("CARD_MASTERCARD"), { ...full, country: "CA", stateProv: "Ontario" }, "CA")).toEqual({ kind: "none" });
    expect(need(t("CARD_MASTERCARD"), { ...full, country: "CA", stateProv: "England" }, "CA")).toMatchObject({ kind: "details", needs: ["state"] });
  });
  it("Interac needs a 10-digit mobile and a province, and submits them when the profile holds them", () => {
    expect(need(t("INTERAC_ETRANSFER"), { firstName: "A", lastName: "B", mobile: "123" }, "CA")).toMatchObject({ kind: "details", needs: ["mobile", "province"] });
    expect(need(t("INTERAC_ETRANSFER"), full, "CA")).toEqual({ kind: "none", details: { mobile: "4165550123", province: "ON" } });
  });
});

describe("checkDetails", () => {
  it("refuses what a scheme needs and was not given, by name", () => {
    expect(checkDetails("SKRILL", { firstName: " " }, "GF")).toEqual({ ok: false, invalid: ["firstName", "lastName"] });
    expect(checkDetails("VISA", { firstName: "A", lastName: "B", phone: "12" }, "GF")).toEqual({ ok: false, invalid: ["phone", "address", "state"] });
    expect(checkDetails("INTERAC_ETRANSFER", { firstName: "A", lastName: "B", mobile: "(416) 555-01", province: "O" }, "CA")).toEqual({ ok: false, invalid: ["mobile", "province"] });
  });
  it("composes the telephone with the dial code given, else the country's", () => {
    expect(checkDetails("APPLE_PAY", { firstName: "A", lastName: "B", phone: "0694 00 00 00" }, "GF")).toEqual({ ok: false, invalid: ["phone"] });
    expect(checkDetails("APPLE_PAY", { firstName: "A", lastName: "B", phone: "06 94 00 00 00", dialCode: "+594" }, "GF")).toEqual({
      ok: true, profile: { firstName: "A", lastName: "B", phone: "+594694000000" }, submit: { firstName: "A", lastName: "B", phone: "+594694000000" },
    });
    expect(checkDetails("GOOGLE_PAY", { firstName: "A", lastName: "B", phone: "07700 900000" }, "GB")).toMatchObject({ ok: true, submit: { phone: "+447700900000" } });
  });
  it("saves a card's details under the profile's names and submits them under the cashier's", () => {
    expect(checkDetails("VISA", { firstName: " Ana ", lastName: "Silva", phone: "+594694000000", address: "1 rue", state: "Québec", city: "", postCode: "97300" }, "CA")).toEqual({
      ok: true,
      profile: { firstName: "Ana", lastName: "Silva", phone: "+594694000000", addressLine1: "1 rue", stateProv: "QC", postalCode: "97300" },
      submit: { firstName: "Ana", lastName: "Silva", phone: "+594694000000", address: "1 rue", postCode: "97300", state: "QC" },
    });
  });
  it("Interac's mobile is digits and its province upper case", () => {
    expect(checkDetails("INTERAC_ETRANSFER", { firstName: "A", lastName: "B", mobile: "(416) 555-0123", province: "on" }, "CA")).toMatchObject({ ok: true, submit: { mobile: "4165550123", province: "ON" } });
  });
});
