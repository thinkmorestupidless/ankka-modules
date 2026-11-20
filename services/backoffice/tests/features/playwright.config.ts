// The bound features (research R18): playwright-bdd over features/backoffice/, the page and its server
// against the doubles. One worker: the doubles and the server's brand reads are shared by every page.
// A scenario whose steps are not bound yet fails when it runs and names the missing step.
import { defineConfig, devices } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";
import path from "node:path";
import { BackofficeUrl, Root, webServer } from "../doubles/env.ts";

const features = path.resolve(Root, "../../features/backoffice");
const here = path.join(Root, "tests/features");

const testDir = defineBddConfig({
  featuresRoot: features,
  features: path.join(features, "*.feature"),
  steps: [path.join(here, "world.ts"), path.join(here, "steps/**/*.ts")],
  outputDir: path.join(here, ".features-gen"),
  missingSteps: "fail-on-run",
});

export default defineConfig({
  testDir,
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  reporter: [["list"]],
  use: { baseURL: BackofficeUrl, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer,
});
