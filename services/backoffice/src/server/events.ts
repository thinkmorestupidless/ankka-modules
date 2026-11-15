// `/api/events` (contracts/routes.md): server-sent events for the signed-in operator. This task sends
// two: `permissions_changed` when what the operator may do here changes, and `signed_out` when the
// session ends. The page re-reads `/api/me` on the first, so an action appears or goes without signing
// in again (FR-008b), and goes to the sign-in on the second. Notices and jobs are later tasks' events.
import type { Hono } from "hono";
import { streamSSE } from "hono/streaming";
import type { Env } from "./main.ts";
import { inBrand, permissionsOf, type BrandReads } from "./roles.ts";
import type { Token, TokenCache } from "./session.ts";

export interface EventsDeps {
  brand: string;
  tokens: TokenCache;
  reads: BrandReads;
  /** How often the operator's permissions are compared: at each, the token and the roles are read again. */
  everyMs: number;
}

/**
 * What the operator may do in this brand, as one comparable value. `undefined` while the brand's roles
 * cannot be read: an unreachable `players` is not a change, so nothing is sent for it.
 */
export async function standing(deps: Pick<EventsDeps, "brand" | "reads">, token: Token): Promise<string | undefined> {
  if (!inBrand(token.roles, deps.brand)) return "not-in-brand";
  const bags = await deps.reads.bags.get();
  if (bags === undefined) return undefined;
  return permissionsOf(token.roles, bags).join(" ");
}

export function eventRoutes(app: Hono<Env>, deps: EventsDeps): void {
  app.get("/api/events", (c) => {
    c.set("logPath", "/api/events");
    const session = c.get("session");
    const first = c.get("token");
    c.header("X-Accel-Buffering", "no");
    return streamSSE(c, async (stream) => {
      let last = await standing(deps, first);
      // The first write opens the stream at every hop, so a buffering proxy shows itself at once.
      await stream.write(": open\n\n");
      while (!stream.aborted) {
        await stream.sleep(deps.everyMs);
        if (stream.aborted) break;
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
        const now = await standing(deps, token);
        if (now !== undefined && now !== last) {
          last = now;
          await stream.writeSSE({ event: "permissions_changed", data: "{}" });
        } else {
          // A comment each quiet interval keeps every hop from closing an idle stream.
          await stream.write(": \n\n");
        }
      }
    });
  });
}
