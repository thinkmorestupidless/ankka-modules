import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";

describe("the skeleton", () => {
  test("the server answers /healthz", () => {
    expect(readFileSync(new URL("../../src/server/main.ts", import.meta.url), "utf8")).toContain('"/healthz"');
  });
});
