import { describe, expect, it } from "vitest";
import { playerSite } from "../../src/site.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

describe("playerSite", () => {
  it("knows the brand's modules from /api/site once ready, and the player from /api/session", async () => {
    const d = doubles({ brand: { modules: ["core", "casino"] } });
    const t = doubleTransport(d);
    await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    const site = playerSite({ transport: t });
    expect(site.runs("casino")).toBe(false);
    await site.ready;
    expect(site.runs("casino")).toBe(true);
    expect(site.runs("support")).toBe(false);
    expect(site.player?.playerId).toBe("p-ana");
  });

  it("asks nothing when the first page already knows the site and the player", async () => {
    const d = doubles();
    const site = playerSite({ transport: doubleTransport(d), known: { site: { brand: "guyanabet", languages: ["fr"], currency: "EUR", modules: ["core"] }, player: null } });
    await site.ready;
    expect(site.runs("core")).toBe(true);
    expect(d.asked).toEqual([]);
  });
});

describe("playerSite's funds", () => {
  it("are read when the player signs in and forgotten when they sign out", async () => {
    const { handClock } = await import("../../src/testing/clock.ts");
    const clock = handClock();
    const d = doubles({ clock });
    const site = playerSite({ transport: doubleTransport(d), clock });
    await site.ready;
    site.session.actions.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    await clock.settle();
    expect(site.funds.current().real).toEqual({ minor: 5000n, currency: "EUR" });
    site.session.actions.signOut();
    await clock.settle();
    expect(site.funds.current().real).toBeNull();
    site.stop();
  });
});
