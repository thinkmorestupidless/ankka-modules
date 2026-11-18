import { join } from "node:path";
import { describeFeature, loadFeature, type FeatureDescriibeCallbackParams } from "@amiceli/vitest-cucumber";
import { playerSite, type PlayerSite } from "../../src/site.ts";
import { handClock, type HandClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport, type Doubles, type FakeBrand } from "../../src/testing/doubles.ts";
import type { Transport } from "../../src/transport.ts";
import { elsewhere } from "./elsewhere.ts";

export const featuresDir = join(import.meta.dirname, "../../../../features/player-site");

/** What a scenario's steps share: the brand's doubles, the hand clock, a transport over them and a site. */
export interface World {
  doubles: Doubles;
  clock: HandClock;
  transport: Transport;
  site: PlayerSite;
}

export async function world(brand: Partial<FakeBrand> = {}): Promise<World> {
  const clock = handClock();
  const d = doubles({ brand, clock });
  const transport = doubleTransport(d);
  const site = playerSite({ transport, clock });
  await site.ready;
  return { doubles: d, clock, transport, site };
}

/**
 * Binds one feature file of `features/player-site/`, skipping the scenarios `elsewhere.ts` names
 * for it; vitest-cucumber fails the run for any other scenario without steps.
 */
export async function bind(file: string, steps: (params: FeatureDescriibeCallbackParams) => void): Promise<void> {
  const feature = await loadFeature(join(featuresDir, file));
  describeFeature(feature, (params) => {
    for (const e of elsewhere.filter((x) => x.feature === file)) {
      const scenario = feature.scenarii.find((s) => s.description === e.scenario);
      if (!scenario) throw new Error(`${file} has no scenario "${e.scenario}"`);
      // A skipped scenario still names its steps; they are no-ops here.
      const noSteps = (s: Record<string, (text: string, fn: () => void) => void>) => {
        for (const step of scenario.steps) s[step.type]!(step.details, () => {});
      };
      if ("examples" in scenario) params.ScenarioOutline.skip(e.scenario, noSteps as never);
      else params.Scenario.skip(e.scenario, noSteps as never);
    }
    steps(params);
  });
}
