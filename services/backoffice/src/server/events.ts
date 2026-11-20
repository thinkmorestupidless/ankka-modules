// `/api/events` (contracts/routes.md): server-sent events for the signed-in operator:
// `permissions_changed` when what the operator may do here changes, `signed_out` when the session
// ends, and `job_done` when a job the operator started ends (FR-029). The page re-reads `/api/me` on
// the first, so an action appears or goes without signing in again (FR-008b), goes to the sign-in on
// the second, and tells the operator wherever they are on the third. For an operator who asked (their
// `onlineNotices` preference) it relays `player_online` from engagement's agent stream (FR-036; spec 014
// FR-024), and `presence` after that stream dropped and was opened again.
import type { Hono } from "hono";
import { streamSSE, type SSEStreamingApi } from "hono/streaming";
import type { Env } from "./main.ts";
import { inBrand, permissionsOf, type BrandReads } from "./roles.ts";
import type { Token, TokenCache } from "./session.ts";
import { ended, type JobState } from "./composite/jobs.ts";
import { match, upstreamPath, type Row } from "./routes.ts";
import { ownerAddress } from "./proxy.ts";

export interface EventsDeps {
  brand: string;
  tokens: TokenCache;
  reads: BrandReads;
  /** How often the operator's permissions are compared: at each, the token and the roles are read again. */
  everyMs: number;
  /** The operator's started jobs and their states now (`composite/jobs.ts`); none when not given. */
  jobs?: (accessToken: string, withinMs: number) => Promise<JobState[]>;
  /** engagement's agent stream, relayed for an operator who asked; nothing is relayed when not given. */
  online?: OnlineDeps;
  /** Ends every stream: the instance is stopping, and an open stream would hold its stop back. */
  shutdown?: AbortSignal;
}

/** What the relay of engagement's agent stream needs (`relayOnline`). */
export interface OnlineDeps {
  brand: string;
  servicesUrl: string;
  sharedProjects: Record<string, string>;
  table: readonly Row[];
  fetch: typeof fetch;
  /** The bound on the ticket and presence calls; the stream itself is bounded by `idleMs`. */
  timeoutMs: number;
  /** Whether the operator asked to be told (their `onlineNotices` preference); false when it cannot be read. */
  asked(accessToken: string): Promise<boolean>;
  /** The first wait before opening the stream again, doubled after each short connection up to the largest. */
  backoff?: { firstMs: number; mostMs: number };
  /** Nothing heard for this long ends the stream (engagement pings every 15 s). */
  idleMs?: number;
  /** The clock a connection's length is told by; the server's own when given. */
  now?: () => number;
}

/** A player who came online, as the page is told it. */
export interface PlayerOnline {
  playerId: string;
  emailPrefix: string;
  sessionId: string;
  occurredAt: string;
}

/**
 * `player_online`'s data as engagement writes it (Frames.playerOnline: snake_case), for the page; undefined
 * for anything else, and for a frame that names a brand other than this one. engagement's agent channel is
 * the brand's (id = slug) and names none; a frame that did would be another brand's player.
 */
export function onlineOf(data: string, brand: string): PlayerOnline | undefined {
  let d: unknown;
  try {
    d = JSON.parse(data);
  } catch {
    return undefined;
  }
  if (d === null || typeof d !== "object") return undefined;
  const o = d as Record<string, unknown>;
  if ("brand" in o && o.brand !== brand) return undefined;
  const text = (v: unknown) => (typeof v === "string" ? v.slice(0, 256) : "");
  const playerId = text(o.player_id);
  if (playerId === "") return undefined;
  return { playerId, emailPrefix: text(o.player_email_prefix), sessionId: text(o.session_id), occurredAt: text(o.occurred_at) };
}

/** The most a frame may hold before the stream is judged broken and opened again. */
const FrameBytes = 64 * 1024;

/**
 * The server-sent frames of `body`, each `{event, data}`, comments skipped; `onBytes` at each read. Ends with
 * the body, or when `signal` ends, the body then cancelled.
 */
export async function* framesOf(body: ReadableStream<Uint8Array>, signal: AbortSignal, onBytes: () => void = () => undefined): AsyncGenerator<{ event: string; data: string }> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const cancel = () => void reader.cancel().catch(() => undefined);
  if (signal.aborted) cancel();
  signal.addEventListener("abort", cancel, { once: true });
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      onBytes();
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, "\n");
      let end: number;
      while ((end = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        let event = "message";
        const data: string[] = [];
        for (const line of block.split("\n")) {
          if (line.startsWith(":")) continue;
          const colon = line.indexOf(":");
          const field = colon < 0 ? line : line.slice(0, colon);
          const text = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
          if (field === "event") event = text;
          else if (field === "data") data.push(text);
        }
        if (data.length > 0) yield { event, data: data.join("\n") };
      }
      if (buffer.length > FrameBytes) throw new Error("frame too large");
    }
  } finally {
    signal.removeEventListener("abort", cancel);
    await reader.cancel().catch(() => undefined);
  }
}

/** Waits `ms`, or less when `signal` ends first. */
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const done = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    signal.addEventListener("abort", done, { once: true });
  });
}

/** A connection that lasted this long was a good one: the next wait starts from the first again. */
const Lasting = 30_000;

/**
 * engagement's agent stream for one operator, until `signal` ends (their events stream ended): a ticket taken
 * with their token (`POST …/agent-stream/ticket`, `presence.read`), the stream opened with it, and each
 * `player_online` of this brand passed to `send`. engagement drops a connection whose queue overflows, with no
 * frame (spec 014 FR-024), so whenever the stream ends it is opened again with a new ticket, after a wait that
 * doubles while connections stay short; once it is open again `presence` is read and sent, since who came online
 * meanwhile was not relayed. One connection at a time and one frame in hand, so memory is bounded; a slow page
 * holds the reads back until engagement drops the connection.
 */
export async function relayOnline(
  deps: Omit<OnlineDeps, "asked">,
  accessToken: () => string,
  send: (event: string, data: string) => Promise<void>,
  signal: AbortSignal,
): Promise<void> {
  const ticketRow = match(deps.table, "POST", "engagement", "/agent-stream/ticket");
  if (ticketRow === undefined) return;
  const presenceRow = match(deps.table, "GET", "engagement", "/presence");
  const ticketUrl = `${ownerAddress(deps, "engagement", ticketRow.row.where)}${upstreamPath(ticketRow, deps.brand)}`;
  const streamUrl = ticketUrl.replace(/\/ticket$/, "");
  const presenceUrl = presenceRow ? `${ownerAddress(deps, "engagement", presenceRow.row.where)}${upstreamPath(presenceRow, deps.brand)}` : undefined;
  const { firstMs, mostMs } = deps.backoff ?? { firstMs: 1_000, mostMs: 60_000 };
  const idleMs = deps.idleMs ?? 45_000;
  const bearer = () => ({ Authorization: `Bearer ${accessToken()}` });
  const now = deps.now ?? Date.now;

  const presence = async (within: AbortSignal) => {
    if (presenceUrl === undefined) return;
    const r = await deps.fetch(presenceUrl, { headers: { ...bearer(), Accept: "application/json" }, signal: AbortSignal.any([within, AbortSignal.timeout(deps.timeoutMs)]) });
    if (!r.ok) return void (await r.body?.cancel());
    const body = (await r.json()) as { online?: unknown; count?: unknown };
    const online = (Array.isArray(body.online) ? (body.online as unknown[]) : [])
      .slice(0, 1_000)
      .filter((x): x is { playerId: string; since?: unknown } => typeof (x as { playerId?: unknown } | null)?.playerId === "string")
      .map((x) => ({ playerId: x.playerId.slice(0, 256), since: typeof x.since === "string" ? x.since : null }));
    await send("presence", JSON.stringify({ online, count: typeof body.count === "number" ? body.count : online.length }));
  };

  /** One connection: "refused" when engagement will not issue this operator a ticket, "ended" otherwise. */
  const once = async (again: boolean): Promise<"refused" | "ended"> => {
    const connection = new AbortController();
    const stop = () => connection.abort();
    signal.addEventListener("abort", stop, { once: true });
    let idle: ReturnType<typeof setTimeout> | undefined;
    const heard = () => {
      clearTimeout(idle);
      idle = setTimeout(stop, idleMs);
    };
    try {
      const t = await deps.fetch(ticketUrl, {
        method: "POST",
        headers: { ...bearer(), Accept: "application/json" },
        signal: AbortSignal.any([connection.signal, AbortSignal.timeout(deps.timeoutMs)]),
      });
      if (!t.ok) {
        await t.body?.cancel();
        return t.status === 401 || t.status === 403 ? "refused" : "ended";
      }
      const ticket = ((await t.json()) as { ticket?: unknown }).ticket;
      if (typeof ticket !== "string" || ticket === "") return "ended";
      heard();
      const s = await deps.fetch(`${streamUrl}?ticket=${encodeURIComponent(ticket)}`, { headers: { Accept: "text/event-stream" }, signal: connection.signal });
      if (!s.ok || s.body === null) {
        await s.body?.cancel();
        return "ended";
      }
      if (again) await presence(connection.signal);
      for await (const f of framesOf(s.body, connection.signal, heard)) {
        if (f.event !== "player_online") continue;
        const o = onlineOf(f.data, deps.brand);
        if (o) await send("player_online", JSON.stringify(o));
      }
    } catch {
      // unreachable, timed out, silent too long, or the operator went: opened again unless they went
    } finally {
      clearTimeout(idle);
      signal.removeEventListener("abort", stop);
      connection.abort();
    }
    return "ended";
  };

  let wait = firstMs;
  let again = false;
  while (!signal.aborted) {
    const started = now();
    const outcome = await once(again);
    if (signal.aborted) break;
    again = true;
    if (outcome === "refused") wait = mostMs;
    else if (now() - started >= Lasting) wait = firstMs;
    await pause(wait, signal);
    wait = Math.min(wait * 2, mostMs);
  }
}


/** A job is looked at while it may still end: started within the last day. */
const Watched = 86_400_000;

/**
 * The jobs that ended since the last look: each once, when it was seen running before or was started
 * after the stream opened. `seen` is the stream's own memory of each reference's last state.
 */
export function newlyEnded(seen: Map<string, string>, now: JobState[], first: boolean): JobState[] {
  const out: JobState[] = [];
  for (const j of now) {
    // A state reporting could not give is no change: the job is looked at again next time.
    if (j.state === "unknown") continue;
    const before = seen.get(j.reference);
    if (ended(j) && !first && (before === undefined || before === "generating")) out.push(j);
    seen.set(j.reference, j.state);
  }
  return out;
}

/**
 * What the operator may do in this brand, as one comparable value. `undefined` while the brand's roles
 * cannot be read: an unreachable `players` is not a change, so nothing is sent for it.
 */
export async function standing(deps: Pick<EventsDeps, "brand" | "reads">, token: Token): Promise<string | undefined> {
  if (!inBrand(token.roles, deps.brand)) return "not-in-brand";
  const bags = await deps.reads.bags.get();
  if (bags === undefined) return undefined;
  return permissionsOf(deps.brand, token.roles, bags).join(" ");
}

/**
 * The most event streams one sign-in holds open at once: one a tab, and each polls its sources and may
 * hold its own connection to engagement. A browser's tabs share the sign-in, so this bounds one
 * operator's tabs; a stream past it is refused 429 and the page's EventSource does not retry it.
 */
export const StreamsPerSession = 8;

export function eventRoutes(app: Hono<Env>, deps: EventsDeps): void {
  const open = new Map<string, number>();
  app.get("/api/events", (c) => {
    c.set("logPath", "/api/events");
    const session = c.get("session");
    const first = c.get("token");
    const held = open.get(session) ?? 0;
    if (held >= StreamsPerSession) return c.json({ error: "too_many_streams", message: `at most ${StreamsPerSession} event streams at once` }, 429);
    open.set(session, held + 1);
    const release = () => {
      const n = (open.get(session) ?? 1) - 1;
      if (n <= 0) open.delete(session);
      else open.set(session, n);
    };
    c.header("X-Accel-Buffering", "no");
    return streamSSE(c, async (stream) => {
      try {
        await follow(stream);
      } finally {
        release();
      }
    });

    async function follow(stream: SSEStreamingApi): Promise<void> {
      let last = await standing(deps, first);
      // engagement's agent stream, for an operator who asked: open while they are of this brand and hold
      // presence.read (or while the brand's roles cannot be read: engagement decides then); ended with this stream.
      const online = deps.online;
      const asked = online === undefined ? false : await online.asked(first.accessToken).catch(() => false);
      let current = first;
      let relay: AbortController | undefined;
      const relaying = (token: Token, now: string | undefined) => {
        if (online === undefined || !asked) return;
        const wanted = inBrand(token.roles, deps.brand) && (now === undefined || now.split(" ").includes("presence.read"));
        if (wanted && relay === undefined) {
          relay = new AbortController();
          const send = async (event: string, data: string) => {
            if (stream.aborted) throw new Error("the operator went");
            await stream.writeSSE({ event, data });
          };
          void relayOnline(online, () => current.accessToken, send, relay.signal).catch(() => undefined);
        } else if (!wanted && relay !== undefined) {
          relay.abort();
          relay = undefined;
        }
      };
      // the operator went, or the instance is stopping: the stream ends, the page's EventSource opens another
      const ended = new AbortController();
      const end = () => ended.abort();
      stream.onAbort(end);
      if (deps.shutdown?.aborted) end();
      deps.shutdown?.addEventListener("abort", end, { once: true });
      ended.signal.addEventListener("abort", () => relay?.abort(), { once: true });
      const seen = new Map<string, string>();
      let looked = false;
      const jobsDone = async (token: Token): Promise<JobState[]> => {
        if (deps.jobs === undefined) return [];
        try {
          const done = newlyEnded(seen, await deps.jobs(token.accessToken, Watched), !looked);
          looked = true;
          return done;
        } catch {
          return [];
        }
      };
      // The first write opens the stream at every hop, so a buffering proxy shows itself at once.
      await stream.write(": open\n\n");
      await jobsDone(first);
      relaying(first, last);
      try {
        while (!ended.signal.aborted) {
          await pause(deps.everyMs, ended.signal);
          if (ended.signal.aborted) break;
          let token: Token | undefined;
          try {
            token = await deps.tokens.token(session);
          } catch {
            await stream.write(": state unavailable\n\n");
            continue;
          }
          if (token === undefined) {
            await stream.writeSSE({ event: "signed_out", data: "{}" });
            break;
          }
          current = token;
          const now = await standing(deps, token);
          relaying(token, now);
          const done = await jobsDone(token);
          for (const j of done) await stream.writeSSE({ event: "job_done", data: JSON.stringify({ reference: j.reference, kind: j.kind, state: j.state }) });
          if (now !== undefined && now !== last) {
            last = now;
            await stream.writeSSE({ event: "permissions_changed", data: "{}" });
          } else if (done.length === 0) {
            // A comment each quiet interval keeps every hop from closing an idle stream.
            await stream.write(": \n\n");
          }
        }
      } finally {
        deps.shutdown?.removeEventListener("abort", end);
        relay?.abort();
      }
    }
  });
}
