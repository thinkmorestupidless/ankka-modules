// T094: an identity document is streamed for the operator from kyc's view of it, the two ids the only
// inputs (FR-031). No address in the page's request is ever fetched, and kyc's link is followed only
// when it is an address in the brand's document store: no URL the page or an owner's body supplies
// sends the backoffice anywhere else.
import { describe, expect, test } from "vitest";
import { inStore } from "../../src/server/composite/document.ts";
import { table } from "../../src/server/routes.ts";
import { Brand, harness, SecretToken } from "./support.ts";

const Store = "https://objects.example.test/goldvibe-kyc/";
const Player = "8b0f4c1e-35a6-4b8e-9d3b-1c2f0a9e7d11";
const Doc = "1de8118c-ca16-49f7-99b5-ae7322e0ed1f";
const Signed = `${Store}players/${Player}/${Doc}?X-Amz-Signature=abc`;
const View = `/api/kyc/${Player}/documents/${Doc}/view`;
const KycView = `http://services.local/kyc/v1/casino/backoffice/${Brand}/kyc/${Player}/views/${Doc}`;

/** The app with kyc answering `link` for the view, and the store answering a PDF. */
function app(link: unknown = Signed, over: Parameters<typeof harness>[0] = {}) {
  const h = harness({ table, documentStore: Store, ...over });
  h.answer((call) => {
    if (call.url.startsWith("http://services.local/kyc/")) return Response.json({ url: link, expiresAt: "2026-11-15T12:15:00Z" });
    if (call.url.startsWith(Store)) return new Response("%PDF-1.4 the document", { headers: { "Content-Type": "application/pdf", "Content-Length": "21" } });
    return Response.json({ error: "not_found" }, { status: 404 });
  });
  return h;
}

describe("a document viewed", () => {
  test("is kyc's view asked as the operator, then the object streamed with no token sent to the store", async () => {
    const h = app();
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(200);
    expect(await r.text()).toBe("%PDF-1.4 the document");
    expect(r.headers.get("content-type")).toBe("application/pdf");
    expect(r.headers.get("cache-control")).toContain("no-store");
    expect(r.headers.get("x-content-type-options")).toBe("nosniff");
    expect(h.calls.map((c) => c.url)).toEqual([KycView, Signed]);
    expect(h.calls[0]!.headers.get("authorization")).toBe(`Bearer ${SecretToken}`);
    expect(h.calls[1]!.headers.get("authorization")).toBeNull();
  });

  test("is refused, and nothing asked, for an operator whose roles do not name the brand", async () => {
    const h = app();
    h.state.sessions.set("s-other", { accessToken: SecretToken, expiresAt: Date.now() + 60_000, roles: ["brand_spinvibe", "brand_support"] });
    const r = await h.ask("GET", View, { session: "s-other" });
    expect(r.status).toBe(403);
    expect((await r.json()) as unknown).toMatchObject({ error: "brand_not_in_roles" });
    expect(h.calls).toHaveLength(0);
  });

  test("is kyc's refusal as kyc gave it, and the store is not asked", async () => {
    const h = harness({ table, documentStore: Store });
    h.answer(() => Response.json({ error: "forbidden", message: "forbidden: the identity.review permission is needed" }, { status: 403 }));
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(403);
    expect(h.calls).toHaveLength(1);
  });

  test("a type a browser would run is given as a file, not shown", async () => {
    const h = harness({ table, documentStore: Store });
    h.answer((call) =>
      call.url.startsWith(Store) ? new Response("<script>1</script>", { headers: { "Content-Type": "text/html" } }) : Response.json({ url: Signed }),
    );
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.headers.get("content-type")).toBe("application/octet-stream");
    expect(r.headers.get("content-disposition")).toBe("attachment");
  });
});

describe("no address in a request is ever fetched", () => {
  test("an address in the page's query is neither fetched nor passed to kyc", async () => {
    const h = app();
    await h.ask("GET", `${View}?url=${encodeURIComponent("http://169.254.169.254/latest/meta-data")}&redirect=http://evil.example`, { session: "s-1" });
    expect(h.calls.map((c) => c.url)).toEqual([KycView, Signed]);
  });

  test("an address in the page's headers is not followed", async () => {
    const h = app();
    await h.ask("GET", View, { session: "s-1", headers: { Referer: "http://evil.example/", "X-Forwarded-For": "http://evil.example", Location: "http://evil.example/" } });
    expect(h.calls.map((c) => c.url)).toEqual([KycView, Signed]);
  });

  test.each([
    ["a host of the page's choosing", "http://evil.example/players/x"],
    ["the services' own address", `http://services.local/players/v1/casino/backoffice/${Brand}/players`],
    ["the metadata address", "http://169.254.169.254/latest/meta-data/"],
    ["the store's host on another port", "https://objects.example.test:8443/goldvibe-kyc/players/x"],
    ["the store's host under another bucket", "https://objects.example.test/another-brand-kyc/players/x"],
    ["a way out of the store's path", "https://objects.example.test/goldvibe-kyc/../another-brand-kyc/x"],
    ["an encoded way out of the store's path", "https://objects.example.test/goldvibe-kyc/%2e%2e/another-brand-kyc/x"],
    ["credentials in the address", "https://user:pass@objects.example.test/goldvibe-kyc/x"],
    ["the store's host as a user name", "https://objects.example.test@evil.example/goldvibe-kyc/x"],
    ["another scheme", "file:///etc/passwd"],
    ["the store's path itself", Store],
    ["a relative address", "/goldvibe-kyc/x"],
    ["no address", null],
  ])("kyc's link to %s is refused and not fetched", async (_, link) => {
    const h = app(link);
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(502);
    expect(h.calls.map((c) => c.url)).toEqual([KycView]);
  });

  test("with no document store given, no link is followed", async () => {
    const h = app(Signed, { documentStore: undefined });
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(503);
    expect((await r.json()) as unknown).toMatchObject({ error: "document_store_unconfigured" });
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

  test("a redirect from the store is not followed", async () => {
    const h = harness({ table, documentStore: Store });
    h.answer((call) => (call.url.startsWith(Store) ? new Response(null, { status: 302, headers: { Location: "http://evil.example/" } }) : Response.json({ url: Signed })));
    const r = await h.ask("GET", View, { session: "s-1" });
    expect(r.status).toBe(503);
    expect(h.calls.map((c) => c.url)).toEqual([KycView, Signed]);
  });
});

describe("an address in the store", () => {
  test("is one under its path, on its host", () => {
    expect(inStore(Store, Signed)).toBe(Signed);
    expect(inStore("https://objects.example.test/goldvibe-kyc", `${Store}x`)).toBe(`${Store}x`);
    expect(inStore("https://objects.example.test/goldvibe-kyc", "https://objects.example.test/goldvibe-kycx/y")).toBeUndefined();
    expect(inStore(undefined, Signed)).toBeUndefined();
  });
});
