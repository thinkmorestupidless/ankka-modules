// The page's side of `/api/events`: `permissions_changed` re-reads `/api/me`, so what is offered follows
// the operator's roles without signing in again (FR-008b); `signed_out` goes to the sign-in, coming
// back to the same place. EventSource reconnects by itself when the stream drops.
import { useEffect } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { signInHref } from "./session.ts";

export interface Source {
  addEventListener(type: string, listener: () => void): void;
  close(): void;
}

export function listen(
  open: () => Source,
  client: Pick<QueryClient, "invalidateQueries">,
  go: (href: string) => void = (h) => window.location.assign(h),
): () => void {
  const source = open();
  source.addEventListener("permissions_changed", () => void client.invalidateQueries({ queryKey: ["me"] }));
  source.addEventListener("signed_out", () => {
    source.close();
    go(signInHref(window.location));
  });
  return () => source.close();
}

export function useEvents(enabled: boolean): void {
  const client = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return;
    return listen(() => new EventSource("/api/events"), client);
  }, [enabled, client]);
}
