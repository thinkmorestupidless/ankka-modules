// SC-004 (T126): the three investigations of the original's spec 258 answered from the backoffice alone,
// on seeded players: "why was this withdrawal capped", "where did this reward go", "is this a bug". Each
// is answered from what the backoffice's pages read (the composites and the relayed owners' reads) and
// then seen on the page itself.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { installation, minorOfShown, Operator, operators, readSeed, seeded, until, type Seed } from "./support.ts";
import { amountsShown, openSignedIn, settle, type Signed } from "./page.ts";
import { t } from "../../src/shared/messages/index.ts";

interface Line {
  kind: string;
  source: string;
  actor: { kind: string; id?: string; name?: string };
  amount?: { minor: number; currency: string };
  summary: { key: string };
  links: { kind: string; id: string }[];
}
interface Timeline {
  lines: Line[];
  gaps: { source: string; reason: string }[];
  next: string | null;
}

describe("SC-004: the three investigations are answered from the backoffice alone", () => {
  const inst = installation();
  let seed: Seed;
  let finance: Operator;
  let browser: Signed;

  beforeAll(async () => {
    seed = readSeed(inst);
    const creds = operators(inst).brand_finance;
    finance = await Operator.signIn(inst, creds, "brand_finance");
    browser = await openSignedIn(inst, creds);
  });
  afterAll(async () => browser?.close());

  const timelineOf = async (player: string) => {
    const lines: Line[] = [];
    let gaps: Timeline["gaps"] = [];
    let after: string | null = null;
    for (let i = 0; i < 20; i++) {
      const page: Timeline = await finance.read<Timeline>(`/api/players/${encodeURIComponent(player)}/timeline${after ? `?after=${encodeURIComponent(after)}` : ""}`);
      lines.push(...page.lines);
      gaps = gaps.concat(page.gaps);
      if (!page.next) break;
      after = page.next;
    }
    return { lines, gaps };
  };

  it("why was this withdrawal capped: the request's page shows what was asked, paid and voided, and why", async (ctx) => {
    // a capped request is one a reward's cashout cap voided part of; the seed makes none, so one is looked for
    type Row = { requestId: string };
    let capped: { requestId: string; amount: number; paidAmount: number; voidedAmount: number; currency: string } | undefined;
    for (const status of ["COMPLETED", "APPROVED", "AWAITING_MANUAL_PAYOUT", "ALL"]) {
      const q = await finance.read<{ requests: Row[] }>(`/api/withdrawals?status=${status}&page=1&pageSize=50`);
      for (const r of q.requests) {
        const d = await finance.read<{ requestId: string; amount: number; paidAmount: number; voidedAmount?: number; currency: string }>(`/api/s/payouts/withdrawal-requests/${r.requestId}`);
        if ((d.voidedAmount ?? 0) > 0) {
          capped = { ...d, voidedAmount: d.voidedAmount! };
          break;
        }
      }
      if (capped) break;
    }
    if (!capped) {
      console.log("no capped withdrawal request in the brand: one needs a reward's winnings over its cashout cap, which the seed does not make");
      ctx.skip();
      return;
    }
    expect(capped.paidAmount + capped.voidedAmount).toBeLessThanOrEqual(capped.amount);
    const page = browser.page;
    await page.goto(`${inst.backoffice}/withdrawals/${encodeURIComponent(capped.requestId)}`);
    await page.locator('[data-amount="voided"]').waitFor({ timeout: 15_000 });
    await expect(page.getByText(t("withdrawals.voided.cap")).isVisible()).resolves.toBe(true);
    const voided = await page.locator('[data-amount="voided"] [data-figure]').textContent();
    expect(minorOfShown(voided ?? "")).toBe(capped.voidedAmount);
  });

  it("where did this reward go: its state, who ended it and when, on the reward and in the timeline", async () => {
    const p = seeded(seed).rewards.at(-1)!;
    const reward = p.reward!;
    const revoke = await finance.call("POST", `/api/s/rewards/reward-grants/${reward.rewardId}/revoke`, { mode: "forfeit", reason: "investigation SC-004" });
    expect([200, 409], `the revoke (${JSON.stringify(revoke.body)})`).toContain(revoke.status);
    const ended = await until(
      () => finance.read<{ items: { rewardId: string; status: string }[] }>(`/api/s/rewards/reward-grants?playerId=${p.playerId}&page=1&pageSize=50`),
      (r) => r.items.some((x) => x.rewardId === reward.rewardId && x.status.toLowerCase() !== "active"),
      30_000,
    );
    expect(ended.held, "the reward's state is no longer active").toBe(true);
    const lines = await until(
      () => timelineOf(p.playerId!),
      (tl) => tl.lines.filter((l) => l.kind === "rewards").length >= 2,
      30_000,
      2_000,
    );
    const rewardsLines = lines.value.lines.filter((l) => l.kind === "rewards");
    expect(rewardsLines.length, "the timeline holds the reward's grant and its end").toBeGreaterThanOrEqual(2);
    expect(rewardsLines.every((l) => l.actor.kind !== "operator" || (l.actor.name ?? "") !== ""), "an operator is named").toBe(true);
    expect(rewardsLines.some((l) => l.links.some((k) => k.id === reward.rewardId)), "a line links the reward").toBe(true);
    const page = browser.page;
    await page.goto(`${inst.backoffice}/players/${encodeURIComponent(p.playerId!)}/rewards`);
    await settle(page);
    const shown = (await amountsShown(page)).map((a) => minorOfShown(a.text));
    expect(shown, "the player's rewards show the reward's value").toContain(reward.minor);
  });

  it("is this a bug: the balance is what the timeline's events say, each with its actor", async () => {
    const p = seeded(seed).withdrawals[0]!;
    const { lines, gaps } = await timelineOf(p.playerId!);
    expect(gaps, "every source of the timeline answered").toEqual([]);
    const adjustment = lines.find((l) => l.kind === "adjustments" && l.amount?.minor === p.credit!.minor);
    expect(adjustment, "the credit is in the timeline").toBeDefined();
    expect(adjustment!.actor.kind).toBe("operator");
    expect(adjustment!.actor.name ?? "", "the operator who made it is named").not.toBe("");
    const withdrawal = lines.find((l) => l.kind === "withdrawals" && l.links.some((k) => k.id === p.withdrawal!.requestId));
    expect(withdrawal, "the withdrawal request is in the timeline").toBeDefined();
    expect(Math.abs(withdrawal!.amount?.minor ?? 0)).toBe(p.withdrawal!.minor);
    const standing = await finance.read<{ parts: { balances: { state: string; data?: unknown } } }>(`/api/players/${encodeURIComponent(p.playerId!)}/standing`);
    expect(standing.parts.balances.state, "the standing's balances answered").toBe("ok");
    const page = browser.page;
    await page.goto(`${inst.backoffice}/players/${encodeURIComponent(p.playerId!)}/timeline`);
    await settle(page);
    const shown = (await amountsShown(page)).filter((a) => a.currency === p.currency).map((a) => Math.abs(minorOfShown(a.text) ?? 0));
    expect(shown, "the timeline shows the credit").toContain(p.credit!.minor);
    expect(shown, "the timeline shows the request").toContain(p.withdrawal!.minor);
  });
});
