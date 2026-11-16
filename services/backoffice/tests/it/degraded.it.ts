// FR-017, FR-022, FR-042 (T126) on a local installation: a source stopped is named and the rest shown, and
// a rollout of the backoffice while a page is open breaks nothing. Pausing and restarting services is the
// person's to allow (BACKOFFICE_IT_MAY_CHANGE_SERVICES=1); without it the two service checks are skipped
// and the files' naming and caching are still checked.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { ankkaServices, installation, Operator, operators, readSeed, seeded, until, type Seed } from "./support.ts";
import { openSignedIn, settle, type Signed } from "./page.ts";

type Kind = { kind: string; state: "ok" | "unavailable"; reason?: string };

describe("a stopped source and a rollout", () => {
  const inst = installation();
  let seed: Seed;
  let admin: Operator;

  beforeAll(async () => {
    seed = readSeed(inst);
    admin = await Operator.signIn(inst, operators(inst).brand_admin, "brand_admin");
  });

  const worklist = () => admin.read<{ kinds: Kind[] }>("/api/worklist");
  const KycKinds = ["documents_awaiting", "players_flagged"];

  it("a paused kyc is named in the worklist and the timeline, and the rest is shown (FR-017, FR-022)", async (ctx) => {
    if (!inst.mayChangeServices) ctx.skip();
    const player = seeded(seed).withdrawals[0]!.playerId!;
    const before = await worklist();
    expect(before.kinds.filter((k) => KycKinds.includes(k.kind)).every((k) => k.state === "ok"), "kyc's kinds answer before").toBe(true);
    await ankkaServices(inst, "pause", "kyc");
    try {
      const during = await until(worklist, (w) => w.kinds.filter((k) => KycKinds.includes(k.kind)).every((k) => k.state === "unavailable"), 120_000, 3_000);
      expect(during.held, "kyc's kinds are unavailable").toBe(true);
      const others = during.value.kinds.filter((k) => !KycKinds.includes(k.kind));
      expect(others.length, "the other kinds are asked").toBeGreaterThan(0);
      expect(others.filter((k) => k.state === "ok").map((k) => k.kind), "and shown").toEqual(before.kinds.filter((k) => !KycKinds.includes(k.kind) && k.state === "ok").map((k) => k.kind));
      const tl = await admin.read<{ lines: { kind: string }[]; gaps: { source: string; from: string; to: string; reason: string }[] }>(`/api/players/${encodeURIComponent(player)}/timeline`);
      const gap = tl.gaps.find((g) => g.source === "identity");
      expect(gap, "the timeline names the identity source and the period it lacks").toBeDefined();
      expect(Date.parse(gap!.from)).toBeLessThan(Date.parse(gap!.to));
      expect(tl.lines.some((l) => l.kind === "adjustments"), "the other sources' lines are shown").toBe(true);
      const standing = await admin.read<{ parts: Record<string, { state: string }> }>(`/api/players/${encodeURIComponent(player)}/standing`);
      expect(standing.parts.identity!.state).toBe("unavailable");
      expect(standing.parts.balances!.state).toBe("ok");
    } finally {
      await ankkaServices(inst, "resume", "kyc");
      await until(worklist, (w) => w.kinds.filter((k) => KycKinds.includes(k.kind)).every((k) => k.state === "ok"), 180_000, 3_000);
    }
  });

  it("the built files are named by their content and the index is served uncached (FR-042)", async () => {
    const index = await fetch(`${inst.backoffice}/`);
    expect(index.status).toBe(200);
    expect(index.headers.get("cache-control") ?? "").toMatch(/no-cache|no-store/);
    const html = await index.text();
    const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1]!);
    expect(assets.length, "the index names its built files").toBeGreaterThan(0);
    for (const a of assets) {
      expect(a, "named by its content").toMatch(/-[A-Za-z0-9_-]{8,}\.(js|css)$/);
      const r = await fetch(`${inst.backoffice}${a}`);
      expect(r.status, a).toBe(200);
      expect(r.headers.get("cache-control") ?? "", a).toMatch(/immutable/);
    }
  });

  describe("a rollout of backoffice while a page is open breaks nothing (FR-042)", () => {
    let browser: Signed;
    afterAll(async () => browser?.close());

    it("the open page goes on to another area after the restart", async (ctx) => {
      if (!inst.mayChangeServices) ctx.skip();
      browser = await openSignedIn(inst, operators(inst).brand_admin);
      const page = browser.page;
      const errors: string[] = [];
      page.on("pageerror", (e) => errors.push(e.message));
      const player = seeded(seed).withdrawals[0]!.playerId!;
      await page.goto(`${inst.backoffice}/players/${encodeURIComponent(player)}`);
      await settle(page);
      const from = browser.seen.length;
      await ankkaServices(inst, "restart", "backoffice");
      // the restart is done when the backoffice answers again
      await until(() => fetch(`${inst.backoffice}/healthz`).then((r) => r.status).catch(() => 0), (s) => s === 200, 180_000, 2_000);
      // within the page: the router moves, and the area's code is loaded from the old build's names
      await page.getByRole("link", { name: /withdrawals/i }).first().click();
      await page.waitForURL(/\/withdrawals/, { timeout: 30_000 });
      await settle(page);
      const failed = browser.seen.slice(from).filter((s) => (s.status ?? 0) >= 400 && !s.url.includes("/api/events"));
      expect(failed.map((s) => `${s.status} ${s.url}`), "no request failed across the rollout").toEqual([]);
      expect(errors, "no script failed").toEqual([]);
      await expect(page.locator("main").isVisible()).resolves.toBe(true);
    });
  });
});
