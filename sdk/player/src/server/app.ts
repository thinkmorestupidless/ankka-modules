import { Hono } from "hono";
import { readConfig, type SiteConfig } from "./config.ts";
import type { LogLine, ServerDeps } from "./context.ts";
import { passThrough } from "./passThrough.ts";
import { sessionRoutes } from "./session.ts";
import { staticFiles } from "./static.ts";

export interface SiteServerOptions {
  /** The built site's directory. */
  readonly static?: string;
  readonly config?: SiteConfig;
  readonly fetch?: typeof fetch;
  readonly now?: () => number;
  readonly log?: (line: LogLine) => void;
}

const defaultLog = (line: LogLine) => console.log(JSON.stringify(line));

/** Which name a request is logged under: the route id, never its query. */
function logged(path: string, method: string): string {
  const m = /^\/api\/c\/([^/]+)\/([^/?]+)/.exec(path);
  if (m) return `${m[1]}/${m[2]}`;
  if (path.startsWith("/api/")) return path;
  return method === "GET" ? "page" : path;
}

/**
 * The site server (contracts/site-server.md): the session routes, the pass-through, the built
 * site. Every request other than a `GET` must come from the site's own pages (research R3).
 */
export function siteServer(options: SiteServerOptions = {}): Hono {
  const deps: ServerDeps = {
    config: options.config ?? readConfig(),
    fetch: options.fetch ?? ((input, init) => fetch(input, init)),
    now: options.now ?? Date.now,
    log: options.log ?? defaultLog,
  };
  const app = new Hono();
  app.use("*", async (c, next) => {
    const started = deps.now();
    if (!["GET", "HEAD", "OPTIONS"].includes(c.req.method) && c.req.header("origin") !== deps.config.siteBase) {
      c.res = new Response(JSON.stringify({ error: "wrong_origin" }), { status: 403, headers: { "content-type": "application/json" } });
    } else {
      await next();
    }
    deps.log({ method: c.req.method, route: logged(new URL(c.req.url).pathname, c.req.method), status: c.res.status, ms: deps.now() - started });
  });
  app.route("/", sessionRoutes(deps));
  app.route("/", passThrough(deps));
  app.all("/api/*", () => new Response(JSON.stringify({ error: "not_named" }), { status: 404, headers: { "content-type": "application/json" } }));
  if (options.static) app.route("/", staticFiles(options.static));
  return app;
}
