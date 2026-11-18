import { test as base } from "playwright-bdd";
import { startDoubleSite, type DoubleSite } from "../doubles/serve.ts";
import type { FakeBrand } from "@eitheror/player-sdk/testing";

let nextPort = 18_500;

/** The reference site over the doubles, started by a scenario's first step and closed after it. */
export interface Sites {
  start(brand?: Partial<FakeBrand>, dist?: string): Promise<DoubleSite>;
  readonly current: DoubleSite;
}

export const test = base.extend<{ sites: Sites }>({
  sites: async ({}, use) => {
    const open: DoubleSite[] = [];
    await use({
      async start(brand, dist) {
        const site = await startDoubleSite({ port: nextPort++, ...(brand ? { brand } : {}), ...(dist ? { dist } : {}) });
        open.push(site);
        return site;
      },
      get current() {
        const site = open[0];
        if (!site) throw new Error("no reference site started in this scenario");
        return site;
      },
    });
    for (const site of open) await site.close();
  },
});
