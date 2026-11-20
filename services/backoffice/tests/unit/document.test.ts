// T094: an identity document is streamed for the operator from kyc's view of it, the two ids the only
// inputs (FR-031). kyc answers the document's bytes (amendment K5) and the backoffice passes them
// through: no address in the page's request, or in kyc's answer, is ever fetched. The only call made is
// kyc's view route, as the operator.
import { describe, expect, test } from "vitest";
import { table } from "../../src/server/routes.ts";
import { Brand, harness, SecretToken } from "./support.ts";

const Player = "8b0f4c1e-35a6-4b8e-9d3b-1c2f0a9e7d11";
const Doc = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";
const View = `/api/kyc/${Player}/documents/${Doc}/view`;
const KycView = `http://services.local/kyc/v1/casino/backoffice/${Brand}/kyc/${Player}/views/${Doc}`;
const Pdf = "%PDF-1.4 the document";

const kycHeaders = { "Content-Type": "application/pdf", "Content-Length": "21", "Content-Disposition": "inline", "Cache-Control": "no-store" };

/** The app with kyc answering `answer` for the view: the document's bytes unless given another. */
function app(answer: () => Response = () => new Response(Pdf, { headers: kycHeaders })) {
  const h = harness({ table });
  h.answer((call) => (call.url.startsWith("http://services.local/kyc/") ? answer() : Response.json({ error: "not_found" }, { status: 404 })));
  return h;
}

describe("a document viewed", () => {
  test("is kyc's bytes, asked as the operator and passed through, with nothing else asked", async () => {
    const h = app();
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(200);
    expect(await r.text()).toBe(Pdf);
    expect(r.headers.get("content-type")).toBe("application/pdf");
    expect(r.headers.get("content-disposition")).toBe("inline");
    expect(r.headers.get("content-length")).toBe("21");
    expect(r.headers.get("cache-control")).toContain("no-store");
    expect(r.headers.get("x-content-type-options")).toBe("nosniff");
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
    expect(h.calls[0]!.headers.get("authorization")).toBe(`Bearer ${SecretToken}`);
  });

  test("is streamed: the first bytes reach the page before kyc has sent the rest", async () => {
    let finish: () => void = () => {};
    const rest = new Promise<void>((resolve) => (finish = resolve));
    const h = app(() => {
      const body = new ReadableStream<Uint8Array>({
        async start(c) {
          c.enqueue(new TextEncoder().encode("%PDF-1.4 "));
          await rest;
          c.enqueue(new TextEncoder().encode("the rest"));
          c.close();
        },
      });
      return new Response(body, { headers: { "Content-Type": "application/pdf" } });
    });
    const r = await h.ask("GET", View, { session: "s-1" });
    const reader = r.body!.getReader();
    const first = await reader.read();
    expect(new TextDecoder().decode(first.value)).toBe("%PDF-1.4 ");
    finish();
    let tail = "";
    for (let c = await reader.read(); !c.done; c = await reader.read()) tail += new TextDecoder().decode(c.value);
    expect(tail).toBe("the rest");
  });

  test("is refused, and nothing asked, for an operator whose roles do not name the brand", async () => {
    const h = app();
    h.state.sessions.set("s-other", { accessToken: SecretToken, expiresAt: Date.now() + 60_000, roles: ["brand_spinvibe", "brand_support@spinvibe"] });
    const r = await h.ask("GET", View, { session: "s-other" });
    expect(r.status).toBe(403);
    expect((await r.json()) as unknown).toMatchObject({ error: "brand_not_in_roles" });
    expect(h.calls).toHaveLength(0);
  });

  test.each([
    [403, "forbidden"],
    [404, "document_not_found"],
    [410, "document_erased"],
    [503, "storage_unavailable"],
  ])("is kyc's refusal %i as kyc gave it", async (status, error) => {
    const h = app(() => Response.json({ error, message: "kyc's words" }, { status }));
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(status);
    expect((await r.json()) as unknown).toMatchObject({ error });
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test("a type a browser would run is given as a file, not shown", async () => {
    const h = app(() => new Response("<script>1</script>", { headers: { "Content-Type": "text/html" } }));
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.headers.get("content-type")).toBe("application/octet-stream");
    expect(r.headers.get("content-disposition")).toBe("attachment");
  });
});

describe("no address is ever fetched beyond kyc's view route", () => {
  test("an address in the page's query is neither fetched nor passed to kyc", async () => {
    const h = app();
    await h.ask("GET", `${View}?url=${encodeURIComponent("http://169.254.169.254/latest/meta-data")}&redirect=http://evil.example`, { session: "s-1" });
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test("an address in the page's headers is not followed", async () => {
    const h = app();
    await h.ask("GET", View, { session: "s-1", headers: { Referer: "http://evil.example/", "X-Forwarded-For": "http://evil.example", Location: "http://evil.example/" } });
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test.each([
    ["a signed link, as kyc answered before K5", () => Response.json({ url: "https://objects.example.test/goldvibe-kyc/x?X-Amz-Signature=abc", expiresAt: "2026-11-15T12:15:00Z" })],
    ["a link to the services' own address", () => Response.json({ url: `http://services.local/players/v1/casino/backoffice/${Brand}/players` })],
    ["the metadata address", () => Response.json({ url: "http://169.254.169.254/latest/meta-data/" })],
  ])("an address in kyc's body (%s) is not fetched; the body is passed as kyc's answer", async (_, answer) => {
    const h = app(answer);
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.headers.get("content-type")).toBe("application/octet-stream");
    expect(r.headers.get("content-disposition")).toBe("attachment");
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test.each([301, 302, 303, 307, 308])("a redirect %i from kyc is neither followed nor passed on", async (status) => {
    const h = app(() => new Response(null, { status, headers: { Location: "http://evil.example/" } }));
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(502);
    expect(r.headers.get("location")).toBeNull();
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test("an id that is not one segment asks nothing", async () => {
    const h = app();
    for (const bad of [`/api/kyc/${Player}%2Fviews%2F${Doc}/documents/x/view`, `/api/kyc/..%2f..%2fplayers/documents/${Doc}/view`]) {
      const r = await h.ask("GET", bad, { session: "s-1" });
      expect(r.status).toBe(404);
    }
    expect(h.calls).toHaveLength(0);
  });

  test("kyc's view route is not passed through under /api/s, where its bytes would lack the view's headers", async () => {
    const h = app();
    const r = await h.ask("GET", `/api/s/kyc/${Player}/views/${Doc}`, { session: "s-1" });
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: "no_such_route" });
    expect(h.calls).toHaveLength(0);
  });
});
