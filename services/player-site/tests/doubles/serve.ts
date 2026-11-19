// The reference site over the services' doubles, at one address, for the browser suites and for a
// person to look at it: `node tests/doubles/serve.ts` (after `npm run build`), then open the address
// it prints. The doubles are the SDK's (`@eitheror/player-sdk/testing`), served over HTTP as the
// platform's calling address is.
import { serve } from "@hono/node-server";
import path from "node:path";
import { allRoutes, readConfig, siteServer } from "@eitheror/player-sdk/server";
import { doubles, serveDoubles, type Doubles, type FakeBrand } from "@eitheror/player-sdk/testing";

const here = path.dirname(new URL(import.meta.url).pathname);

export interface DoubleSite {
  readonly url: string;
  readonly doubles: Doubles;
  close(): Promise<void>;
}

export async function startDoubleSite(options: { port?: number; brand?: Partial<FakeBrand>; dist?: string } = {}): Promise<DoubleSite> {
  const d = doubles({ brand: options.brand ?? {} });
  const served = await serveDoubles(d, allRoutes);
  const port = options.port ?? 18_430;
  const url = `http://localhost:${port}`;
  const config = readConfig({
    CASINO_BRAND: d.brand.brand,
    CASINO_MODULES: d.brand.modules.join(","),
    PLAYER_SITE_LANGUAGES: d.brand.languages.join(","),
    PLAYER_SITE_CURRENCY: d.brand.currency,
    PLAYER_SITE_BASE: url,
    PLAYER_SITE_LOCAL: "true",
    ANKKA_SERVICES_URL: served.url,
    INGEST_URL: `${served.url}/ingest`,
    PORT: String(port),
  });
  const app = siteServer({ config, static: options.dist ?? path.join(here, "../../dist"), log: () => {} });
  const server = serve({ fetch: app.fetch, port, hostname: "127.0.0.1" });
  return {
    url,
    doubles: d,
    async close() {
      for (const c of d.brand.pushes) c.end();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await served.close();
    },
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const site = await startDoubleSite({ port: Number(process.env.PORT ?? 18_430) });
  console.log(`the reference site over the doubles: ${site.url} (ana@example.com / correct horse battery 1!)`);
}
