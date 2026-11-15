// T099: `/api/jobs` starts reporting's exports as the operator and keeps only their references in the
// operator's preferences; their states are read from reporting; `/api/events` says `job_done` once
// when one ends (FR-029).
import { describe, expect, test } from "vitest";
import { table } from "../../src/server/routes.ts";
import { referenceOf } from "../../src/server/composite/jobs.ts";
import { newlyEnded } from "../../src/server/events.ts";
import { Kept, type Bags } from "../../src/server/roles.ts";
import { OperatorBags } from "./bags.ts";
import { Brand, harness, write, type Call } from "./support.ts";

const token = `a.${Buffer.from(JSON.stringify({ sub: "op-fin", name: "Fin" })).toString("base64url")}.s`;
const Ref = "0b6d4a52-6f0e-4b8e-9d55-2f3a1c9e7a10";

function jobs(answer: (call: Call) => Response | Promise<Response>, over: Parameters<typeof harness>[0] = {}) {
  const h = harness({ table, ...over });
  h.state.sessions.set("s-j", { accessToken: token, expiresAt: Date.now() + 60_000, roles: [`brand_${Brand}`, "brand_finance"] });
  h.answer(answer);
  return h;
}

const post = (h: ReturnType<typeof jobs>, body: unknown) =>
  h.ask("POST", "/api/jobs", { session: "s-j", headers: write, body: JSON.stringify(body) });

describe("POST /api/jobs", () => {
  test("starts the revenue export as the operator, with format=csv, and keeps its reference from reporting's 202", async () => {
    const h = jobs(() => Response.json({ reference: Ref, status: "generating" }, { status: 202 }));
    const r = await post(h, { kind: "revenue", params: { from: "2026-10-01", to: "2026-10-31", currency: "EUR" } });
    expect(r.status).toBe(200);
    const b = (await r.json()) as { job: { reference: string; state: string }; kept: boolean };
    expect(b.job).toMatchObject({ reference: Ref, kind: "revenue", state: "generating" });
    expect(b.kept).toBe(true);
    const call = h.calls[0]!;
    expect(call.url).toBe(`http://services.local/reporting/v1/casino/brands/${Brand}/reporting/reports/ggr?from=2026-10-01&to=2026-10-31&currency=EUR&format=csv`);
    expect(call.headers.get("authorization")).toBe(`Bearer ${token}`);
    const written = JSON.parse(h.state.written[0]!.body) as { jobs: { reference: string; kind: string; startedAt: string }[] };
    expect(Object.keys(written)).toEqual(["jobs"]);
    expect(written.jobs).toEqual([{ reference: Ref, kind: "revenue", startedAt: expect.any(String) }]);
  });

  test("reads the reference of an export rendered at once from its attachment's name, and does not keep the file", async () => {
    const h = jobs(() => new Response("date,bets\r\n", { status: 200, headers: { "Content-Type": "text/csv", "Content-Disposition": `attachment; filename="ggr-report-${Ref}.csv"` } }));
    const b = (await (await post(h, { kind: "revenue", params: {} })).json()) as { job: { reference: string; state: string } };
    expect(b.job).toMatchObject({ reference: Ref, state: "ready" });
  });

  test("keeps at most 20 jobs, newest first", async () => {
    const h = jobs(() => Response.json({ reference: Ref, status: "generating" }, { status: 202 }));
    h.state.prefs = { ...h.state.prefs, jobs: Array.from({ length: 20 }, (_, i) => ({ reference: `r-${i}`, kind: "players", startedAt: "2026-11-01T00:00:00Z" })) };
    await post(h, { kind: "players", params: {} });
    const written = JSON.parse(h.state.written[0]!.body) as { jobs: { reference: string }[] };
    expect(written.jobs).toHaveLength(20);
    expect(written.jobs[0]!.reference).toBe(Ref);
    expect(written.jobs.at(-1)!.reference).toBe("r-18");
  });

  test("keeps no player id: a dispute report's player is handed to reporting, not kept", async () => {
    const h = jobs(() => Response.json({ reference: Ref, status: "generating" }, { status: 202 }));
    await post(h, { kind: "dispute_report", params: { playerId: "6d1f7c1e-1b33-4e0e-9a4c-1e0f2b3c4d5e", fromDate: "2026-10-01", toDate: "2026-10-31", format: "pdf" } });
    expect(h.calls[0]!.url).toContain("/reporting/players/6d1f7c1e-1b33-4e0e-9a4c-1e0f2b3c4d5e/dispute-report?fromDate=2026-10-01&toDate=2026-10-31&format=pdf");
    expect(h.state.written[0]!.body).not.toContain("6d1f7c1e");
  });

  test("answers reporting's refusal as it gave it, and keeps nothing", async () => {
    const h = jobs(() => Response.json({ error: "incomplete_period", completeFrom: "2026-10-12" }, { status: 409 }));
    const r = await post(h, { kind: "revenue", params: { from: "2026-10-01", to: "2026-10-31" } });
    expect(r.status).toBe(409);
    expect(await r.json()).toEqual({ error: "incomplete_period", completeFrom: "2026-10-12" });
    expect(h.state.written).toHaveLength(0);
  });

  test("refuses a kind it does not know, a parameter the kind does not take, and a path parameter that is not one segment", async () => {
    const h = jobs(() => Response.json({}));
    expect((await post(h, { kind: "everything" })).status).toBe(400);
    expect((await post(h, { kind: "revenue", params: { playerId: "x" } })).status).toBe(400);
    expect((await post(h, { kind: "day_end", params: {} })).status).toBe(400);
    expect((await post(h, { kind: "day_end", params: { day: "../../x" } })).status).toBe(400);
    expect(h.calls).toHaveLength(0);
  });

  test("is a write: it needs the page's own origin", async () => {
    const h = jobs(() => Response.json({}));
    const r = await h.ask("POST", "/api/jobs", { session: "s-j", body: "{}" });
    expect(r.status).toBe(403);
  });
});

describe("GET /api/jobs", () => {
  test("reads each kept job's state from reporting's documents, and the progress of one generating", async () => {
    const h = jobs((call) => {
      if (call.url.includes("/reporting/documents?")) return Response.json({ documents: [
        { reference: "r-ready", status: "ready", rows: 12, expiresAt: "2026-11-20T00:00:00Z" },
        { reference: "r-gen", status: "generating", rows: 0 },
      ] });
      if (call.url.endsWith("/reporting/documents/r-gen")) return Response.json({ reference: "r-gen", status: "generating", progress: 40 }, { status: 202 });
      return new Response("not asked", { status: 500 });
    });
    h.state.prefs = { ...h.state.prefs, jobs: [
      { reference: "r-gen", kind: "transactions", startedAt: new Date().toISOString() },
      { reference: "r-ready", kind: "revenue", startedAt: new Date().toISOString() },
      { reference: "r-gone", kind: "players", startedAt: new Date().toISOString() },
    ] };
    const r = await h.ask("GET", "/api/jobs", { session: "s-j" });
    const b = (await r.json()) as { jobs: { reference: string; state: string; progress?: number; reason?: string }[] };
    expect(b.jobs.map((j) => [j.reference, j.state])).toEqual([["r-gen", "generating"], ["r-ready", "ready"], ["r-gone", "unknown"]]);
    expect(b.jobs[0]!.progress).toBe(40);
    // a ready document's own route serves its file: it is never asked here
    expect(h.calls.some((c) => c.url.endsWith("/documents/r-ready"))).toBe(false);
  });

  test("says unknown, naming why, when reporting is not answering", async () => {
    const h = jobs(() => new Response(null, { status: 503 }));
    h.state.prefs = { ...h.state.prefs, jobs: [{ reference: "r-1", kind: "revenue", startedAt: new Date().toISOString() }] };
    const b = (await (await h.ask("GET", "/api/jobs", { session: "s-j" })).json()) as { jobs: { state: string; reason: string }[] };
    expect(b.jobs[0]).toMatchObject({ state: "unknown", reason: "status_503" });
  });
});

describe("a job's reference", () => {
  test("is read from each of reporting's attachment names", () => {
    expect(referenceOf(`attachment; filename="ggr-report-${Ref}.csv"`)).toBe(Ref);
    expect(referenceOf(`attachment; filename="day-end-report-${Ref}.csv"`)).toBe(Ref);
    expect(referenceOf(`attachment; filename="dispute-report-${Ref}.pdf"`)).toBe(Ref);
    expect(referenceOf("attachment")).toBeUndefined();
  });
});

describe("job_done", () => {
  const j = (reference: string, state: "generating" | "ready" | "failed") =>
    ({ reference, kind: "revenue", startedAt: "2026-11-15T10:00:00Z", ...(state === "generating" ? { state, progress: 0 } : state === "ready" ? { state, rows: 1, expiresAt: null } : { state }) }) as Parameters<typeof newlyEnded>[1][number];

  test("is said once for a job seen running that ended, and for one started after the stream opened", () => {
    const seen = new Map<string, string>();
    expect(newlyEnded(seen, [j("a", "generating"), j("old", "ready")], true)).toEqual([]);
    expect(newlyEnded(seen, [j("a", "ready"), j("old", "ready"), j("b", "failed")], false).map((x) => x.reference)).toEqual(["a", "b"]);
    expect(newlyEnded(seen, [j("a", "ready"), j("b", "failed")], false)).toEqual([]);
  });

  test("is sent on /api/events when a job the operator started ends", async () => {
    let status = "generating";
    const h = jobs(
      (call) => {
        if (call.url.includes("/reporting/documents?")) return Response.json({ documents: [{ reference: Ref, status, rows: 3 }] });
        return Response.json({ reference: Ref, status, progress: 10 }, { status: 202 });
      },
      {
        eventsEveryMs: 20,
        reads: { bags: new Kept<Bags>(async () => OperatorBags, 0, () => Date.now()), modules: new Kept<string[]>(async () => ["core"], 0, () => Date.now()) },
      },
    );
    h.state.sessions.set("s-j", { accessToken: token, expiresAt: Date.now() + 10_000, roles: [`brand_${Brand}`, "brand_finance"] });
    h.state.prefs = { ...h.state.prefs, jobs: [{ reference: Ref, kind: "revenue", startedAt: new Date().toISOString() }] };
    const r = await h.ask("GET", "/api/events", { session: "s-j" });
    setTimeout(() => void (status = "ready"), 80);
    const reader = r.body!.getReader();
    let seen = "";
    const deadline = Date.now() + 2_000;
    while (Date.now() < deadline && !seen.includes("event: job_done")) {
      const next = await reader.read();
      if (next.done) break;
      seen += new TextDecoder().decode(next.value);
    }
    await reader.cancel();
    expect(seen).toContain("event: job_done");
    expect(seen).toContain(`"reference":"${Ref}"`);
    expect(seen).toContain(`"state":"ready"`);
  });
});
