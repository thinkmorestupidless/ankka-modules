// A player's withdrawals (T081; US4, parity *A player's withdrawal history in every state*): every request
// the player made, in every state, newest first, a page at a time with payouts' total (Y4). The original's
// tab showed only the pending one. A row opens the request's page.
import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOpenRequest } from "../withdrawals/queue.tsx";
import { readHistory, type Row } from "../withdrawals/read.ts";

const PageSize = 50;
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

const columns = (tz: string): Column<Row>[] => [
  { id: "created", header: "withdrawals.queue.created", cell: (r) => <DateTime instant={r.createdAt} zone={tz} /> },
  { id: "amount", header: "withdrawals.queue.amount", numeric: true, cell: (r) => <Money value={{ minor: r.amount, currency: r.currency }} /> },
  { id: "terminal", header: "withdrawals.queue.terminal", cell: (r) => r.terminalName },
  { id: "destination", header: "withdrawals.queue.destination", cell: (r) => (r.destinationLastFour ? t("withdrawals.destination.lastFour", { lastFour: r.destinationLastFour }) : null) },
  {
    id: "status",
    header: "withdrawals.queue.status",
    cell: (r) => (
      <>
        <Status value={r.status.toLowerCase()} />
        {r.payoutStatus && r.payoutStatus.toLowerCase() !== "none" ? (
          <>
            {" "}
            <Status value={r.payoutStatus.toLowerCase()} />
          </>
        ) : null}
      </>
    ),
  },
];

export function PlayerWithdrawals({ player }: { player: string }) {
  const tz = zone();
  const list = useListState(useRouterListStore());
  const at = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).at });
  const open = useOpenRequest();
  const freshness = useFreshness(["payouts"]);
  const q = useQuery({ queryKey: ["payouts", "history", player, list.state.page], queryFn: () => readHistory(player, list.state.page, PageSize), enabled: player !== "" });
  return (
    <section>
      <h1>{t("withdrawals.player.title", { player })}</h1>
      <p className="bo-muted">{t("withdrawals.player.every")}</p>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="withdrawals.player.caption"
        columns={columns(tz)}
        rows={q.data?.requests}
        rowKey={(r) => r.requestId}
        total={q.data?.total}
        complete={q.data ? completeOf(q.data) : false}
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
