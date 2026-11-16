// SC-006 (T125): no action is offered and then refused. For each of the five default roles, a role made in
// the backoffice and a payments role: the permissions the page offers by (`/api/me`) are those the
// operator's roles carry in the brand's bags; every action the page offers is admitted by its owner when
// called; every action it does not offer is refused by its owner when called directly.
//
// "Admitted" is the owner's ACL letting the call through: any answer but 401 and 403. Each call names a
// record that does not exist (a fresh UUID) and sends a body that is not JSON, so an admitted call is
// answered 400 or 404 by the handler and changes nothing. Two routes take no record and could act on a
// body read leniently (a module request, the offers' order): they are only called where they are
// refused. A route the proxy cannot reach (its service not run in this installation) is listed, and
// fails the sweep only for a service of the brand's core module.
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { randomUUID } from "node:crypto";
import { table, type Row } from "../../src/server/routes.ts";
import { permissionsOf } from "../../src/server/roles.ts";
import { Actions } from "../../src/web/actions/index.ts";
import { CoreServices, DefaultRoles, deleteRole, giveRoles, installation, Operator, operators, pool, runRole, setRole, until, type Label } from "./support.ts";

const Writes = new Set(["POST", "PUT", "PATCH", "DELETE"]);
/** Called only where refused: a lenient read of the body could act. */
const NotProbedWhenHeld = new Set(["POST players /modules/requests", "PUT rewards /offers/order"]);
const keyOf = (r: Row) => `${r.method} ${r.service} ${r.path}`;

/** The browser's path for a row, naming records that do not exist. */
function probePath(row: Row): string {
  const filled = row.path.replace(/\{(\w+)\}/g, (_, name: string) => (name === "currency" ? "EUR" : name === "kind" ? "house" : randomUUID()));
  return `/api/s/${row.service}${filled}`;
}

type Verdict = "admitted" | "refused" | "unreachable";

function verdictOf(status: number, body: unknown): Verdict {
  if (status === 401 || status === 403) return "refused";
  const b = body as { error?: string; source?: string } | null;
  if (status === 503 && b?.error === "source_unavailable") return "unreachable";
  if (status === 503 && typeof body === "string" && /no service/.test(body)) return "unreachable";
  if (status === 502 || status === 504) return "unreachable";
  return "admitted";
}

/** The permissions of the role the sweep makes: a mixed bag, some writes and some reads. */
const MadePermissions = ["player.read", "wallet.read", "withdrawal.read", "note.write", "withdrawal.review", "identity.read", "reward.read"];

describe("SC-006: every action offered is admitted, every action not offered is refused", () => {
  const inst = installation();
  const creds = operators(inst);
  const made = runRole("sweep");
  let admin: Operator;

  beforeAll(async () => {
    admin = await Operator.signIn(inst, creds.brand_admin, "brand_admin");
    const set = await setRole(admin, made, MadePermissions);
    expect(set.status, `making ${made}: ${JSON.stringify(set.body)}`).toBe(200);
    const operator = await Operator.signIn(inst, creds.made, "made");
    const subject = (await operator.me()).subject!;
    const given = await giveRoles(admin, subject, [made]);
    expect(given.status, `giving ${made}: ${JSON.stringify(given.body)}`).toBe(200);
  });

  afterAll(async () => {
    if (!admin) return;
    const operator = await Operator.signIn(inst, creds.made, "made").catch(() => undefined);
    const subject = operator && (await operator.me()).subject;
    if (subject) await giveRoles(admin, subject, []);
    await deleteRole(admin, made);
  });

  const swept: Label[] = [...DefaultRoles, "made", "payments"];
  for (const label of swept) {
    it(`${label}`, async () => {
      // a role given is in the realm user's token from their next sign-in
      const op = await Operator.signIn(inst, creds[label], label);
      const me =
        label === "made"
          ? (await until(() => op.me(), (m) => m.roles.includes(made), 30_000, 2_000)).value
          : await op.me();
      if (label === "made") expect(me.roles, "the made role is in the operator's token").toContain(made);
      const bags = (await op.read<{ roles: Record<string, string[]> }>("/api/permissions")).roles;
      expect([...me.permissions].sort(), "the page offers by the roles' bags").toEqual([...permissionsOf(me.roles, bags)].sort());

      const held = new Set(me.permissions);
      const offered = Object.entries(Actions).filter(([, a]) => held.has(a.permission)).map(([id]) => id);
      const notOffered = Object.entries(Actions).filter(([, a]) => !held.has(a.permission)).map(([id]) => id);
      expect(offered.length + notOffered.length).toBe(Object.keys(Actions).length);

      const wrong: string[] = [];
      const unreachable: string[] = [];
      const actionPermissions = new Set<string>(Object.values(Actions).map((a) => a.permission));
      const rows = table.filter((r) => Writes.has(r.method) || actionPermissions.has(r.permission));
      await pool(rows, 8, async (row) => {
        const mayAct = held.has(row.permission);
        if (mayAct && NotProbedWhenHeld.has(keyOf(row))) return;
        const a = await op.call(row.method, probePath(row), row.method === "GET" || row.method === "DELETE" ? undefined : "{", { "Content-Type": "application/json" });
        const v = verdictOf(a.status, a.body);
        if (v === "unreachable") {
          unreachable.push(`${keyOf(row)} (${a.status})`);
          if (CoreServices.has(row.service)) wrong.push(`${keyOf(row)}: unreachable (${a.status})`);
          return;
        }
        const expected: Verdict = mayAct ? "admitted" : "refused";
        if (v !== expected) wrong.push(`${keyOf(row)} needs ${row.permission}: ${mayAct ? "offered" : "not offered"}, but ${v} (${a.status} ${JSON.stringify(a.body).slice(0, 120)})`);
      });
      if (unreachable.length > 0) console.log(`${label}: not reachable in this installation (${unreachable.length}):\n  ${unreachable.sort().join("\n  ")}`);
      expect(wrong.sort(), `${label} (roles ${me.roles.join(", ")})`).toEqual([]);
    });
  }

  it("an operator of another brand only is told the role needed and sees nothing (FR-004)", async () => {
    const op = await Operator.signIn(inst, creds.other_brand, "other_brand");
    const me = await op.get<{ error?: string; brand?: string }>("/api/me");
    expect(me.status).toBe(403);
    expect(me.body).toMatchObject({ error: "brand_not_in_roles", brand: inst.brand });
    for (const path of ["/api/worklist", "/api/players/x/standing", "/api/withdrawals"]) expect((await op.get(path)).status, path).toBe(403);
    const players = await op.get("/api/s/players/players?page=1&pageSize=50");
    expect([401, 403], "the owner refuses a list").toContain(players.status);
  });
});
