import type { Routes } from "../route.ts";

const catalogue = ["category", "themes", "features", "bonusTypes", "tag", "developer", "search", "country", "limit", "offset", "ids"] as const;

/** `casino`'s route table (contracts/module-clients.md): `game-provider` and `rewards`. */
export const routes = [
  { module: "casino", id: "launch", service: "game-provider", method: "POST", path: "/v1/casino/sessions/{brand}/launch", audience: "player" },
  { module: "casino", id: "games", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games", audience: "public", brandQuery: true, query: catalogue },
  { module: "casino", id: "game", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games/{gameId}", audience: "public", brandQuery: true, query: ["provider"] },
  { module: "casino", id: "freeRounds", service: "rewards", method: "GET", path: "/v1/casino/brands/{brand}/rewards/{player}/free-rounds", audience: "player", query: ["currency"] },
] as const satisfies Routes;
