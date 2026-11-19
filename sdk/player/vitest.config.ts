import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts", "tests/**/*.test.tsx", "tests/features/**/*.steps.ts"],
    environment: "node",
  },
});
