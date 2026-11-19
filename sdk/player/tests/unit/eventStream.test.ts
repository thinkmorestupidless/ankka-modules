import { describe, expect, it } from "vitest";
import { readEvents, type StreamEvent } from "../../src/eventStream.ts";

const body = (...chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(new TextEncoder().encode(c));
      controller.close();
    },
  });

const all = async (stream: ReadableStream<Uint8Array>) => {
  const out: StreamEvent[] = [];
  for await (const e of readEvents(stream)) out.push(e);
  return out;
};

describe("reading an event stream", () => {
  it("reads named events and their data", async () => {
    expect(await all(body("event: ready\ndata: {}\n\nevent: signal\ndata: {\"kind\":\"inbox\"}\n\n"))).toEqual([
      { event: "ready", data: "{}" },
      { event: "signal", data: '{"kind":"inbox"}' },
    ]);
  });

  it("reads a frame split across chunks, even inside a line end", async () => {
    expect(await all(body("event: del", "ta\r", "\ndata: {\"token\":\"Bon", "jour\"}\r\n\r", "\n"))).toEqual([{ event: "delta", data: '{"token":"Bonjour"}' }]);
  });

  it("joins several data lines, skips comments, and names an unnamed event message", async () => {
    expect(await all(body(": heartbeat\n\ndata: a\ndata: b\nid: 7\n\n"))).toEqual([{ event: "message", data: "a\nb", id: "7" }]);
  });

  it("does not deliver a frame the body ends in the middle of", async () => {
    expect(await all(body("event: done\ndata: {}\n\nevent: delta\ndata: {\"token\":\"x\"}"))).toEqual([{ event: "done", data: "{}" }]);
  });

  it("a frame with no data is no event", async () => {
    expect(await all(body("event: ping\n\n"))).toEqual([]);
  });
});
