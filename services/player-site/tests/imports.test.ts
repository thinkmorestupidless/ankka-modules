import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { forbidden } from "./imports.ts";

describe("the import rule (research R12)", () => {
  it("holds for the reference site's pages", () => {
    expect(forbidden(path.resolve(import.meta.dirname, "../src"))).toEqual([]);
  });

  it("finds a page that calls a service itself, and lets the site server do so", () => {
    const src = mkdtempSync(path.join(tmpdir(), "imports-"));
    mkdirSync(path.join(src, "pages"));
    mkdirSync(path.join(src, "server"));
    writeFileSync(path.join(src, "pages", "Bad.tsx"), 'const r = await fetch("/api/x");\nnew EventSource("/push");\nnavigator.sendBeacon("/x");');
    writeFileSync(path.join(src, "pages", "Client.ts"), 'import { coreClient } from "@eitheror/player-sdk/core";');
    writeFileSync(path.join(src, "server", "main.ts"), 'await fetch("http://services/x");');
    expect(forbidden(src)).toEqual(["pages/Bad.tsx: fetch", "pages/Bad.tsx: EventSource", "pages/Bad.tsx: sendBeacon", "pages/Client.ts: a module client"]);
  });
});
