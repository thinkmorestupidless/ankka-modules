import { describe, expect, test } from "vitest";
import { decimalOf, exponentOf, formatMoney, parseMoney, sumMoney, UnknownCurrency } from "../../src/shared/money.ts";

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
