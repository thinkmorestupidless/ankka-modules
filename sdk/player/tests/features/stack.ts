import { siteServer } from "../../src/server/app.ts";
import { readConfig, type SiteConfig } from "../../src/server/config.ts";
import type { LogLine } from "../../src/server/context.ts";
import { allRoutes } from "../../src/server/modules.ts";
import { playerSite, type PlayerSite } from "../../src/site.ts";
import { handClock, type HandClock } from "../../src/testing/clock.ts";
import { doubles, serveDoubles, type Doubles, type FakeBrand } from "../../src/testing/doubles.ts";
import { pageTransport, type Transport } from "../../src/transport.ts";
import { jar, SITE } from "../server/helpers.ts";

/**
 * The whole way from a page to the brand: a page transport, the site server, and the doubles at
 * a calling address over HTTP. The browser's cookie jar is kept here; the page's script never
 * sees it, as in a browser, where the cookies are HttpOnly.
 */
export interface Stack {
  doubles: Doubles;
  clock: HandClock;
  transport: Transport;
  site: PlayerSite;
  browser: ReturnType<typeof jar>;
  /** Every answer the site server gave the page, as text. */
  answers: Array<{ path: string; status: number; headers: [string, string][]; body: string }>;
  logs: LogLine[];
  config: SiteConfig;
  /** A new page in the same browser: a new transport and site, the same cookies. */
  reload(options?: { known?: boolean }): Promise<void>;
  close(): Promise<void>;
}

export async function stack(options: { brand?: Partial<FakeBrand>; boundMs?: number; config?: Partial<SiteConfig> } = {}): Promise<Stack> {
  const clock = handClock();
  const d = doubles({ brand: options.brand ?? {} });
  const served = await serveDoubles(d, allRoutes);
  const config: SiteConfig = {
    ...readConfig({
      CASINO_BRAND: d.brand.brand,
      CASINO_MODULES: d.brand.modules.join(","),
      PLAYER_SITE_LANGUAGES: d.brand.languages.join(","),
      PLAYER_SITE_CURRENCY: d.brand.currency,
      PLAYER_SITE_BASE: SITE,
      ANKKA_SERVICES_URL: served.url,
      INGEST_URL: `${served.url}/ingest`,
    }),
    boundMs: options.boundMs ?? 300,
    ...options.config,
  };
  const logs: LogLine[] = [];
  const app = siteServer({ config, log: (l) => logs.push(l) });
  const browser = jar();
  const answers: Stack["answers"] = [];
  const pageFetch = async (input: string, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    headers.set("cookie", browser.header());
    if ((init?.method ?? "GET") !== "GET") headers.set("origin", SITE);
    const r = browser.take(await app.request(input, { ...init, headers }));
    const body = await r.clone().text();
    answers.push({ path: input, status: r.status, headers: [...r.headers], body });
    return r;
  };
  const s: Stack = {
    doubles: d,
    clock,
    browser,
    answers,
    logs,
    config,
    transport: pageTransport(pageFetch),
    site: undefined as unknown as PlayerSite,
    async reload() {
      s.site?.stop();
      s.transport = pageTransport(pageFetch);
      s.site = playerSite({ transport: s.transport, clock });
      await s.site.ready;
    },
    close: served.close,
  };
  await s.reload();
  return s;
}
