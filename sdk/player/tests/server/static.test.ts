import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { wholeSite } from "./helpers.ts";

let close: (() => Promise<void>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});

function built() {
  const dir = mkdtempSync(join(tmpdir(), "site-"));
  mkdirSync(join(dir, "assets"));
  writeFileSync(join(dir, "index.html"), "<!doctype html><title>site</title>");
  writeFileSync(join(dir, "assets", "index-a1b2c3.js"), "console.log(1)");
  return dir;
}

describe("the built site (FR-045)", () => {
  it("serves a file named by its content for a year, and the first page never kept", async () => {
    const s = await wholeSite({ static: built() });
    close = s.close;
    const asset = await s.request("GET", "/assets/index-a1b2c3.js");
    expect(asset.status).toBe(200);
    expect(asset.headers.get("cache-control")).toBe("public, max-age=31536000, immutable");
    expect(asset.headers.get("content-type")).toContain("text/javascript");
    for (const path of ["/", "/game/pools", "/cashier/return?status=ok"]) {
      const page = await s.request("GET", path);
      expect(page.headers.get("cache-control"), path).toBe("no-cache");
      expect(await page.text(), path).toContain("<title>site</title>");
    }
  });

  it("an asset an old build named and this one lacks is not found, and nothing outside the build is served", async () => {
    const s = await wholeSite({ static: built() });
    close = s.close;
    expect((await s.request("GET", "/assets/index-old000.js")).status).toBe(404);
    expect((await s.request("GET", "/assets/..%2f..%2fetc%2fpasswd")).status).toBe(404);
  });
});
