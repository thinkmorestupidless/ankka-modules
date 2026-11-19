import { behaviour, machine, type Answer, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { CoreClient } from "../core/client.ts";
import { hasPlayableFunds, type Funds } from "../core/funds.ts";
import { refusal as makeRefusal, type Refusal } from "../refusal.ts";
import type { SignedInPlayer } from "../transport.ts";
import type { CasinoClient, Game } from "./client.ts";

/** The play behaviour's steps for one game (data-model.md, *Play*). */
export type PlayStep =
  | { kind: "idle"; allowed: readonly ["launch"]; game: string }
  | { kind: "needs_registration" | "needs_sign_in" | "needs_deposit"; allowed: readonly ["launch", "dismiss"]; game: string }
  | { kind: "launching"; allowed: readonly []; game: string; funMode: boolean }
  | { kind: "playing"; allowed: readonly ["close"]; game: string; address: string; gameSession: string; hidden: boolean }
  | { kind: "reality_check"; allowed: readonly ["keepOn", "stop"]; game: string; gameSession: string; played: number }
  | { kind: "refused"; allowed: readonly ["dismiss"]; refusal: Refusal; game: string }
  | { kind: "ended"; allowed: readonly []; game: string };

type A = Answer<PlayStep["kind"]>;

export interface PlayActions {
  launch(options?: { funMode?: boolean }): A;
  dismiss(): A;
  close(): A;
  keepOn(): A;
  stop(): A;
}

/** A play behaviour, and what `site.games` tells it. */
export interface PlayBehaviour extends Behaviour<PlayStep, PlayActions> {
  readonly game: string;
  /** Shown or hidden by `site.games`; the bet check runs only while shown. */
  hidden(isHidden: boolean): void;
  /** The reality check fell due on this game, with the seconds played. */
  realityCheck(played: number): void;
}

export interface PlayOptions {
  readonly game: string;
  readonly core: CoreClient;
  readonly casino: CasinoClient;
  readonly clock: Clock;
  readonly player: () => SignedInPlayer | null;
  readonly funds: () => Funds;
  /** No playable funds: the site opens the deposit (FR-028). */
  readonly needsDeposit: () => void;
  /** A bet within six minutes: the sign-in is refreshed and the player is not idle. */
  readonly recentBet: () => void;
  /** The reality check was kept on: its count starts again. */
  readonly keptOn: () => void;
}

export const BetCheckMs = 5 * 60_000;
export const RecentBetMs = 6 * 60_000;

/**
 * Whether the catalogue says the game is not offered in the country (FR-031,
 * `GameManagerContext.openGame`): restricted or prohibited there. No country, or no game read,
 * blocks nothing.
 */
export function blockedIn(game: Game | null, country: string | null | undefined): boolean {
  const c = (country ?? "").trim().toUpperCase();
  if (!game || !c) return false;
  return [...(game.restrictedTerritories ?? []), ...(game.prohibitedTerritories ?? [])].some((t) => t.toUpperCase() === c);
}

export function playBehaviour(options: PlayOptions): PlayBehaviour {
  const { game, core, casino } = options;
  const m = machine<PlayStep>({ kind: "idle", allowed: ["launch"], game }, options.clock);
  let isHidden = false;
  let checking: (() => void) | undefined;

  /** The game session while it is open at the brand: what an end is sent for. */
  const liveSession = (): string | null => {
    const at = m.step();
    return at.kind === "playing" || at.kind === "reality_check" ? at.gameSession : null;
  };
  const end = (sessionId: string) => void core.endGameSession(sessionId);
  const ended = () => {
    checking?.();
    checking = undefined;
    m.set({ kind: "ended", allowed: [], game });
  };
  const needs = (kind: "needs_registration" | "needs_sign_in" | "needs_deposit"): PlayStep => ({ kind, allowed: ["launch", "dismiss"], game });

  function betCheck() {
    checking?.();
    checking = isHidden ? undefined : m.every(BetCheckMs, () => {
      const sessionId = liveSession();
      if (!sessionId) return;
      void core.gameSession(sessionId).then((r) => {
        if (!r.ok || liveSession() !== sessionId) return;
        // A session the brand ended: say so, and ask no end of it (FR-060).
        if (r.value.status !== "active") return ended();
        const at = r.value.lastBetAt ? Date.parse(r.value.lastBetAt) : Number.NaN;
        if (!Number.isNaN(at) && options.clock.now() - at <= RecentBetMs) options.recentBet();
      });
    });
  }

  function launch(funMode: boolean) {
    const player = options.player();
    if (!player) return m.set(needs(funMode ? "needs_sign_in" : "needs_registration"));
    if (!funMode && !hasPlayableFunds(options.funds(), game)) {
      m.set(needs("needs_deposit"));
      options.needsDeposit();
      return;
    }
    const step: PlayStep = { kind: "launching", allowed: [], game, funMode };
    m.set(step);
    void Promise.all([casino.launch(game, { currency: player.currency, funMode }), casino.game(game)]).then(([launched, read]) => {
      if (!launched.ok) {
        if (m.still(step)) m.set({ kind: "refused", allowed: ["dismiss"], refusal: launched.refusal, game });
        return;
      }
      if (!m.still(step)) return end(launched.value.gameSession);
      if (blockedIn(read.ok ? read.value : null, player.country)) {
        // The page is never given the address, and the session opened for it is ended.
        end(launched.value.gameSession);
        return m.set({ kind: "refused", allowed: ["dismiss"], refusal: makeRefusal("blocked_in_territory", 403), game });
      }
      m.set({ kind: "playing", allowed: ["close"], game, address: launched.value.address, gameSession: launched.value.gameSession, hidden: isHidden });
      betCheck();
    });
  }

  // The page leaving, the game closed by `site.games` or sign-out: end the session, so it outlives the page.
  m.onStop(() => {
    const sessionId = liveSession();
    if (sessionId) end(sessionId);
  });

  const actions: PlayActions = {
    launch: m.action("launch", (o: { funMode?: boolean } = {}) => launch(o.funMode === true)),
    dismiss: m.action("dismiss", () => m.set({ kind: "idle", allowed: ["launch"], game })),
    close: m.action("close", () => {
      const sessionId = liveSession();
      if (sessionId) end(sessionId);
      ended();
    }),
    keepOn: m.action("keepOn", () => {
      const at = m.step();
      if (at.kind !== "reality_check") return;
      void core.acknowledgeRealityCheck(at.gameSession);
      m.set({ kind: "playing", allowed: ["close"], game, address: address!, gameSession: at.gameSession, hidden: isHidden });
      betCheck();
      options.keptOn();
    }),
    stop: m.action("stop", () => {
      const sessionId = liveSession();
      if (sessionId) end(sessionId);
      ended();
    }),
  };

  let address: string | undefined;
  m.subscribe((s) => {
    if (s.kind === "playing") address = s.address;
  });

  return {
    ...behaviour(m, actions),
    game,
    hidden(h) {
      if (isHidden === h) return;
      isHidden = h;
      const at = m.step();
      if (at.kind === "playing") {
        m.set({ ...at, hidden: h });
        betCheck();
      }
    },
    realityCheck(played) {
      const at = m.step();
      if (at.kind !== "playing") return;
      checking?.();
      checking = undefined;
      m.set({ kind: "reality_check", allowed: ["keepOn", "stop"], game, gameSession: at.gameSession, played });
    },
  };
}
