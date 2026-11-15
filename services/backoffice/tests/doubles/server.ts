// The services double: one process at the calling address (`ANKKA_SERVICES_URL`). The first path
// segment names the owner, as the platform's proxy reads it: `/players/…` is the brand's `players`,
// `/merchant.payments/…` the shared project's `merchant` (research R3; V3 in README.md). Each owner
// answers from `fixtures/<owner>.json`; `/realms/eitheror/…` is the double realm; `/__double/…` is
// the tests' control (README.md). Run it with `node tests/doubles/server.ts` (DOUBLES_PORT).
import http from "node:http";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { table } from "../../src/server/routes.ts";
import permissions from "../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import { Owner, type Answer, type Asked, type Fixtures, type Script } from "./owner.ts";
import { Realm } from "./realm.ts";
import type { Preferences } from "../../src/server/session.ts";

const here = path.dirname(fileURLToPath(import.meta.url));
export const FixturesDir = path.join(here, "fixtures");

/** The seeded brand (`fixtures/brand.json`). */
export interface Seed {
  slug: string;
  name: string;
  zone: string;
  currencies: string[];
  defaultCurrency: string;
  modules: string[];
  preferences: Preferences;
  players: { playerId: string; username: string; currency: string }[];
}

export function seed(): Seed {
  return JSON.parse(readFileSync(path.join(FixturesDir, "brand.json"), "utf8")) as Seed;
}

/** Every owner the route table names, at its calling address's first segment, and `backoffice-state`. */
export function ownerNames(): string[] {
  const names = new Set(table.map((r) => (r.where === "brand" ? r.service : `${r.service}.${r.where}`)));
  names.add("backoffice-state");
  return [...names].sort();
}

function read(file: string): Fixtures {
  return existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Fixtures) : {};
}

function scripted(name: string): Answer {
  const file = path.join(FixturesDir, "scripted", `${name}.json`);
  if (!/^[\w./-]+$/.test(name) || name.includes("..") || !existsSync(file)) return { status: 500, body: { error: "no_scripted_fixture", name } };
  return JSON.parse(readFileSync(file, "utf8")) as Answer;
}

/** The brand's roles as players answers them, from the contract's defaults unless the fixture names them. */
function rolesOf(): Fixtures {
  return {
    "GET /v1/casino/brands/{brand}/players/roles": {
      anonymous: true,
      body: {
        roles: Object.entries(permissions.defaults).map(([name, ps]) => ({ name, description: "", permissions: [...ps].sort(), default: true })),
      },
    },
  };
}

export interface Doubles {
  url: string;
  owners: Map<string, Owner>;
  realm: Realm;
  close(): Promise<void>;
}

const send = (res: http.ServerResponse, a: Answer) => {
  const status = a.status ?? 200;
  const headers: Record<string, string> = { ...(a.headers ?? {}) };
  if (status === 204 || a.body === undefined) return void res.writeHead(status, headers).end();
  const text = typeof a.body === "string" && headers["Content-Type"] ? a.body : JSON.stringify(a.body);
  headers["Content-Type"] ??= "application/json";
  res.writeHead(status, headers).end(text);
};

const bodyOf = (req: http.IncomingMessage) =>
  new Promise<string>((resolve, reject) => {
    const parts: Buffer[] = [];
    req.on("data", (b: Buffer) => parts.push(b));
    req.on("end", () => resolve(Buffer.concat(parts).toString("utf8")));
    req.on("error", reject);
  });

const flat = (h: http.IncomingHttpHeaders) =>
  Object.fromEntries(Object.entries(h).map(([k, v]) => [k, Array.isArray(v) ? v.join(", ") : (v ?? "")]));

export async function startDoubles(port = 0): Promise<Doubles> {
  const brand = seed();
  const realm = new Realm(() => structuredClone(seed().preferences));
  const owners = new Map<string, Owner>();
  for (const name of ownerNames())
    owners.set(name, new Owner(name, scripted, name === "backoffice-state" ? (a) => realm.state(a) : undefined));
  const reset = () => {
    realm.reset();
    for (const [name, o] of owners) o.load({ ...(name === "players" ? rolesOf() : {}), ...read(path.join(FixturesDir, `${name}.json`)) });
  };
  reset();

  const control = async (req: http.IncomingMessage, res: http.ServerResponse, url: URL, body: string) => {
    const parts = url.pathname.split("/").slice(2).map(decodeURIComponent);
    const given = () => (body ? (JSON.parse(body) as Record<string, unknown>) : {});
    const owner = (name: string | undefined) => (name ? owners.get(name) : undefined);
    const [what, a, b] = parts;
    if (req.method === "POST" && what === "reset") return send(res, { body: { reset: true } });
    if (req.method === "GET" && what === "seed") return send(res, { body: brand });
    if (req.method === "GET" && what === "owners") return send(res, { body: [...owners.keys()] });
    if (req.method === "POST" && what === "script") {
      const s = given() as Script & { owner?: string };
      const o = owner(s.owner);
      if (!o) return send(res, { status: 404, body: { error: "no_such_owner", owner: s.owner } });
      o.script(s);
      return send(res, { body: { scripted: s.owner } });
    }
    if (req.method === "PUT" && what === "fixtures" && owner(a)) {
      owner(a)!.merge(given() as Fixtures);
      return send(res, { body: { merged: a } });
    }
    if (req.method === "GET" && what === "calls") {
      const name = url.searchParams.get("owner");
      const all = [...owners.values()].filter((o) => !name || o.name === name).flatMap((o) => o.calls);
      return send(res, { body: all.sort((x, y) => x.at - y.at) });
    }
    if (req.method === "DELETE" && what === "calls") {
      for (const o of owners.values()) o.calls.length = 0;
      return send(res, { body: { cleared: true } });
    }
    if (req.method === "PUT" && what === "preferences" && a) {
      realm.setPreferences(a, given() as Partial<Preferences>);
      return send(res, { body: { subject: a } });
    }
    if (req.method === "PUT" && what === "subjects" && a && b === "roles") {
      realm.setRoles(a, (given().roles as string[]) ?? []);
      return send(res, { body: { subject: a } });
    }
    if (req.method === "PUT" && what === "settings") {
      const s = given();
      if (typeof s.tokenLifeMs === "number") realm.tokenLifeMs = s.tokenLifeMs;
      return send(res, { body: { tokenLifeMs: realm.tokenLifeMs } });
    }
    send(res, { status: 404, body: { error: "no_such_control", path: url.pathname } });
  };

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url ?? "/", "http://doubles");
      const body = await bodyOf(req);
      if (url.pathname === "/healthz") return send(res, { body: { ok: true } });
      if (url.pathname.startsWith("/__double/")) {
        if (url.pathname === "/__double/reset" && req.method === "POST") reset();
        return await control(req, res, url, body);
      }
      if (url.pathname === "/realms/eitheror/protocol/openid-connect/auth") {
        const out = realm.authorise(url, req.headers.cookie);
        return "location" in out ? void res.writeHead(302, { Location: out.location }).end() : send(res, out);
      }
      const [, first = "", ...rest] = url.pathname.split("/");
      const o = owners.get(first);
      if (!o) {
        const [service, project] = first.split(".");
        const why = project ? `no service '${service}' in the project '${project}'` : `no service '${first}'`;
        return send(res, { status: 503, body: { error: why }, headers: { "X-Ankka-Answered-By": "proxy" } });
      }
      const asked: Asked = { method: req.method ?? "GET", path: `/${rest.join("/")}`, query: url.searchParams, headers: flat(req.headers), body };
      const out = o.handle(asked);
      if (out.delayMs > 0) await new Promise((r) => setTimeout(r, out.delayMs));
      if ("drop" in out) return void req.socket.destroy();
      send(res, out.answer);
    } catch (e) {
      send(res, { status: 500, body: { error: "double_failed", message: e instanceof Error ? e.message : String(e) } });
    }
  });
  await new Promise<void>((resolve) => server.listen(port, resolve));
  const at = server.address();
  const url = `http://127.0.0.1:${typeof at === "object" && at ? at.port : port}`;
  return {
    url,
    owners,
    realm,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const d = await startDoubles(Number(process.env.DOUBLES_PORT ?? 4710));
  console.log(JSON.stringify({ started: "doubles", url: d.url, owners: [...d.owners.keys()] }));
  process.on("SIGTERM", () => void d.close().then(() => process.exit(0)));
  process.on("SIGINT", () => void d.close().then(() => process.exit(0)));
}
