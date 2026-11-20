// engagement's agent stream as its double keeps it (spec 014 FR-024; AgentStream.scala, Frames.scala):
// `POST …/agent-stream/ticket` with a bearer issues a single-use ticket; `GET …/agent-stream?ticket=`
// opens the server-sent stream once with it (`403 invalid_ticket` otherwise); a player brought online
// from the tests (`/__double/online`) is written to every open stream as `player_online`, snake_case,
// as engagement writes it; `GET …/presence` lists the players brought online. Each brand prefix is its
// own: a ticket is the brand's it was issued under, and a player is written only to that brand's streams.
import type http from "node:http";
import { randomBytes } from "node:crypto";
import type { Answer } from "./owner.ts";

const Ticket = /^\/v1\/casino\/backoffice\/([^/]+)\/agent-stream\/ticket$/;
const Stream = /^\/v1\/casino\/backoffice\/([^/]+)\/agent-stream$/;
const Presence = /^\/v1\/casino\/backoffice\/([^/]+)\/presence$/;

export interface ComingOnline {
  brand?: string;
  playerId: string;
  emailPrefix?: string;
  sessionId?: string;
}

interface Open {
  brand: string;
  operator: string;
  res: http.ServerResponse;
}

export class AgentStreams {
  private tickets = new Map<string, { brand: string; operator: string; used: boolean }>();
  private open = new Set<Open>();
  private online = new Map<string, { brand: string; playerId: string; since: string }>();

  reset(): void {
    this.tickets.clear();
    this.online.clear();
    for (const o of this.open) o.res.end();
    this.open.clear();
  }

  /** The operators whose streams are open now, each once per stream. */
  streams(brand?: string): string[] {
    return [...this.open].filter((o) => !brand || o.brand === brand).map((o) => o.operator);
  }

  /** A player comes online: written to every open stream of their brand; answers how many. */
  comeOnline(p: ComingOnline, defaultBrand: string): number {
    const brand = p.brand ?? defaultBrand;
    const occurredAt = new Date().toISOString();
    this.online.set(`${brand}/${p.playerId}`, { brand, playerId: p.playerId, since: occurredAt });
    const data = JSON.stringify({
      player_id: p.playerId,
      player_email_prefix: p.emailPrefix ?? "",
      session_id: p.sessionId ?? randomBytes(8).toString("hex"),
      occurred_at: occurredAt,
    });
    let n = 0;
    for (const o of this.open)
      if (o.brand === brand) {
        o.res.write(`event: player_online\ndata: ${data}\n\n`);
        n++;
      }
    return n;
  }

  /**
   * Handles an agent-stream or presence call: `true` when it answered (the stream left open), an answer
   * to send, or `undefined` when the path is not one of these, so the fixtures answer.
   */
  handle(method: string, path: string, query: URLSearchParams, subject: string | undefined, res: http.ServerResponse): Answer | true | undefined {
    const ticket = Ticket.exec(path);
    if (ticket && method === "POST") {
      if (!subject) return { status: 401, body: { error: "unauthenticated" } };
      const id = randomBytes(32).toString("base64url");
      this.tickets.set(id, { brand: decodeURIComponent(ticket[1]!), operator: subject, used: false });
      return { body: { ticket: id, expires_in: 30 } };
    }
    const stream = Stream.exec(path);
    if (stream && method === "GET") {
      const brand = decodeURIComponent(stream[1]!);
      const t = this.tickets.get(query.get("ticket") ?? "");
      if (!t || t.used || t.brand !== brand) {
        if (t) t.used = true;
        return { status: 403, body: { error: "invalid_ticket" } };
      }
      t.used = true;
      const o: Open = { brand, operator: t.operator, res };
      res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
      res.write("event: ping\ndata: {}\n\n");
      this.open.add(o);
      res.on("close", () => this.open.delete(o));
      return true;
    }
    const presence = Presence.exec(path);
    if (presence && method === "GET") {
      if (!subject) return { status: 401, body: { error: "unauthenticated" } };
      const brand = decodeURIComponent(presence[1]!);
      const rows = [...this.online.values()].filter((r) => r.brand === brand).map((r) => ({ playerId: r.playerId, since: r.since }));
      return { body: { online: rows, count: rows.length } };
    }
    return undefined;
  }
}
