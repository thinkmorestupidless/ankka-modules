// The events of the push connection, read by hand: a description gives a stream's answer no shape
// (spec 031, research R10), so these are not generated. An event carries no data the SDK reads.
import type { StreamEvent } from "../eventStream.ts";

/** `ready` once the connection is the player's, `signal` when their messages changed, `closing` before the service ends it. */
export type PushEvent = "ready" | "signal" | "closing";

/** A frame read as an event of the push connection; a frame the SDK does not know is none. */
export function pushEvent(e: StreamEvent): PushEvent | null {
  return e.event === "ready" || e.event === "signal" || e.event === "closing" ? e.event : null;
}
