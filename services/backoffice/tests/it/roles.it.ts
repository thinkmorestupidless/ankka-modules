// FR-008b to FR-008d (T128) on a local installation: a role's bag changed in the backoffice is offered and
// admitted within 15 seconds without signing in again, and taken away likewise, with nothing deployed or
// restarted; giving a role that carries a permission the giver lacks is refused; the last role carrying
// `roles.change` cannot lose it; a service started while `players` is paused answers unavailable, and one
// already running keeps its last copy of the bags.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { randomUUID } from "node:crypto";
import { ankkaServices, bagsNow, deleteRole, giveRoles, installation, Operator, operators, runRole, setRole, sleep, until } from "./support.ts";

const InForce = 15_000;

describe("roles kept by the brand, changed while it runs", () => {
  const inst = installation();
  const creds = operators(inst);
  const role = runRole("bag");
  const giver = runRole("giver");
  const carrier = runRole("carrier");
  let admin: Operator;
  let made: Operator;
  let subject: string;

  /** An owner's route needing `note.write`, naming no player: admitted answers 400 or 404, refused 401 or 403. */
  const noteWrite = (op: Operator) => op.call("POST", `/api/s/players/players/${randomUUID()}/notes`, "{", { "Content-Type": "application/json" });
  const admitted = (status: number) => status !== 401 && status !== 403 && status !== 503;

  beforeAll(async () => {
    admin = await Operator.signIn(inst, creds.brand_admin, "brand_admin");
    expect((await setRole(admin, role, ["player.read"])).status).toBe(200);
    made = await Operator.signIn(inst, creds.made, "made");
    subject = (await made.me()).subject!;
    expect((await giveRoles(admin, subject, [role])).status).toBe(200);
    // the role given reaches the realm user's token at their next sign-in
    made = await Operator.signIn(inst, creds.made, "made");
    const me = await until(() => made.me(), (m) => m.roles.includes(role), 30_000, 2_000);
    expect(me.value.roles, "the role given is in the token").toContain(role);
  });

  afterAll(async () => {
    if (!admin) return;
    if (subject) await giveRoles(admin, subject, []);
    for (const r of [role, giver, carrier]) await deleteRole(admin, r);
  });

  it("a permission added to a role is offered and admitted within 15 seconds, without signing in again", async () => {
    expect((await made.me()).permissions).not.toContain("note.write");
    expect(admitted((await noteWrite(made)).status), "refused before").toBe(false);
    const changedAt = Date.now();
    expect((await setRole(admin, role, ["player.read", "note.write"])).status).toBe(200);
    const offered = await until(() => made.me(), (m) => m.permissions.includes("note.write"), InForce, 500);
    expect(offered.held, `offered after ${offered.afterMs} ms`).toBe(true);
    const owner = await until(() => noteWrite(made), (a) => admitted(a.status), InForce - (Date.now() - changedAt), 500);
    expect(owner.held, `admitted by players after ${Date.now() - changedAt} ms (last ${owner.value.status})`).toBe(true);
    expect(Date.now() - changedAt).toBeLessThanOrEqual(InForce);
  });

  it("and taken away likewise", async () => {
    const changedAt = Date.now();
    expect((await setRole(admin, role, ["player.read"])).status).toBe(200);
    const gone = await until(() => made.me(), (m) => !m.permissions.includes("note.write"), InForce, 500);
    expect(gone.held, "no longer offered").toBe(true);
    const owner = await until(() => noteWrite(made), (a) => a.status === 401 || a.status === 403, InForce - (Date.now() - changedAt), 500);
    expect(owner.held, `refused by players after ${Date.now() - changedAt} ms (last ${owner.value.status})`).toBe(true);
  });

  it("giving a role that carries a permission the giver lacks is refused", async () => {
    expect((await setRole(admin, giver, ["player.read", "roles.read", "roles.give"])).status).toBe(200);
    expect((await setRole(admin, carrier, ["player.read", "adjustment.make"])).status).toBe(200);
    expect((await giveRoles(admin, subject, [role, giver])).status).toBe(200);
    made = await Operator.signIn(inst, creds.made, "made");
    const me = (await until(() => made.me(), (m) => m.permissions.includes("roles.give"), 30_000, 2_000)).value;
    expect(me.permissions).toContain("roles.give");
    expect(me.permissions).not.toContain("adjustment.make");
    const other = (await Operator.signIn(inst, creds.brand_support, "brand_support").then((o) => o.me())).subject!;
    const refused = await giveRoles(made, other, [carrier]);
    expect(refused.status).toBe(403);
    expect(refused.body).toMatchObject({ error: "forbidden", permission: "adjustment.make" });
    // a role within the giver's own permissions is given (a giving replaces the brand's roles: brand_support is given back)
    try {
      expect((await giveRoles(made, other, [role])).status).toBe(202);
    } finally {
      expect((await giveRoles(admin, other, ["brand_support"])).status).toBe(202);
    }
  });

  it("the last role carrying roles.change cannot lose it", async () => {
    const bags = await bagsNow(admin);
    const changers = Object.entries(bags).filter(([, ps]) => ps.includes("roles.change")).map(([r]) => r);
    expect(changers, "only brand_admin carries roles.change in a seeded brand").toEqual(["brand_admin"]);
    const without = bags.brand_admin!.filter((p) => p !== "roles.change");
    const r = await setRole(admin, "brand_admin", without);
    expect(r.status).toBe(409);
    expect(r.body).toMatchObject({ error: "last_role_changer" });
    expect((await bagsNow(admin)).brand_admin).toContain("roles.change");
  });

  it("a service started while players is paused answers unavailable; one already running keeps its last copy", async (ctx) => {
    if (!inst.mayChangeServices) ctx.skip();
    const withdrawalRead = (op: Operator) => op.get(`/api/s/payouts/players/${randomUUID()}/withdrawal-history`);
    const walletRead = (op: Operator) => op.get(`/api/s/wallet/wallets/${randomUUID()}/EUR/balance`);
    expect(admitted((await withdrawalRead(admin)).status), "payouts admits before").toBe(true);
    await ankkaServices(inst, "pause", "players");
    try {
      await sleep(12_000);
      await ankkaServices(inst, "restart", "wallet");
      const fresh = await until(() => walletRead(admin), (a) => a.status === 503, 180_000, 3_000);
      expect(fresh.held, `wallet, started with players paused, answers 503 (last ${fresh.value.status})`).toBe(true);
      const kept = await withdrawalRead(admin);
      expect(admitted(kept.status), `payouts, already running, keeps its copy (${kept.status})`).toBe(true);
    } finally {
      await ankkaServices(inst, "resume", "players");
      await until(() => walletRead(admin), (a) => a.status !== 503, 180_000, 3_000);
    }
  });
});
