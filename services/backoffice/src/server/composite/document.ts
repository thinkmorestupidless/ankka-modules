// `GET /api/kyc/{p}/documents/{d}/view` (contracts/routes.md; FR-031): an identity document streamed to
// an operator the brand's kyc admits for it. The player and document ids are the only inputs: nothing
// else in the page's request (its query, its headers) is read or passed on, and no address the page
// names is ever fetched. kyc is asked for the view as the operator, through its row of the route table,
// and answers a link signed for that one object; the link is followed only when it is an address in the
// brand's document store (`BACKOFFICE_DOCUMENT_STORE`), never another, so an owner's body cannot send
// the backoffice anywhere else either. The original's viewer fetched any address it was given.
import { match, type Row } from "../routes.ts";
import { pass, unavailable, type ProxyDeps } from "../proxy.ts";

export interface DocumentDeps extends ProxyDeps {
  table: readonly Row[];
  /** The document store's address and path prefix that kyc's signed links name; none follows no link. */
  documentStore?: string;
}

/** The kyc row a view is asked through: by its template, so a row of another shape is never it. */
const ViewTemplate = "/{playerId}/views/{documentId}";

/** The types a browser is given to show; any other is given as a file to save. */
const Shown = new Set(["application/pdf", "image/jpeg", "image/png", "image/webp", "image/gif"]);

const refuse = (status: number, error: string, message: string) => Response.json({ error, message }, { status });

/**
 * The link when it is an address in the store, else undefined: the same scheme, host and port, under
 * the store's path, with no credentials in it. The parser resolves `..` and its encodings first.
 */
export function inStore(store: string | undefined, link: unknown): string | undefined {
  if (store === undefined || store === "" || typeof link !== "string") return undefined;
  let base: URL;
  let url: URL;
  try {
    base = new URL(store);
    url = new URL(link);
  } catch {
    return undefined;
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") return undefined;
  if (url.protocol !== base.protocol || url.host !== base.host || url.username !== "" || url.password !== "") return undefined;
  const prefix = base.pathname.endsWith("/") ? base.pathname : `${base.pathname}/`;
  if (!url.pathname.startsWith(prefix) || url.pathname.length === prefix.length) return undefined;
  return url.href;
}

export async function viewDocument(deps: DocumentDeps, player: string, document: string, accessToken: string): Promise<Response> {
  const m = match(deps.table, "GET", "kyc", `/${player}/views/${document}`);
  if (m === undefined || m.row.path !== ViewTemplate) return refuse(404, "no_such_route", "the backoffice does not ask for this");

  // kyc decides whether this operator may see this document, and records the view.
  const asked = new Request("http://backoffice/view", { headers: { Accept: "application/json" } });
  const { response } = await pass(deps, m, asked, accessToken);
  if (!response.ok) return response;
  let link: unknown;
  try {
    link = ((await response.json()) as { url?: unknown })?.url;
  } catch {
    return refuse(502, "document_unreadable", "kyc's answer named no document");
  }
  if (deps.documentStore === undefined || deps.documentStore === "")
    return refuse(503, "document_store_unconfigured", "this backoffice has no document store to read from");
  const at = inStore(deps.documentStore, link);
  if (at === undefined) return refuse(502, "document_address_refused", "kyc's link is not an address in the brand's document store");

  let object: Response;
  try {
    // no token of the operator's goes to the store: the link is signed for this object alone
    object = await deps.fetch(at, { method: "GET", redirect: "manual", signal: AbortSignal.timeout(deps.timeoutMs) });
  } catch {
    return unavailable("document-store");
  }
  if (object.status !== 200) {
    await object.body?.cancel();
    return object.status === 403 || object.status === 404
      ? refuse(410, "document_link_expired", "the document's link has expired or the object is gone")
      : unavailable("document-store");
  }
  const type = (object.headers.get("content-type") ?? "").split(";")[0]!.trim().toLowerCase();
  const shown = Shown.has(type);
  const headers = new Headers({
    "Content-Type": shown ? type : "application/octet-stream",
    "Content-Disposition": shown ? "inline" : "attachment",
    "Cache-Control": "no-store, private",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'",
    "Referrer-Policy": "no-referrer",
  });
  const length = object.headers.get("content-length");
  if (length !== null && /^\d+$/.test(length)) headers.set("Content-Length", length);
  return new Response(object.body, { status: 200, headers });
}
