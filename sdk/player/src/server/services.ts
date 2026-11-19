import type { Route } from "../route.ts";
import type { SiteConfig } from "./config.ts";

export interface ServiceCall {
  readonly path?: Readonly<Record<string, string>>;
  /** The service's own query, beside any the route fixes. */
  readonly query?: URLSearchParams;
  readonly method?: string;
  readonly body?: BodyInit | null;
  readonly headers?: Readonly<Record<string, string>>;
  /** A stream needs only to begin within the bound. */
  readonly stream?: boolean;
}

export type ServiceAnswer = { answered: true; response: Response } | { answered: false };

/** The service's address: `ingest` at the shared payments' public address, any other at the calling address. */
export function serviceUrl(config: SiteConfig, route: Route, values: Readonly<Record<string, string>>, query?: URLSearchParams): string {
  const [template, fixed] = route.path.split("?");
  const path = template!.replace(/\{([A-Za-z]+)\}/g, (_, name: string) => encodeURIComponent(name === "brand" ? config.brand : (values[name] ?? "")));
  const base = route.service === "ingest" ? config.cashierUrl : `${config.servicesUrl}/${route.service}`;
  const params = new URLSearchParams(fixed ?? "");
  for (const [k, v] of query ?? []) params.append(k, v);
  const q = params.toString();
  return `${base}${path}${q ? `?${q}` : ""}`;
}

/** Calls a service, bounded: no answer (a stream: no beginning) within the bound is no answer. */
export async function callService(config: SiteConfig, route: Route, call: ServiceCall = {}, fetchImpl: typeof fetch = fetch): Promise<ServiceAnswer> {
  const url = serviceUrl(config, route, call.path ?? {}, call.query);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.boundMs);
  try {
    const response = await fetchImpl(url, {
      method: call.method ?? route.method,
      headers: call.headers ?? {},
      ...(call.body !== undefined && call.body !== null ? { body: call.body } : {}),
      signal: controller.signal,
      // A body sent as a stream needs it.
      ...({ duplex: "half" } as object),
    });
    if (!call.stream) {
      // Read the whole answer inside the bound, so a service that stalls mid-answer is no answer.
      const text = await response.arrayBuffer();
      clearTimeout(timer);
      return { answered: true, response: new Response(text, { status: response.status, headers: response.headers }) };
    }
    clearTimeout(timer);
    return { answered: true, response };
  } catch {
    clearTimeout(timer);
    return { answered: false };
  }
}
