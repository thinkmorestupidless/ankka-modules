// The brand's payouts by period (parity *Payouts*; the programme's O40 to O42 and the export): one
// payout per affiliate and currency, never summed across currencies. A period is closed (again, when
// facts arrived after: the programme joins a close already running and answers 202 while it runs); a
// pending payout is approved or rejected, an approved one marked paid; anything else the programme
// refuses (`status_transition`). The export is the programme's CSV for the period, downloaded as is.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { useActiveAccounts } from "./links.tsx";
import { PeriodField, Read } from "./parts.tsx";
import { accountName, actorName, json, thisMonth, type Payout } from "./programme.ts";

/** The programme's export for a period, read through the backoffice's own route (a GET, as the signed-in operator). */
const exportHref = (period: string) => `/api/s/affiliate-programme/payouts/export?${new URLSearchParams({ period })}`;

function Row({ p, names, done }: { p: Payout; names: ReadonlyMap<string, string>; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const who = names.get(p.affiliateId) ?? p.affiliateId;
  const money = (minor: number) => <Money value={{ minor, currency: p.currency }} />;
  const subject = <p>{t("affiliates.payout.of", { name: who, period: p.period })}</p>;
  const by = p.paidBy ?? p.rejectedBy ?? p.approvedBy;
  const at = p.paidAt ?? p.approvedAt;
  return (
    <tr data-payout={p.payoutId}>
      <th scope="row">{who}</th>
      <td>{p.currency}</td>
      <td data-numeric>{money(p.cpaCommission)}</td>
      <td data-numeric>{money(p.revShareCommission)}</td>
      <td data-numeric>{money(p.overrideCommission)}</td>
      <td data-numeric>{money(p.adjustments)}</td>
      <td data-numeric>{money(p.totalPayable)}</td>
      <td>
        <Status value={p.status} />
        {p.rejectionReason ? <div className="bo-muted">{p.rejectionReason}</div> : null}
      </td>
      <td>
        {by ? <div>{actorName(by, me)}</div> : null}
        {at ? <DateTime instant={at} zone={tz} /> : null}
      </td>
      <td>
        {readOnly ? null : (
          <div className="bo-aff-actions">
            {(["programme.payout.approve", "programme.payout.reject", "programme.payout.pay"] as const).map((a) => (
              <Action
                key={a}
                action={Actions[a]}
                me={me}
                bags={bags}
                state={p.status}
                subject={subject}
                run={(reason) =>
                  programme.patchPayoutsStatus(
                    { payout: p.payoutId },
                    { body: { status: a === "programme.payout.approve" ? "approved" : a === "programme.payout.reject" ? "rejected" : "paid", ...(reason ? { reason } : {}) } },
                  )
                }
                onDone={done}
              />
            ))}
          </div>
        )}
      </td>
    </tr>
  );
}

export function PayoutsTable({ payouts, names, done }: { payouts: readonly Payout[]; names: ReadonlyMap<string, string>; done(): void }) {
  if (payouts.length === 0) return <p className="bo-muted">{t("affiliates.payout.none")}</p>;
  return (
    <div className="bo-aff-scroll">
      <table className="bo-aff-table">
        <caption className="bo-sr-only">{t("affiliates.section.payouts")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("affiliates.affiliate")}</th>
            <th scope="col">{t("affiliates.figure.currency")}</th>
            <th scope="col" data-numeric>
              {t("affiliates.figure.cpa")}
            </th>
            <th scope="col" data-numeric>
              {t("affiliates.figure.revShare")}
            </th>
            <th scope="col" data-numeric>
              {t("affiliates.figure.override")}
            </th>
            <th scope="col" data-numeric>
              {t("affiliates.figure.adjustments")}
            </th>
            <th scope="col" data-numeric>
              {t("affiliates.payout.total")}
            </th>
            <th scope="col">{t("affiliates.payout.status")}</th>
            <th scope="col">{t("affiliates.payout.by")}</th>
            <th scope="col">
              <span className="bo-sr-only">{t("affiliates.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {payouts.map((p) => (
            <Row key={p.payoutId} p={p} names={names} done={done} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function PayoutsSection() {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const zone = useZone();
  const [period, setPeriod] = useState(() => thisMonth(zone));
  const [closed, setClosed] = useState<string | undefined>(undefined);
  const q = useQuery({ queryKey: ["programme", "payouts", period], queryFn: () => json<Payout[]>(programme.getPayouts({ query: { period } })) });
  const accounts = useActiveAccounts();
  const names = new Map((accounts.data?.items ?? []).map((r) => [r.account.affiliateId, accountName(r.account)]));
  const done = () => void client.invalidateQueries({ queryKey: ["programme", "payouts"] });
  return (
    <div>
      <h2>{t("affiliates.section.payouts")}</h2>
      <div className="bo-aff-filters">
        <PeriodField
          value={period}
          onChange={(p) => {
            setPeriod(p);
            setClosed(undefined);
          }}
        />
        <a className="bo-button" href={exportHref(period)} download={`payouts-${period}.csv`}>
          {t("affiliates.payout.export")}
        </a>
        {readOnly ? null : (
          <Action
            action={Actions["programme.payouts.close"]}
            me={me}
            bags={bags}
            subject={<p>{t("affiliates.payout.closing", { period })}</p>}
            run={() => programme.postPayoutsClose({ query: { period } })}
            onDone={(answer) => {
              void answer
                .clone()
                .json()
                .then((a: { created?: number }) => setClosed(answer.status === 202 ? t("affiliates.payout.stillClosing", { period }) : t("affiliates.payout.closed", { period, count: a.created ?? 0 })))
                .catch(() => setClosed(undefined));
              done();
            }}
          />
        )}
      </div>
      {closed ? <p role="status">{closed}</p> : null}
      <ZoneNote zone={zone} />
      <Read q={q}>{(rows) => <PayoutsTable payouts={rows} names={names} done={done} />}</Read>
    </div>
  );
}
