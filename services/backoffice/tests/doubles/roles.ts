// The brand's roles as the players double keeps them, so a role made or changed in the backoffice is
// what the next read of the roles answers, and a role given is what the realm's next token carries.
// players' own rules (the giver's permissions, the last role changer, reserved names) are held by its
// own suites; the double keeps only what the backoffice's scenarios read back.
import permissions from "../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import type { Answer } from "./owner.ts";

interface Role {
  name: string;
  description: string;
  permissions: string[];
  default: boolean;
}

const Operator = /^\/v1\/casino\/backoffice\/([^/]+)\/(roles|role-assignments)(?:\/([^/]+))?(?:\/(reset))?$/;
const Service = /^\/v1\/casino\/brands\/([^/]+)\/players\/roles$/;

export class BrandRoles {
  private roles = new Map<string, Role>();
  private readonly give: (operator: string, roles: string[]) => void;

  constructor(give: (operator: string, roles: string[]) => void) {
    this.give = give;
    this.reset();
  }

  reset(): void {
    this.roles = new Map(Object.entries(permissions.defaults).map(([name, ps]) => [name, { name, description: "", permissions: [...ps].sort(), default: true }]));
  }

  private list(): Answer {
    return { body: { roles: [...this.roles.values()].sort((a, b) => a.name.localeCompare(b.name)) } };
  }

  /** An answer when the path is one of players' role routes; undefined otherwise. */
  handle(method: string, path: string, body: string, authorised: boolean): Answer | undefined {
    if (method === "GET" && Service.test(path)) return this.list();
    const m = Operator.exec(path);
    if (!m) return undefined;
    if (!authorised) return { status: 401, body: { error: "unauthenticated" } };
    const [, brand, what, name, reset] = m;
    const given = () => (body ? (JSON.parse(body) as Record<string, unknown>) : {});
    if (what === "roles" && !name && method === "GET") return this.list();
    if (what === "roles" && name && !reset && method === "PUT") {
      const b = given();
      const before = this.roles.get(name);
      const role: Role = { name, description: String(b.description ?? ""), permissions: [...((b.permissions as string[]) ?? [])].sort(), default: before?.default ?? false };
      this.roles.set(name, role);
      return { body: role };
    }
    if (what === "roles" && name && reset && method === "POST") {
      const bag = (permissions.defaults as Record<string, string[]>)[name];
      if (!bag) return { status: 404, body: { error: "no_such_role" } };
      this.roles.set(name, { name, description: "", permissions: [...bag].sort(), default: true });
      return { body: this.roles.get(name) };
    }
    if (what === "roles" && name && method === "DELETE") {
      this.roles.delete(name);
      return { status: 204 };
    }
    if (what === "role-assignments" && name && method === "PUT") {
      const roles = [...((given().roles as string[]) ?? [])].sort();
      const unknown = roles.find((r) => !this.roles.has(r));
      if (unknown) return { status: 404, body: { error: "no_such_role", role: unknown } };
      this.give(name, [`brand_${brand}`, ...roles]);
      return { body: { operator: name, brand, roles } };
    }
    return { status: 405, body: { error: "method_not_allowed" } };
  }
}
