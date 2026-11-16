// engagement's parked sends (T111): messages the processor could not take, parked rather than lost,
// searched by trigger or reason and paged (50 a page, engagement's), each retried (the attempt waited on,
// no reason) or discarded with the reason engagement requires. Only a parked send is either (409
// `not_parked` otherwise).
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId } from "react";
import { t } from "../../../shared/messages/index.ts";
import { engagement } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { ConfigPage, read } from "./page.tsx";

export interface Send {
  sendId: string;
  trigger: string;
  event: string;
  class: string;
  channel: string;
  time: string;
  outcome: string;
  reason: string;
}

const PageSize = 50;
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

function SendActions({ s, done }: { s: Send; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const state = s.outcome.toLowerCase();
  const subject = <p>{t("config.sends.subject", { kind: s.trigger, reason: s.reason })}</p>;
  return (
    <span className="bo-actions">
      <Action action={Actions["send.retry"]} me={me} bags={bags} state={state} subject={subject} run={() => engagement.postParkedSendsRetry({ sendId: s.sendId })} onDone={done} />
      <Action action={Actions["send.discard"]} me={me} bags={bags} state={state} subject={subject} run={(reason) => engagement.postParkedSendsDiscard({ sendId: s.sendId }, { body: { reason } })} onDone={done} />
    </span>
  );
}

export function SendsPage() {
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const list = useListState(useRouterListStore());
  const searchId = useId();
  const tz = zone();
  const page = list.state.page;
  const search = list.state.filters.search ?? "";
  const q = useQuery({
    queryKey: ["config", "engagement", "parked-sends", search, page],
    queryFn: () => read<{ sends: Send[]; page: number; total: number }>(engagement.getParkedSends({ query: { page: String(page), ...(search ? { search } : {}) } })),
    refetchInterval: 30_000,
  });
  const done = () => void client.invalidateQueries({ queryKey: ["config", "engagement", "parked-sends"] });
  const columns: Column<Send>[] = [
    { id: "time", header: "config.sends.time", cell: (s) => <DateTime instant={s.time} zone={tz} /> },
    { id: "trigger", header: "config.sends.trigger", cell: (s) => s.trigger },
    { id: "channel", header: "config.sends.channel", cell: (s) => `${s.channel} (${s.class})` },
    { id: "outcome", header: "config.sends.outcome", cell: (s) => word("config.crm.outcome", s.outcome) },
    { id: "reason", header: "config.sends.reason", cell: (s) => s.reason },
    ...(readOnly ? [] : [{ id: "act", header: "config.callbacks.act" as const, cell: (s: Send) => <SendActions s={s} done={done} /> }]),
  ];
  return (
    <ConfigPage heading="config.sends.title" note="config.sends.note">
      <div className="bo-field">
        <label htmlFor={searchId}>{t("config.sends.search")}</label>
        <input id={searchId} className="bo-input" defaultValue={search} onKeyDown={(e) => e.key === "Enter" && list.setFilter("search", e.currentTarget.value.trim())} onBlur={(e) => list.setFilter("search", e.currentTarget.value.trim())} />
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="config.sends.caption"
        columns={columns}
        rows={q.data?.sends}
        rowKey={(s) => s.sendId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
      />
    </ConfigPage>
  );
}
