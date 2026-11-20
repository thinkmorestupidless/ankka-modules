// @vitest-environment jsdom
// A keyed command (constitution II): the key and the body first sent are kept while the outcome is not
// known, and only a settled answer takes a new key.
import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { settles, useKeyed } from "../../src/web/areas/corrections/shared.tsx";

describe("an answer that settles a keyed command", () => {
  test.each([200, 201, 400, 403, 404, 409, 422])("%i settles it", (status) => expect(settles(status)).toBe(true));
  test.each([408, 429, 500, 502, 503, 504])("%i leaves it unknown", (status) => expect(settles(status)).toBe(false));
});

describe("a command sent under a key", () => {
  type Body = { amount: number };
  const sent: { key: string; body: Body }[] = [];
  const answering = (status: number | Error) => async (key: string, body: Body) => {
    sent.push({ key, body });
    if (status instanceof Error) throw status;
    return new Response("{}", { status });
  };

  test("after a 502, a 504, a 500 or no answer, sends the first body again under the same key; a settled answer takes a new key", async () => {
    sent.length = 0;
    const { result } = renderHook(() => useKeyed<Body>("adjust"));
    const first = result.current.key;
    for (const status of [502, 504, 500]) await act(() => result.current.send({ amount: 100 }, answering(status)));
    await act(async () => {
      await result.current.send({ amount: 999 }, answering(new TypeError("fetch failed"))).catch(() => undefined);
    });
    expect(result.current.unknown).toBe(true);
    expect(result.current.key).toBe(first);
    await act(() => result.current.send({ amount: 999 }, answering(201)));
    expect(sent.map((s) => s.key)).toEqual([first, first, first, first, first]);
    expect(sent.every((s) => s.body.amount === 100)).toBe(true);
    expect(result.current.unknown).toBe(false);
    expect(result.current.key).not.toBe(first);
    await act(() => result.current.send({ amount: 999 }, answering(422)));
    expect(sent.at(-1)!.body.amount).toBe(999);
  });
});
