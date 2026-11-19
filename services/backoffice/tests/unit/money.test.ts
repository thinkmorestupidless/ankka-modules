import { describe, expect, test } from "vitest";
import { decimalOf, exponentOf, formatMoney, parseMoney, sumMoney, UnknownCurrency, walletMinor, walletMinorText, walletText } from "../../src/shared/money.ts";

describe("minor units by the contract's table", () => {
  test.each([
    ["JPY", 0],
    ["EUR", 2],
    ["KWD", 3],
  ])("%s has %i minor units", (currency, e) => expect(exponentOf(currency)).toBe(e));

  test("a currency the table does not hold is refused, never guessed", () => {
    expect(() => exponentOf("XXZ")).toThrow(UnknownCurrency);
  });
});

describe("an amount's exact decimal", () => {
  test.each([
    [{ minor: "123456", currency: "JPY" }, "123456"],
    [{ minor: "123456", currency: "EUR" }, "1234.56"],
    [{ minor: "123456", currency: "KWD" }, "123.456"],
    [{ minor: "5", currency: "KWD" }, "0.005"],
    [{ minor: "-5", currency: "EUR" }, "-0.05"],
    [{ minor: 0, currency: "EUR" }, "0.00"],
  ])("%o is %s", (money, text) => expect(decimalOf(money)).toBe(text));

  test("beyond a float's precision, every digit is kept", () => {
    expect(decimalOf({ minor: "90071992547409931", currency: "EUR" })).toBe("900719925474099.31");
  });

  test("a non-integer minor amount is refused", () => {
    expect(() => decimalOf({ minor: "12.5", currency: "EUR" })).toThrow(RangeError);
    expect(() => decimalOf({ minor: 12.5, currency: "EUR" })).toThrow(RangeError);
  });
});

describe("an amount shown", () => {
  test.each([
    [{ minor: "123456", currency: "JPY" }, "JP¥123,456"],
    [{ minor: "123456", currency: "EUR" }, "€1,234.56"],
    [{ minor: "123456", currency: "KWD" }, "KWD 123.456"],
  ])("%o reads %s", (money, text) => expect(formatMoney(money).replace(/ /g, " ")).toBe(text));

  test("with every digit of a large amount", () => {
    expect(formatMoney({ minor: "90071992547409931", currency: "EUR" })).toBe("€900,719,925,474,099.31");
  });
});

describe("an amount typed", () => {
  test.each([
    ["1,234.56", "EUR", 123456n],
    ["1234", "JPY", 1234n],
    ["0.005", "KWD", 5n],
    ["12.5", "KWD", 12500n],
    ["-3.10", "EUR", -310n],
    // "," groups thousands, whatever the currency's decimals: 1,500 KWD is fifteen hundred, not 1.5
    ["1,500", "KWD", 1500000n],
  ])("%s in %s is %s minor", (text, currency, minor) => expect(parseMoney(text, currency)).toBe(minor));

  test.each([
    ["1.5", "JPY"],
    ["1.234", "EUR"],
    ["0.0001", "KWD"],
    ["abc", "EUR"],
    ["1e3", "EUR"],
  ])("%s in %s is refused", (text, currency) => expect(parseMoney(text, currency)).toBeUndefined());
});

describe("summing", () => {
  test("amounts in one currency", () => {
    expect(sumMoney([{ minor: "100", currency: "KWD" }, { minor: 5, currency: "KWD" }])).toEqual({ minor: 105n, currency: "KWD" });
  });

  test("never across currencies", () => {
    expect(() => sumMoney([{ minor: "100", currency: "EUR" }, { minor: "100", currency: "JPY" }])).toThrow(RangeError);
  });
});

describe("the wallet's amount text", () => {
  // The wallet writes major units by the currency's exponent (Wire.major, Amounts.render; spec 027 FR-058).
  test("is read by the currency's own count of decimals", () => {
    expect(walletMinor("12.50", "EUR")).toBe(1250n);
    expect(walletMinor("500", "JPY")).toBe(500n);
    expect(walletMinor("150000", "CLP")).toBe(150000n);
    expect(walletMinor("1.500", "KWD")).toBe(1500n);
    expect(walletMinor("-0.07", "EUR")).toBe(-7n);
    expect(walletMinorText("500", "JPY")).toBe("500");
  });

  test("shown in the currency, the figure is the wallet's", () => {
    expect(formatMoney({ minor: walletMinor("500", "JPY")!, currency: "JPY" })).toBe(formatMoney({ minor: 500n, currency: "JPY" }));
    expect(formatMoney({ minor: walletMinor("1.500", "KWD")!, currency: "KWD" })).toBe(formatMoney({ minor: 1500n, currency: "KWD" }));
  });

  test("another count of decimals is another currency's amount, not this one's", () => {
    for (const text of ["5", "5.0", "1.500", "12,50", "", " ", "abc", 500, null, undefined]) expect(walletMinor(text, "EUR")).toBeUndefined();
    for (const text of ["5.00", "500.0"]) expect(walletMinor(text, "JPY")).toBeUndefined();
    expect(walletMinor("1.50", "KWD")).toBeUndefined();
    expect(walletMinor("5.00", "XYZ")).toBeUndefined();
  });

  test("written back by the same rule, as the wallet's parse takes it", () => {
    expect(walletText(500n, "EUR")).toBe("5.00");
    expect(walletText(-1250n, "EUR")).toBe("-12.50");
    expect(walletText(500n, "JPY")).toBe("500");
    expect(walletText(-1250n, "KWD")).toBe("-1.250");
    for (const [minor, currency] of [[0n, "EUR"], [77n, "JPY"], [1234n, "KWD"], [-5n, "CLP"]] as const)
      expect(walletMinor(walletText(minor, currency), currency)).toBe(minor);
  });
});
