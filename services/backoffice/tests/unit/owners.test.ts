import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import { nameOf, render, write } from "../../scripts/owners.ts";
import { table } from "../../src/server/routes.ts";
import * as owners from "../../src/shared/owners/index.ts";

const committed = path.join(import.meta.dirname, "../../src/shared/owners");

describe("the typed clients", () => {
  test("are what the generator makes from the table now", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "owners-"));
    try {
      write(dir);
      expect(readdirSync(committed).sort(), "run `npm run owners`").toEqual(readdirSync(dir).sort());
      for (const name of readdirSync(dir)) {
        expect(readFileSync(path.join(committed, name), "utf8"), `${name}: run \`npm run owners\``).toBe(readFileSync(path.join(dir, name), "utf8"));
      }
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("one client per row, each name unique within its service", () => {
    const files = render();
    expect(files.size).toBe(new Set(table.map((r) => r.service)).size + 1);
    const names = table.map((r) => `${r.service} ${nameOf(r)}`);
    expect(new Set(names).size).toBe(table.length);
  });
});

describe("a client", () => {
  afterEach(() => vi.unstubAllGlobals());

  function recorded() {
    const calls: Request[] = [];
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push(new Request(new URL(String(input), "https://bo.example.test"), init));
      return new Response(null, { status: 204 });
    });
    return calls;
  }

  test("a read asks the path with its parameters encoded and its query, and no marker", async () => {
    const calls = recorded();
    await owners.players.getPlayersProfile({ playerId: "p 1/x" }, { query: { page: "2" } });
    expect(calls[0]!.method).toBe("GET");
    expect(new URL(calls[0]!.url).pathname + new URL(calls[0]!.url).search).toBe("/api/s/players/players/p%201%2Fx/profile?page=2");
    expect(calls[0]!.headers.get("X-Backoffice")).toBeNull();
  });

  test("a change carries the marker and its body as JSON", async () => {
    const calls = recorded();
    await owners.players.patchPlayersSuspend({ playerId: "p-1" }, { body: { reason: "r" } });
    expect(calls[0]!.method).toBe("PATCH");
    expect(calls[0]!.headers.get("X-Backoffice")).toBe("1");
    expect(calls[0]!.headers.get("Content-Type")).toBe("application/json");
    expect(await calls[0]!.text()).toBe('{"reason":"r"}');
  });

  test("a change with no body carries the marker and no content type", async () => {
    const calls = recorded();
    await owners.players.patchPlayersReinstate({ playerId: "p-1" });
    expect(calls[0]!.headers.get("X-Backoffice")).toBe("1");
    expect(calls[0]!.headers.get("Content-Type")).toBeNull();
  });

  test("a shared project's service is asked under its own name", async () => {
    const calls = recorded();
    await owners.merchant.getCashier();
    expect(new URL(calls[0]!.url).pathname).toBe("/api/s/merchant/cashier");
  });
});
