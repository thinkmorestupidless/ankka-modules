// axe on every page route, dark and light (SC-008), against the doubles.
import { defineConfig, devices } from "@playwright/test";
import { BackofficeUrl, webServer } from "../doubles/env.ts";

export default defineConfig({
  testDir: ".",
  testMatch: "*.spec.ts",
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: BackofficeUrl },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer,
});
