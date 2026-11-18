import type { Result } from "../client.ts";
import type { StreamEvent } from "../eventStream.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";

/** One part of the support agent's answer (spec 017's stream), the agent's words as it sent them. */
export type ChatEvent =
  | { readonly kind: "delta"; readonly text: string }
  | { readonly kind: "confirm"; readonly action: string; readonly effect: string }
  | { readonly kind: "result"; readonly action: string; readonly success: boolean; readonly detail?: string }
  | { readonly kind: "done" }
  | { readonly kind: "error"; readonly words: string };

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

const text = (v: unknown) => (typeof v === "string" ? v : "");

/** A frame read as a part; a frame the SDK does not know, or whose data is not JSON, is no part. */
export function chatEvent(e: StreamEvent): ChatEvent | null {
  let d: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(e.data || "{}");
    d = typeof parsed === "object" && parsed !== null ? (parsed as Record<string, unknown>) : {};
  } catch {
    return null;
  }
  switch (e.event) {
    case "delta":
      return { kind: "delta", text: text(d.token) };
    case "confirm_action":
      return { kind: "confirm", action: text(d.action_name), effect: text(d.summary) };
    case "action_result":
      return { kind: "result", action: text(d.action_name), success: d.success === true, ...(typeof d.detail === "string" ? { detail: d.detail } : {}) };
    case "done":
      return { kind: "done" };
    case "error":
      return { kind: "error", words: text(d.message) };
    default:
      return null;
  }
}

async function* parts(events: AsyncIterable<StreamEvent>): AsyncGenerator<ChatEvent> {
  for await (const e of events) {
    const part = chatEvent(e);
    if (part) yield part;
  }
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
    turn: (sessionId, message) => open("turn", {}, { message, session_id: sessionId }),
    decide: (sessionId, approve, feedback) => open("decide", { session: sessionId }, { action: approve ? "approve" : "reject", ...(feedback ? { feedback } : {}) }),
  };
}
