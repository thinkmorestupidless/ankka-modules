import type { Selection } from "../selection.ts";

const agent = "/v1/casino/agent/the-brand";

/**
 * `support`'s rows (spec 031): the support agent's chat, both streams, whose events `events.ts`
 * parses. The turn's last segment is a path parameter in the service and has one value.
 */
export const selection = [
  { id: "turn", service: "support-agent", operation: `POST ${agent}/run/{route}`, fixed: { route: "chat_with_player" }, takes: "Turn" },
  { id: "decide", service: "support-agent", operation: `POST ${agent}/resume/{session}`, takes: "Decision" },
] as const satisfies Selection;
