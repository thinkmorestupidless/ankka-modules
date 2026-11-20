// The programme's dashboard (parity *Programme dashboard; reporting by campaign and country*): the
// programme's health now (active affiliates, applications waiting, payouts waiting for the month,
// fraud flags open), the brand's traffic and acquisition for a period with the change on the month
// before (DR-19: clicks, registrations, first deposits, qualified first deposits and the rates between
// them, from the programme's `traffic`, spec 030 A3), and its figures per currency, by campaign and by
// country.
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Glyph } from "../../components/glyph.tsx";
import { CountText, FiguresTable, PeriodField, Read } from "./parts.tsx";
import {
  change,
  json,
  monthBefore,
  rates,
  thisMonth,
  type AccountsPage,
  type FraudFlag,
  type Payout,
  type Revenue,
  type Traffic,
  type TrafficCounts,
} from "./programme.ts";
import { useZone } from "../../components/date.tsx";

interface CampaignRow {
  brandId: string;
  campaign: string;
  ftdCount: number;
}
interface GeoRow {
  brandId: string;
  country: string;
  registrationCount: number;
  ftdCount: number;
}

const counts = new Intl.NumberFormat("en-GB");

function Count({ heading, value }: { heading: MessageKey; value: number | undefined }) {
  return (
    <div className="bo-aff-card" data-count={heading}>
      <h3>{t(heading)}</h3>
      <p>{value === undefined ? t("affiliates.unread") : counts.format(value)}</p>
    </div>
  );
}

const percent = (value: number) => t("chart.percent", { value: value.toFixed(1) });

/** The change on the month before: an arrow and words, never colour alone; nothing when there was nothing before. */
function Change({ now, before }: { now: number; before: number | null | undefined }) {
  const c = before === undefined || before === null ? null : change(now, before);
  if (c === null) return null;
  const glyph = c.trend === "up" ? "up" : c.trend === "down" ? "down" : "minus";
  const rate = t("chart.percent", { value: c.percent });
  return (
    <span className="bo-change" data-trend={c.trend}>
      <Glyph name={glyph} />
      <span>{c.trend === "level" ? t("affiliates.traffic.level") : t(c.trend === "up" ? "affiliates.traffic.up" : "affiliates.traffic.down", { rate })}</span>
    </span>
  );
}

function Metric({ heading, children, sub, delta }: { heading: MessageKey; children: ReactNode; sub?: ReactNode; delta?: ReactNode }) {
  return (
    <div className="bo-aff-card" data-count={heading}>
      <h3>{t(heading)}</h3>
      <p>{children}</p>
      {sub !== undefined && <span className="bo-muted bo-aff-sub">{sub}</span>}
      {delta}
    </div>
  );
}

type Counted = keyof TrafficCounts;

const countCards: readonly { key: Counted; heading: MessageKey }[] = [
  { key: "clickCount", heading: "affiliates.traffic.clicks" },
  { key: "registrationCount", heading: "affiliates.traffic.registrations" },
  { key: "ftdCount", heading: "affiliates.traffic.ftds" },
  { key: "qftdCount", heading: "affiliates.traffic.qftds" },
];

const rateCards: readonly { rate: keyof typeof rates; heading: MessageKey; of: Counted; by: Counted }[] = [
  { rate: "conversion", heading: "affiliates.traffic.conversion", of: "ftdCount", by: "clickCount" },
  { rate: "clickToRegistration", heading: "affiliates.traffic.clickToRegistration", of: "registrationCount", by: "clickCount" },
  { rate: "registrationToFtd", heading: "affiliates.traffic.registrationToFtd", of: "ftdCount", by: "registrationCount" },
];

/** The period's traffic and acquisition, each with its change on the month before when that was read. */
export function TrafficCards({ now, before }: { now: Traffic; before: Traffic | undefined }) {
  return (
    <>
      <div className="bo-aff-cards" aria-label={t("affiliates.traffic.heading")} role="group">
        {countCards.map((c) => (
          <Metric key={c.key} heading={c.heading} delta={<Change now={now[c.key]} before={before?.[c.key]} />}>
            <CountText value={now[c.key]} />
          </Metric>
        ))}
        {rateCards.map((c) => {
          const value = rates[c.rate](now);
          return (
            <Metric
              key={c.rate}
              heading={c.heading}
              sub={value === null ? undefined : t("affiliates.traffic.ratio", { count: counts.format(now[c.of]), total: counts.format(now[c.by]) })}
              delta={value === null || before === undefined ? null : <Change now={value} before={rates[c.rate](before)} />}
            >
              {value === null ? <CountText value={undefined} /> : percent(value)}
            </Metric>
          );
        })}
      </div>
      <p className="bo-muted">{t("affiliates.traffic.notCounted")}</p>
    </>
  );
}

export function DashboardSection() {
  const zone = useZone();
  const [period, setPeriod] = useState(() => thisMonth(zone));
  const month = thisMonth(zone);
  const active = useQuery({ queryKey: ["programme", "accounts", "count", "active"], queryFn: () => json<AccountsPage>(programme.getAccounts({ query: { status: "active", pageSize: "1" } })) });
  const pending = useQuery({ queryKey: ["programme", "accounts", "count", "pending"], queryFn: () => json<AccountsPage>(programme.getAccounts({ query: { status: "pending", pageSize: "1" } })) });
  const payouts = useQuery({ queryKey: ["programme", "payouts", month], queryFn: () => json<Payout[]>(programme.getPayouts({ query: { period: month } })) });
  const flags = useQuery({ queryKey: ["programme", "fraud-flags"], queryFn: () => json<FraudFlag[]>(programme.getFraudFlags()) });
  const revenue = useQuery({ queryKey: ["programme", "revenue", period], queryFn: () => json<Revenue>(programme.getRevenue({ query: { period } })) });
  const traffic = useQuery({ queryKey: ["programme", "traffic", period], queryFn: () => json<Traffic>(programme.getTraffic({ query: { period } })) });
  const before = monthBefore(period);
  const trafficBefore = useQuery({ queryKey: ["programme", "traffic", before], queryFn: () => json<Traffic>(programme.getTraffic({ query: { period: before } })) });
  const campaigns = useQuery({ queryKey: ["programme", "revenue", "campaigns", period], queryFn: () => json<CampaignRow[]>(programme.getRevenueByCampaign({ query: { period } })) });
  const geo = useQuery({ queryKey: ["programme", "revenue", "geo", period], queryFn: () => json<GeoRow[]>(programme.getRevenueGeo({ query: { period } })) });

  return (
    <div>
      <h2>{t("affiliates.dashboard.health")}</h2>
      <div className="bo-aff-cards">
        <Count heading="affiliates.dashboard.active" value={active.data?.total} />
        <Count heading="affiliates.dashboard.pending" value={pending.data?.total} />
        <Count heading="affiliates.dashboard.payoutsPending" value={payouts.data?.filter((p) => p.status === "pending").length} />
        <Count heading="affiliates.dashboard.flagsOpen" value={flags.data?.filter((f) => !f.dismissed).length} />
      </div>
      <h2>{t("affiliates.dashboard.figures")}</h2>
      <div className="bo-aff-filters">
        <PeriodField value={period} onChange={setPeriod} />
      </div>
      <h3>{t("affiliates.traffic.heading")}</h3>
      <Read q={traffic}>{(now) => <TrafficCards now={now} before={trafficBefore.data} />}</Read>
      <Read q={revenue}>{(r) => <FiguresTable figures={r.figures} caption="affiliates.dashboard.figures" />}</Read>
      <h3>{t("affiliates.dashboard.byCampaign")}</h3>
      <Read q={campaigns}>
        {(rows) =>
          rows.length === 0 ? (
            <p className="bo-muted">{t("affiliates.noFigures")}</p>
          ) : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.dashboard.byCampaign")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.campaign")}</th>
                  <th scope="col" data-numeric>
                    {t("affiliates.figure.ftds")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.campaign}>
                    <th scope="row">{c.campaign}</th>
                    <td data-numeric><CountText value={c.ftdCount} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </Read>
      <h3>{t("affiliates.dashboard.byCountry")}</h3>
      <Read q={geo}>
        {(rows) =>
          rows.length === 0 ? (
            <p className="bo-muted">{t("affiliates.noFigures")}</p>
          ) : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.dashboard.byCountry")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.country")}</th>
                  <th scope="col" data-numeric>
                    {t("affiliates.figure.registrations")}
                  </th>
                  <th scope="col" data-numeric>
                    {t("affiliates.figure.ftds")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((g) => (
                  <tr key={g.country}>
                    <th scope="row">{g.country}</th>
                    <td data-numeric><CountText value={g.registrationCount} /></td>
                    <td data-numeric><CountText value={g.ftdCount} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </Read>
    </div>
  );
}
