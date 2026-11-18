import { realClock, type Clock } from "./clock.ts";
import { coreClient, type CoreClient } from "./core/client.ts";
import { depositBehaviour, type DepositBehaviour } from "./core/deposit.ts";
import { fundsStore, type FundsStore } from "./core/funds.ts";
import { sessionBehaviour, type SessionBehaviour } from "./core/session.ts";
import type { SignedInPlayer, SiteFacts, Transport } from "./transport.ts";

export interface PlayerSiteOptions {
  readonly transport: Transport;
  readonly clock?: Clock;
  /** What the first page already knows (the site and the player), so nothing is asked before it is shown. */
  readonly known?: { readonly site: SiteFacts; readonly player: SignedInPlayer | null };
  /** The site's idle times (data-model.md, *Site configuration*): 30 minutes, warned 30 seconds before. */
  readonly idleMinutes?: number;
  readonly idleWarningSeconds?: number;
  /** The site's preset amounts per currency, in major units (data-model.md, *Site configuration*). */
  readonly presets?: Readonly<Record<string, readonly (string | number)[]>>;
  /** The site's bounds per currency, which trim the presets only. */
  readonly bounds?: Readonly<Record<string, { readonly minimum?: string | number; readonly maximum?: string | number }>>;
}

/**
 * One per page (contracts/behaviours.md): the behaviours of the modules the brand runs. Until
 * `ready` settles the site does not know which modules those are, unless `known` told it.
 */
export interface PlayerSite {
  /** Settles when the site's facts and the player are known. */
  readonly ready: Promise<void>;
  readonly facts: SiteFacts | undefined;
  readonly player: SignedInPlayer | null;
  /** The player's registering, signing in, staying signed in and signing out (`core`). */
  readonly session: SessionBehaviour;
  /** The player's balance and free rounds (FR-026, FR-027). */
  readonly funds: FundsStore;
  /** `core`'s client, the one every core behaviour of this site calls through. */
  readonly core: CoreClient;
  /** The page's visibility, which the funds' and play's clocks follow. */
  visible(isVisible: boolean): void;
  /**
   * Opens a deposit for the signed-in player, as `currentDeposit`; one already open is answered
   * as it stands. Nobody signed in: `undefined`.
   */
  deposit(): DepositBehaviour | undefined;
  /** The deposit open now, until it is closed; told through `subscribe`. */
  readonly currentDeposit: DepositBehaviour | undefined;
  /** Whether the brand runs a module, by `/api/site` alone. */
  runs(module: string): boolean;
  /** Told when anything the site holds changes (`currentDeposit`, the player). */
  subscribe(listener: () => void): () => void;
  /** Ends every behaviour's timers and streams, sending what must outlive the page. */
  stop(): void;
  readonly transport: Transport;
  readonly clock: Clock;
}

export function playerSite(options: PlayerSiteOptions): PlayerSite {
  const { transport } = options;
  const clock = options.clock ?? realClock;
  const listeners = new Set<() => void>();
  const stops: Array<() => void> = [];
  let facts = options.known?.site;
  let player = options.known?.player ?? null;
  const changed = () => {
    for (const l of [...listeners]) l();
  };
  const core = coreClient(transport, () => player?.playerId ?? null);
  const funds = fundsStore(core, clock);
  stops.push(funds.stop);
  const session = sessionBehaviour({
    transport,
    clock,
    player,
    ...(options.idleMinutes !== undefined ? { idleMinutes: options.idleMinutes } : {}),
    ...(options.idleWarningSeconds !== undefined ? { idleWarningSeconds: options.idleWarningSeconds } : {}),
    onPlayer(p) {
      player = p;
      if (!p) closeDeposit();
      funds.player(p?.currency ?? null);
      changed();
    },
  });
  stops.push(session.stop);
  if (player) funds.player(player.currency);
  let currentDeposit: DepositBehaviour | undefined;
  const openDeposit = (): DepositBehaviour | undefined => {
    if (!player) return undefined;
    if (currentDeposit) return currentDeposit;
    const bounds = options.bounds?.[player.currency] ?? {};
    const presets = options.presets?.[player.currency];
    const d = depositBehaviour({
      core,
      clock,
      currency: player.currency,
      country: player.country,
      ...(presets ? { presets } : {}),
      ...(bounds.minimum !== undefined ? { minimum: bounds.minimum } : {}),
      ...(bounds.maximum !== undefined ? { maximum: bounds.maximum } : {}),
      succeeded: () => void funds.read(),
    });
    currentDeposit = d;
    d.subscribe((step) => {
      if (step.kind === "closed" && currentDeposit === d) {
        currentDeposit = undefined;
        changed();
      }
    });
    changed();
    return d;
  };
  const closeDeposit = () => {
    const d = currentDeposit;
    currentDeposit = undefined;
    d?.stop();
  };
  stops.push(closeDeposit);
  const ready = options.known
    ? Promise.resolve()
    : Promise.all([transport.session.site(), transport.session.read()]).then(([s, p]) => {
        if (s.ok) facts = s.site;
        if (p.ok) {
          player = p.player;
          session.hydrate(p.player);
        }
        changed();
      });
  return {
    ready,
    get facts() {
      return facts;
    },
    get player() {
      return player;
    },
    session,
    deposit: openDeposit,
    get currentDeposit() {
      return currentDeposit;
    },
    funds,
    core,
    visible: (isVisible) => funds.visible(isVisible),
    runs: (module) => facts?.modules.includes(module) ?? false,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    stop() {
      for (const s of stops.splice(0)) s();
      listeners.clear();
    },
    transport,
    clock,
  };
}

/**
 * The deposit address rule (FR-025, the original's `DepositParamWatcher`): a page address with
 * `deposit=true` opens the deposit for a signed-in player at once, and for a visitor once they
 * have signed in. Answers what the page should do now, and a function that stops waiting.
 */
export function followDepositAddress(site: PlayerSite, address: string | URL): { now: "none" | "opened" | "sign_in"; stop: () => void } {
  const url = typeof address === "string" ? new URL(address, "http://site") : address;
  if (url.searchParams.get("deposit") !== "true") return { now: "none", stop: () => {} };
  if (site.player) {
    site.deposit();
    return { now: "opened", stop: () => {} };
  }
  const stop = site.subscribe(() => {
    if (!site.player) return;
    stop();
    site.deposit();
  });
  return { now: "sign_in", stop };
}
