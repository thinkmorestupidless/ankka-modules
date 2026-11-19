import type { Routes } from "../route.ts";

/** `engagement`'s route table (contracts/module-clients.md): the push connection. */
export const routes = [
  { module: "engagement", id: "push", service: "engagement", method: "GET", path: "/v1/stream", audience: "player", stream: true },
] as const satisfies Routes;
