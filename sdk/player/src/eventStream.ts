/** One event of a `text/event-stream`. */
export interface StreamEvent {
  readonly event: string;
  readonly data: string;
  readonly id?: string;
}

/**
 * Reads a `text/event-stream` body as events, by the WHATWG rules the SDK needs: `event`, `data`
 * (several joined by a line feed), `id`, comments, CR, LF and CRLF line ends, a frame split across
 * chunks. A body ending in the middle of a frame does not deliver that frame (research R9: `fetch`
 * is used because the chat's routes are `POST`s, which `EventSource` cannot send).
 */
export async function* readEvents(body: ReadableStream<Uint8Array>, signal?: AbortSignal): AsyncGenerator<StreamEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let event = "";
  let data: string[] = [];
  let id: string | undefined;
  const abort = () => void reader.cancel().catch(() => {});
  signal?.addEventListener("abort", abort, { once: true });
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      for (;;) {
        const m = /\r\n|\r|\n/.exec(buffer);
        if (!m) break;
        // A CR at the very end may be the first half of a CRLF still to come.
        if (m[0] === "\r" && m.index === buffer.length - 1) break;
        const line = buffer.slice(0, m.index);
        buffer = buffer.slice(m.index + m[0].length);
        if (line === "") {
          if (data.length > 0) yield id === undefined ? { event: event || "message", data: data.join("\n") } : { event: event || "message", data: data.join("\n"), id };
          event = "";
          data = [];
          continue;
        }
        if (line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        else if (field === "data") data.push(value);
        else if (field === "id") id = value;
      }
    }
  } finally {
    signal?.removeEventListener("abort", abort);
    reader.releaseLock();
  }
}
