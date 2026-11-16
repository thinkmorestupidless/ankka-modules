// The page's side of `/api/events`: `permissions_changed` re-reads `/api/me`, so what is offered follows
// the operator's roles without signing in again (FR-008b); `signed_out` goes to the sign-in, coming
// back to the same place; `job_done` re-reads the jobs and leaves a notice wherever the operator is
// (FR-029); `player_online` leaves the notice that opens the player and re-reads who is online, and
// `presence` (the agent stream opened again) re-reads it (FR-036). EventSource reconnects by itself when
// the stream drops; the stream is opened again when the operator's online notices are turned on or off.
import { useEffect } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { signInHref } from "./session.ts";
import { announce, noticeOf } from "../areas/jobs/notices.tsx";
import { announceOnline, onlineNoticeOf } from "../areas/outreach/notices.tsx";

export interface Source {
  addEventListener(type: string, listener: (e?: { data?: unknown }) => void): void;
  close(): void;
}

export function listen(
  open: () => Source,
  client: Pick<QueryClient, "invalidateQueries">,
  go: (href: string) => void = (h) => window.location.assign(h),
): () => void {
  const source = open();
  source.addEventListener("permissions_changed", () => void client.invalidateQueries({ queryKey: ["me"] }));
  source.addEventListener("job_done", (e) => {
    void client.invalidateQueries({ queryKey: ["jobs"] });
    const n = noticeOf(e?.data);
    if (n) announce(n);
  });
  source.addEventListener("player_online", (e) => {
    const n = onlineNoticeOf(e?.data);
    if (n) announceOnline(n);
    void client.invalidateQueries({ queryKey: ["presence"] });
  });
  source.addEventListener("presence", () => void client.invalidateQueries({ queryKey: ["presence"] }));
  source.addEventListener("signed_out", () => {
    source.close();
    go(signInHref(window.location));
  });
  return () => source.close();
}

/** `/api/events` while `enabled`; opened again when `asked` (the operator's online notices) changes. */
export function useEvents(enabled: boolean, asked = false): void {
  const client = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return;
    return listen(() => new EventSource("/api/events"), client);
  }, [enabled, asked, client]);
}
