// The integration suites (quickstart §6): against a seeded brand on a local installation, one file at a
// time, as several of them change the brand (roles, a paused service) and the others read it.
// `just backoffice-it <slug>` runs them; `-- <file>` names one.
import { defineConfig } from "vitest/config";
import path from "node:path";

const here = path.dirname(new URL(import.meta.url).pathname);

export default defineConfig({
  test: {
    root: path.resolve(here, "../.."),
    include: ["tests/it/**/*.it.ts"],
    fileParallelism: false,
    testTimeout: 15 * 60_000,
    hookTimeout: 5 * 60_000,
    reporters: ["verbose"],
  },
});
