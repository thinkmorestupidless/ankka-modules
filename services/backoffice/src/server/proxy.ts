// The pass-through (contracts/routes.md): a matched row is called at the owner's calling address with
// the operator's token, and the owner's answer is returned as it gave it. The body is not parsed. A write's
// `Idempotency-Key` is passed on (the affiliate programme's adjustments read it), once it is checked.
import { type Matched, upstreamPath } from "./routes.ts";

export interface ProxyDeps {
  brand: string;
  servicesUrl: string;
  /** A shared project's key (`payments`, …) to its project's name, from the brand's values. */
  sharedProjects: Record<string, string>;
  fetch: typeof fetch;
  timeoutMs: number;
}

/** The headers of an owner's answer the page may need; nothing else is passed back (see `answeredHeaders`). */
const Answered = ["content-type", "content-disposition", "content-length", "retry-after", "location"];
/** The headers of the page's request an owner may need. */
const Asked = ["content-type", "accept", "accept-language", "traceparent", "tracestate"];

/** The longest `Idempotency-Key` passed on, and what one may hold: printable ASCII only. */
export const IdempotencyKeyMax = 200;
const Printable = /^[\x20-\x7e]+$/;

/** Whether a page's `Idempotency-Key` may be passed on: 1 to 200 printable ASCII characters. */
export function idempotencyKeyValid(key: string): boolean {
  return key.length <= IdempotencyKeyMax && Printable.test(key);
}

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
  const key = req.headers.get("idempotency-key");
  if (key !== null) {
    if (!idempotencyKeyValid(key))
      return {
        response: Response.json({ error: "invalid_idempotency_key", message: `Idempotency-Key must be 1 to ${IdempotencyKeyMax} printable ASCII characters` }, { status: 400 }),
        why: "idempotency-key",
      };
    headers.set("idempotency-key", key);
  }
  const body = req.method === "GET" || req.method === "HEAD" ? undefined : await req.arrayBuffer();
  const bound = new Bound(deps.timeoutMs);
  let answer: Response;
  try {
    bound.arm();
    answer = await deps.fetch(url, {
      method: req.method,
      headers,
      body,
      redirect: "manual",
      signal: bound.signal,
    });
  } catch (e) {
    return { response: unavailable(m.row.service), why: e instanceof Error ? e.name : "unknown" };
  } finally {
    bound.disarm();
  }
  if (answer.headers.has("x-ankka-answered-by")) {
    await answer.body?.cancel();
    return { response: unavailable(m.row.service), why: `proxy ${answer.status}` };
  }
  const passed = answer.body === null ? null : bound.body(answer.body);
  return { response: new Response(passed, { status: answer.status, headers: answeredHeaders(answer.headers) }) };
}

/**
 * The bound on a call to an owner (plan.md: 15 s for a pass-through): on the start of its answer, then
 * on each wait for the next part of its body. A long download or export that keeps arriving is never
 * cut; an owner silent for the bound is. Only the owner's silence counts: no wait is timed while the
 * page is not reading.
 */
class Bound {
  private readonly controller = new AbortController();
  private timer: ReturnType<typeof setTimeout> | undefined;
  readonly signal = this.controller.signal;
  private readonly ms: number;

  constructor(ms: number) {
    this.ms = ms;
  }

  arm(): void {
    this.disarm();
    this.timer = setTimeout(() => this.controller.abort(new DOMException(`no answer within ${this.ms} ms`, "TimeoutError")), this.ms);
  }

  disarm(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
  }

  body(given: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
    const reader = given.getReader();
    const signal = this.signal;
    const next = () =>
      new Promise<ReadableStreamReadResult<Uint8Array>>((resolve, reject) => {
        if (signal.aborted) return reject(signal.reason);
        const stop = () => reject(signal.reason);
        signal.addEventListener("abort", stop, { once: true });
        reader.read().then(resolve, reject).finally(() => signal.removeEventListener("abort", stop));
      });
    return new ReadableStream<Uint8Array>({
      pull: async (controller) => {
        this.arm();
        try {
          const { value, done } = await next();
          if (done) controller.close();
          else controller.enqueue(value);
        } catch (e) {
          void reader.cancel(e).catch(() => undefined);
          throw e;
        } finally {
          this.disarm();
        }
      },
      cancel: async (reason) => {
        this.disarm();
        await reader.cancel(reason);
      },
    });
  }
}

/**
 * The owner's headers the page is given. fetch hands over the body decoded, so an owner's
 * `Content-Length` is kept only for a body it sent unencoded: the length of a compressed body is not
 * the length of what is passed on. Every answer is `nosniff`: an owner's bytes are never run as
 * another type.
 */
export function answeredHeaders(given: Headers): Headers {
  const out = new Headers({ "X-Content-Type-Options": "nosniff" });
  const encoded = (given.get("content-encoding") ?? "identity").trim().toLowerCase() !== "identity";
  for (const h of Answered) {
    const v = given.get(h);
    if (v !== null && !(encoded && h === "content-length")) out.set(h, v);
  }
  return out;
}
