import { describe, expect, it } from "vitest";
import table from "../../src/currencies.v1.json" with { type: "json" };
import { exponent, format, fromMajor, majorText, readMajor } from "../../src/amount.ts";

const plain = (s: string) => s.replace(/[  ]/g, " ");

describe("amounts (FR-037, FR-038)", () => {
  it.each([
    ["1234.50", "EUR", 123450n],
    ["0.05", "EUR", 5n],
    ["10.00", "EUR", 1000n],
    ["100", "JPY", 100n],
    ["0.10", "USD", 10n],
    ["1.500", "KWD", 1500n],
    ["1.00", "JPY", 1n],
    ["-2.50", "EUR", -250n],
  ])("reads %s %s as %s minor units", (written, currency, minor) => {
    expect(readMajor(written, currency)).toEqual({ ok: true, amount: { minor, currency } });
  });

  it.each(["10.005", "ten", "1e3", "", "1.", ".5", "1,00", " 1 0", "0x10", "Infinity"])("refuses %j as malformed, never rounded", (written) => {
    expect(readMajor(written, "EUR")).toEqual({ ok: false, code: "malformed_amount", given: written });
  });

  it("refuses a currency that is no code", () => {
    expect(readMajor("1.00", "euro").ok).toBe(false);
  });

  it("reads an amount beyond 2^53 exactly", () => {
    expect(readMajor("92233720368547758.07", "EUR")).toEqual({ ok: true, amount: { minor: 9223372036854775807n, currency: "EUR" } });
  });

  it.each([
    ["fr", 123450n, "EUR", "1 234,50 €"],
    ["en", 123450n, "EUR", "€1,234.50"],
    ["fr", 100n, "JPY", "100 JPY"],
    ["pt", 123450n, "BRL", "R$ 1.234,50"],
    ["en", 5n, "EUR", "€0.05"],
    ["en", -250n, "EUR", "-€2.50"],
  ])("shows in %s %s minor units of %s as %s", (language, minor, currency, shown) => {
    expect(plain(format({ minor, currency }, language))).toBe(shown);
  });

  it("a site's figure is a decimal text or a whole number, never a fraction as a number", () => {
    expect(fromMajor(50, "EUR")).toEqual({ ok: true, amount: { minor: 5000n, currency: "EUR" } });
    expect(fromMajor("12.50", "EUR")).toEqual({ ok: true, amount: { minor: 1250n, currency: "EUR" } });
    expect(fromMajor(12.5, "EUR")).toEqual({ ok: false, code: "malformed_amount", given: "12.5" });
    expect(fromMajor(1000, "JPY")).toEqual({ ok: true, amount: { minor: 1000n, currency: "JPY" } });
  });

  it("every currency of the contract's table reads its own rendering back (SC-005)", () => {
    for (const [currency, exp] of Object.entries(table)) {
      expect(exponent(currency)).toBe(exp);
      for (const minor of [0n, 1n, 99n, 123456789n, 9007199254740993n]) {
        const text = majorText({ minor, currency });
        expect(text.split(".")[1]?.length ?? 0, `${currency} ${minor}`).toBe(exp);
        expect(readMajor(text, currency), `${currency} ${minor}`).toEqual({ ok: true, amount: { minor, currency } });
      }
    }
  });

  it("a code the table lacks has two decimals, as the services", () => {
    expect(exponent("QQQ")).toBe(2);
  });
});
