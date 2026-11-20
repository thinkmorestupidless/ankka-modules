import { behaviour, machine, type Answer, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { CoreClient } from "../core/client.ts";
import type { EngagementClient, Push } from "./client.ts";
import { pushEvent } from "./events.ts";

/** The inbox behaviour's steps (data-model.md, *Inbox*). */
export type InboxStep =
  | { kind: "closed"; allowed: readonly [] }
  | { kind: "connecting"; allowed: readonly []; attempt: number; unread?: number }
  | { kind: "connected"; allowed: readonly ["refresh"]; unread: number };

export interface InboxActions {
  /** Reads the unread count again. */
  refresh(): Answer<InboxStep["kind"]>;
}

export interface InboxBehaviour extends Behaviour<InboxStep, InboxActions> {
  /** Signed in: open the push connection; signed out: close it, and open none again. */
  signedIn(isSignedIn: boolean): void;
  /** The page shown again: the count is read without waiting for a push. */
  visible(isVisible: boolean): void;
}

export const MaxWaitMs = 30_000;
/** The wait before the next connection: `min(1 s × 2^attempt, 30 s)`. */
export const waitFor = (attempt: number) => Math.min(1000 * 2 ** attempt, MaxWaitMs);

/**
 * The inbox (contracts/behaviours.md): a push connection held through the site server while the
 * player is signed in, and the unread count, read on `ready`, on each `signal`, when the page is
 * shown again and on `refresh`. A connection that ends or is refused is opened again after a wait
 * that doubles to 30 seconds; `ready` starts the wait again from 1 second.
 */
export function inboxBehaviour(options: { engagement: EngagementClient; core: CoreClient; clock: Clock }): InboxBehaviour {
  const m = machine<InboxStep>({ kind: "closed", allowed: [] }, options.clock);
  let open: Push | undefined;
  let generation = 0;
  let attempt = 0;
  let unread: number | undefined;
  let reopening: (() => void) | undefined;
  let shown = true;

  const connecting = (): InboxStep => ({ kind: "connecting", allowed: [], attempt, ...(unread !== undefined ? { unread } : {}) });
  const read = () => {
    const mine = generation;
    void options.core.unread().then((r) => {
      if (!r.ok || mine !== generation || m.step().kind === "closed") return;
      unread = r.value;
      const at = m.step();
      if (at.kind === "connected") m.set({ kind: "connected", allowed: ["refresh"], unread });
      else if (at.kind === "connecting") m.set(connecting());
    });
  };

  const reopen = (mine: number) => {
    if (mine !== generation) return;
    open = undefined;
    const wait = waitFor(attempt);
    attempt++;
    m.set(connecting());
    reopening = m.after(wait, () => connect(mine));
  };

  function connect(mine: number) {
    if (mine !== generation) return;
    void options.engagement.push().then(async (r) => {
      if (mine !== generation) {
        if (r.ok) r.value.close();
        return;
      }
      if (!r.ok) return reopen(mine);
      open = r.value;
      try {
        for await (const e of r.value.events) {
          if (mine !== generation) return;
          const event = pushEvent(e);
          if (event === "ready") {
            attempt = 0;
            m.set({ kind: "connected", allowed: ["refresh"], unread: unread ?? 0 });
            read();
          } else if (event === "signal") read();
          else if (event === "closing") break;
        }
      } catch {
        // a connection that broke is one that ended
      }
      if (mine === generation) {
        r.value.close();
        reopen(mine);
      }
    });
  }

  const close = () => {
    generation++;
    reopening?.();
    reopening = undefined;
    open?.close();
    open = undefined;
    attempt = 0;
    unread = undefined;
    m.set({ kind: "closed", allowed: [] });
  };
  m.onStop(close);

  const actions: InboxActions = { refresh: m.action("refresh", read) };

  return {
    ...behaviour(m, actions),
    signedIn(isSignedIn) {
      if (!isSignedIn) return close();
      if (m.step().kind !== "closed") return;
      const mine = ++generation;
      attempt = 0;
      m.set(connecting());
      connect(mine);
    },
    visible(isVisible) {
      const was = shown;
      shown = isVisible;
      if (isVisible && !was && m.step().kind !== "closed") read();
    },
  };
}
