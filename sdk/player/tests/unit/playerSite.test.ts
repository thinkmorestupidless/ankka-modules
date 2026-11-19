import { describe, expect, it } from "vitest";
import { playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const siteOf = async (modules: string[], script?: (d: ReturnType<typeof doubles>) => void) => {
  const clock = handClock();
  const d = doubles({ clock, brand: { modules } });
  script?.(d);
  const site = playerSite({ transport: doubleTransport(d), clock });
  await site.ready;
  return { site, d };
};

describe("a site offers the behaviours of the modules /api/site names (FR-047)", () => {
  it("games where the brand runs casino, and none where it does not", async () => {
    expect((await siteOf(["core", "casino"])).site.games).toBeDefined();
    expect((await siteOf(["core", "engagement"])).site.games).toBeUndefined();
  });

  it("is decided by the brand's list, never by whether a service answered", async () => {
    // casino not named, though game-provider answers: no games
    const without = await siteOf(["core"]);
    expect(without.site.games).toBeUndefined();
    expect(without.d.askedOf("games")).toEqual([]);
    // casino named, though game-provider does not answer: games
    const unavailable = await siteOf(["core", "casino"], (d) => d.script("games", { status: 503, body: { error: "service_unavailable" } }));
    expect(unavailable.site.games).toBeDefined();
  });

  it("asks nothing of a module the brand does not run", async () => {
    const { site, d } = await siteOf(["core"]);
    site.session.actions.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    await new Promise((r) => setTimeout(r, 0));
    expect(d.asked.filter((a) => a.service === "game-provider" || a.service === "rewards")).toEqual([]);
  });

  it("knows its modules before /api/site is read when given them", async () => {
    const d = doubles({ brand: { modules: ["core", "casino"] } });
    const site = playerSite({ transport: doubleTransport(d), known: { site: { brand: "guyanabet", languages: ["fr", "en"], currency: "EUR", modules: ["core"] }, player: null } });
    await site.ready;
    expect(site.games).toBeUndefined();
  });
});

describe("the package's subpath exports resolve", () => {
  it("each names what its module offers", async () => {
    const root = await import("@eitheror/player-sdk");
    const core = await import("@eitheror/player-sdk/core");
    const casino = await import("@eitheror/player-sdk/casino");
    const engagement = await import("@eitheror/player-sdk/engagement");
    const support = await import("@eitheror/player-sdk/support");
    const server = await import("@eitheror/player-sdk/server");
    const testing = await import("@eitheror/player-sdk/testing");
    expect(typeof root.playerSite).toBe("function");
    expect(typeof core.coreClient).toBe("function");
    expect(typeof casino.gamesBehaviour).toBe("function");
    expect(engagement.engagementRoutes.length).toBeGreaterThan(0);
    expect(support.supportRoutes.length).toBeGreaterThan(0);
    expect(typeof server.siteServer).toBe("function");
    expect(typeof testing.doubles).toBe("function");
  });
});
