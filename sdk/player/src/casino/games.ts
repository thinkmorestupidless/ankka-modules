import { behaviour, machine, type Answer, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { CoreClient } from "../core/client.ts";
import type { Funds } from "../core/funds.ts";
import type { SignedInPlayer } from "../transport.ts";
import type { CasinoClient } from "./client.ts";
import { playBehaviour, type PlayBehaviour } from "./play.ts";

/** `site.games`' one step (data-model.md, *Play*): the open games, in the order opened, and the one shown. */
export interface GamesStep {
  readonly kind: "games";
  readonly allowed: readonly ["pick", "show", "hideAll"];
  readonly open: readonly PlayBehaviour[];
  readonly shown: PlayBehaviour | undefined;
}

export interface GamesActions {
  pick(game: string, options?: { funMode?: boolean }): Answer<"games">;
  show(game: string): Answer<"games">;
  hideAll(): Answer<"games">;
}

export interface GamesBehaviour extends Behaviour<GamesStep, GamesActions> {
  /** The page's visibility: a hidden page shows no game, for the count as for the bet check. */
  visible(isVisible: boolean): void;
  /** Sign-out: every open game's session ended, and none left open (FR-059). */
  closeAll(): void;
}

export interface GamesOptions {
  readonly core: CoreClient;
  readonly casino: CasinoClient;
  readonly clock: Clock;
  readonly player: () => SignedInPlayer | null;
  readonly funds: () => Funds;
  readonly needsDeposit: () => void;
  readonly recentBet: () => void;
}

const live = new Set(["launching", "playing", "reality_check"]);
const needing = new Set(["needs_registration", "needs_sign_in", "needs_deposit"]);

/**
 * The open games (FR-059): one play behaviour each, one shown and the rest hidden, and the
 * reality check's one count for the player: the time any game has been shown since the last
 * check, paused while none is (FR-033), falling due on the game shown.
 */
export function gamesBehaviour(options: GamesOptions): GamesBehaviour {
  const { clock } = options;
  let open: PlayBehaviour[] = [];
  let shown: PlayBehaviour | undefined;
  let pageVisible = true;
  const m = machine<GamesStep>({ kind: "games", allowed: ["pick", "show", "hideAll"], open, shown }, clock);
  const tell = () => m.set({ kind: "games", allowed: ["pick", "show", "hideAll"], open: [...open], shown });

  // The reality check's count.
  let interval: number | null | undefined; // minutes; undefined: not read yet for this sign-in
  let reading: Promise<void> | undefined;
  let sinceCheck = 0; // ms shown since the last check
  let played = 0; // ms shown since the first of the open games was launched
  let runningSince: number | null = null;
  let due: (() => void) | undefined;

  const counting = () => pageVisible && shown?.step().kind === "playing";
  const pause = () => {
    due?.();
    due = undefined;
    if (runningSince !== null) {
      const ran = clock.now() - runningSince;
      sinceCheck += ran;
      played += ran;
      runningSince = null;
    }
  };
  const run = () => {
    pause();
    if (!counting()) return;
    runningSince = clock.now();
    if (!interval) return;
    const left = Math.max(0, interval * 60_000 - sinceCheck);
    due = m.after(left, () => {
      pause();
      sinceCheck = 0;
      shown?.realityCheck(Math.floor(played / 1000));
    });
  };
  const readInterval = () => {
    const p = options.player();
    if (!p || interval !== undefined || reading) return;
    reading = options.core.protection(p.currency).then((r) => {
      interval = r.ok ? r.value.realityCheckIntervalMinutes : null;
      reading = undefined;
      run();
    });
  };

  const showOnly = (game: PlayBehaviour | undefined) => {
    pause();
    shown = game;
    for (const g of open) g.hidden(g !== game || !pageVisible);
    tell();
    run();
  };
  const remove = (g: PlayBehaviour) => {
    const wasShown = shown === g;
    open = open.filter((x) => x !== g);
    if (open.length === 0) {
      pause();
      played = 0;
    }
    if (wasShown) showOnly(open.at(-1));
    else tell();
  };

  const make = (game: string) => {
    const g = playBehaviour({
      game,
      core: options.core,
      casino: options.casino,
      clock,
      player: options.player,
      funds: options.funds,
      needsDeposit: options.needsDeposit,
      recentBet: options.recentBet,
      keptOn: () => run(),
    });
    g.subscribe((s) => {
      // A play ended, or dismissed back to idle, leaves the open games.
      if (s.kind === "ended" || s.kind === "idle") {
        if (open.includes(g)) {
          remove(g);
          g.stop();
        }
        return;
      }
      if (g === shown) run();
    });
    return g;
  };

  const actions: GamesActions = {
    pick: m.action("pick", (game: string, o: { funMode?: boolean } = {}) => {
      readInterval();
      const existing = open.find((g) => g.game === game);
      if (existing) {
        const kind = existing.step().kind;
        if (needing.has(kind)) existing.actions.launch(o);
        if (live.has(kind) || needing.has(kind) || kind === "refused") showOnly(existing);
        return;
      }
      const g = make(game);
      open = [...open, g];
      showOnly(g);
      g.actions.launch(o);
    }),
    show: m.action("show", (game: string) => {
      const g = open.find((x) => x.game === game);
      if (g) showOnly(g);
    }),
    hideAll: m.action("hideAll", () => showOnly(undefined)),
  };

  const closeAll = () => {
    pause();
    const all = open;
    open = [];
    shown = undefined;
    played = sinceCheck = 0;
    interval = undefined;
    for (const g of all) g.stop();
    tell();
  };
  m.onStop(closeAll);

  return {
    ...behaviour(m, actions),
    visible(isVisible) {
      if (pageVisible === isVisible) return;
      pageVisible = isVisible;
      showOnly(shown);
    },
    closeAll,
  };
}
