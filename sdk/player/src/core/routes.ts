import type { Routes } from "../route.ts";

const players = "/v1/casino/players/{brand}";

/**
 * `core`'s route table (contracts/module-clients.md): `players`, the wallet, responsible gambling
 * and the cashier. The rows marked `serverOnly` are called by the site server's own session
 * routes and are not in a page's allow-list.
 */
export const routes = [
  { module: "core", id: "signIn", service: "players", method: "POST", path: `${players}/login`, audience: "public", serverOnly: true },
  { module: "core", id: "refresh", service: "players", method: "POST", path: `${players}/refresh`, audience: "public", serverOnly: true },
  { module: "core", id: "signOut", service: "players", method: "POST", path: `${players}/{player}/logout`, audience: "player", serverOnly: true },
] as const satisfies Routes;
