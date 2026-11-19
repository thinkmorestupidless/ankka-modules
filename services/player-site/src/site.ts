import type { PlayerSiteOptions } from "@eitheror/player-sdk";

/**
 * The reference site's configuration of the SDK's behaviours (data-model.md, *Site
 * configuration*): its preset amounts and bounds per currency, in major units, and its idle times.
 * The brand, the languages and the currency are the server's (`/api/site`); no address of a
 * service is the site's.
 */
export const siteOptions = {
  presets: { EUR: ["10", "25", "50", "100", "200", "500"] },
  bounds: { EUR: { minimum: "5" } },
  idleMinutes: 30,
  idleWarningSeconds: 30,
} as const satisfies Omit<PlayerSiteOptions, "transport">;
