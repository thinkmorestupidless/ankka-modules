import { Hono } from "hono";
import { pathNames } from "../route.ts";
import { traceHeaders, visitorHeaders, type ServerDeps } from "./context.ts";
import { pageRoute, runs } from "./modules.ts";
import { callService } from "./services.ts";
import { held } from "./session.ts";

const refuse = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

/** What of the service's answer reaches the page: its status and body, unchanged, and what describes them. */
const passed = ["content-type", "content-language", "retry-after"];

/**
 * `/api/c/<module>/<id>` (contracts/site-server.md, *Pass-through*): the page's only way to a
 * service. The row must be in the module's table; the brand is the server's; the sign-in is added
 * by the server and never comes from the page; a cashier ticket travels only in its header.
 */
export function passThrough(deps: ServerDeps): Hono {
  const app = new Hono();
  const { config } = deps;

  app.all("/api/c/:module/:id", async (c) => {
    const module = c.req.param("module");
    const route = pageRoute(module, c.req.param("id"), c.req.method);
    if (!route) return refuse(404, { error: "not_named" });
    if (!runs(config, module)) return refuse(404, { error: "module_not_run" });

    const url = new URL(c.req.url);
    if (url.searchParams.has("ticket")) return refuse(400, { error: "not_named", field: "ticket" });
    const given = url.searchParams.get("brand");
    if (given !== null && given !== config.brand) return refuse(403, { error: "wrong_brand", brand: config.brand });

    const names = pathNames(route.path);
    const values: Record<string, string> = {};
    for (const name of names) {
      if (name === "brand" || name === "ticket") continue;
      const v = url.searchParams.get(name);
      if (v === null || v === "") return refuse(400, { error: "missing_fields", fields: [name] });
      values[name] = v;
    }
    const query = new URLSearchParams();
    for (const name of route.query ?? []) {
      for (const v of url.searchParams.getAll(name)) query.append(name, v);
    }
    if (route.brandQuery) query.set("brand", config.brand);

    const headers: Record<string, string> = { ...traceHeaders(c.req.raw) };
    const type = c.req.header("content-type");
    if (type) headers["content-type"] = type;
    const accept = c.req.header("accept");
    if (accept) headers.accept = accept;
    if (route.visitor) Object.assign(headers, visitorHeaders(c.req.raw));

    if (route.audience !== "public") {
      const h = held(deps, c.req.raw);
      if (!h.session || !h.player) return refuse(401, { error: "signed_out" });
      if (values.player !== undefined && values.player !== h.player.playerId) return refuse(403, { error: "player_id_mismatch" });
      if (route.audience === "player") headers.authorization = `Bearer ${h.session}`;
      else {
        const ticket = c.req.header("cashierTicket");
        if (!ticket) return refuse(401, { error: "UNAUTHENTICATED" });
        headers.cashierTicket = ticket;
        if (names.includes("ticket")) values.ticket = ticket;
      }
    }

    const body = ["GET", "HEAD"].includes(route.method) ? undefined : await c.req.arrayBuffer();
    const answer = await callService(config, route, {
      path: values,
      query,
      headers,
      ...(body && body.byteLength > 0 ? { body } : {}),
      ...(route.stream ? { stream: true } : {}),
    }, deps.fetch);
    if (!answer.answered) return refuse(503, { error: "service_unavailable", service: route.service });
    const r = answer.response;
    if (r.status === 401 && route.audience === "player") return refuse(401, { error: "signed_out" });
    const out = new Headers({ "cache-control": "no-store" });
    for (const name of passed) {
      const v = r.headers.get(name);
      if (v) out.set(name, v);
    }
    if (route.stream && r.ok) out.set("x-accel-buffering", "no");
    return new Response(r.body, { status: r.status, headers: out });
  });

  return app;
}
