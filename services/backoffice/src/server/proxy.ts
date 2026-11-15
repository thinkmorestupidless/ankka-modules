// The pass-through (contracts/routes.md): a matched row is called at the owner's calling address with
// the operator's token, and the owner's answer is returned as it gave it. The body is not parsed.
import { type Matched, upstreamPath } from "./routes.ts";

export interface ProxyDeps {
  brand: string;
  servicesUrl: string;
  /** A shared project's key (`payments`, …) to its project's name, from the brand's values. */
  sharedProjects: Record<string, string>;
  fetch: typeof fetch;
  timeoutMs: number;
}

/** The headers of an owner's answer the page may need; nothing else is passed back. */
const Answered = ["content-type", "content-disposition", "content-length", "retry-after", "location"];
/** The headers of the page's request an owner may need. */
const Asked = ["content-type", "accept", "accept-language", "traceparent", "tracestate"];

export function unavailable(source: string): Response {
  return Response.json({ error: "source_unavailable", source }, { status: 503 });
}

export function ownerAddress(deps: Pick<ProxyDeps, "servicesUrl" | "sharedProjects">, service: string, where: string): string {
  return where === "brand"
    ? `${deps.servicesUrl}/${service}`
    : `${deps.servicesUrl}/${service}.${deps.sharedProjects[where] ?? where}`;
}

/** What happened, for the log line: never the token, never the error's message. */
export interface Outcome {
  response: Response;
  why?: string;
}

export async function pass(deps: ProxyDeps, m: Matched, req: Request, accessToken: string): Promise<Outcome> {
  const url = `${ownerAddress(deps, m.row.service, m.row.where)}${upstreamPath(m, deps.brand)}${new URL(req.url).search}`;
  const headers = new Headers({ Authorization: `Bearer ${accessToken}` });
  for (const h of Asked) {
    const v = req.headers.get(h);
    if (v !== null) headers.set(h, v);
  }
  const body = req.method === "GET" || req.method === "HEAD" ? undefined : await req.arrayBuffer();
  let answer: Response;
  try {
    answer = await deps.fetch(url, {
      method: req.method,
      headers,
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(deps.timeoutMs),
    });
  } catch (e) {
    return { response: unavailable(m.row.service), why: e instanceof Error ? e.name : "unknown" };
  }
  if (answer.headers.has("x-ankka-answered-by")) {
    await answer.body?.cancel();
    return { response: unavailable(m.row.service), why: `proxy ${answer.status}` };
  }
  const out = new Headers();
  for (const h of Answered) {
    const v = answer.headers.get(h);
    if (v !== null) out.set(h, v);
  }
  return { response: new Response(answer.body, { status: answer.status, headers: out }) };
}
