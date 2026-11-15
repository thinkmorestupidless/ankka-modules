// The operator's session as the page sees it: `/api/me`, the sign-in redirect on 401, and the narrow
// window's read-only mode (FR-041).
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { Me } from "../../shared/access.ts";
import { explain, type Refusal } from "../../shared/refusals.ts";

export type MeState =
  | { state: "loading" }
  | { state: "signedIn"; me: Me }
  | { state: "refused"; refusal: Refusal }
  | { state: "unreachable" };

/** Where the browser goes to sign in, coming back to where it was. */
export function signInHref(location: { pathname: string; search: string }): string {
  return `/auth/sign-in?return=${encodeURIComponent(location.pathname + location.search)}`;
}

export async function readMe(fetchFn: typeof fetch = fetch, go: (href: string) => void = (h) => window.location.assign(h)): Promise<MeState> {
  let r: Response;
  try {
    r = await fetchFn("/api/me", { headers: { Accept: "application/json" } });
  } catch {
    return { state: "unreachable" };
  }
  if (r.status === 401) {
    go(signInHref(window.location));
    return { state: "loading" };
  }
  if (!r.ok) return { state: "refused", refusal: explain(r.status, await r.text()) };
  return { state: "signedIn", me: (await r.json()) as Me };
}

export function useMe(): MeState {
  const q = useQuery({ queryKey: ["me"], queryFn: () => readMe(), staleTime: 10_000, refetchInterval: 30_000 });
  return q.data ?? { state: "loading" };
}

export const WideEnough = "(min-width: 1280px)";

/** Below 1280 px the backoffice is for looking a player up: no editing is offered. */
export function useReadOnly(): boolean {
  const query = typeof window !== "undefined" && window.matchMedia ? window.matchMedia(WideEnough) : undefined;
  const [narrow, setNarrow] = useState(() => (query ? !query.matches : false));
  useEffect(() => {
    if (!query) return;
    const on = (e: MediaQueryListEvent) => setNarrow(!e.matches);
    query.addEventListener("change", on);
    return () => query.removeEventListener("change", on);
  }, [query]);
  return narrow;
}

/** The operator's theme on the document, dark unless they chose light. */
export function useTheme(me: Me | undefined): void {
  const theme = (me?.preferences as { theme?: string } | null | undefined)?.theme === "light" ? "light" : "dark";
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
}
