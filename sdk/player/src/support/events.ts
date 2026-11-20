// The events of the support agent's chat (spec 017's stream), parsed by hand: a description gives a
// stream's answer no shape (spec 031, research R10), so these are not generated.
import type { StreamEvent } from "../eventStream.ts";

/** One part of the support agent's answer (spec 017's stream), the agent's words as it sent them. */
export type ChatEvent =
  | { readonly kind: "delta"; readonly text: string }
  | { readonly kind: "confirm"; readonly action: string; readonly effect: string }
  | { readonly kind: "result"; readonly action: string; readonly success: boolean; readonly detail?: string }
  | { readonly kind: "done" }
  | { readonly kind: "error"; readonly words: string };

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

export async function* parts(events: AsyncIterable<StreamEvent>): AsyncGenerator<ChatEvent> {
  for await (const e of events) {
    const part = chatEvent(e);
    if (part) yield part;
  }
}
