// T085: an adjustment batch. One wallet adjustment per row under `batch/<id>/<row>`, the id the same for
// the same reason and cause, the row's the same for what it adjusts; eight at a time, at most 500 rows;
// each row's outcome streamed. A repeat makes none twice (the wallet answers a key its first outcome),
// an edited list included; a player twice is two rows.
import { describe, expect, test } from "vitest";
import { batchId, keyOf, readBatch, rowIds, type Batch } from "../../src/server/composite/batch.ts";
import { table } from "../../src/server/routes.ts";
import { Brand, harness, write, type Call } from "./support.ts";

const A = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";
const B = "7f0c2a55-3d1e-4b8a-9c41-0a6f5e2d9b13";
const C = "c3b1f4e2-8a7d-4e19-b0c5-2f9d6a1e7c48";

const Key = "0b7c2f8e-4d1a-4c3e-9f2a-6e5d4c3b2a10";
const body = (rows: unknown[], over: Record<string, unknown> = {}) =>
  JSON.stringify({ key: Key, reason: "outage on 15 November", category: "compensation", cause: { kind: "incident", ref: "INC-1115" }, rows, ...over });
const row = (player: string, amountMinor: number | string = 500, over: Record<string, unknown> = {}) => ({ player, currency: "EUR", walletType: "real", direction: "credit", amountMinor, ...over });

/** The wallet as the batch asks it: balances in its major text, each key's first outcome kept, a repeat marked `repeated`. */
function wallet(start: Record<string, number>) {
  const balances = new Map(Object.entries(start));
  const keys = new Map<string, { status: number; body: Record<string, unknown> }>();
  let inFlight = 0;
  let most = 0;
  const major = (m: number) => (m / 100).toFixed(2);
  const answer = async (call: Call): Promise<Response> => {
    const m = /\/wallets\/([^/]+)\/([^/]+)\/(balance|adjustments)$/.exec(new URL(call.url).pathname);
    if (!m) return Response.json({ error: "not_found" }, { status: 404 });
    const player = m[1]!;
    inFlight++;
    most = Math.max(most, inFlight);
    await new Promise((r) => setTimeout(r, 5));
    inFlight--;
    const now = balances.get(player) ?? 0;
    const r = JSON.parse(call.body) as { key: string; amountMinor: number };
    const first = keys.get(r.key);
    if (first) return Response.json(first.status === 200 ? { ...first.body, realBalance: major(balances.get(player)!), repeated: true } : first.body, { status: first.status });
    const after = now + r.amountMinor;
    if (after < 0) {
      const refused = { error: "DEBIT_EXCEEDS_BALANCE", message: `Requested debit of ${-r.amountMinor} exceeds current real balance of ${now}` };
      keys.set(r.key, { status: 400, body: refused });
      return Response.json(refused, { status: 400 });
    }
    balances.set(player, after);
    const out = { realBalance: major(after), bonusBalance: "0.00", currency: "EUR", txnId: `t-${r.key}`, walletType: "real", amountMinor: r.amountMinor, before: now, after, repeated: false };
    keys.set(r.key, { status: 200, body: out });
    return Response.json(out);
  };
  return { answer, balances, keys, most: () => most };
}

async function run(h: ReturnType<typeof harness>, text: string) {
  const r = await h.ask("POST", "/api/adjustment-batches", { session: "s-1", headers: write, body: text });
  const lines = (await r.text()).trim().split("\n").filter((l) => l !== "").map((l) => JSON.parse(l) as Record<string, unknown>);
  return { status: r.status, type: r.headers.get("content-type"), lines, rows: lines.filter((l) => "row" in l), done: lines.at(-1)! };
}

const adjustCalls = (h: ReturnType<typeof harness>) => h.calls.filter((c) => c.method === "POST" && c.url.endsWith("/adjustments"));

describe("the batch's id and keys", () => {
  const b = (readBatch(JSON.parse(body([row(A), row(B)]))) as { batch: Batch }).batch;

  test("the same key, reason, category and cause are the same batch; a change to one is another", () => {
    expect(batchId(b)).toBe(batchId(structuredClone(b)));
    expect(batchId(b)).toMatch(/^[0-9a-f]{64}$/);
    expect(batchId({ ...b, reason: "another" })).not.toBe(batchId(b));
    expect(batchId({ ...b, category: "goodwill" })).not.toBe(batchId(b));
    expect(batchId({ ...b, cause: undefined })).not.toBe(batchId(b));
    const fewer: Batch = { ...b, rows: [b.rows[1]!] };
    expect(batchId(fewer)).toBe(batchId(b));
    expect(batchId({ ...b, key: "5f0e1d2c-3b4a-4968-8776-655443322110" })).not.toBe(batchId(b));
  });

  test("a batch without its key, or with one not a UUID, is refused", () => {
    expect(readBatch(JSON.parse(body([row(A)], { key: undefined })))).toMatchObject({ error: expect.stringContaining("key") });
    expect(readBatch(JSON.parse(body([row(A)], { key: "batch-1" })))).toMatchObject({ error: expect.stringContaining("key") });
  });

  test("a row's id is what it adjusts and which of its identical rows it is, wherever it stands", () => {
    const [a, c] = rowIds(b.rows);
    expect(rowIds([b.rows[1]!, b.rows[0]!])).toEqual([c, a]);
    expect(rowIds([b.rows[0]!, { ...b.rows[1]!, amountMinor: 501 }])[0]).toBe(a);
    expect(rowIds([b.rows[0]!, { ...b.rows[1]!, amountMinor: 501 }])[1]).not.toBe(c);
    const twice = rowIds([b.rows[0]!, b.rows[0]!]);
    expect(twice[0]).toBe(a);
    expect(twice[1]).not.toBe(a);
  });

  test("a row's key is the batch and the row, within the wallet's 255 characters", () => {
    expect(keyOf("abc", "def")).toBe("batch/abc/def");
    expect(keyOf(batchId(b), rowIds(b.rows)[0]!).length).toBeLessThanOrEqual(255);
  });

  test.each([
    [{ reason: " " }, "reason"],
    [{ category: "Compensation!" }, "category"],
    [{ cause: { kind: "whim", ref: "x" } }, "cause"],
    [{ cause: { kind: "round", ref: "" } }, "cause"],
  ])("a batch with %j is refused", (over, word) => {
    const r = readBatch(JSON.parse(body([row(A)], over)));
    expect("error" in r && r.error).toContain(word);
  });

  test.each([
    [row(A, 0), "amount"],
    [row(A, -5), "amount"],
    [row(A, 1.5), "amount"],
    [row(A, 500, { currency: "eur" }), "currency"],
    [row(A, 500, { walletType: "bonus" }), "real or restricted"],
    [row(A, 500, { direction: "up" }), "credit or a debit"],
    [row("../x"), "player"],
  ])("a row %j is refused with its number", (r, word) => {
    const read = readBatch(JSON.parse(body([row(B), r])));
    expect(read).toMatchObject({ row: 2 });
    expect("error" in read && read.error).toContain(word);
  });

  test("an amount sent as digits is read as minor units", () => {
    expect(readBatch(JSON.parse(body([row(A, "1250")])))).toMatchObject({ batch: { rows: [{ amountMinor: 1250 }] } });
  });
});

describe("POST /api/adjustment-batches", () => {
  test("each row is one adjustment under its own key, as the operator, each outcome streamed with before and after", async () => {
    const h = harness({ table });
    const w = wallet({ [A]: 2000, [B]: 100 });
    h.answer(w.answer);
    const r = await run(h, body([row(A), row(B, 300, { direction: "debit" })]));
    expect(r.status).toBe(200);
    expect(r.type).toContain("application/x-ndjson");
    const id = r.done.batch as string;
    const [rowA, rowB] = rowIds([
      { player: A, currency: "EUR", walletType: "real", direction: "credit", amountMinor: 500 },
      { player: B, currency: "EUR", walletType: "real", direction: "debit", amountMinor: 300 },
    ]);
    expect(r.rows).toEqual(
      expect.arrayContaining([
        { row: 1, player: A, currency: "EUR", outcome: "made", before: "2000", after: "2500" },
        expect.objectContaining({ row: 2, player: B, outcome: "refused", reason: "DEBIT_EXCEEDS_BALANCE", status: 400 }),
      ]),
    );
    expect(r.done).toEqual({ done: true, batch: id, made: 1, refused: 1, alreadyMade: 0 });
    const sent = adjustCalls(h).map((c) => JSON.parse(c.body) as Record<string, unknown>);
    expect(sent.map((s) => s.key).sort()).toEqual([keyOf(id, rowA!), keyOf(id, rowB!)].sort());
    expect(sent.find((s) => s.key === keyOf(id, rowA!))).toEqual({
      key: keyOf(id, rowA!), walletType: "real", amountMinor: 500, reason: "outage on 15 November", category: "compensation", cause: { kind: "incident", ref: "INC-1115" },
    });
    expect(sent.find((s) => s.key === keyOf(id, rowB!))!.amountMinor).toBe(-300);
    for (const c of h.calls) expect(c.headers.get("authorization")).toBe("Bearer eyJ.secret-access-token.sig");
    expect(adjustCalls(h)[0]!.url).toBe(`http://services.local/wallet/v1/casino/backoffice/goldvibe/wallets/${A}/EUR/adjustments`);
  });

  test("a refusal names the wallet's code and words", async () => {
    const h = harness({ table });
    h.answer(wallet({ [A]: 100 }).answer);
    const r = await run(h, body([row(A, 500, { direction: "debit" })]));
    expect(r.rows).toEqual([
      { row: 1, player: A, currency: "EUR", outcome: "refused", reason: "DEBIT_EXCEEDS_BALANCE", message: "Requested debit of 500 exceeds current real balance of 100", status: 400 },
    ]);
  });

  test("a repeat makes none twice: every made row is already made, a refused row refused again", async () => {
    const h = harness({ table });
    const w = wallet({ [A]: 1000, [B]: 1000, [C]: 100 });
    h.answer(w.answer);
    const text = body([row(A), row(B), row(C, 500, { direction: "debit" })]);
    const first = await run(h, text);
    expect(first.done).toMatchObject({ made: 2, refused: 1, alreadyMade: 0 });
    const again = await run(h, text);
    expect(again.done).toMatchObject({ batch: first.done.batch, made: 0, refused: 1, alreadyMade: 2 });
    expect(again.rows.filter((x) => x.outcome === "already_made").map((x) => [x.before, x.after])).toEqual([["1000", "1500"], ["1000", "1500"]]);
    expect(w.balances.get(A)).toBe(1500);
    expect(w.balances.get(B)).toBe(1500);
    expect(new Set(adjustCalls(h).map((c) => (JSON.parse(c.body) as { key: string }).key)).size).toBe(3);
  });

  test("a list cut short, one refused row corrected and sent again, makes only that row: the rest are already made", async () => {
    const h = harness({ table });
    const w = wallet({ [A]: 1000, [B]: 1000, [C]: 100 });
    h.answer(w.answer);
    const first = await run(h, body([row(A), row(B), row(C, 500, { direction: "debit" })]));
    expect(first.done).toMatchObject({ made: 2, refused: 1 });
    // the operator corrects C's row and moves it first
    const again = await run(h, body([row(C, 100, { direction: "debit" }), row(A), row(B)]));
    expect(again.done).toMatchObject({ batch: first.done.batch, made: 1, refused: 0, alreadyMade: 2 });
    expect(w.balances.get(A)).toBe(1500);
    expect(w.balances.get(B)).toBe(1500);
    expect(w.balances.get(C)).toBe(0);
  });

  test("a player twice is two rows under two keys, both made", async () => {
    const h = harness({ table });
    const w = wallet({ [A]: 1000 });
    h.answer(w.answer);
    const r = await run(h, body([row(A), row(A)]));
    expect(r.done).toMatchObject({ made: 2, refused: 0, alreadyMade: 0 });
    expect(r.rows.map((x) => x.player)).toEqual([A, A]);
    expect(new Set(adjustCalls(h).map((c) => (JSON.parse(c.body) as { key: string }).key)).size).toBe(2);
    expect(w.balances.get(A)).toBe(2000);
  });

  test("at most eight rows are asked at once", async () => {
    const h = harness({ table });
    const players = Array.from({ length: 30 }, (_, i) => `p-${i}`);
    const w = wallet(Object.fromEntries(players.map((p) => [p, 0])));
    h.answer(w.answer);
    const r = await run(h, body(players.map((p) => row(p))));
    expect(r.done).toMatchObject({ made: 30 });
    expect(w.most()).toBeLessThanOrEqual(8);
    expect(w.most()).toBeGreaterThan(1);
  });

  test("a page that goes stops the batch: no row is asked after it", async () => {
    const h = harness({ table });
    const players = Array.from({ length: 40 }, (_, i) => `p-${i}`);
    const w = wallet(Object.fromEntries(players.map((p) => [p, 0])));
    h.answer(w.answer);
    const r = await h.ask("POST", "/api/adjustment-batches", { session: "s-1", headers: write, body: body(players.map((p) => row(p))) });
    const reader = r.body!.getReader();
    await reader.read();
    await reader.cancel();
    const asked = adjustCalls(h).length;
    await new Promise((res) => setTimeout(res, 100));
    // the eight in flight finish at the wallet; none is started after
    expect(adjustCalls(h).length).toBeLessThanOrEqual(asked + 8);
    expect(adjustCalls(h).length).toBeLessThan(40);
  });

  test("at most 500 rows", async () => {
    const h = harness({ table });
    const r = await h.ask("POST", "/api/adjustment-batches", { session: "s-1", headers: write, body: body(Array.from({ length: 501 }, () => row(A))) });
    expect(r.status).toBe(400);
    expect(await r.json()).toMatchObject({ error: "invalid_batch" });
    expect(h.calls).toHaveLength(0);
  });

  test("already made is the wallet's word: a repeat whose balance has moved since is still already made", async () => {
    const h = harness({ table });
    const w = wallet({ [A]: 1000 });
    h.answer(w.answer);
    const text = body([row(A)]);
    expect((await run(h, text)).done).toMatchObject({ made: 1, alreadyMade: 0 });
    w.balances.set(A, 4000);
    const again = await run(h, text);
    expect(again.rows).toEqual([{ row: 1, player: A, currency: "EUR", outcome: "already_made", before: "1000", after: "1500" }]);
    expect(w.balances.get(A)).toBe(4000);
  });

  test("each row is asked once, its adjustment alone: no balance is read first", async () => {
    const h = harness({ table });
    h.answer(wallet({ [A]: 1000, [B]: 1000 }).answer);
    await run(h, body([row(A), row(B)]));
    expect(h.calls.filter((c) => c.method === "GET")).toHaveLength(0);
    expect(adjustCalls(h)).toHaveLength(2);
  });

  test("a row the wallet does not answer is refused as unavailable", async () => {
    const h = harness({ table });
    h.answer(() => Response.json({ error: "source_unavailable" }, { status: 503 }));
    const r = await run(h, body([row(A)]));
    expect(r.rows).toEqual([{ row: 1, player: A, currency: "EUR", outcome: "refused", reason: "source_unavailable", source: "wallet", status: 503 }]);
  });

  test("an operator whose roles do not give adjustment.make starts nothing", async () => {
    const h = harness({ table });
    h.state.sessions.set("s-2", { accessToken: "eyJ.other.sig", expiresAt: Date.now() + 60_000, roles: ["brand_goldvibe", `brand_support@${Brand}`] });
    const r = await h.ask("POST", "/api/adjustment-batches", { session: "s-2", headers: write, body: body([row(A)]) });
    expect(r.status).toBe(403);
    expect(await r.json()).toMatchObject({ error: "forbidden" });
    expect(h.calls).toHaveLength(0);
  });

  test("a write not from the page is refused", async () => {
    const h = harness({ table });
    const r = await h.ask("POST", "/api/adjustment-batches", { session: "s-1", headers: { "Content-Type": "application/json" }, body: body([row(A)]) });
    expect(r.status).toBe(403);
  });
});
