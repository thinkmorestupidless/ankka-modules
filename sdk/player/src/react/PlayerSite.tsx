import { createContext, useContext, useSyncExternalStore, type ReactNode } from "react";
import type { DepositBehaviour } from "../core/deposit.ts";
import type { PlayerSite } from "../site.ts";

const Site = createContext<PlayerSite | null>(null);

/** Gives the page's one site to the components under it. Context only: no rule, no state of its own. */
export function PlayerSiteProvider({ site, children }: { site: PlayerSite; children?: ReactNode }) {
  return <Site.Provider value={site}>{children}</Site.Provider>;
}

export function usePlayerSite(): PlayerSite {
  const site = useContext(Site);
  if (!site) throw new Error("usePlayerSite outside a PlayerSiteProvider");
  return site;
}

/** The deposit open now, rendered again when one opens or closes (`site.subscribe`). */
export function useCurrentDeposit(site: PlayerSite): DepositBehaviour | undefined {
  return useSyncExternalStore(site.subscribe, () => site.currentDeposit, () => site.currentDeposit);
}

/** The signed-in player, rendered again on a sign-in or sign-out. */
export function usePlayer(site: PlayerSite) {
  return useSyncExternalStore(site.subscribe, () => site.player, () => site.player);
}
