// Fraud cases (T097; US7, parity *Fraud cases: list, review, dismiss*): players' cases by status, a case
// opened with every player it links and whether players still flags each as a suspected duplicate,
// reviewed, or dismissed with optional notes, which clears the flag on every player it links (players'
// `FraudRoutes.dismiss`); the dialog names those players before it is confirmed. players answers the
// list whole, unpaged (amendment P11), so the page pages what it answers.
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { flagOf, profileKey, profileQuery } from "../players/read.ts";
import { explain } from "../../../shared/refusals.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { RecordLink } from "../../components/links.tsx";
import { List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";

export interface FraudCase {
  caseId: string;
  playerIds: string[];
  signals: string[];
  status: "OPEN" | "REVIEWED" | "DISMISSED";
  detectedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNotes: string | null;
}

export const Statuses = ["OPEN", "REVIEWED", "DISMISSED", "ALL"] as const;
const PageSize = 50;

async function readCases(status: string): Promise<FraudCase[]> {
  const r = await players.getFraudCases({ query: { status } });
  if (!r.ok) throw explain(r.status, await r.text());
  return ((await r.json()) as { cases: FraudCase[] }).cases;
}


const columns = (tz: string): Column<FraudCase>[] => [
  { id: "case", header: "fraud.case", cell: (c) => <code>{c.caseId.slice(0, 8)}</code> },
  { id: "players", header: "fraud.players", cell: (c) => c.playerIds.join(", ") },
  { id: "signals", header: "fraud.signals", cell: (c) => c.signals.map((s) => word("fraud.signal", s)).join(", ") },
  { id: "status", header: "fraud.status", cell: (c) => <Status value={c.status.toLowerCase()} /> },
  { id: "detected", header: "fraud.detected", cell: (c) => <DateTime instant={c.detectedAt} zone={tz} /> },
  { id: "reviewed", header: "fraud.reviewed", cell: (c) => (c.reviewedAt ? <span>{t("fraud.reviewedBy", { operator: c.reviewedBy ?? "" })} <DateTime instant={c.reviewedAt} zone={tz} /></span> : null) },
];

function CaseDetail({ c, onDecided }: { c: FraudCase; onDecided(next: Partial<FraudCase>): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const flags = useQueries({ queries: c.playerIds.map((p) => ({ ...profileQuery(p), select: flagOf })) });
  const state = c.status.toLowerCase();
  const decided = async (answer: Response) => onDecided((await answer.clone().json().catch(() => ({}))) as Partial<FraudCase>);
  return (
    <section data-fraud-case={c.caseId} aria-labelledby={`fraud-${c.caseId}`}>
      <h2 id={`fraud-${c.caseId}`}>{t("fraud.caseTitle", { id: c.caseId })}</h2>
      <dl className="bo-facts">
        <dt>{t("fraud.status")}</dt>
        <dd>
          <Status value={state} />
        </dd>
        <dt>{t("fraud.signals")}</dt>
        <dd>{c.signals.map((s) => word("fraud.signal", s)).join(", ")}</dd>
        <dt>{t("fraud.detected")}</dt>
        <dd>
          <DateTime instant={c.detectedAt} zone={tz} />
        </dd>
        {c.reviewNotes ? (
          <>
            <dt>{t("fraud.notes")}</dt>
            <dd>{c.reviewNotes}</dd>
          </>
        ) : null}
      </dl>
      <h3>{t("fraud.players")}</h3>
      <ul>
        {c.playerIds.map((p, i) => {
          const f = flags[i]!;
          return (
            <li key={p} data-player={p}>
              <RecordLink to={{ kind: "player", id: p }}>{f.data?.username ?? p}</RecordLink>{" "}
              <span className="bo-status">{f.isError ? t("fraud.flagUnknown") : f.data === undefined ? t("list.loading") : f.data.flagged ? t("fraud.flagged") : t("fraud.notFlagged")}</span>
            </li>
          );
        })}
      </ul>
      {readOnly ? null : (
        <div className="bo-actions">
          <Action action={Actions["fraud.review"]} me={me} bags={bags} state={state} run={() => players.patchFraudCasesReview({ caseId: c.caseId })} onDone={(a) => void decided(a)} />
          <Action
            action={Actions["fraud.dismiss"]}
            me={me}
            bags={bags}
            state={state}
            subject={<p>{t("fraud.dismiss.clears", { players: c.playerIds.join(", ") })}</p>}
            run={(notes) => players.patchFraudCasesDismiss({ caseId: c.caseId }, { body: notes ? { notes } : {} })}
            onDone={(a) => void decided(a)}
          />
        </div>
      )}
    </section>
  );
}

export function FraudCasesPage() {
  const router = useRouter();
  const client = useQueryClient();
  const opened = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).case });
  const list = useListState(useRouterListStore());
  const status = list.state.filters.status ?? "OPEN";
  const statusId = useId();
  const tz = useZone();
  const freshness = useFreshness(["players"]);
  const q = useQuery({ queryKey: ["fraud", status], queryFn: () => readCases(status), refetchInterval: 30_000 });
  const caseId = typeof opened === "string" ? opened : undefined;
  // a case opened is kept as last read, so a decision that moves it out of this filter leaves it shown
  const [kept, setKept] = useState<FraudCase | undefined>(undefined);
  const inList = q.data?.find((c) => c.caseId === caseId);
  const all = useQuery({ queryKey: ["fraud", "ALL"], queryFn: () => readCases("ALL"), enabled: caseId !== undefined && q.data !== undefined && !inList && kept?.caseId !== caseId });
  const shownCase = kept?.caseId === caseId ? kept : (inList ?? all.data?.find((c) => c.caseId === caseId));

  const open = (c: FraudCase) => {
    setKept(c);
    void router.navigate({ to: router.state.location.pathname, search: (prev: Record<string, unknown>) => ({ ...prev, case: c.caseId }) } as never);
  };
  const decided = (base: FraudCase) => (next: Partial<FraudCase>) => {
    setKept({ ...base, ...next, caseId: base.caseId, playerIds: base.playerIds, signals: base.signals, detectedAt: base.detectedAt });
    void client.invalidateQueries({ queryKey: ["fraud"] });
    for (const p of base.playerIds) void client.invalidateQueries({ queryKey: profileKey(p) });
  };

  const rows = q.data;
  const page = rows?.slice((list.state.page - 1) * PageSize, list.state.page * PageSize);
  return (
    <section>
      <h1>{t("area.fraud")}</h1>
      <div className="bo-field">
        <label htmlFor={statusId}>{t("fraud.filter")}</label>
        <select id={statusId} value={status} onChange={(e) => list.setFilter("status", e.target.value === "OPEN" ? undefined : e.target.value)}>
          {Statuses.map((s) => (
            <option key={s} value={s}>
              {word("fraud.filter", s)}
            </option>
          ))}
        </select>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="fraud.caption"
        columns={columns(tz)}
        rows={page}
        rowKey={(c) => c.caseId}
        total={rows?.length}
        complete={rows === undefined ? false : list.state.page * PageSize >= rows.length}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={open}
        current={caseId}
      />
      {shownCase ? <CaseDetail key={shownCase.caseId} c={shownCase} onDecided={decided(shownCase)} /> : null}
    </section>
  );
}
