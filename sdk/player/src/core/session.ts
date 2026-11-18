import { behaviour, machine, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { Refusal } from "../refusal.ts";
import type { SignedInPlayer, SignedOutReason, Transport } from "../transport.ts";
import { coreClient, type Registration } from "./client.ts";

/** The session behaviour's steps (data-model.md, *Session*). */
export type SessionStep =
  | { kind: "signed_out"; allowed: readonly ["signIn", "register", "verifyEmail", "resendVerification", "requestPasswordReset"]; reason?: SignedOutReason; verified?: true; resent?: true; resetRequested?: true }
  | { kind: "signing_in"; allowed: readonly [] }
  | { kind: "registering"; allowed: readonly [] }
  | { kind: "awaiting_verification"; allowed: readonly ["resendVerification", "verifyEmail", "signIn"]; email: string; resent?: true }
  | { kind: "verifying"; allowed: readonly [] }
  | { kind: "signed_in"; allowed: readonly ["signOut"]; player: SignedInPlayer }
  | { kind: "idle_warning"; allowed: readonly ["stay", "signOut"]; player: SignedInPlayer; signsOutAt: number }
  | { kind: "signing_out"; allowed: readonly [] }
  | { kind: "refused"; allowed: readonly ["back"]; refusal: Refusal; during: "signing_in" | "registering" | "verifying" | "resending" };

export interface SessionActions {
  signIn(credentials: { email: string; password: string; deviceFingerprint?: string }): ReturnType<Act>;
  register(registration: Registration): ReturnType<Act>;
  verifyEmail(token: string): ReturnType<Act>;
  resendVerification(email?: string): ReturnType<Act>;
  requestPasswordReset(email: string): ReturnType<Act>;
  signOut(): ReturnType<Act>;
  stay(): ReturnType<Act>;
  back(): ReturnType<Act>;
}
type Act = () => { ok: true } | { ok: false; notAllowedAt: SessionStep["kind"] };

/** A session behaviour, and the inputs only the page and play give it. */
export interface SessionBehaviour extends Behaviour<SessionStep, SessionActions> {
  /** A key press, a click or a scroll: the idle count starts again. */
  activity(): void;
  /** Play's report of a recent bet: the sign-in is refreshed and the idle count starts again. */
  notIdle(): void;
  /** The player `GET /api/session` named, for a session made before it answered. */
  hydrate(player: SignedInPlayer | null): void;
}

export interface SessionOptions {
  readonly transport: Transport;
  readonly clock: Clock;
  readonly player: SignedInPlayer | null;
  readonly idleMinutes?: number;
  readonly idleWarningSeconds?: number;
  /** Told whenever the player signs in or out, so the site can follow. */
  readonly onPlayer?: (player: SignedInPlayer | null) => void;
}

const signedOutAllowed = ["signIn", "register", "verifyEmail", "resendVerification", "requestPasswordReset"] as const;
const signedOut = (extra: Partial<Extract<SessionStep, { kind: "signed_out" }>> = {}): SessionStep => ({ kind: "signed_out", allowed: signedOutAllowed, ...extra });
const signedIn = (player: SignedInPlayer): SessionStep => ({ kind: "signed_in", allowed: ["signOut"], player });
const awaiting = (email: string, resent?: true): SessionStep => ({ kind: "awaiting_verification", allowed: ["resendVerification", "verifyEmail", "signIn"], email, ...(resent ? { resent } : {}) });
const busy = (kind: "signing_in" | "registering" | "verifying" | "signing_out"): SessionStep => ({ kind, allowed: [] });

export function sessionBehaviour(options: SessionOptions): SessionBehaviour {
  const { transport } = options;
  const idleMs = (options.idleMinutes ?? 30) * 60_000;
  const warningMs = (options.idleWarningSeconds ?? 30) * 1000;
  const core = coreClient(transport);
  const m = machine<SessionStep>(options.player ? signedIn(options.player) : signedOut(), options.clock);
  let before: SessionStep = m.step();
  let idleTimer: (() => void) | undefined;

  let held: SignedInPlayer | null = options.player;
  const tell = (step: SessionStep) => {
    m.set(step);
    if (step.kind === "signed_in") startIdle();
    else if (step.kind !== "idle_warning") stopIdle();
    const now = step.kind === "signed_in" || step.kind === "idle_warning" ? step.player : step.kind === "signing_out" ? held : null;
    if ((now === null) !== (held === null)) {
      held = now;
      options.onPlayer?.(now);
    }
  };
  const stopIdle = () => {
    idleTimer?.();
    idleTimer = undefined;
  };
  function startIdle() {
    stopIdle();
    idleTimer = m.after(idleMs - warningMs, () => {
      const at = m.step();
      if (at.kind !== "signed_in") return;
      const warning: SessionStep = { kind: "idle_warning", allowed: ["stay", "signOut"], player: at.player, signsOutAt: m.clock.now() + warningMs };
      tell(warning);
      idleTimer = m.after(warningMs, () => {
        if (!m.still(warning)) return;
        void endSession("idle");
      });
    });
  }
  const refused = (r: Refusal, during: Extract<SessionStep, { kind: "refused" }>["during"]) => tell({ kind: "refused", allowed: ["back"], refusal: r, during });
  async function endSession(reason: SignedOutReason) {
    tell(busy("signing_out"));
    await transport.session.signOut();
    tell(signedOut({ reason }));
  }

  m.onStop(
    transport.onSignedOut((reason) => {
      const at = m.step();
      if (at.kind === "signed_in" || at.kind === "idle_warning") tell(signedOut({ reason }));
    }),
  );
  if (options.player) startIdle();

  const actions: SessionActions = {
    signIn: m.action("signIn", (credentials: { email: string; password: string; deviceFingerprint?: string }) => {
      before = m.step();
      const step = busy("signing_in");
      tell(step);
      void transport.session.signIn(credentials).then((r) => {
        if (!m.still(step)) return;
        if (r.ok) tell(signedIn(r.player));
        else refused(r.refusal, "signing_in");
      });
    }),
    register: m.action("register", (registration: Registration) => {
      before = m.step();
      const step = busy("registering");
      tell(step);
      void core.register(registration).then((r) => {
        if (!m.still(step)) return;
        if (r.ok) tell(awaiting(registration.email));
        else refused(r.refusal, "registering");
      });
    }),
    verifyEmail: m.action("verifyEmail", (token: string) => {
      before = m.step();
      const step = busy("verifying");
      tell(step);
      void core.verifyEmail(token).then((r) => {
        if (!m.still(step)) return;
        if (r.ok) tell(signedOut({ verified: true }));
        else refused(r.refusal, "verifying");
      });
    }),
    resendVerification: m.action("resendVerification", (email?: string) => {
      const at = m.step();
      before = at;
      const to = email ?? (at.kind === "awaiting_verification" ? at.email : "");
      void core.resendVerification(to).then((r) => {
        if (!m.still(at)) return;
        if (!r.ok) return refused(r.refusal, "resending");
        tell(at.kind === "awaiting_verification" ? awaiting(at.email, true) : signedOut({ resent: true }));
      });
    }),
    requestPasswordReset: m.action("requestPasswordReset", (email: string) => {
      const at = m.step();
      // Whatever the brand answered: a reset request never says whether the address is known.
      void core.requestPasswordReset(email).then(() => {
        if (m.still(at)) tell(signedOut({ resetRequested: true }));
      });
    }),
    signOut: m.action("signOut", () => void endSession("signed_out")),
    stay: m.action("stay", () => {
      const at = m.step();
      if (at.kind !== "idle_warning") return;
      stopIdle();
      void transport.session.refresh().then((r) => {
        if (!m.still(at)) return;
        if (r.ok || r.refusal.code !== "refresh_expired") tell(signedIn(at.player));
        else tell(signedOut({ reason: "refresh_expired" }));
      });
    }),
    back: m.action("back", () => tell(before.kind === "awaiting_verification" ? before : signedOut())),
  };

  return {
    ...behaviour(m, actions),
    activity() {
      if (m.step().kind === "signed_in") startIdle();
    },
    hydrate(player) {
      const at = m.step();
      if (player && at.kind === "signed_out" && !at.reason) tell(signedIn(player));
    },
    notIdle() {
      const at = m.step();
      if (at.kind !== "signed_in") return;
      startIdle();
      void transport.session.refresh().then((r) => {
        if (!r.ok && r.refusal.code === "refresh_expired" && m.still(at)) tell(signedOut({ reason: "refresh_expired" }));
      });
    },
  };
}
