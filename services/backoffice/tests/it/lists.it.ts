// SC-005 (T124): no list drops a row. On the seeded brand, each of the brand's paged lists is read as the
// page reads it (the same route, the same page size), page by page to its end: the total is the
// source's count, every row is reached once, the last page is reachable, and the page past it is empty.
// A list the seed fed holds at least what the seed made, and every seeded row is among those reached.
import { beforeAll, describe, expect, it } from "vitest";
import { installation, Operator, operators, readSeed, seeded, type Seed } from "./support.ts";

interface Paged {
  name: string;
  /** The route for a page, as the list's area asks it. */
  path(page: number, pageSize: number): string;
  pageSize: number;
  rows: string;
  id: string;
  /** The ids the seed made in this list. */
  seededIds(seed: Seed): string[];
}

const Lists: Paged[] = [
  {
    name: "players (areas/players/list.tsx)",
    path: (page, size) => `/api/s/players/players?page=${page}&pageSize=${size}`,
    pageSize: 50,
    rows: "items",
    id: "playerId",
    seededIds: (s) => seeded(s).players.map((p) => p.playerId!),
  },
  {
    name: "withdrawals pending review (areas/withdrawals/queue.tsx)",
    path: (page, size) => `/api/withdrawals?status=PENDING_REVIEW&page=${page}&pageSize=${size}`,
    pageSize: 50,
    rows: "requests",
    id: "requestId",
    seededIds: (s) => seeded(s).withdrawals.map((p) => p.withdrawal!.requestId),
  },
  {
    name: "rewards (areas/rewards/list.tsx)",
    path: (page, size) => `/api/s/rewards/reward-grants?page=${page}&pageSize=${size}`,
    pageSize: 50,
    rows: "items",
    id: "rewardId",
    seededIds: (s) => seeded(s).rewards.map((p) => p.reward!.rewardId),
  },
  {
    name: "the brand's player sessions (areas/players/sessions.tsx)",
    path: (page, size) => `/api/s/players/sessions?page=${page}&pageSize=${size}`,
    pageSize: 50,
    rows: "sessions",
    id: "sessionId",
    seededIds: () => [],
  },
];

describe("SC-005: every list's total is its source's count and every row is reachable", () => {
  const inst = installation();
  let admin: Operator;
  let seed: Seed;

  beforeAll(async () => {
    seed = readSeed(inst);
    admin = await Operator.signIn(inst, operators(inst).brand_admin, "brand_admin");
  });

  for (const list of Lists) {
    it(list.name, async () => {
      type Page = Record<string, unknown> & { total: number };
      const first = await admin.read<Page>(list.path(1, list.pageSize));
      const total = first.total;
      expect(Number.isInteger(total), "the list answers its total").toBe(true);
      const pages = Math.max(1, Math.ceil(total / list.pageSize));
      const reached = new Map<string, number>();
      for (let page = 1; page <= pages; page++) {
        const answer = page === 1 ? first : await admin.read<Page>(list.path(page, list.pageSize));
        expect(answer.total, `page ${page}'s total`).toBe(total);
        const rows = answer[list.rows] as Record<string, unknown>[];
        expect(Array.isArray(rows), `page ${page} holds ${list.rows}`).toBe(true);
        if (page < pages) expect(rows.length, `page ${page} is full`).toBe(list.pageSize);
        else expect(rows.length, "the last page holds the rest").toBe(total - (pages - 1) * list.pageSize);
        for (const r of rows) reached.set(String(r[list.id]), (reached.get(String(r[list.id])) ?? 0) + 1);
      }
      const twice = [...reached].filter(([, n]) => n > 1).map(([id]) => id);
      expect(twice, "no row is on two pages").toEqual([]);
      expect(reached.size, "every row is reached once").toBe(total);
      const past = await admin.read<Page>(list.path(pages + 1, list.pageSize));
      expect((past[list.rows] as unknown[]).length, "the page past the last is empty").toBe(0);
      const mine = list.seededIds(seed);
      expect(total, "the list holds at least what the seed made").toBeGreaterThanOrEqual(mine.length);
      const lost = mine.filter((id) => !reached.has(id));
      expect(lost.slice(0, 10), `seeded rows not reached (${lost.length})`).toEqual([]);
    });
  }

  it("players: each facet's counts add up to the list's total", async () => {
    const answer = await admin.read<{ total: number; facets?: Record<string, Record<string, number>> }>("/api/s/players/players?page=1&pageSize=50&facets=true");
    expect(answer.facets, "players answers its facets").toBeDefined();
    for (const name of ["status", "currency"]) {
      const counts = answer.facets?.[name];
      if (!counts) continue;
      expect(Object.values(counts).reduce((a, b) => a + b, 0), `the ${name} facet`).toBe(answer.total);
    }
    const byCurrency = answer.facets?.currency ?? {};
    for (const c of ["JPY", "EUR", "KWD"]) expect(byCurrency[c] ?? 0, `players in ${c}`).toBeGreaterThanOrEqual(seeded(seed).inCurrency(c as "JPY").length);
  });

  it("withdrawals: the queue's count of requests pending review is its total", async () => {
    const answer = await admin.read<{ total: number; counts?: { pendingReview: number } }>("/api/withdrawals?status=PENDING_REVIEW&page=1&pageSize=50");
    expect(answer.counts?.pendingReview).toBe(answer.total);
  });
});
