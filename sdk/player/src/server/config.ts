/** The site server's configuration (data-model.md, *Site configuration*), from the environment. */
export interface SiteConfig {
  readonly brand: string;
  /** Run on a developer's machine over plain HTTP: plain cookie names and no `Secure`. Never from a request. */
  readonly local: boolean;
  /** The modules the brand runs, its own list (`CASINO_MODULES`); which behaviours are offered. */
  readonly modules: readonly string[];
  readonly languages: readonly string[];
  readonly currency: string;
  /** The origin the server accepts a change from. */
  readonly siteBase: string;
  /** The calling address: `<servicesUrl>/<service>/<path>`. */
  readonly servicesUrl: string;
  /** The shared payments' public address, where the cashier is. */
  readonly cashierUrl: string;
  readonly port: number;
  /** How long a service has to answer (a stream: to begin). */
  readonly boundMs: number;
}

export class MissingConfiguration extends Error {
  readonly names: string[];
  constructor(names: string[]) {
    super(`the site server needs ${names.join(", ")}`);
    this.names = names;
  }
}

const list = (text: string) => text.split(",").map((s) => s.trim()).filter((s) => s !== "");

/** Reads the configuration, refusing a missing field by its name. */
export function readConfig(env: Readonly<Record<string, string | undefined>> = process.env): SiteConfig {
  const required = ["CASINO_BRAND", "CASINO_MODULES", "PLAYER_SITE_LANGUAGES", "PLAYER_SITE_CURRENCY", "PLAYER_SITE_BASE", "ANKKA_SERVICES_URL", "INGEST_URL"] as const;
  const missing = required.filter((n) => !env[n]?.trim());
  if (missing.length) throw new MissingConfiguration([...missing]);
  const get = (n: (typeof required)[number]) => env[n]!.trim();
  const modules = list(get("CASINO_MODULES"));
  return {
    brand: get("CASINO_BRAND"),
    local: ["1", "true", "yes"].includes((env.PLAYER_SITE_LOCAL ?? "").trim().toLowerCase()),
    modules: modules.includes("core") ? modules : ["core", ...modules],
    languages: list(get("PLAYER_SITE_LANGUAGES")),
    currency: get("PLAYER_SITE_CURRENCY"),
    siteBase: new URL(get("PLAYER_SITE_BASE")).origin,
    servicesUrl: get("ANKKA_SERVICES_URL").replace(/\/+$/, ""),
    cashierUrl: get("INGEST_URL").replace(/\/+$/, ""),
    port: Number.parseInt(env.PORT ?? "8080", 10) || 8080,
    boundMs: 15_000,
  };
}
