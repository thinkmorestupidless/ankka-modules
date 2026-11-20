// The operator's session as the page sees it: `/api/me`, the sign-in redirect on 401, and the narrow
// window's read-only mode (FR-041).
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { Me } from "../../shared/access.ts";
import { explain, type Refusal } from "../../shared/refusals.ts";

export type MeState =
  | { state: "loading" }
  | { state: "signedIn"; me: Me }
  | { state: "refused"; refusal: Refusal }
  /** The operator's roles do not name this brand; `lost` when they did earlier in this page's life. */
  | { state: "notInBrand"; brand: string; lost: boolean }
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
  if (!r.ok) {
    const body = await r.text();
    const notIn = r.status === 403 ? brandRefused(body) : undefined;
    if (notIn !== undefined) return { state: "notInBrand", brand: notIn, lost: false };
    return { state: "refused", refusal: explain(r.status, body) };
  }
  return { state: "signedIn", me: (await r.json()) as Me };
}

function brandRefused(body: string): string | undefined {
  try {
    const b = JSON.parse(body) as { error?: unknown; brand?: unknown };
    return b.error === "brand_not_in_roles" && typeof b.brand === "string" ? b.brand : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Whether the operator lost the brand: refused it now, having been let in earlier. The page then signs
 * them out and says why (edge case 1).
 */
export function afterRead(before: MeState | undefined, now: MeState): MeState {
  if (now.state === "notInBrand" && (before?.state === "signedIn" || (before?.state === "notInBrand" && before.lost)))
    return { ...now, lost: true };
  return now;
}

export function useMe(): MeState {
  const last = useRef<MeState | undefined>(undefined);
  const q = useQuery({
    queryKey: ["me"],
    queryFn: async () => (last.current = afterRead(last.current, await readMe())),
    staleTime: 10_000,
    refetchInterval: (query) => (query.state.data?.state === "notInBrand" ? false : 30_000),
  });
  return q.data ?? { state: "loading" };
}

export const WideEnough = "(min-width: 1280px)";

/** Below 1280 px the backoffice is for looking a player up: no editing is offered. */
export function useReadOnly(): boolean {
  // one MediaQueryList for the component's life, so the listener is added once, not on every render
  const [query] = useState(() => (typeof window !== "undefined" && window.matchMedia ? window.matchMedia(WideEnough) : undefined));
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
