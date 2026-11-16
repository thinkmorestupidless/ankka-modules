// The programme's dashboard (parity *Programme dashboard; reporting by campaign and country*): the
// programme's health now (active affiliates, applications waiting, payouts waiting for the month,
// fraud flags open) and the brand's figures for a period, per currency, by campaign and by country.
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { FiguresTable, PeriodField, Read } from "./parts.tsx";
import { json, thisMonth, type AccountsPage, type FraudFlag, type Payout, type Revenue } from "./programme.ts";
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

export function DashboardSection() {
  const zone = useZone();
  const [period, setPeriod] = useState(() => thisMonth(zone));
  const month = thisMonth(zone);
  const active = useQuery({ queryKey: ["programme", "accounts", "count", "active"], queryFn: () => json<AccountsPage>(programme.getAccounts({ query: { status: "active", pageSize: "1" } })) });
  const pending = useQuery({ queryKey: ["programme", "accounts", "count", "pending"], queryFn: () => json<AccountsPage>(programme.getAccounts({ query: { status: "pending", pageSize: "1" } })) });
  const payouts = useQuery({ queryKey: ["programme", "payouts", month], queryFn: () => json<Payout[]>(programme.getPayouts({ query: { period: month } })) });
  const flags = useQuery({ queryKey: ["programme", "fraud-flags"], queryFn: () => json<FraudFlag[]>(programme.getFraudFlags()) });
  const revenue = useQuery({ queryKey: ["programme", "revenue", period], queryFn: () => json<Revenue>(programme.getRevenue({ query: { period } })) });
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
                    <td data-numeric>{counts.format(c.ftdCount)}</td>
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
                    <td data-numeric>{counts.format(g.registrationCount)}</td>
                    <td data-numeric>{counts.format(g.ftdCount)}</td>
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
