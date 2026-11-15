// The page's server: one program on PORT (contracts/routes.md). It serves the built page, signs the
// operator in, and passes the page's calls to the brand's services with the operator's token. It stores
// nothing; `backoffice-state` keeps the sign-ins. Node runs this file directly.
import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { Hono, type Context } from "hono";
import { getRequestListener } from "@hono/node-server";
import { dropSession, sessionOf, stateClient, TokenCache, type StateClient, type Token } from "./session.ts";
import { match, table as routeTable, type Row } from "./routes.ts";
import { pass, unavailable } from "./proxy.ts";
import { authRoutes, type Realm } from "./auth.ts";
import { eventRoutes } from "./events.ts";
import { brandReads, inBrand, permissionsOf, PlatformAdmin, type BrandReads } from "./roles.ts";

export interface Deps {
  /** The brand this backoffice is, from `CASINO_BRAND`; the browser never names one. */
  brand: string;
  /** `ANKKA_SERVICES_URL`: the calling address. */
  servicesUrl: string;
  /** A shared project's key to its project's name, from `BACKOFFICE_SHARED_PROJECTS`. */
  sharedProjects: Record<string, string>;
  state: StateClient;
  fetch: typeof fetch;
  log: (line: string) => void;
  now: () => number;
  table: readonly Row[];
  /** The bound on a call to an owner. */
  timeoutMs: number;
  /** The built page; served only when given (production). */
  dist?: string;
  realm: Realm;
  /** `BACKOFFICE_HOST`: this backoffice's own address, the realm client's redirect base. */
  address: string;
  /** The brand's roles and modules from players; made from the calling address when not given. */
  reads?: BrandReads;
  /** How often `/api/events` compares the operator's permissions; 5 s when not given. */
  eventsEveryMs?: number;
}

/** The claims of the realm's token, read without checking it: it came from `backoffice-state`. */
export function claimsOf(token: string): { sub?: string; name?: string; preferred_username?: string } {
  try {
    return JSON.parse(Buffer.from(token.split(".")[1] ?? "", "base64url").toString("utf8")) as Record<string, string>;
  } catch {
    return {};
  }
}

export type Env = { Variables: { session: string; token: Token; logPath: string; why: string } };

const Reads = new Set(["GET", "HEAD"]);

/** The preferences the page writes; `jobs` are the server's own (data-model.md). */
const PageWritable = new Set(["theme", "worklistKinds", "onlineNotices"]);

/** The address the proxy says this request was sent to, as an `Origin` would name it. */
function selfOrigin(c: Context): string | undefined {
  const host = c.req.header("x-forwarded-host") ?? c.req.header("host");
  return host ? `${c.req.header("x-forwarded-proto") ?? "http"}://${host}` : undefined;
}

export function createApp(deps: Deps): Hono<Env> {
  const app = new Hono<Env>();
  const tokens = new TokenCache(deps.state, deps.now);
  const reads = deps.reads ?? brandReads(deps.servicesUrl, deps.brand, deps.fetch, deps.now);

  // One line a request: the route's template, never its parameters, never a token.
  app.use("*", async (c, next) => {
    const started = deps.now();
    await next();
    const named = c.get("logPath") ?? (c.req.path.startsWith("/api/") ? "/api/…" : c.req.path.startsWith("/auth/") ? c.req.path : "page");
    deps.log(JSON.stringify({ method: c.req.method, route: named, status: c.res.status, ms: deps.now() - started, ...(c.get("why") ? { why: c.get("why") } : {}) }));
  });

  // A write comes only from this site's page.
  app.use("*", async (c, next) => {
    if (Reads.has(c.req.method)) return next();
    const origin = selfOrigin(c);
    if (c.req.header("x-backoffice") !== "1" || origin === undefined || c.req.header("origin") !== origin)
      return c.json({ error: "bad_origin" }, 403);
    return next();
  });

  app.get("/healthz", (c) => c.text("ok"));
  authRoutes(app, { realm: deps.realm, address: deps.address, state: deps.state, tokens, now: deps.now });

  // Every /api route needs a session the state service still holds.
  app.use("/api/*", async (c, next) => {
    const session = sessionOf(c);
    if (session === undefined) return c.json({ error: "signed_out" }, 401);
    let token: Token | undefined;
    try {
      token = await tokens.token(session);
    } catch (e) {
      c.set("why", e instanceof Error ? e.name : "unknown");
      return unavailable("backoffice-state");
    }
    if (token === undefined) {
      dropSession(c);
      return c.json({ error: "signed_out" }, 401);
    }
    c.set("session", session);
    c.set("token", token);
    return next();
  });

  // The operator as this brand knows them; the permissions opened here from their roles and the brand's bags.
  app.get("/api/me", async (c) => {
    c.set("logPath", "/api/me");
    const token = c.get("token");
    if (!inBrand(token.roles, deps.brand)) return c.json({ error: "brand_not_in_roles", brand: deps.brand }, 403);
    const [bags, modules] = await Promise.all([reads.bags.get(), reads.modules.get()]);
    if (bags === undefined && !token.roles.includes(PlatformAdmin)) return unavailable("players");
    const claims = claimsOf(token.accessToken);
    const preferences = claims.sub ? await deps.state.preferences(claims.sub).catch(() => null) : null;
    return c.json({
      subject: claims.sub ?? null,
      name: claims.name ?? claims.preferred_username ?? null,
      roles: [...token.roles].sort(),
      brand: { slug: deps.brand, name: deps.brand, modules: modules ?? null },
      preferences,
      permissions: permissionsOf(token.roles, bags ?? {}),
    });
  });

  // The operator's own preferences: theme, worklist kinds, online notices. Jobs are kept by /api/jobs.
  app.put("/api/preferences", async (c) => {
    c.set("logPath", "/api/preferences");
    const subject = claimsOf(c.get("token").accessToken).sub;
    if (!subject) return c.json({ error: "signed_out" }, 401);
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: "invalid_preference", message: "the body is not a JSON object" }, 400);
    }
    if (body === null || typeof body !== "object" || Array.isArray(body))
      return c.json({ error: "invalid_preference", message: "the body is not a JSON object" }, 400);
    const unknown = Object.keys(body).find((k) => !PageWritable.has(k));
    if (unknown !== undefined) return c.json({ error: "unknown_preference", message: `no preference '${unknown}' is written here` }, 400);
    try {
      const r = await deps.state.writePreferences(subject, JSON.stringify(body));
      return new Response(r.body, { status: r.status, headers: { "Content-Type": "application/json" } });
    } catch (e) {
      c.set("why", e instanceof Error ? e.name : "unknown");
      return unavailable("backoffice-state");
    }
  });

  eventRoutes(app, { brand: deps.brand, tokens, reads, everyMs: deps.eventsEveryMs ?? 5_000 });

  // The brand's roles as players holds them now, so the page can name a role that would give a permission.
  app.get("/api/permissions", async (c) => {
    c.set("logPath", "/api/permissions");
    const bags = await reads.bags.get();
    return bags === undefined ? unavailable("players") : c.json({ roles: bags });
  });

  app.all("/api/s/:service/*", async (c) => {
    const service = c.req.param("service");
    const pathname = new URL(c.req.url).pathname;
    const prefix = `/api/s/${service}`;
    const m = /^[a-z0-9-]+$/.test(service) ? match(deps.table, c.req.method, service, pathname.slice(prefix.length)) : undefined;
    if (m === undefined) return c.json({ error: "no_such_route" }, 404);
    c.set("logPath", `${prefix}${m.row.path}`);
    const { response, why } = await pass(deps, m, c.req.raw, c.get("token").accessToken);
    if (why) c.set("why", why);
    return response;
  });

  if (deps.dist) {
    const dist = deps.dist;
    app.get("*", (c) => serveBuilt(dist, new URL(c.req.url).pathname));
  }
  return app;
}

const types: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".json": "application/json",
  ".woff2": "font/woff2",
};

async function isFile(file: string): Promise<boolean> {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

/** A built file, immutable under assets/; any other path that is not a file is the page, uncached (FR-042). */
export async function serveBuilt(dist: string, pathname: string): Promise<Response> {
  const file = path.join(dist, decodeURIComponent(pathname));
  if (!file.startsWith(dist + path.sep) && file !== dist) return new Response(null, { status: 404 });
  if (await isFile(file)) {
    const immutable = file.startsWith(path.join(dist, "assets") + path.sep);
    return new Response(await readFile(file), {
      headers: {
        "Content-Type": types[path.extname(file)] ?? "application/octet-stream",
        "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
      },
    });
  }
  if (path.extname(pathname) !== "") return new Response(null, { status: 404 });
  return new Response(await readFile(path.join(dist, "index.html")), {
    headers: { "Content-Type": types[".html"]!, "Cache-Control": "no-cache" },
  });
}

/** `payments=payments,programme=programme` to a map. */
export function sharedProjects(text: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const pair of (text ?? "").split(",")) {
    const [key, project] = pair.split("=").map((s) => s.trim());
    if (key && project) out[key] = project;
  }
  return out;
}

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    console.error(`${name} is not set`);
    process.exit(2);
  }
  return v;
}

async function main(): Promise<void> {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
  const port = Number(process.env.PORT ?? 3000);
  const production = process.env.NODE_ENV === "production";
  const servicesUrl = required("ANKKA_SERVICES_URL").replace(/\/$/, "");
  const app = createApp({
    brand: required("CASINO_BRAND"),
    servicesUrl,
    sharedProjects: sharedProjects(process.env.BACKOFFICE_SHARED_PROJECTS),
    state: stateClient(servicesUrl, fetch),
    fetch,
    log: (line) => console.log(line),
    now: () => Date.now(),
    table: routeTable,
    timeoutMs: 15_000,
    dist: production ? path.join(root, "dist") : undefined,
    realm: { issuer: required("ANKKA_AUTH_STAFF_ISSUER").replace(/\/$/, ""), clientId: `backoffice-${required("CASINO_BRAND")}` },
    address: required("BACKOFFICE_HOST").replace(/\/$/, ""),
  });
  const serve = getRequestListener(app.fetch);
  let page: ((req: http.IncomingMessage, res: http.ServerResponse, next: () => void) => void) | undefined;
  if (!production) {
    const { createServer } = await import("vite");
    page = (await createServer({ configFile: path.join(root, "vite.config.ts"), server: { middlewareMode: true }, appType: "spa" })).middlewares;
  }
  const ours = (url: string) => url === "/healthz" || url.startsWith("/api/") || url.startsWith("/auth/");
  const server = http.createServer((req, res) => {
    if (page && !ours(req.url ?? "/")) return page(req, res, () => void res.writeHead(404).end());
    void serve(req, res);
  });
  server.listen(port, () => console.log(JSON.stringify({ started: "backoffice", port })));
  // A rollout stops an instance: finish what is in flight, take nothing new.
  process.on("SIGTERM", () => {
    server.close(() => process.exit(0));
    server.closeIdleConnections();
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
