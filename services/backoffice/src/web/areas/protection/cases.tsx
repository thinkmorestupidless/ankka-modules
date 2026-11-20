// Responsible gambling cases (T096; US7, parity *Cases list; notes; close*). A player's cases come with
// responsible-gambling's read of the player: each with its notes, a note added, and closed with an
// optional note; a case opened by the operator. `/protection` is the brand's cases, responsible-
// gambling's `cases?status=` (amendment G2), open ones by default and oldest first as it answers them,
// each linking to the player's protection, where its notes are, and closed from the list.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { responsibleGambling as rg } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { RecordLink } from "../../components/links.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";
import { CaseTypes, type Case } from "./rg.ts";


function CaseRow({ player, c, done }: { player: string; c: Case; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  return (
    <tr data-case={c.caseId}>
      <th scope="row">{word("protection.case", c.caseType)}</th>
      <td>
        <Status value={c.status} />
      </td>
      <td>{word("protection.trigger", c.trigger)}</td>
      <td>
        <DateTime instant={c.openedAt} zone={tz} />
      </td>
      <td>{c.closedAt ? <DateTime instant={c.closedAt} zone={tz} /> : c.closesAt ? <span>{t("protection.case.closes")} <DateTime instant={c.closesAt} zone={tz} /></span> : null}</td>
      <td>
        {c.notes.length === 0 && !c.closeNote ? <span className="bo-muted">{t("protection.case.noNotes")}</span> : null}
        <ul className="bo-notes">
          {c.notes.map((n, i) => (
            <li key={`${n.at}-${i}`}>
              <DateTime instant={n.at} zone={tz} /> <span>{t("protection.case.noteBy", { operator: n.operatorId })}</span> <span>{n.text}</span>
            </li>
          ))}
          {c.closeNote ? <li>{t("protection.case.closeNote", { note: c.closeNote })}</li> : null}
        </ul>
      </td>
      <td>
        {readOnly ? null : (
          <div className="bo-actions">
            <Action action={Actions["protection.noteCase"]} me={me} bags={bags} state={c.status} run={(note) => rg.postCasesNotes({ player, caseId: c.caseId }, { body: { note } })} onDone={done} />
            <Action
              action={Actions["protection.closeCase"]}
              me={me}
              bags={bags}
              state={c.status}
              subject={<p>{word("protection.case", c.caseType)}</p>}
              run={(reason) => rg.postCasesClose({ player, caseId: c.caseId }, { body: reason ? { reason } : {} })}
              onDone={done}
            />
          </div>
        )}
      </td>
    </tr>
  );
}

/** A player's cases, newest first as opened, with a case opened by the operator. */
export function PlayerCases({ player, cases, done }: { player: string; cases: readonly Case[]; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const typeId = useId();
  const [caseType, setCaseType] = useState("");
  const shown = [...cases].sort((a, b) => b.openedAt.localeCompare(a.openedAt));
  return (
    <section aria-labelledby="protection-cases">
      <h3 id="protection-cases">{t("protection.cases")}</h3>
      {shown.length === 0 ? (
        <p className="bo-muted">{t("protection.cases.none")}</p>
      ) : (
        <table className="bo-table">
          <thead>
            <tr>
              <th scope="col">{t("protection.case.type")}</th>
              <th scope="col">{t("protection.case.status")}</th>
              <th scope="col">{t("protection.case.trigger")}</th>
              <th scope="col">{t("protection.case.opened")}</th>
              <th scope="col">{t("protection.case.closed")}</th>
              <th scope="col">{t("protection.case.notes")}</th>
              <th scope="col">{t("identity.document.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((c) => (
              <CaseRow key={c.caseId} player={player} c={c} done={done} />
            ))}
          </tbody>
        </table>
      )}
      {readOnly ? null : (
        <Action
          action={Actions["protection.openCase"]}
          me={me}
          bags={bags}
          ready={caseType !== ""}
          subject={
            <div className="bo-field">
              <label htmlFor={typeId}>{t("protection.case.type")}</label>
              <select id={typeId} value={caseType} onChange={(e) => setCaseType(e.target.value)}>
                <option value="">{t("protection.case.choose")}</option>
                {CaseTypes.map((c) => (
                  <option key={c} value={c}>
                    {word("protection.case", c)}
                  </option>
                ))}
              </select>
            </div>
          }
          run={(note) => rg.postCases({ player }, { body: { caseType, ...(note ? { note } : {}) } })}
          onDone={() => {
            setCaseType("");
            done();
          }}
        />
      )}
    </section>
  );
}

/** A case as responsible-gambling lists the brand's (`GET cases`): no notes, which the player's read carries. */
export interface ListedCase {
  caseId: string;
  playerId: string;
  caseType: string;
  trigger: string;
  status: string;
  openedAt: string;
  closesAt: string | null;
  closedAt: string | null;
  closeReason: string | null;
}

const PageSize = 50;
/** The `status` filter: open (the default here, as the original's pending cases), closed, or every case. */
const Shown = ["open", "closed", "all"] as const;

/** `/protection`: the brand's cases (G2), the filter and page in the URL. */
export function ProtectionCasesPage() {
  const tz = useZone();
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const freshness = useFreshness(["responsible-gambling"]);
  const statusId = useId();
  const status = list.state.filters.status ?? "open";
  const query = { status, page: String(list.state.page), pageSize: String(PageSize) };
  const q = useQuery({
    queryKey: ["responsible-gambling", "cases", query],
    queryFn: () => readJson<{ cases: ListedCase[]; total: number }>(rg.getCases({ query })),
    placeholderData: (prev) => prev,
  });
  const done = () => void client.invalidateQueries({ queryKey: ["responsible-gambling"] });
  const columns: Column<ListedCase>[] = [
    { id: "player", header: "protection.cases.player", cell: (c) => <RecordLink to={{ kind: "case", player: c.playerId, id: c.caseId }}>{c.playerId}</RecordLink> },
    { id: "type", header: "protection.case.type", cell: (c) => word("protection.case", c.caseType) },
    { id: "trigger", header: "protection.case.trigger", cell: (c) => word("protection.trigger", c.trigger) },
    { id: "opened", header: "protection.case.opened", cell: (c) => <DateTime instant={c.openedAt} zone={tz} /> },
    {
      id: "closes",
      header: "protection.case.closed",
      cell: (c) =>
        c.closedAt ? (
          <DateTime instant={c.closedAt} zone={tz} />
        ) : c.closesAt ? (
          <span>
            {t("protection.case.closes")} <DateTime instant={c.closesAt} zone={tz} />
          </span>
        ) : null,
    },
    { id: "status", header: "protection.case.status", cell: (c) => <Status value={c.status} /> },
    {
      id: "actions",
      header: "identity.document.actions",
      cell: (c) =>
        readOnly ? null : (
          <Action
            action={Actions["protection.closeCase"]}
            me={me}
            bags={bags}
            state={c.status}
            subject={<p>{word("protection.case", c.caseType)} · {c.playerId}</p>}
            run={(reason) => rg.postCasesClose({ player: c.playerId, caseId: c.caseId }, { body: reason ? { reason } : {} })}
            onDone={done}
          />
        ),
    },
  ];
  return (
    <section>
      <h1>{t("area.protection")}</h1>
      <div role="search" className="bo-players-filters">
        <div className="bo-field">
          <label htmlFor={statusId}>{t("protection.case.status")}</label>
          <select id={statusId} value={status} onChange={(e) => list.setFilter("status", e.target.value === "open" ? undefined : e.target.value)}>
            {Shown.map((s) => (
              <option key={s} value={s}>
                {t(`protection.cases.shown.${s}`)}
              </option>
            ))}
          </select>
        </div>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="protection.cases.caption"
        columns={columns}
        rows={q.data?.cases}
        rowKey={(c) => c.caseId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
      />
    </section>
  );
}
