// A list's place in the router's search parameters (FR-010): the router's location is the store, so
// returning to the list (back, or a link with the same search) restores it. Other search parameters of
// the page are kept as they are.
import { useMemo } from "react";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { fromSearch, listSearchKeys, toSearch, type ListStore } from "./state.ts";

export function useRouterListStore(): ListStore {
  const router = useRouter();
  const search = useRouterState({ select: (s) => s.location.search as Record<string, unknown> });
  return useMemo<ListStore>(
    () => ({
      get: () => fromSearch(search),
      set(next, how) {
        const current = router.state.location.search as Record<string, unknown>;
        const kept = Object.fromEntries(Object.entries(current).filter(([k]) => !listSearchKeys(current).includes(k)));
        void router.navigate({ to: router.state.location.pathname, search: { ...kept, ...toSearch(next) }, replace: how?.replace ?? false });
      },
    }),
    [router, search],
  );
}
