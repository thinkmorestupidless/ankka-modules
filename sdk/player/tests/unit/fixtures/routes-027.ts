// The four hand-written route tables of spec 027, as they stood before spec 031 generated them:
// the fixture `generate.test.ts` holds the generated tables equal to, row for row.
import type { Routes } from "../../../src/route.ts";

const players = "/v1/casino/players/{brand}";

export const core = [
  { module: "core", id: "register", service: "players", method: "POST", path: `${players}/register`, audience: "public", visitor: true },
  { module: "core", id: "verifyEmail", service: "players", method: "POST", path: `${players}/verify-email`, audience: "public" },
  { module: "core", id: "resendVerification", service: "players", method: "POST", path: `${players}/resend-verification-email`, audience: "public" },
  { module: "core", id: "requestPasswordReset", service: "players", method: "POST", path: `${players}/password-reset/request`, audience: "public" },
  { module: "core", id: "signIn", service: "players", method: "POST", path: `${players}/login`, audience: "public", serverOnly: true, visitor: true },
  { module: "core", id: "refresh", service: "players", method: "POST", path: `${players}/refresh`, audience: "public", serverOnly: true },
  { module: "core", id: "signOut", service: "players", method: "POST", path: `${players}/{player}/logout`, audience: "player", serverOnly: true },
  { module: "core", id: "profile", service: "players", method: "GET", path: `${players}/{player}`, audience: "player" },
  { module: "core", id: "changeProfile", service: "players", method: "PATCH", path: `${players}/{player}`, audience: "player" },
  { module: "core", id: "startDeposit", service: "players", method: "POST", path: `${players}/{player}/deposits`, audience: "player" },
  { module: "core", id: "unread", service: "players", method: "GET", path: `${players}/{player}/messages?status=unread&page=1&pageSize=1`, audience: "player" },
  { module: "core", id: "gameSession", service: "players", method: "GET", path: "/v1/casino/sessions/{brand}/{sessionId}", audience: "player" },
  { module: "core", id: "endGameSession", service: "players", method: "PATCH", path: "/v1/casino/sessions/{brand}/{sessionId}/end", audience: "player" },
  { module: "core", id: "country", service: "players", method: "GET", path: "/v1/casino/geo/lookup", audience: "public", visitor: true },
  { module: "core", id: "balance", service: "wallet", method: "GET", path: "/v1/casino/players/{brand}/wallets/{currency}/balance", audience: "player" },
  { module: "core", id: "protection", service: "responsible-gambling", method: "GET", path: "/v1/casino/rg/{brand}/{player}/limits", audience: "player", query: ["currency"] },
  { module: "core", id: "acknowledgeRealityCheck", service: "responsible-gambling", method: "POST", path: "/v1/casino/rg/{brand}/{player}/reality-check/acknowledge", audience: "player" },
  { module: "core", id: "submitCashier", service: "ingest", method: "POST", path: "/v1/cashier/sessions/{ticket}/submit", audience: "ticket" },
  { module: "core", id: "abandonCashier", service: "ingest", method: "POST", path: "/v1/cashier/sessions/{ticket}/abandon", audience: "ticket" },
  { module: "core", id: "payment", service: "ingest", method: "GET", path: "/v1/payments/{paymentId}", audience: "ticket" },
] as const satisfies Routes;

const catalogue = ["category", "themes", "features", "bonusTypes", "tag", "developer", "search", "country", "limit", "offset", "ids"] as const;

export const casino = [
  { module: "casino", id: "launch", service: "game-provider", method: "POST", path: "/v1/casino/sessions/{brand}/launch", audience: "player" },
  { module: "casino", id: "games", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games", audience: "public", brandQuery: true, query: catalogue },
  { module: "casino", id: "game", service: "game-provider", method: "GET", path: "/v1/casino/catalog/games/{gameId}", audience: "public", brandQuery: true, query: ["provider"] },
  { module: "casino", id: "freeRounds", service: "rewards", method: "GET", path: "/v1/casino/brands/{brand}/rewards/{player}/free-rounds", audience: "player", query: ["currency"] },
] as const satisfies Routes;

export const engagement = [
  { module: "engagement", id: "push", service: "engagement", method: "GET", path: "/v1/stream", audience: "player", stream: true },
] as const satisfies Routes;

export const support = [
  { module: "support", id: "turn", service: "support-agent", method: "POST", path: "/v1/casino/agent/{brand}/run/chat_with_player", audience: "player", stream: true },
  { module: "support", id: "decide", service: "support-agent", method: "POST", path: "/v1/casino/agent/{brand}/resume/{session}", audience: "player", stream: true },
] as const satisfies Routes;
