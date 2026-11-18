import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

// reference-site.feature in a browser, against the built site and the services' doubles (T046).
// Its first scenario, the modules tool's render, is the tool's own suite's (RenderSuite, T049).
const testDir = defineBddConfig({
  features: "../../features/player-site/reference-site.feature",
  steps: "tests/features/*.ts",
  featuresRoot: "../../features",
  outputDir: "tests/features/.generated",
});

export default defineConfig({
  testDir,
  grepInvert: /brand guyanabet runs the core, the casino, engagement, support and the player site/,
  workers: 1,
  reporter: [["list"]],
  use: { browserName: "chromium", headless: true },
});
