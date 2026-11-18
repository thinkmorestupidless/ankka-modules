import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { steps, written } from "../../scripts/steps.ts";

const readme = path.resolve(import.meta.dirname, "../../README.md");

describe("the README's steps (T053)", () => {
  it("are the behaviours' steps as the sources declare them: `npm run readme` writes them", () => {
    const now = readFileSync(readme, "utf8");
    expect(now).toBe(written(now));
  });

  it("read every member of a step union, its allowed actions and its fields", () => {
    const source = `export type XStep =
  | { kind: "a" | "b"; allowed: readonly ["go"] | readonly ["go", "stop"]; when: number; what?: { deep: string } }
  | { kind: "c"; allowed: readonly [] };
`;
    expect(steps(source, "XStep")).toEqual([
      { kinds: ["a", "b"], allowed: ["go", "stop"], fields: ["when", "what?"] },
      { kinds: ["c"], allowed: [], fields: [] },
    ]);
  });
});
