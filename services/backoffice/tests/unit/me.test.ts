import { describe, expect, test } from "vitest";
import { AllPermissions, parseZone } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import { Brand, harness, whole } from "./support.ts";

const jwt = (claims: object) => `h.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.sig`;

function as(roles: string[], claims: object = { sub: "op-7", name: "Ana Operator" }) {
  const h = harness();
  h.state.sessions.set("s-me", { accessToken: jwt(claims), expiresAt: Date.now() + 300_000, roles });
  return h;
}

describe("/api/me", () => {
  test("the operator, the brand, its modules, their preferences, and the permissions their roles carry", async () => {
    const h = as([`brand_${Brand}`, `brand_finance@${Brand}`]);
    const r = await h.ask("GET", "/api/me", { session: "s-me" });
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({
      subject: "op-7",
      name: "Ana Operator",
      roles: ["brand_finance"],
      brand: { slug: Brand, name: Brand, modules: ["casino", "core"], zone: "America/Sao_Paulo" },
      preferences: { theme: "dark", worklistKinds: null, onlineNotices: false, jobs: [] },
      permissions: AllPermissions.filter((p) => OperatorBags.brand_finance!.includes(p)),
    });
  });

  test("the brand's zone is players' reporting zone; UTC, players' default, when it was never read (FR-015)", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    h.zoneIs(() => "Asia/Tokyo");
    const me = (await (await h.ask("GET", "/api/me", { session: "s-me" })).json()) as { brand: { zone: string } };
    expect(me.brand.zone).toBe("Asia/Tokyo");
    const unread = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    unread.zoneIs(() => {
      throw new Error("players down");
    });
    const fallback = (await (await unread.ask("GET", "/api/me", { session: "s-me" })).json()) as { brand: { zone: string } };
    expect(fallback.brand.zone).toBe("UTC");
  });

  test("players' brand configuration gives the zone; one this runtime cannot name is not taken", () => {
    expect(parseZone({ reportingZone: "America/Sao_Paulo", name: "Goldvibe" })).toBe("America/Sao_Paulo");
    expect(() => parseZone({ reportingZone: "Mars/Olympus" })).toThrow();
    expect(() => parseZone({})).toThrow();
  });

  test("two roles carry what either carries", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`, `brand_compliance@${Brand}`]);
    const me = (await (await h.ask("GET", "/api/me", { session: "s-me" })).json()) as { permissions: string[] };
    expect(me.permissions).toContain("identity.review");
    expect(me.permissions).toContain("player.read");
    expect(me.permissions).not.toContain("adjustment.make");
  });

  test("the brand's bags as players holds them now, not the matrix", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    h.bagsAre(() => ({ ...OperatorBags, brand_support: ["player.read"] }));
    const me = (await (await h.ask("GET", "/api/me", { session: "s-me" })).json()) as { permissions: string[] };
    expect(me.permissions).toEqual(["player.read"]);
  });

  test("platform_admin carries every permission", async () => {
    const h = as(["platform_admin"]);
    const me = (await (await h.ask("GET", "/api/me", { session: "s-me" })).json()) as { permissions: string[] };
    expect(me.permissions).toEqual([...AllPermissions, "programme.manage", "programme.managers", "payments.read", "payments.operate", "payments.admin"]);
  });

  test("a programme manager carries the programme's permission, from the realm role, and none of a bag", async () => {
    const h = as([`brand_${Brand}`, `affiliate_manager@${Brand}`]);
    const me = (await (await h.ask("GET", "/api/me", { session: "s-me" })).json()) as { permissions: string[] };
    expect(me.permissions).toEqual(["programme.manage"]);
  });

  test("an operator whose roles do not name the brand is refused brand_not_in_roles", async () => {
    const h = as(["brand_othervibe", "brand_admin@othervibe"]);
    const r = await h.ask("GET", "/api/me", { session: "s-me" });
    expect(r.status).toBe(403);
    expect(((await r.json()) as { error: string }).error).toBe("brand_not_in_roles");
  });

  test.each([
    ["GET", "/api/permissions"],
    ["GET", "/api/search?q=ana"],
    ["GET", "/api/freshness?services=players"],
    ["GET", "/api/jobs"],
    ["GET", "/api/s/players/players/p-1/profile"],
  ])("an operator of another brand is refused %s %s before anything is read", async (method, path) => {
    const h = as(["brand_othervibe", "brand_admin@othervibe"]);
    const r = await h.ask(method, path, { session: "s-me" });
    expect(r.status).toBe(403);
    expect(await r.json()).toEqual({ error: "brand_not_in_roles", brand: Brand });
    expect(h.calls).toHaveLength(0);
  });

  test("the brand's roles never read is 503 from players", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    h.bagsAre(() => {
      throw new Error("players down");
    });
    const r = await h.ask("GET", "/api/me", { session: "s-me" });
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "source_unavailable", source: "players" });
  });

  test("the token itself is never answered", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    const token = h.state.sessions.get("s-me")!.accessToken;
    expect(await whole(await h.ask("GET", "/api/me", { session: "s-me" }))).not.toContain(token);
  });
});

describe("/api/permissions", () => {
  test("every role of the brand and what it carries", async () => {
    const h = as([`brand_${Brand}`, `brand_support@${Brand}`]);
    h.bagsAre(() => ({ ...OperatorBags, brand_vip_desk: ["player.read", "tier.change"] }));
    const r = (await (await h.ask("GET", "/api/permissions", { session: "s-me" })).json()) as { roles: Record<string, string[]> };
    expect(r.roles.brand_vip_desk).toEqual(["player.read", "tier.change"]);
    expect(Object.keys(r.roles).sort()).toEqual(["brand_admin", "brand_compliance", "brand_finance", "brand_support", "brand_vip_desk"]);
  });
});
