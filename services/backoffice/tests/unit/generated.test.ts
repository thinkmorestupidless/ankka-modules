import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";

// src/shared/generated is copied from the contract by `just backoffice-contract`; a copy that differs
// would show amounts by a stale currency table or name a permission the contract no longer has.
const read = (p: string) => readFileSync(new URL(p, import.meta.url), "utf8");

describe("the contract's published files", () => {
  test.each([
    ["operator-permissions.v1.json", "../../../../contract/schemas/access/operator-permissions.v1.json"],
    ["currencies.v1.json", "../../../../contract/schemas/currencies.v1.json"],
  ])("%s is the contract's own", (name, source) => {
    expect(read(`../../src/shared/generated/${name}`), `run just backoffice-contract`).toBe(read(source));
  });
});
