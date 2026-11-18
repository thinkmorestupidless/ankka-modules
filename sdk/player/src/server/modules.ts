import { routes as casino } from "../casino/routes.ts";
import { routes as core } from "../core/routes.ts";
import { routes as engagement } from "../engagement/routes.ts";
import type { ModuleName, Route } from "../route.ts";
import { routes as support } from "../support/routes.ts";
import type { SiteConfig } from "./config.ts";

/** Every module's route table: the site server's allow-list (FR-004). */
export const tables: Readonly<Record<ModuleName, readonly Route[]>> = { core, casino, engagement, support };

export const allRoutes: readonly Route[] = Object.values(tables).flat();

/** A row a page may ask for: in its module's table, with its method, and not the server's own. */
export function pageRoute(module: string, id: string, method: string): Route | undefined {
  const table = (tables as Record<string, readonly Route[] | undefined>)[module];
  const row = table?.find((r) => r.id === id);
  if (!row || row.serverOnly || row.method !== method) return undefined;
  return row;
}

/** Whether the brand runs a module, by its own list (`CASINO_MODULES`), never by whether a service answers. */
export function runs(config: SiteConfig, module: string): boolean {
  return config.modules.includes(module);
}
