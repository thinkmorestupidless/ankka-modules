import { spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { descriptionsIn, generate, GenerationError, Header, modules, run, selectionsIn, type Description, type Descriptions, type Selections } from "../../scripts/generate.ts";
import { routes as casinoRoutes } from "../../src/casino/routes.ts";
import { routes as coreRoutes } from "../../src/core/routes.ts";
import { routes as engagementRoutes } from "../../src/engagement/routes.ts";
import type { Route } from "../../src/route.ts";
import type { Selection } from "../../src/selection.ts";
import { routes as supportRoutes } from "../../src/support/routes.ts";
import * as hand from "./fixtures/routes-027.ts";

const sdk = path.resolve(import.meta.dirname, "../..");
const services = path.resolve(sdk, "../../services");

const descriptions = descriptionsIn(services);
const selections = await selectionsIn(path.join(sdk, "src"));

/** A row as a comparison reads it: its fields whatever their order, its query whatever its order. */
const read = (r: Route) => ({ ...r, ...(r.query ? { query: [...r.query].sort() } : {}) });

/** A small description and a selection of it, for the rules. */
const balance = "GET /v1/casino/players/the-brand/wallets/{currency}/balance";
const fixture = (schema: object = { type: "object", required: ["real"], properties: { real: { type: "string" } } }): Descriptions => ({
  wallet: {
    paths: {
      "/v1/casino/players/the-brand/wallets/{currency}/balance": {
        get: {
          tags: ["player"],
          parameters: [{ name: "currency", in: "path" }, { name: "at", in: "query" }],
          responses: { "200": { content: { "application/json": { schema: { $ref: "#/components/schemas/Balance" } } } } },
        },
      },
      "/v1/stream": { get: { tags: ["player"], responses: { "200": { content: { "text/event-stream": {} } } } } },
    },
    components: { schemas: { Balance: schema } },
  } as Description,
});
const only = (core: Selection): Selections => ({ core, casino: [], engagement: [], support: [] });
const problems = (d: Descriptions, s: Selections): readonly string[] => {
  try {
    generate(d, s);
  } catch (e) {
    if (e instanceof GenerationError) return e.problems;
    throw e;
  }
  return [];
};

describe("the generated tables (spec 031, contracts/generation.md)", () => {
  const made = generate(descriptions, selections);

  it.each(modules)("%s's table equals the hand-written table of spec 027, row for row", (module) => {
    expect(made.tables[module].map(read)).toEqual(hand[module].map(read));
  });

  it("the tables in the sources are the generated ones, and hold the 27 rows", () => {
    const kept = { core: coreRoutes, casino: casinoRoutes, engagement: engagementRoutes, support: supportRoutes };
    for (const module of modules) expect(kept[module].map(read)).toEqual(hand[module].map(read));
    expect(modules.map((m) => kept[m].length)).toEqual([20, 4, 1, 2]);
  });

  it("every generated file is headed as generated, and the sources hold them as generated", () => {
    for (const [file, text] of Object.entries(made.files)) {
      expect(text.startsWith(`${Header}\n`), file).toBe(true);
      expect(readFileSync(path.join(sdk, file), "utf8"), file).toBe(text);
    }
    expect(Object.keys(made.files).sort()).toEqual(modules.flatMap((m) => [`src/${m}/routes.ts`, `src/${m}/shapes.ts`]).sort());
  });

  it("the output is the same across two runs", () => {
    expect(generate(descriptionsIn(services), selections).files).toEqual(made.files);
  });
});

describe("what a row gets from the description", () => {
  const audience = (operation: object) => generate({ s: { paths: { "/v1/x": { get: { responses: { "200": { content: { "text/event-stream": {} } } }, ...operation } } } } }, only([{ id: "x", service: "s", operation: "GET /v1/x" }])).tables.core[0]!.audience;

  it("the audience: the ticket's tag, then signed-in's, then an open route, else the player", () => {
    expect(audience({ tags: ["player", "ticket"], security: [] })).toBe("ticket");
    expect(audience({ tags: ["player", "signed-in"], security: [] })).toBe("player");
    expect(audience({ tags: ["player"], security: [] })).toBe("public");
    expect(audience({ tags: ["player"] })).toBe("player");
  });

  it("the brand's segment, a rename, a fixed value and a fixed query", () => {
    const d: Descriptions = { s: { paths: { "/v1/the-brand/{playerId}/{route}": { get: { parameters: [{ name: "playerId", in: "path" }, { name: "route", in: "path" }, { name: "status", in: "query" }], responses: { "200": { content: { "text/event-stream": {} } } } } } } } };
    const made = generate(d, only([{ id: "x", service: "s", operation: "GET /v1/the-brand/{playerId}/{route}", rename: { playerId: "player" }, fixed: { route: "chat" }, fixedQuery: "status=unread" }]));
    expect(made.tables.core[0]).toEqual({ module: "core", id: "x", service: "s", method: "GET", path: "/v1/{brand}/{player}/chat?status=unread", audience: "player", stream: true });
  });
});

describe("the rules, each failing with its message", () => {
  it("a selection's operation is in its service's kept description", () => {
    expect(problems(fixture(), only([{ id: "profile", service: "wallet", operation: "GET /v1/nowhere", answers: "X" }]))).toEqual([
      "core/selection.ts: profile names GET /v1/nowhere which services/wallet/openapi.player.json does not hold",
    ]);
    expect(problems(fixture(), only([{ id: "profile", service: "absent", operation: balance, answers: "X" }]))[0]).toContain("which services/absent/openapi.player.json does not hold");
  });

  it("rename and fixed name path parameters the operation has", () => {
    expect(problems(fixture(), only([{ id: "balance", service: "wallet", operation: balance, rename: { playerId: "player" }, fixed: { wallet: "main" }, answers: "Balance" }]))).toEqual([
      `core/selection.ts: balance renames the path parameter playerId, which ${balance} does not have`,
      `core/selection.ts: balance fixes the path parameter wallet, which ${balance} does not have`,
    ]);
  });

  it("query, fixedQuery and brandQuery name query parameters the operation has", () => {
    expect(problems(fixture(), only([{ id: "balance", service: "wallet", operation: balance, query: ["at", "since"], fixedQuery: "page=1", brandQuery: true, answers: "Balance" }]))).toEqual([
      `core/selection.ts: balance names the query parameter since, which ${balance} does not have`,
      `core/selection.ts: balance names the query parameter page, which ${balance} does not have`,
      `core/selection.ts: balance names the query parameter brand, which ${balance} does not have`,
    ]);
  });

  it("two rows of a module do not share an id", () => {
    expect(problems(fixture(), only([{ id: "balance", service: "wallet", operation: balance, answers: "Balance" }, { id: "balance", service: "wallet", operation: "GET /v1/stream" }]))).toEqual([
      `core/selection.ts: two rows share the id balance: ${balance} and GET /v1/stream`,
    ]);
  });

  it("a schema uses only the subset, and one outside it fails naming the schema and the keyword", () => {
    const row = [{ id: "balance", service: "wallet", operation: balance, answers: "Balance" }] as const;
    expect(problems(fixture({ type: "object", properties: { real: { type: "string", pattern: "^[0-9]+$" } } }), only(row))).toEqual([
      "services/wallet/openapi.player.json: the schema Balance.real uses pattern, outside what the generator reads",
    ]);
    expect(problems(fixture({ allOf: [{ type: "object" }] }), only(row))).toEqual(["services/wallet/openapi.player.json: the schema Balance uses allOf, outside what the generator reads"]);
  });

  it("a row with answers has a JSON answer, a stream has none, and a row says which it is", () => {
    expect(problems(fixture(), only([{ id: "push", service: "wallet", operation: "GET /v1/stream", answers: "Events" }]))).toEqual([
      "core/selection.ts: push names what it answers (Events), and GET /v1/stream answers a stream, which has no shape",
    ]);
    expect(problems(fixture(), only([{ id: "balance", service: "wallet", operation: balance }]))).toEqual([`core/selection.ts: balance does not name what it answers, and ${balance} is not a stream`]);
    expect(problems(fixture(), only([{ id: "balance", service: "wallet", operation: balance, takes: "Ask", answers: "Balance" }]))).toEqual([
      `core/selection.ts: balance names what it takes (Ask), and ${balance} takes no JSON body`,
    ]);
  });
});

describe("the shapes", () => {
  it("optional, nullable, arrays, references and a record, with unknown fields passed", () => {
    const d = fixture({
      type: "object",
      required: ["real", "rows"],
      properties: {
        real: { type: "string" },
        held: { type: ["integer", "null"], format: "int64" },
        rows: { type: "array", items: { $ref: "#/components/schemas/Row" } },
        primary: { anyOf: [{ $ref: "#/components/schemas/Row" }, { type: "null" }] },
        extra: { type: "object" },
      },
    });
    const withRow: Descriptions = { wallet: { ...d.wallet!, components: { schemas: { ...d.wallet!.components!.schemas!, Row: { type: "object", required: ["id"], properties: { id: { type: "string" } } } } } } };
    const text = generate(withRow, only([{ id: "balance", service: "wallet", operation: balance, answers: "MajorBalance" }])).files["src/core/shapes.ts"]!;
    expect(text).toBe(`${Header}
import { z } from "zod";

const WalletRow = z.looseObject({
  id: z.string(),
});

/** What \`balance\` answers: wallet's ${balance}. */
export const MajorBalance = z.looseObject({
  real: z.string(),
  held: z.number().int().nullable().optional(),
  rows: z.array(WalletRow),
  primary: WalletRow.nullable().optional(),
  extra: z.record(z.string(), z.unknown()).optional(),
});
export type MajorBalance = z.infer<typeof MajorBalance>;
`);
  });
});

describe("the command", () => {
  it("generates with an empty environment, reading only the kept descriptions and the selections", () => {
    const ran = spawnSync(process.execPath, [path.join(sdk, "scripts/generate.ts"), "--check"], { env: {}, encoding: "utf8" });
    expect(ran.stderr).toBe("");
    expect(ran.status).toBe(0);
    expect(ran.stdout.trim()).toBe("4 modules: current");
    const source = readFileSync(path.join(sdk, "scripts/generate.ts"), "utf8");
    const imported = [...source.matchAll(/^import .* from "([^"]+)";$/gm)].map((m) => m[1]!);
    expect(imported.filter((i) => !i.startsWith("node:") && !i.startsWith("../src/"))).toEqual([]);
    expect(imported.filter((i) => i.startsWith("node:")).sort()).toEqual(["node:fs", "node:path", "node:url"]);
    expect(source).not.toMatch(/process\.env|fetch\(|child_process/);
  });

  it("runs when it is reached through a link whose path holds a space", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "generate link "));
    try {
      const link = path.join(dir, "generate.ts");
      symlinkSync(path.join(sdk, "scripts/generate.ts"), link);
      const ran = spawnSync(process.execPath, [link, "--check"], { env: {}, encoding: "utf8" });
      expect(ran.stderr).toBe("");
      expect(ran.stdout.trim()).toBe("4 modules: current");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("the check names each file that differs and writes nothing; generating writes them", async () => {
    const copy = mkdtempSync(path.join(tmpdir(), "generate-"));
    cpSync(path.join(sdk, "src"), path.join(copy, "sdk/src"), { recursive: true });
    for (const service of Object.keys(descriptions)) cpSync(path.join(services, service, "openapi.player.json"), path.join(copy, "services", service, "openapi.player.json"));
    const at = { sdk: path.join(copy, "sdk"), services: path.join(copy, "services") };
    expect(await run({ ...at, check: true })).toEqual({ code: 0, lines: [] });

    // The wallet's balance answers a field more: its kept description changed, and the shapes with it.
    const wallet = path.join(at.services, "wallet/openapi.player.json");
    const d = JSON.parse(readFileSync(wallet, "utf8"));
    d.components.schemas.MajorBalance.properties.pending = { type: "string" };
    writeFileSync(wallet, JSON.stringify(d));
    const before = readFileSync(path.join(at.sdk, "src/core/shapes.ts"), "utf8");
    expect(await run({ ...at, check: true })).toEqual({ code: 1, lines: ["src/core/shapes.ts is out of date: run npm run generate -w sdk/player"] });
    expect(readFileSync(path.join(at.sdk, "src/core/shapes.ts"), "utf8")).toBe(before);
    expect(await run({ ...at, check: false })).toEqual({ code: 0, lines: [] });
    expect(readFileSync(path.join(at.sdk, "src/core/shapes.ts"), "utf8")).toContain("pending: z.string().optional(),");
    expect(await run({ ...at, check: true })).toEqual({ code: 0, lines: [] });
  });
});
