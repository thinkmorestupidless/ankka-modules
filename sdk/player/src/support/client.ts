import type { Result } from "../client.ts";
import type { Transport } from "../transport.ts";
import { parts, type ChatEvent } from "./events.ts";
import { routes } from "./routes.ts";
import type { Decision, Turn } from "./shapes.ts";

export { chatEvent, type ChatEvent } from "./events.ts";

/** A reply arriving: its parts until the stream ends, and a way to stop reading it. */
export interface Reply {
  readonly events: AsyncIterable<ChatEvent>;
  close(): void;
}

/** `support`'s client (contracts/module-clients.md): a turn and a decision, each a posted stream. */
export interface SupportClient {
  turn(sessionId: string, message: string): Promise<Result<Reply>>;
  /** The player's approve, or cancel (sent as `reject`). */
  decide(sessionId: string, approve: boolean, feedback?: string): Promise<Result<Reply>>;
}

/**
 * The support agent's words reach the page (FR-054), so a refusal before the stream keeps its
 * `message` as `words`: the unavailable and closing words.
 */
export function supportClient(transport: Transport, brand: () => string): SupportClient {
  const row = (id: string) => routes.find((r) => r.id === id)!;
  const open = async (id: string, path: Record<string, string>, body: unknown): Promise<Result<Reply>> => {
    const r = await transport.stream(row(id), { path: { brand: brand(), ...path }, body, words: true });
    return r.ok ? { ok: true, value: { events: parts(r.events), close: r.close } } : r;
  };
  return {
    turn: (sessionId, message) => open("turn", {}, { message, session_id: sessionId } satisfies Turn),
    decide: (sessionId, approve, feedback) => open("decide", { session: sessionId }, { action: approve ? "approve" : "reject", ...(feedback ? { feedback } : {}) } satisfies Decision),
  };
}
