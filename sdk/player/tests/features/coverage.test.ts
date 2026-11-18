import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { elsewhere, unbound, wholly } from "./elsewhere.ts";
import { featuresDir } from "./setup.ts";

const files = readdirSync(featuresDir).filter((f) => f.endsWith(".feature"));
const scenarios = (file: string) => [...readFileSync(join(featuresDir, file), "utf8").matchAll(/^\s*Scenario(?: Outline)?:\s*(.+?)\s*$/gm)].map((m) => m[1]!);

describe("every scenario of features/player-site is bound somewhere", () => {
  it("each feature file has its steps here, or is named as bound wholly elsewhere or not yet bound", () => {
    for (const f of files) {
      const steps = existsSync(join(import.meta.dirname, f.replace(/\.feature$/, ".steps.ts")));
      expect(steps || f in wholly || f in unbound, f).toBe(true);
      expect(steps && f in unbound, `${f} has steps and is still listed unbound`).toBe(false);
    }
  });

  it("each scenario named as bound elsewhere is a scenario of its file", () => {
    for (const e of elsewhere) expect(scenarios(e.feature), e.feature).toContain(e.scenario);
  });
});
