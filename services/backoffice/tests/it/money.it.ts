// SC-007 (T124): every amount matches its record's minor units, in JPY, EUR and KWD (zero, two and three
// minor units). For seeded records in each currency: the owner's record holds the minor units the seed
// sent, and each screen that shows them (a withdrawal request, the player's wallet, the player's
// rewards, the queue) shows every amount with its currency's own number of decimals, and the seeded
// amount among them, digit for digit. The oracle is the contract's table read here, not the page's code.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import currencies from "../../src/shared/generated/currencies.v1.json" with { type: "json" };
import { installation, minorOfShown, Operator, operators, readSeed, seeded, SeedCurrencies, spread, type Seed } from "./support.ts";
import { amountsShown, openSignedIn, settle, type Signed } from "./page.ts";

const exponent = (c: string): number => {
  const e = (currencies as Record<string, number>)[c];
  if (e === undefined) throw new Error(`the contract has no ${c}`);
  return e;
};

/** The wallet's major-unit text as minor units, by the contract's exponent. */
function minorOfMajor(text: string, currency: string): number {
  const e = exponent(currency);
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text.trim());
  if (!m || (m[3] ?? "").length > e) throw new Error(`${text} is not an amount in ${currency}`);
  const n = Number(m[2]) * 10 ** e + Number((m[3] ?? "").padEnd(e, "0") || "0");
  return m[1] ? -n : n;
}

/** The decimals an amount is shown with: `¥1,234` 0, `€12.34` 2, `KWD 1.234` 3. */
const decimalsShown = (text: string) => /[.,](\d+)(?!.*\d[.,]\d)\D*$/.exec(text)?.[1]?.length ?? 0;

describe("SC-007: every amount equals its record's minor units", () => {
  const inst = installation();
  let seed: Seed;
  let admin: Operator;
  let browser: Signed;

  beforeAll(async () => {
    seed = readSeed(inst);
    const creds = operators(inst).brand_admin;
    admin = await Operator.signIn(inst, creds, "brand_admin");
    browser = await openSignedIn(inst, creds);
  });
  afterAll(async () => browser?.close());

  const sample = (c: (typeof SeedCurrencies)[number], from: ReturnType<typeof seeded>["players"]) => spread(seeded(seed).inCurrency(c, from), 5);

  /** Every amount on the screen at `route`: its decimals are its currency's, and `minor` in `currency` is among them. */
  async function screen(route: string, minor: number, currency: string) {
    const page = browser.page;
    await page.goto(`${inst.backoffice}${route}`);
    await settle(page);
    await page.locator(`[data-figure][data-currency="${currency}"]`).first().waitFor({ timeout: 15_000 });
    const shown = await amountsShown(page);
    for (const a of shown) expect(decimalsShown(a.text), `${route}: ${a.text} has ${a.currency}'s decimals`).toBe(exponent(a.currency));
    const values = shown.filter((a) => a.currency === currency).map((a) => minorOfShown(a.text));
    expect(values, `${route} shows ${minor} minor units of ${currency}`).toContain(minor);
  }

  for (const currency of SeedCurrencies) {
    describe(currency, () => {
      it("each withdrawal request holds the minor units requested, and its page shows them", async () => {
        const ps = sample(currency, seeded(seed).withdrawals);
        expect(ps.length, `seeded requests in ${currency}`).toBeGreaterThan(0);
        for (const p of ps) {
          const w = p.withdrawal!;
          const r = await admin.read<{ amount: number; currency: string }>(`/api/s/payouts/withdrawal-requests/${w.requestId}`);
          expect(r).toMatchObject({ amount: w.minor, currency });
          await screen(`/withdrawals/${encodeURIComponent(w.requestId)}`, w.minor, currency);
        }
      });

      it("each credited wallet's balance is the credit, and the player's wallet shows it", async () => {
        const ps = sample(currency, seeded(seed).credited.length > 0 ? seeded(seed).credited : seeded(seed).withdrawals);
        expect(ps.length, `seeded credits in ${currency}`).toBeGreaterThan(0);
        for (const p of ps) {
          const b = await admin.read<{ realBalance: string; reserved?: string }>(`/api/s/wallet/wallets/${p.playerId}/${currency}/balance`);
          const held = p.withdrawal ? p.withdrawal.minor : 0;
          const real = minorOfMajor(b.realBalance, currency);
          // a request holds its amount: what is left is the credit less it, wherever the wallet keeps the hold
          expect([p.credit!.minor, p.credit!.minor - held]).toContain(real);
          await screen(`/players/${encodeURIComponent(p.playerId!)}/wallets`, real, currency);
        }
      });

      it("each reward's face value is the bonus given, and the rewards list shows it", async () => {
        const ps = sample(currency, seeded(seed).rewards);
        expect(ps.length, `seeded rewards in ${currency}`).toBeGreaterThan(0);
        for (const p of ps) {
          const r = await admin.read<{ items: { rewardId: string; faceValue: number; currency: string }[] }>(`/api/s/rewards/reward-grants?playerId=${p.playerId}&page=1&pageSize=50`);
          const mine = r.items.find((x) => x.rewardId === p.reward!.rewardId);
          expect(mine).toMatchObject({ faceValue: p.reward!.minor, currency });
          await screen(`/rewards?f.q=${encodeURIComponent(p.playerId!)}`, p.reward!.minor, currency);
        }
      });
    });
  }

  it("an adjustment batch sent again makes nothing twice (FR-028)", async () => {
    // one credit to a rewarded player in each currency; the batch is named by its content, so every run of
    // this suite sends the same batch, and only the first run ever makes it
    const rows = SeedCurrencies.map((c) => seeded(seed).inCurrency(c, seeded(seed).rewards)[0]!).map((p) => ({
      player: p.playerId!,
      currency: p.currency,
      walletType: "real",
      direction: "credit",
      amountMinor: 1,
    }));
    const batch = { key: crypto.randomUUID(), reason: `FR-028 check of seed ${seed.run}`, category: "compensation", rows };
    const send = async () => {
      const a = await admin.call<string>("POST", "/api/adjustment-batches", batch);
      expect(a.status).toBe(200);
      const lines = String(a.body).trim().split("\n").map((l) => JSON.parse(l) as { done?: true; made?: number; alreadyMade?: number; refused?: number });
      return lines.find((l) => l.done)!;
    };
    const balance = (p: (typeof rows)[number]) => admin.read<{ realBalance: string }>(`/api/s/wallet/wallets/${p.player}/${p.currency}/balance`).then((b) => minorOfMajor(b.realBalance, p.currency));
    const first = await send();
    expect(first.refused).toBe(0);
    expect((first.made ?? 0) + (first.alreadyMade ?? 0)).toBe(rows.length);
    const before = await Promise.all(rows.map(balance));
    const again = await send();
    expect(again).toMatchObject({ made: 0, refused: 0, alreadyMade: rows.length });
    expect(await Promise.all(rows.map(balance))).toEqual(before);
  });

  it("the withdrawals queue shows every row's amount in its currency's decimals", async () => {
    const page = browser.page;
    await page.goto(`${inst.backoffice}/withdrawals`);
    await settle(page);
    await page.locator("[data-figure][data-currency]").first().waitFor({ timeout: 15_000 });
    const shown = await amountsShown(page);
    expect(shown.length).toBeGreaterThan(0);
    for (const a of shown) expect(decimalsShown(a.text), `${a.text}`).toBe(exponent(a.currency));
    const q = await admin.read<{ requests: { amount: number; currency: string }[] }>("/api/withdrawals?status=PENDING_REVIEW&page=1&pageSize=50");
    const fromRecords = q.requests.map((r) => `${r.currency}:${r.amount}`).sort();
    const fromScreen = shown.map((a) => `${a.currency}:${minorOfShown(a.text)}`);
    for (const r of fromRecords) expect(fromScreen, "a queued request's amount is on the screen").toContain(r);
  });
});
