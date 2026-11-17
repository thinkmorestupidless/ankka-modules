// The provider's calls the brand has not acknowledged (T110; parity *Unacknowledged callbacks*):
// game-provider's list, oldest first with its total (GP3), each replayed (the transaction's own
// workflow resumed under the same id, no reason) or resolved by hand with the reason game-provider
// requires (`reason required`), which ends the workflow so a late retry is not resumed.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { gameProvider } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { RecordLink } from "../../components/links.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { ConfigPage, read } from "./page.tsx";

export interface Unacknowledged {
  transactionId: string;
  callback: string;
  kind: string;
  /** Minor units of `currency`. */
  amount: number;
  currency: string;
  provider: string;
  roundId: string;
  playerId: string;
  error: string;
  recordedAt: string;
}

interface Page {
  entries: Unacknowledged[];
  total: number;
  page: number | null;
  pageSize: number | null;
}

const PageSize = 50;

function RowActions({ e, done }: { e: Unacknowledged; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const subject = (
    <p>
      {t("config.callbacks.subject", { kind: word("config.callbacks.kind", e.kind), id: e.transactionId })} <Money value={{ minor: e.amount, currency: e.currency }} />
    </p>
  );
  return (
    <span className="bo-actions">
      <Action action={Actions["callback.replay"]} me={me} bags={bags} subject={subject} run={() => gameProvider.postUnacknowledgedReplay({ transactionId: e.transactionId })} onDone={done} />
      <Action
        action={Actions["callback.resolve"]}
        me={me}
        bags={bags}
        subject={subject}
        run={(reason) => gameProvider.postUnacknowledgedResolve({ transactionId: e.transactionId }, { body: { reason } })}
        onDone={done}
      />
    </span>
  );
}

export function CallbacksPage() {
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const list = useListState(useRouterListStore());
  const tz = useZone();
  const page = list.state.page;
  const q = useQuery({
    queryKey: ["config", "game-provider", "unacknowledged", page],
    queryFn: () => read<Page>(gameProvider.getUnacknowledged({ query: { page: String(page), pageSize: String(PageSize) } })),
    refetchInterval: 30_000,
  });
  const done = () => void client.invalidateQueries({ queryKey: ["config", "game-provider", "unacknowledged"] });
  const columns: Column<Unacknowledged>[] = [
    { id: "recorded", header: "config.callbacks.recorded", cell: (e) => <DateTime instant={e.recordedAt} zone={tz} /> },
    { id: "kind", header: "config.callbacks.kindHeader", cell: (e) => word("config.callbacks.kind", e.kind) },
    { id: "amount", header: "config.callbacks.amount", numeric: true, cell: (e) => <Money value={{ minor: e.amount, currency: e.currency }} /> },
    { id: "player", header: "config.callbacks.player", cell: (e) => <RecordLink to={{ kind: "player", id: e.playerId }} /> },
    { id: "round", header: "config.callbacks.round", cell: (e) => <RecordLink to={{ kind: "round", player: e.playerId, id: e.roundId }} /> },
    { id: "provider", header: "config.callbacks.provider", cell: (e) => `${e.provider} (${e.callback})` },
    { id: "error", header: "config.callbacks.error", cell: (e) => e.error },
    ...(readOnly ? [] : [{ id: "act", header: "config.callbacks.act" as const, cell: (e: Unacknowledged) => <RowActions e={e} done={done} /> }]),
  ];
  return (
    <ConfigPage heading="config.callbacks.title" note="config.callbacks.note">
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      <List
        caption="config.callbacks.caption"
        columns={columns}
        rows={q.data?.entries}
        rowKey={(e) => e.transactionId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
      />
    </ConfigPage>
  );
}
