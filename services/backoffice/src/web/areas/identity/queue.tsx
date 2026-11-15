// The identity queue (T095; US7, parity *Identity queue*): kyc's queue by filter, first in first out
// as kyc orders it (oldest first by the filter's time; never re-sorted here), the operator's place
// kept in the URL (the filter, the page, the scroll offset and the player last opened), so returning
// from a player brings them back to the row they left. kyc answers at most 1,000 rows of a filter with
// every filter's live count and no pages yet (amendment K2), so the page pages what it answers and
// says when the count is more than it was given.
import { useQuery } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useId } from "react";
import { t } from "../../../shared/messages/index.ts";
import { word } from "../word.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { Status } from "../../components/status.tsx";
import { Filters, readQueue, type QueueRow } from "./kyc.ts";

export const PageSize = 50;
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/** The time a row waits from under a filter, as kyc orders by it (KycQueue.Filter.since). */
export function waitingSince(filter: string, row: QueueRow): string | null {
  if (filter === "awaiting") return row.oldestAwaitingAt;
  if (filter === "ready") return row.readyAt;
  if (filter === "sof" || filter === "flagged") return row.flaggedAt;
  return row.updatedAt;
}

/** A page of kyc's rows, in kyc's order; the total is kyc's count for the filter where it gives one. */
export function pageOf(rows: readonly QueueRow[], counts: Record<string, number>, filter: string, page: number) {
  const counted = Filters.find(([f]) => f === filter)?.[1];
  const total = counted !== undefined ? counts[counted] : undefined;
  const shown = rows.slice((page - 1) * PageSize, page * PageSize);
  const answered = page * PageSize >= rows.length;
  return { shown, total, complete: answered && (total === undefined || total <= rows.length), capped: total !== undefined && total > rows.length };
}

const columns = (filter: string, tz: string): Column<QueueRow>[] => [
  { id: "player", header: "identity.queue.player", cell: (r) => <code>{r.playerId}</code> },
  { id: "since", header: "identity.queue.since", cell: (r) => (waitingSince(filter, r) ? <DateTime instant={waitingSince(filter, r)!} zone={tz} /> : null) },
  { id: "status", header: "identity.queue.status", cell: (r) => <Status value={r.status ?? "unverified"} /> },
  { id: "awaiting", header: "identity.queue.awaiting", numeric: true, cell: (r) => r.awaiting ?? 0 },
  { id: "mode", header: "identity.queue.mode", cell: (r) => t(r.mode === "manual" ? "identity.mode.manual" : "identity.mode.automatic") },
  { id: "flags", header: "identity.queue.flags", cell: (r) => (r.flags ?? []).map((f) => word("identity.flag", f)).join(", ") },
  { id: "sof", header: "identity.queue.sof", cell: (r) => t(r.sof === "cleared" ? "identity.sof.cleared" : "identity.sof.unverified") },
];

export function IdentityQueuePage() {
  const router = useRouter();
  const at = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).at });
  const list = useListState(useRouterListStore());
  const filter = list.state.filters.filter ?? "awaiting";
  const filterId = useId();
  const tz = zone();
  const freshness = useFreshness(["kyc"]);
  const q = useQuery({ queryKey: ["kyc", "queue", filter], queryFn: () => readQueue(filter), refetchInterval: 30_000 });

  const open = (row: QueueRow) => {
    // the place kept: the row opened is named in the queue's own entry, then the player is opened
    void router
      .navigate({ to: router.state.location.pathname, search: (prev: Record<string, unknown>) => ({ ...prev, at: row.playerId }), replace: true } as never)
      .then(() => router.navigate({ to: "/players/$player/identity", params: { player: row.playerId } } as never));
  };

  const paged = q.data ? pageOf(q.data.rows, q.data.counts, filter, list.state.page) : undefined;
  return (
    <section>
      <h1>{t("area.identity")}</h1>
      <p className="bo-muted">{t("identity.queue.order")}</p>
      <div className="bo-field">
        <label htmlFor={filterId}>{t("identity.queue.filter")}</label>
        <select id={filterId} value={filter} onChange={(e) => list.setFilter("filter", e.target.value === "awaiting" ? undefined : e.target.value)}>
          {Filters.map(([f, counted]) => (
            <option key={f} value={f}>
              {counted !== undefined && q.data?.counts[counted] !== undefined
                ? t("identity.queue.filterCounted", { filter: word("identity.filter", f), count: q.data.counts[counted]! })
                : word("identity.filter", f)}
            </option>
          ))}
        </select>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {paged?.capped ? <p className="bo-muted">{t("identity.queue.capped", { count: q.data!.rows.length })}</p> : null}
      <List
        caption="identity.queue.caption"
        columns={columns(filter, tz)}
        rows={paged?.shown}
        rowKey={(r) => r.playerId}
        total={paged?.total}
        complete={paged?.complete ?? false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={open}
        current={typeof at === "string" ? at : undefined}
      />
    </section>
  );
}
