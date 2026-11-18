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
