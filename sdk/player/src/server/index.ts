import { serve } from "@hono/node-server";
import { siteServer, type SiteServerOptions } from "./app.ts";
import { readConfig } from "./config.ts";

export { siteServer, type SiteServerOptions } from "./app.ts";
export { readConfig, MissingConfiguration, type SiteConfig } from "./config.ts";
export type { LogLine } from "./context.ts";

/** Starts the site server on `PORT`, as the platform's proxy expects (ankka web hosting). */
export function startSiteServer(options: SiteServerOptions = {}) {
  const config = options.config ?? readConfig();
  const app = siteServer({ ...options, config });
  return serve({ fetch: app.fetch, port: config.port, hostname: "0.0.0.0" });
}
