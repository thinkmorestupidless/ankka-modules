import type { Route } from "../../src/route.ts";
import { readConfig, type SiteConfig } from "../../src/server/config.ts";
import type { LogLine, ServerDeps } from "../../src/server/context.ts";
import { readCookies } from "../../src/server/cookies.ts";
import { doubles as makeDoubles, serveDoubles, type Doubles } from "../../src/testing/doubles.ts";

export const SITE = "https://player-site-guyanabet.example";

/** The doubles at a calling address, and the deps of a site server calling them. */
export async function served(routes: readonly Route[], options: { config?: Partial<SiteConfig>; doubles?: Doubles; boundMs?: number } = {}) {
  const d = options.doubles ?? makeDoubles();
  const s = await serveDoubles(d, routes);
  const config: SiteConfig = {
    ...readConfig({
      CASINO_BRAND: "guyanabet",
      CASINO_MODULES: "core,casino,engagement,support,player-site",
      PLAYER_SITE_LANGUAGES: "fr,en",
      PLAYER_SITE_CURRENCY: "EUR",
      PLAYER_SITE_BASE: SITE,
      ANKKA_SERVICES_URL: s.url,
      INGEST_URL: `${s.url}/ingest`,
    }),
    ...(options.boundMs ? { boundMs: options.boundMs } : {}),
    ...options.config,
  };
  const logs: LogLine[] = [];
  const outbound: Array<{ url: string; headers: Record<string, string>; body?: string }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    const headers = Object.fromEntries(new Headers(init?.headers).entries());
    outbound.push({ url: String(input), headers, ...(typeof init?.body === "string" ? { body: init.body } : {}) });
    return fetch(input, init);
  };
  const deps: ServerDeps = { config, fetch: fetchImpl, now: () => Date.now(), log: (l) => logs.push(l) };
  return { doubles: d, deps, config, logs, outbound, close: s.close };
}

/** The cookies a response sets, by name, with their attributes. */
export function setCookies(r: Response): Map<string, { value: string; attributes: string[] }> {
  const out = new Map<string, { value: string; attributes: string[] }>();
  for (const line of r.headers.getSetCookie()) {
    const [first, ...attributes] = line.split(";").map((s) => s.trim());
    const i = first!.indexOf("=");
    out.set(first!.slice(0, i), { value: decodeURIComponent(first!.slice(i + 1)), attributes });
  }
  return out;
}

/** A browser's cookie jar, kept from each response's `Set-Cookie`. */
export function jar() {
  const cookies = new Map<string, string>();
  return {
    take(r: Response) {
      for (const [name, c] of setCookies(r)) {
        if (c.attributes.includes("Max-Age=0")) cookies.delete(name);
        else cookies.set(name, c.value);
      }
      return r;
    },
    header: () => [...cookies].map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("; "),
    get: (name: string) => cookies.get(name),
    delete: (name: string) => cookies.delete(name),
    names: () => [...cookies.keys()].sort(),
  };
}

export { readCookies };

import { siteServer } from "../../src/server/app.ts";
import { allRoutes } from "../../src/server/modules.ts";

/** A whole site server over the HTTP doubles, with a browser that keeps its cookies. */
export async function wholeSite(options: Parameters<typeof served>[1] & { static?: string } = {}) {
  const s = await served(allRoutes, options);
  const app = siteServer({ config: s.config, fetch: s.deps.fetch, log: s.deps.log, ...(options.static ? { static: options.static } : {}) });
  const browser = jar();
  const request = async (method: string, path: string, init: { body?: unknown; headers?: Record<string, string>; origin?: string | null } = {}) => {
    const headers: Record<string, string> = { cookie: browser.header(), ...(init.headers ?? {}) };
    if (init.origin !== null && method !== "GET") headers.origin = init.origin ?? SITE;
    if (init.body !== undefined) headers["content-type"] = "application/json";
    return browser.take(await app.request(path, { method, headers, ...(init.body !== undefined ? { body: JSON.stringify(init.body) } : {}) }));
  };
  const signIn = () => request("POST", "/api/session/sign-in", { body: { email: "ana@example.com", password: "correct horse battery 1!" } });
  return { ...s, app, browser, request, signIn };
}
