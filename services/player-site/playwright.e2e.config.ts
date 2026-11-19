import { defineConfig } from "@playwright/test";

// The end-to-end scenario against a running brand (tests/e2e, T051): `npm run e2e`, by `just player-site-it`.
export default defineConfig({
  testDir: "tests/e2e",
  workers: 1,
  reporter: [["list"]],
  // a French visitor: the site follows the browser's language (SC-007 is "in French")
  use: { browserName: "chromium", headless: true, locale: "fr-FR" },
});
