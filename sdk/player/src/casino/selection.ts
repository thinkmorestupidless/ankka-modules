import type { Selection } from "../selection.ts";

/** `casino`'s rows (spec 031): `game-provider` and `rewards`, in the order of spec 027's table. */
export const selection = [
  { id: "launch", service: "game-provider", operation: "POST /v1/casino/sessions/{brand}/launch", takes: "Launch", answers: "WireLaunch" },
  {
    id: "games",
    service: "game-provider",
    operation: "GET /v1/casino/catalog/games",
    brandQuery: true,
    query: ["category", "themes", "features", "bonusTypes", "tag", "developer", "search", "country", "limit", "offset", "ids"],
    answers: "Catalogue",
  },
  { id: "game", service: "game-provider", operation: "GET /v1/casino/catalog/games/{gameCode}", rename: { gameCode: "gameId" }, brandQuery: true, query: ["provider"], answers: "WireGame" },
  { id: "freeRounds", service: "rewards", operation: "GET /v1/casino/brands/the-brand/rewards/{playerId}/free-rounds", rename: { playerId: "player" }, query: ["currency"], answers: "Grants" },
] as const satisfies Selection;
