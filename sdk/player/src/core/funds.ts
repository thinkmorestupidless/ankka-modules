import type { Amount } from "../amount.ts";
import type { Clock } from "../clock.ts";
import type { CoreClient } from "./client.ts";

/** What the player has to play with (data-model.md, *Funds*); `null` is "not read yet". */
export interface Funds {
  readonly real: Amount | null;
  readonly restricted: Amount | null;
  /** The games the player holds free rounds for. */
  readonly freeRoundsGames: readonly string[] | null;
}

export const unread: Funds = { real: null, restricted: null, freeRoundsGames: null };

/**
 * The one playable-funds rule every launch applies (FR-027), the original's `hasNoPlayableFunds`
 * turned round: a real balance that is not zero, or a restricted balance that is not zero, or free
 * rounds for the game (any free rounds, with no game named). A balance not read yet counts as
 * playable: the wallet checks again at the bet, and blocking before the first read would ask for
 * deposits nobody needs.
 */
export function hasPlayableFunds(funds: Funds, game?: string): boolean {
  if (funds.real === null || funds.real.minor !== 0n) return true;
  if (funds.restricted !== null && funds.restricted.minor !== 0n) return true;
  if (funds.freeRoundsGames === null) return true;
  return game ? funds.freeRoundsGames.includes(game) : funds.freeRoundsGames.length > 0;
}

/** Reads the games free rounds are held for; `casino` gives one (T028). */
export type FreeRoundsReader = () => Promise<readonly string[] | null>;

/** The player's funds, read on sign-in, on demand, after a deposit and every 5 s while visible (FR-026). */
export interface FundsStore {
  current(): Funds;
  subscribe(listener: (funds: Funds) => void): () => void;
  /** Reads now; answers once the read has settled. A failed read keeps what was known. */
  read(): Promise<void>;
  /** The page's visibility: hidden, nothing is read on the clock. */
  visible(isVisible: boolean): void;
  /** The player signed in (with their currency), or out (null). */
  player(currency: string | null): void;
  /** Gives the store its free rounds reader; none given counts as not read. */
  freeRounds(reader: FreeRoundsReader): void;
  stop(): void;
}

export const FundsIntervalMs = 5000;

export function fundsStore(core: CoreClient, clock: Clock): FundsStore {
  let funds: Funds = unread;
  let currency: string | null = null;
  let shown = true;
  let reader: FreeRoundsReader | undefined;
  let cancel: (() => void) | undefined;
  let generation = 0;
  const listeners = new Set<(f: Funds) => void>();
  const set = (next: Funds) => {
    funds = next;
    for (const l of [...listeners]) l(next);
  };
  const tick = () => {
    cancel?.();
    cancel = currency && shown ? clock.every(FundsIntervalMs, () => void store.read()) : undefined;
  };
  const store: FundsStore = {
    current: () => funds,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    async read() {
      const c = currency;
      if (!c) return;
      const g = generation;
      const [b, rounds] = await Promise.all([core.balance(c), reader ? reader().catch(() => null) : Promise.resolve(null)]);
      if (g !== generation) return;
      set({
        real: b.ok ? b.value.real : funds.real,
        restricted: b.ok ? b.value.restricted : funds.restricted,
        freeRoundsGames: reader ? (rounds ?? funds.freeRoundsGames) : null,
      });
    },
    visible(isVisible) {
      if (shown === isVisible) return;
      shown = isVisible;
      tick();
      if (isVisible) void store.read();
    },
    player(c) {
      generation += 1;
      currency = c;
      set(unread);
      tick();
      if (c) void store.read();
    },
    freeRounds(r) {
      reader = r;
    },
    stop() {
      cancel?.();
      cancel = undefined;
      generation += 1;
      listeners.clear();
    },
  };
  return store;
}
