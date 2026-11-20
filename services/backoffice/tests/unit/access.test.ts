import { describe, expect, test } from "vitest";
import { holds, mayGiveRole, paymentsPermissionsOf, programmePermissionsOf, rolesGiving, runs } from "../../src/shared/access.ts";
import { OperatorBags } from "./bags.ts";

describe("holding a permission", () => {
  test("is what /api/me answers, nothing derived from a role's name", () => {
    expect(holds({ permissions: ["player.read"] }, "player.read")).toBe(true);
    expect(holds({ permissions: ["player.read"] }, "adjustment.make")).toBe(false);
  });
});

describe("the role a disabled action needs", () => {
  test("every role whose bag holds it, the default roles first", () => {
    expect(rolesGiving("adjustment.make", OperatorBags)).toEqual(["brand_finance", "brand_admin"]);
  });

  test("the brand's own roles after, by name, from the bags as they are now", () => {
    const bags = { ...OperatorBags, brand_vip_desk: ["tier.change"], brand_a_team: ["tier.change"] };
    expect(rolesGiving("tier.change", bags)).toEqual(["brand_admin", "brand_a_team", "brand_vip_desk"]);
  });

  test("a programme permission names the realm roles that give it, whatever the bags", () => {
    expect(rolesGiving("programme.manage", OperatorBags)).toEqual(["affiliate_manager", "brand_admin", "platform_admin"]);
    expect(rolesGiving("programme.managers", {})).toEqual(["brand_admin", "platform_admin"]);
    expect(programmePermissionsOf("goldvibe", ["brand_goldvibe", "affiliate_manager@goldvibe"])).toEqual(["programme.manage"]);
    expect(programmePermissionsOf("goldvibe", ["brand_goldvibe", "brand_support@goldvibe"])).toEqual([]);
  });

  test("a payments permission comes from the payments role's level, and names the roles at or above it (T112)", () => {
    expect(paymentsPermissionsOf(["brand_goldvibe", "resource_viewer"])).toEqual(["payments.read"]);
    expect(paymentsPermissionsOf(["resource_operator"])).toEqual(["payments.read", "payments.operate"]);
    expect(paymentsPermissionsOf(["resource_admin"])).toEqual(["payments.read", "payments.operate", "payments.admin"]);
    expect(paymentsPermissionsOf(["brand_admin"])).toEqual([]);
    expect(rolesGiving("payments.admin", {})).toContain("resource_admin");
    expect(rolesGiving("payments.admin", {})).not.toContain("resource_operator");
  });

  test("a payments role is given only by one strictly above it, as merchant refuses PRIVILEGE_ESCALATION (T114)", () => {
    expect(mayGiveRole(["resource_operator"], "resource_viewer")).toBe(true);
    expect(mayGiveRole(["resource_operator"], "support_agent")).toBe(false);
    expect(mayGiveRole(["resource_operator"], "resource_admin")).toBe(false);
    expect(mayGiveRole(["brand_admin"], "resource_viewer")).toBe(false);
  });

  test("none when no role carries it", () => {
    expect(rolesGiving("tier.change", { brand_support: ["player.read"] })).toEqual([]);
  });
});

describe("a module", () => {
  test("is run when the brand lists it", () => {
    const me = { brand: { slug: "g", name: "g", modules: ["core", "casino"] } };
    expect(runs(me, "casino")).toBe(true);
    expect(runs(me, "predictions")).toBe(false);
  });
});
