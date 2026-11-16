// The brand's fraud flags (parity *Fraud flags*; the programme's O53, O54): a player an affiliate
// referred whose bonus deductions or chargebacks passed the brand's threshold in a period. The player is
// the programme's pseudonym, never a name. A flag is dismissed with a reason, which the programme keeps
// with the manager who gave it; a dismissed flag shows both.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { json, refusalOf, actorName, type FraudFlag } from "./programme.ts";
import { RecordLink } from "../../components/links.tsx";

const Shows = ["open", "dismissed", "all"] as const;
const stateOf = (f: FraudFlag) => (f.dismissed ? "dismissed" : "open");

function FlagDetail({ flag, done }: { flag: FraudFlag; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  return (
    <article className="bo-aff-detail" data-flag={flag.flagId} aria-labelledby={`flag-${flag.flagId}`}>
      <h3 id={`flag-${flag.flagId}`}>{t("affiliates.flag.title", { player: flag.playerId })}</h3>
      <dl className="bo-aff-facts">
        <dt>{t("affiliates.flag.state")}</dt>
        <dd>
          <Status value={stateOf(flag)} />
        </dd>
        <dt>{t("affiliates.flag.reason")}</dt>
        <dd>{word("affiliates.flagReason", flag.reason)}</dd>
        <dt>{t("affiliates.flag.description")}</dt>
        <dd>{flag.description}</dd>
        <dt>{t("affiliates.period")}</dt>
        <dd>{flag.period}</dd>
        <dt>{t("affiliates.affiliate")}</dt>
        <dd>
          <code>{flag.affiliateId}</code>
        </dd>
        {flag.flaggedAt ? (
          <>
            <dt>{t("affiliates.flag.flaggedAt")}</dt>
            <dd>
              <DateTime instant={flag.flaggedAt} zone={tz} />
            </dd>
          </>
        ) : null}
        {flag.dismissed ? (
          <>
            <dt>{t("affiliates.flag.dismissedBy")}</dt>
            <dd>{actorName(flag.dismissedBy, me)}</dd>
            {flag.dismissedAt ? (
              <>
                <dt>{t("affiliates.flag.dismissedAt")}</dt>
                <dd>
                  <DateTime instant={flag.dismissedAt} zone={tz} />
                </dd>
              </>
            ) : null}
            <dt>{t("affiliates.flag.dismissalReason")}</dt>
            <dd>{flag.dismissalReason ?? ""}</dd>
          </>
        ) : null}
      </dl>
      {readOnly ? null : (
        <Action
          action={Actions["programme.flag.dismiss"]}
          me={me}
          bags={bags}
          state={stateOf(flag)}
          subject={<p>{t("affiliates.flag.title", { player: flag.playerId })}</p>}
          run={(reason) => programme.patchFraudFlagsDismiss({ flag: flag.flagId }, { body: { reason: reason ?? "" } })}
          onDone={done}
        />
      )}
    </article>
  );
}

export function FraudSection() {
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const show = (list.state.filters.show as (typeof Shows)[number] | undefined) ?? "open";
  const opened = list.state.filters.flag;
  const tz = useZone();
  const q = useQuery({ queryKey: ["programme", "fraud-flags"], queryFn: () => json<FraudFlag[]>(programme.getFraudFlags()) });
  const rows = q.data?.filter((f) => show === "all" || stateOf(f) === show);
  const columns: Column<FraudFlag>[] = [
    { id: "player", header: "affiliates.flag.player", cell: (f) => <RecordLink to={{ kind: "player", id: f.playerId }}>{f.playerId}</RecordLink> },
    { id: "reason", header: "affiliates.flag.reason", cell: (f) => word("affiliates.flagReason", f.reason) },
    { id: "period", header: "affiliates.period", cell: (f) => f.period },
    { id: "affiliate", header: "affiliates.affiliate", cell: (f) => <code>{f.affiliateId}</code> },
    { id: "at", header: "affiliates.flag.flaggedAt", cell: (f) => (f.flaggedAt ? <DateTime instant={f.flaggedAt} zone={tz} /> : null) },
    { id: "state", header: "affiliates.flag.state", cell: (f) => <Status value={stateOf(f)} /> },
  ];
  const flag = q.data?.find((f) => f.flagId === opened);
  return (
    <div>
      <h2>{t("affiliates.section.fraud")}</h2>
      <div className="bo-aff-filters" role="group" aria-label={t("affiliates.flag.show")}>
        {Shows.map((s) => (
          <button key={s} type="button" className="bo-aff-pill" aria-pressed={s === show} onClick={() => list.setFilter("show", s === "open" ? undefined : s)}>
            {t(`affiliates.flag.show.${s}`)}
          </button>
        ))}
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
          caption="affiliates.section.fraud"
          columns={columns}
          rows={rows}
          rowKey={(f) => f.flagId}
          total={rows?.length}
          complete
          fetching={q.isFetching}
          zone={tz}
          list={list}
          current={opened}
        onOpen={(f) => list.setFilter("flag", f.flagId)}
      />
      {flag ? <FlagDetail key={flag.flagId} flag={flag} done={() => void client.invalidateQueries({ queryKey: ["programme", "fraud-flags"] })} /> : null}
    </div>
  );
}
