import { behaviour, machine, type Answer, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { Refusal } from "../refusal.ts";
import type { Reply, SupportClient } from "./client.ts";

/** One turn of a conversation: the player's text, or the agent's words as sent. */
export interface Turn {
  readonly from: "player" | "agent";
  readonly text: string;
  readonly interrupted?: boolean;
  readonly result?: { readonly action: string; readonly success: boolean; readonly detail?: string };
}

/** An approval request: the action, and the agent's statement of its effect (none when learnt from a refusal). */
export interface ApprovalRequest {
  readonly action: string;
  readonly effect?: string;
}

/** The chat behaviour's steps (data-model.md, *Chat*; FR-049). */
export type ChatStep =
  | { kind: "ready"; allowed: readonly ["send"]; turns: readonly Turn[]; sessionId?: string }
  | { kind: "answering"; allowed: readonly []; turns: readonly Turn[] }
  | { kind: "awaiting_approval"; allowed: readonly ["approve", "cancel"]; turns: readonly Turn[]; request: ApprovalRequest }
  | { kind: "deciding"; allowed: readonly []; turns: readonly Turn[]; decision: "approve" | "reject" }
  | { kind: "refused"; allowed: readonly ["dismiss"]; turns: readonly Turn[]; refusal: Refusal }
  | { kind: "ended"; allowed: readonly ["send"]; turns: readonly Turn[]; words: string }
  | { kind: "unavailable"; allowed: readonly []; words: string };

type A = Answer<ChatStep["kind"]>;

export interface ChatActions {
  send(text: string): A;
  approve(): A;
  cancel(feedback?: string): A;
  dismiss(): A;
}

/** Where the conversation's session id is kept for the tab (`sessionStorage` in a page). */
export interface ChatStore {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

export interface ChatBehaviour extends Behaviour<ChatStep, ChatActions> {
  /** The signed-in player, or none: the conversation is the player's, and nothing of it outlives a sign-out (FR-055). */
  player(playerId: string | null): void;
}

export const storeKey = (playerId: string) => `eo_chat_${playerId}`;

/** The tab's `sessionStorage`, or nothing where it cannot be used. */
export const tabStore: ChatStore = {
  get(key) {
    try {
      return globalThis.sessionStorage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      globalThis.sessionStorage?.setItem(key, value);
    } catch {
      // kept only for this page
    }
  },
  remove(key) {
    try {
      globalThis.sessionStorage?.removeItem(key);
    } catch {
      // nothing kept
    }
  },
};

const mint = () => globalThis.crypto.randomUUID();

/**
 * The chat with the support agent (contracts/behaviours.md, *Chat*), extracted from the
 * original's `MotokoChat.tsx`. A turn is sent only from `ready` or `ended`, a decision only from
 * `awaiting_approval`, each once: the behaviour never sends anything again by itself (FR-051).
 * The agent's words reach the page as sent; the behaviour adds none (FR-054).
 */
export function chatBehaviour(options: { support: SupportClient; clock: Clock; store?: ChatStore }): ChatBehaviour {
  const store = options.store ?? tabStore;
  const m = machine<ChatStep>({ kind: "ready", allowed: ["send"], turns: [] }, options.clock);
  let playerId: string | null = null;
  let sessionId: string | undefined;
  let turns: Turn[] = [];
  let reading: Reply | undefined;
  let generation = 0;

  const ready = (): ChatStep => ({ kind: "ready", allowed: ["send"], turns, ...(sessionId ? { sessionId } : {}) });
  const keep = (id: string | undefined) => {
    sessionId = id;
    if (!playerId) return;
    if (id) store.set(storeKey(playerId), id);
    else store.remove(storeKey(playerId));
  };
  /** The agent's turn being answered is the last; each change gives the page a new list. */
  const agent = (change: (t: Turn) => Turn) => {
    const last = turns.at(-1);
    if (last?.from !== "agent") return;
    turns = [...turns.slice(0, -1), change(last)];
  };
  const dropEmptyAgentTurn = () => {
    const last = turns.at(-1);
    if (last?.from === "agent" && last.text === "" && !last.result) turns = turns.slice(0, -1);
  };

  const refused = (r: Refusal) => {
    dropEmptyAgentTurn();
    switch (r.code) {
      case "agent_unavailable":
        return m.set({ kind: "unavailable", allowed: [], words: r.words ?? "" });
      case "conversation_ended":
        return m.set({ kind: "ended", allowed: ["send"], turns, words: r.words ?? "" });
      case "Session already active": {
        const f = r.fields as { action?: string };
        return m.set({ kind: "awaiting_approval", allowed: ["approve", "cancel"], turns, request: { action: f.action ?? "" } });
      }
      case "already_decided":
        return m.set(ready());
      case "session_not_yours":
        keep(undefined);
        return m.set({ kind: "refused", allowed: ["dismiss"], turns, refusal: r });
      default:
        return m.set({ kind: "refused", allowed: ["dismiss"], turns, refusal: r });
    }
  };

  async function follow(open: Promise<{ ok: true; value: Reply } | { ok: false; refusal: Refusal }>, mine: number) {
    const r = await open;
    if (mine !== generation) {
      if (r.ok) r.value.close();
      return;
    }
    if (!r.ok) return refused(r.refusal);
    reading = r.value;
    let request: ApprovalRequest | undefined;
    let answered = false;
    try {
      for await (const part of r.value.events) {
        if (mine !== generation) return;
        if (part.kind === "delta") {
          agent((t) => ({ ...t, text: t.text + part.text }));
          m.set({ kind: "answering", allowed: [], turns });
        } else if (part.kind === "confirm") request = { action: part.action, effect: part.effect };
        else if (part.kind === "result") {
          agent((t) => ({ ...t, result: { action: part.action, success: part.success, ...(part.detail !== undefined ? { detail: part.detail } : {}) } }));
          m.set({ kind: "answering", allowed: [], turns });
        } else if (part.kind === "error") {
          // a guardrail's refusal, or the agent's unavailable words: the reply, as sent
          agent((t) => ({ ...t, text: part.words }));
          answered = true;
          break;
        } else if (part.kind === "done") {
          answered = true;
          break;
        }
      }
    } catch {
      // a reply that broke is one that stopped
    }
    if (mine !== generation) return;
    reading?.close();
    reading = undefined;
    if (!answered) agent((t) => ({ ...t, interrupted: true }));
    dropEmptyAgentTurn();
    if (answered && request) return m.set({ kind: "awaiting_approval", allowed: ["approve", "cancel"], turns, request });
    m.set(ready());
  }

  const decide = (approve: boolean, feedback?: string) => {
    const at = m.step();
    if (at.kind !== "awaiting_approval" || !sessionId) return;
    turns = [...turns, { from: "agent", text: "" }];
    m.set({ kind: "deciding", allowed: [], turns, decision: approve ? "approve" : "reject" });
    void follow(options.support.decide(sessionId, approve, feedback), generation);
  };

  const actions: ChatActions = {
    send: m.action("send", (given: string) => {
      // as the original: the text trimmed, and an empty turn not sent
      const text = given.trim();
      if (!text) return;
      // the first turn, and the first after a conversation ended, start a conversation
      if (!sessionId || m.step().kind === "ended") keep(mint());
      turns = [...turns, { from: "player", text }, { from: "agent", text: "" }];
      m.set({ kind: "answering", allowed: [], turns });
      void follow(options.support.turn(sessionId!, text), generation);
    }),
    approve: m.action("approve", () => decide(true)),
    cancel: m.action("cancel", (feedback?: string) => decide(false, feedback)),
    dismiss: m.action("dismiss", () => m.set(ready())),
  };

  const reset = () => {
    generation++;
    reading?.close();
    reading = undefined;
    turns = [];
    sessionId = undefined;
    m.set(ready());
  };
  m.onStop(() => {
    generation++;
    reading?.close();
  });

  return {
    ...behaviour(m, actions),
    player(id) {
      if (id === playerId) return;
      if (playerId && !id) store.remove(storeKey(playerId));
      playerId = id;
      reset();
      if (id) {
        sessionId = store.get(storeKey(id)) ?? undefined;
        m.set(ready());
      }
    },
  };
}
