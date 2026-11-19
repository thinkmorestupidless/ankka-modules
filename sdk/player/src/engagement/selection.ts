import type { Selection } from "../selection.ts";

/** `engagement`'s row (spec 031): the push connection, a stream, whose events `events.ts` parses. */
export const selection = [{ id: "push", service: "engagement", operation: "GET /v1/stream" }] as const satisfies Selection;
