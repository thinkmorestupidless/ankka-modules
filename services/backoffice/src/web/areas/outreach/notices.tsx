// The notice a player coming online leaves, wherever the operator is (T122; US11, FR-036): `/api/events`
// relays engagement's `player_online` for an operator who asked, `frame/events.ts` announces it here, and
// the frame shows it beside the jobs' notices until it is dismissed or followed to the player. Where the
// operator let the browser show them, it is shown on the desktop too, one per session across the
// operator's tabs (the original's feature 182). A polite live region: read without moving focus.
import { useSyncExternalStore } from "react";
import { Link } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { Glyph } from "../../components/glyph.tsx";
import "./outreach.css";

export interface OnlineNotice {
  playerId: string;
  emailPrefix: string;
  sessionId: string;
  occurredAt: string;
}

/** The most notices shown at once; an older one goes when a newer comes. */
const Shown = 5;

let notices: readonly OnlineNotice[] = [];
const listeners = new Set<() => void>();
const changed = () => listeners.forEach((l) => l());

export const playerHref = (playerId: string) => `/players/${encodeURIComponent(playerId)}`;

export function noticeText(n: Pick<OnlineNotice, "playerId" | "emailPrefix">): string {
  return n.emailPrefix ? t("outreach.notice.online", { name: n.emailPrefix }) : t("outreach.notice.onlineUnnamed", { player: n.playerId });
}

/** On the desktop too, when the browser was let show them; one per session, whichever tabs are open. */
function desktop(n: OnlineNotice): void {
  try {
    if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
    const shown = new Notification(noticeText(n), { tag: n.sessionId || n.playerId });
    shown.onclick = () => {
      window.focus();
      window.location.assign(playerHref(n.playerId));
      shown.close();
    };
  } catch {
    // a browser that will not show it: the page's notice stands
  }
}

export function announceOnline(n: OnlineNotice): void {
  const same = (x: OnlineNotice) => x.playerId === n.playerId;
  const repeat = notices.some((x) => same(x) && x.sessionId === n.sessionId);
  notices = [n, ...notices.filter((x) => !same(x))].slice(0, Shown);
  changed();
  if (!repeat) desktop(n);
}

export function dismissOnline(playerId: string): void {
  notices = notices.filter((x) => x.playerId !== playerId);
  changed();
}

/** `player_online`'s data, when it is what the server sends. */
export function onlineNoticeOf(data: unknown): OnlineNotice | undefined {
  try {
    const d = JSON.parse(String(data)) as Partial<OnlineNotice>;
    return typeof d.playerId === "string" && d.playerId !== ""
      ? {
          playerId: d.playerId,
          emailPrefix: typeof d.emailPrefix === "string" ? d.emailPrefix : "",
          sessionId: typeof d.sessionId === "string" ? d.sessionId : "",
          occurredAt: typeof d.occurredAt === "string" ? d.occurredAt : "",
        }
      : undefined;
  } catch {
    return undefined;
  }
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => void listeners.delete(l);
}

/** The notices shown now, newest first. */
export const onlineNoticesNow = (): readonly OnlineNotice[] => notices;

export function useOnlineNotices(): readonly OnlineNotice[] {
  return useSyncExternalStore(subscribe, () => notices, () => notices);
}

export function OnlineNotices() {
  const shown = useOnlineNotices();
  return (
    <div className="bo-online-notices" aria-live="polite">
      {shown.map((n) => (
        <p key={n.playerId} className="bo-notice" data-online={n.playerId}>
          <Glyph name="dot" />
          <span>{noticeText(n)}</span>
          <Link className="bo-link" to={playerHref(n.playerId)} onClick={() => dismissOnline(n.playerId)}>
            {t("outreach.notice.open")}
          </Link>
          <button type="button" className="bo-button" onClick={() => dismissOnline(n.playerId)}>
            {t("outreach.notice.dismiss")}
          </button>
        </p>
      ))}
    </div>
  );
}
