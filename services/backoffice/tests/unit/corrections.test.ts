// T086, T087: the corrections pages' own reading. The wallet's amount text read by its rule (major
// units by the currency's exponent); the batch's pasted rows checked line by line; the server's
// streamed lines read as they arrive.
import { describe, expect, test } from "vitest";
import { walletMinor, walletText } from "../../src/web/areas/corrections/shared.tsx";
import { parseRows, readStream, type Answered } from "../../src/web/areas/corrections/batch.tsx";
import { amountOf } from "../../src/web/areas/corrections/adjust.tsx";
import { profileCurrency } from "../../src/web/areas/players/read.ts";

describe("the wallet's amount text", () => {
  test.each([
    ["20.00", "EUR", 2000n],
    ["-12.50", "EUR", -1250n],
    ["0.00", "EUR", 0n],
    ["500", "JPY", 500n],
    ["1.500", "KWD", 1500n],
  ])("%s %s is %s minor units", (text, currency, minor) => {
    expect(walletMinor(text, currency)).toBe(minor);
    expect(walletText(minor, currency)).toBe(text);
  });

  test.each(["20", "20.5", "abc", 20])("%j is not the wallet's text in EUR", (v) => expect(walletMinor(v, "EUR")).toBeUndefined());

  test("an amount typed is above zero in the currency's own decimals", () => {
    expect(amountOf("5.00", "EUR")).toBe(500n);
    expect(amountOf("500", "JPY")).toBe(500n);
    expect(amountOf("5.5", "JPY")).toBeUndefined();
    expect(amountOf("1.234", "KWD")).toBe(1234n);
    expect(amountOf("0", "EUR")).toBeUndefined();
    expect(amountOf("-5", "EUR")).toBeUndefined();
  });
});

describe("the batch's rows", () => {
  const A = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";

  test("player, currency, amount and an optional balance; a negative amount is a debit; a header is skipped", () => {
    const { rows, errors } = parseRows(`player,currency,amount\n${A},EUR,5.00\n\n${A};JPY;-300;restricted\n`);
    expect(errors).toEqual([]);
    expect(rows).toEqual([
      { n: 1, player: A, currency: "EUR", walletType: "real", direction: "credit", amountMinor: 500n },
      { n: 2, player: A, currency: "JPY", walletType: "restricted", direction: "debit", amountMinor: 300n },
    ]);
  });

  test("each wrong line is named by its number", () => {
    const { rows, errors } = parseRows(`${A},EUR\n${A},QQQ,5\n${A},EUR,0\n${A},JPY,1.5\n${A},EUR,5,bonus\n../x,EUR,5\n${A},EUR,5`);
    expect(errors).toEqual([
      { line: 1, key: "corrections.batch.error.columns" },
      { line: 2, key: "corrections.batch.error.currency" },
      { line: 3, key: "corrections.batch.error.amount" },
      { line: 4, key: "corrections.batch.error.amount" },
      { line: 5, key: "corrections.batch.error.walletType" },
      { line: 6, key: "corrections.batch.error.player" },
    ]);
    expect(rows).toHaveLength(1);
  });
});

describe("the streamed answer", () => {
  test("each row as its line arrives, however the lines are cut, then the counts", async () => {
    const text = '{"row":1,"player":"a","currency":"EUR","outcome":"made","before":"0","after":"5"}\n{"row":2,"player":"b","currency":"EUR","outcome":"refused","reason":"X"}\n{"done":true,"made":1,"refused":1,"alreadyMade":0}\n';
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        for (const part of [text.slice(0, 30), text.slice(30, 95), text.slice(95)]) c.enqueue(encoder.encode(part));
        c.close();
      },
    });
    const rows: Answered[] = [];
    const done = await readStream(new Response(body), (a) => rows.push(a));
    expect(rows.map((r) => [r.row, r.outcome])).toEqual([[1, "made"], [2, "refused"]]);
    expect(done).toEqual({ done: true, made: 1, refused: 1, alreadyMade: 0 });
  });

  test("an answer cut before its counts has none", async () => {
    const done = await readStream(new Response('{"row":1,"player":"a","currency":"EUR","outcome":"made"}\n'), () => {});
    expect(done).toBeUndefined();
  });
});

describe("the player's currency an adjustment is in", () => {
  test("is the player's own, as players answers the profile", () => {
    expect(profileCurrency({ player: { playerId: "p-ana", currency: "BRL" }, wallet: { currency: "EUR" }, activeSessions: [], pinnedNotes: [] })).toBe("BRL");
  });

  test("is the wallet's when the player's is blank or missing", () => {
    expect(profileCurrency({ player: { playerId: "p-ana", currency: "" }, wallet: { realBalance: "0.00", currency: "BRL" } })).toBe("BRL");
    expect(profileCurrency({ player: { playerId: "p-ana" }, wallet: { currency: "KWD" } })).toBe("KWD");
  });

  test("is read from an answer of the player alone", () => expect(profileCurrency({ playerId: "p-ana", currency: "JPY" })).toBe("JPY"));

  test.each([[{ player: { currency: "" }, wallet: null }], [{}], [null], ["BRL"]])("is none in %j", (body) => expect(profileCurrency(body)).toBeUndefined());
});
