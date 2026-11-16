// T080: the queue's search. A name or an email is resolved to player ids through players' search, and
// payouts is asked for those ids (it holds no name); a UUID is a player id as typed; nobody matched is an
// empty list with payouts' counts; players unavailable is said, never answered as nobody.
import { describe, expect, test } from "vitest";
import { MaxPlayers, withdrawals } from "../../src/server/composite/withdrawals.ts";
import { table } from "../../src/server/routes.ts";

const Id = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";
const Queue = "/payouts/v1/casino/brands/goldvibe/withdrawal-requests";
const Players = "/players/v1/casino/backoffice/goldvibe/players";

function owners(answers: Record<string, (url: URL) => Response>) {
  const calls: { url: URL; auth: string | null }[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    const u = new URL(url);
    calls.push({ url: u, auth: new Headers(init.headers).get("authorization") });
    const answer = answers[u.pathname];
    return answer ? answer(u) : Response.json({ error: "not_found" }, { status: 404 });
  }) as typeof fetch;
  return { calls, fetchFn };
}

const deps = (fetchFn: typeof fetch) => ({
  brand: "goldvibe",
  servicesUrl: "http://services.local",
  sharedProjects: {},
  fetch: fetchFn,
  timeoutMs: 100,
  table,
});
const page = (requests: unknown[] = []) => Response.json({ requests, total: requests.length, page: 1, pageSize: 50, counts: { pendingReview: 3, needsReview: 1 } });
const read = async (r: Response) => ({ status: r.status, body: (await r.json()) as Record<string, unknown> });

describe("the withdrawals queue's search", () => {
  test("with nothing typed, payouts is asked as the page asked, as the operator", async () => {
    const { calls, fetchFn } = owners({ [Queue]: () => page() });
    const a = await read(await withdrawals(deps(fetchFn), new URLSearchParams("status=ALL&sort=oldest&page=2"), "tok"));
    expect(calls.map((c) => `${c.url.pathname}?${c.url.searchParams}`)).toEqual([`${Queue}?status=ALL&sort=oldest&page=2`]);
    expect(calls[0]!.auth).toBe("Bearer tok");
    expect(a.body.players).toBeUndefined();
  });

  test("a name is resolved to player ids by players' search, and payouts asked for those ids", async () => {
    const { calls, fetchFn } = owners({
      [Players]: () => Response.json({ items: [{ playerId: "p-1" }, { playerId: "p-2" }, { playerId: "p-1" }], total: 2 }),
      [Queue]: () => page([{ requestId: "r-1", playerId: "p-2" }]),
    });
    const a = await read(await withdrawals(deps(fetchFn), new URLSearchParams("q= ana souza &kyc=verified&playerSearch=sneaky"), "tok"));
    expect(calls[0]!.url.searchParams.get("search")).toBe("ana souza");
    expect(calls[0]!.url.searchParams.get("pageSize")).toBe(String(MaxPlayers));
    const asked = calls[1]!.url.searchParams;
    expect(asked.get("playerSearch")).toBe("p-1,p-2");
    expect(asked.get("kyc")).toBe("verified");
    expect(asked.has("q")).toBe(false);
    expect(a.body).toMatchObject({ requests: [{ requestId: "r-1" }], players: { matched: 2, more: false } });
  });

  test("an email is resolved the same way; more matches than taken are said", async () => {
    const { fetchFn } = owners({
      [Players]: () => Response.json({ items: [{ playerId: "p-1" }], total: 140 }),
      [Queue]: () => page(),
    });
    const a = await read(await withdrawals(deps(fetchFn), new URLSearchParams("q=ana@example.test"), "tok"));
    expect(a.body.players).toEqual({ matched: 1, more: true });
  });

  test("a UUID is a player id as typed: players is not asked", async () => {
    const { calls, fetchFn } = owners({ [Queue]: () => page() });
    await withdrawals(deps(fetchFn), new URLSearchParams(`q=${Id}`), "tok");
    expect(calls.map((c) => c.url.pathname)).toEqual([Queue]);
    expect(calls[0]!.url.searchParams.get("playerSearch")).toBe(Id);
  });

  test("nobody matched: an empty list with payouts' counts", async () => {
    const { calls, fetchFn } = owners({ [Players]: () => Response.json({ items: [], total: 0 }), [Queue]: () => page([{ requestId: "r-9" }]) });
    const a = await read(await withdrawals(deps(fetchFn), new URLSearchParams("q=nobody&page=3"), "tok"));
    expect(calls[1]!.url.searchParams.has("playerSearch")).toBe(false);
    expect(a.body).toMatchObject({ requests: [], total: 0, page: 3, pageSize: 50, counts: { pendingReview: 3, needsReview: 1 }, players: { matched: 0, more: false } });
  });

  test("players unavailable is said, and payouts not asked", async () => {
    const { calls, fetchFn } = owners({ [Players]: () => Response.json({ error: "down" }, { status: 503 }), [Queue]: () => page() });
    const a = await withdrawals(deps(fetchFn), new URLSearchParams("q=ana"), "tok");
    expect(a.status).toBe(503);
    expect(calls.map((c) => c.url.pathname)).toEqual([Players]);
  });

  test("a refusal by players is the operator's to see", async () => {
    const { fetchFn } = owners({ [Players]: () => Response.json({ error: "forbidden" }, { status: 403 }) });
    expect((await withdrawals(deps(fetchFn), new URLSearchParams("q=ana"), "tok")).status).toBe(403);
  });
});
