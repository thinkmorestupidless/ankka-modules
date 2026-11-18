import type { Routes } from "../route.ts";

/** `support`'s route table (contracts/module-clients.md): the support agent's chat. */
export const routes = [
  { module: "support", id: "turn", service: "support-agent", method: "POST", path: "/v1/casino/agent/{brand}/run/chat_with_player", audience: "player", stream: true },
  { module: "support", id: "decide", service: "support-agent", method: "POST", path: "/v1/casino/agent/{brand}/resume/{session}", audience: "player", stream: true },
] as const satisfies Routes;
