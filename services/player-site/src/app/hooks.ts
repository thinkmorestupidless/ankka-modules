import type { Funds, PlayerSite } from "@eitheror/player-sdk";
import { useEffect, useSyncExternalStore } from "react";

/** The player's funds, rendered again on each read. */
export function useFunds(site: PlayerSite): Funds {
  return useSyncExternalStore(site.funds.subscribe, site.funds.current, site.funds.current);
}

/**
 * The page's own events, told to the site: its visibility, the player's activity (for the idle
 * count) and the page being left, when what must outlive it is sent.
 */
export function usePageEvents(site: PlayerSite) {
  useEffect(() => {
    const visibility = () => site.visible(document.visibilityState === "visible");
    const active = () => site.session.activity();
    const leave = () => site.stop();
    document.addEventListener("visibilitychange", visibility);
    for (const e of ["keydown", "pointerdown", "scroll"]) window.addEventListener(e, active, { passive: true });
    window.addEventListener("pagehide", leave);
    return () => {
      document.removeEventListener("visibilitychange", visibility);
      for (const e of ["keydown", "pointerdown", "scroll"]) window.removeEventListener(e, active);
      window.removeEventListener("pagehide", leave);
    };
  }, [site]);
}
