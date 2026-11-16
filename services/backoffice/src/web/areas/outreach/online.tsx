// Who is online (T122; US11): the brand's players with an open connection now, from engagement's
// presence (`presence.read`), the longest online first, each opening the player; re-read when the
// events stream says a player came online or the agent stream was opened again. Whether the operator is
// told when a player comes online is their preference; showing it on the desktop too is the browser's
// permission, asked from here with a click (the original's banner: a prompt without one is suppressed).
import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { engagement } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { List, memoryStore, useListState, type Column } from "../../components/list/index.ts";
import { useOperator } from "../../frame/context.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { playerHref } from "./notices.tsx";
import "./outreach.css";

interface Online {
  playerId: string;
  since: string | null;
}

/** Whether the operator asked to be told (their `onlineNotices` preference). */
export function askedForNotices(preferences: unknown): boolean {
  return (preferences as { onlineNotices?: unknown } | null | undefined)?.onlineNotices === true;
}

type Permission = NotificationPermission | "unsupported";
const permissionNow = (): Permission => (typeof Notification === "undefined" ? "unsupported" : Notification.permission);

function Desktop() {
  const [permission, setPermission] = useState<Permission>(permissionNow);
  if (permission === "unsupported") return null;
  if (permission === "granted") return <p className="bo-muted">{t("outreach.desktopOn")}</p>;
  if (permission === "denied") return <p className="bo-muted">{t("outreach.desktopBlocked")}</p>;
  return (
    <p className="bo-online-desktop">
      <button type="button" className="bo-button" onClick={() => void Notification.requestPermission().then(setPermission, () => setPermission(permissionNow()))}>
        {t("outreach.desktop")}
      </button>
    </p>
  );
}

const store = memoryStore();

export function OnlinePage() {
  const me = useOperator();
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const list = useListState(store);
  const q = useQuery({
    queryKey: ["presence"],
    queryFn: () => readJson<{ online: Online[]; count: number }>(engagement.getPresence()),
    refetchInterval: 30_000,
  });
  const asked = askedForNotices(me.preferences);
  const columns: Column<Online>[] = [
    {
      id: "player",
      header: "outreach.player",
      cell: (o) => (
        <Link className="bo-link" to={playerHref(o.playerId)}>
          {o.playerId}
        </Link>
      ),
    },
    { id: "since", header: "outreach.since", cell: (o) => (o.since ? <DateTime instant={o.since} zone={tz} /> : null) },
  ];
  return (
    <section aria-labelledby="online-title">
      <h1 id="online-title">{t("outreach.title")}</h1>
      <p className="bo-muted">{t("outreach.intro")}</p>
      <p>
        {asked ? t("outreach.asked") : t("outreach.notAsked")}{" "}
        <Link className="bo-link" to="/preferences">
          {t("area.preferences")}
        </Link>
      </p>
      {asked ? <Desktop /> : null}
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      {q.data ? <p role="status">{q.data.count === 0 ? t("outreach.none") : t("outreach.count", { count: q.data.count })}</p> : null}
      <List caption="outreach.title" columns={columns} rows={q.data?.online} rowKey={(o) => o.playerId} total={q.data?.count} complete={true} fetching={q.isFetching} zone={tz} list={list} />
    </section>
  );
}
