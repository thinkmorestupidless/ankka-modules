import type { SiteConfig } from "./config.ts";

/** One line of the request log: never a header, a cookie, a query or a body (contracts/site-server.md). */
export interface LogLine {
  readonly method: string;
  readonly route: string;
  readonly status: number;
  readonly ms: number;
}

/** What the site server is made with. */
export interface ServerDeps {
  readonly config: SiteConfig;
  readonly fetch: typeof fetch;
  readonly now: () => number;
  readonly log: (line: LogLine) => void;
}

/**
 * The visitor's network address and user agent, as `players` reads them for a sign-in, a
 * registration and the country (research V3): the last entry of `X-Forwarded-For`, which the
 * platform's gateway writes, sent on as `X-Forwarded-For` and `X-Real-IP`.
 */
export function visitorHeaders(request: Request): Record<string, string> {
  const out: Record<string, string> = {};
  const forwarded = request.headers.get("x-forwarded-for");
  const address = forwarded?.split(",").map((s) => s.trim()).filter((s) => s !== "").at(-1);
  if (address) {
    out["x-forwarded-for"] = address;
    out["x-real-ip"] = address;
  }
  const agent = request.headers.get("user-agent");
  if (agent) out["user-agent"] = agent;
  return out;
}

/** The trace context, passed on as it arrived; the server writes no span of its own. */
export function traceHeaders(request: Request): Record<string, string> {
  const out: Record<string, string> = {};
  for (const name of ["traceparent", "tracestate"]) {
    const v = request.headers.get(name);
    if (v) out[name] = v;
  }
  return out;
}
