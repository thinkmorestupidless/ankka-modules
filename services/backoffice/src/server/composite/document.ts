// `GET /api/kyc/{p}/documents/{d}/view` (contracts/routes.md; FR-031): an identity document streamed to
// an operator the brand's kyc admits for it. The player and document ids are the only inputs: nothing
// else in the page's request (its query, its headers) is read or passed on. kyc is asked for the view as
// the operator, through its row of the route table; kyc decides, records the view and answers the
// document's bytes (amendment K5), which pass through as they arrive. No other address is ever fetched:
// not one the page names, not one in kyc's answer. The original's viewer fetched any address it was given.
import { match, type Row } from "../routes.ts";
import { pass, type ProxyDeps } from "../proxy.ts";

export interface DocumentDeps extends ProxyDeps {
  table: readonly Row[];
}

/** The kyc row a view is asked through: by its template, so a row of another shape is never it. */
const ViewTemplate = "/{playerId}/views/{documentId}";

/** The types a browser is given to show; any other is given as a file to save. */
const Shown = new Set(["application/pdf", "image/jpeg", "image/png", "image/webp", "image/gif"]);

const refuse = (status: number, error: string, message: string) => Response.json({ error, message }, { status });

export async function viewDocument(deps: DocumentDeps, player: string, document: string, accessToken: string): Promise<Response> {
  const m = match(deps.table, "GET", "kyc", `/${player}/views/${document}`);
  if (m === undefined || m.row.path !== ViewTemplate) return refuse(404, "no_such_route", "the backoffice does not ask for this");

  // kyc decides whether this operator may see this document, records the view, and answers its bytes.
  const asked = new Request("http://backoffice/view");
  const { response } = await pass(deps, m, asked, accessToken);
  // a refusal as kyc gave it; anything else that is not the document (a redirect, an empty answer) is none
  if (response.status >= 400) return response;
  if (response.status !== 200) {
    await response.body?.cancel();
    return refuse(502, "document_unreadable", "kyc's answer held no document");
  }
  const type = (response.headers.get("content-type") ?? "").split(";")[0]!.trim().toLowerCase();
  const shown = Shown.has(type);
  const headers = new Headers({
    "Content-Type": shown ? type : "application/octet-stream",
    "Content-Disposition": shown ? "inline" : "attachment",
    "Cache-Control": "no-store, private",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'",
    "Referrer-Policy": "no-referrer",
  });
  const length = response.headers.get("content-length");
  if (length !== null && /^\d+$/.test(length)) headers.set("Content-Length", length);
  return new Response(response.body, { status: 200, headers });
}
