// `/api/events` (contracts/routes.md): server-sent events for the signed-in operator:
// `permissions_changed` when what the operator may do here changes, `signed_out` when the session
// ends, and `job_done` when a job the operator started ends (FR-029). The page re-reads `/api/me` on
// the first, so an action appears or goes without signing in again (FR-008b), goes to the sign-in on
// the second, and tells the operator wherever they are on the third. Notices are a later task's.
import type { Hono } from "hono";
import { streamSSE } from "hono/streaming";
import type { Env } from "./main.ts";
import { inBrand, permissionsOf, type BrandReads } from "./roles.ts";
import type { Token, TokenCache } from "./session.ts";
import { ended, type JobState } from "./composite/jobs.ts";

export interface EventsDeps {
  brand: string;
  tokens: TokenCache;
  reads: BrandReads;
  /** How often the operator's permissions are compared: at each, the token and the roles are read again. */
  everyMs: number;
  /** The operator's started jobs and their states now (`composite/jobs.ts`); none when not given. */
  jobs?: (accessToken: string, withinMs: number) => Promise<JobState[]>;
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
    });
  });
}
