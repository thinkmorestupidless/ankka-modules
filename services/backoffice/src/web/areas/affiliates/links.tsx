// The brand's tracking links (parity *Tracking links*; the programme's O35, O36): every link of the
// brand with its affiliate, campaign, signed tag, the landing address carrying it, and its counts folded
// from the facts; a link made for an affiliate with an active membership here (the programme refuses
// another, `not_a_member`).
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Read } from "./parts.tsx";
import { accountName, json, zone, type AccountsPage, type Link } from "./programme.ts";

const counts = new Intl.NumberFormat("en-GB");

/** The brand's active affiliates, for a picker (the programme pages at most 200). */
export function useActiveAccounts() {
  return useQuery({ queryKey: ["programme", "accounts", "active-picker"], queryFn: () => json<AccountsPage>(programme.getAccounts({ query: { status: "active", pageSize: "200", sortBy: "name", sortDir: "asc" } })) });
}

export function LinksTable({ links, names }: { links: readonly Link[]; names?: ReadonlyMap<string, string> }) {
  const tz = zone();
  if (links.length === 0) return <p className="bo-muted">{t("affiliates.link.none")}</p>;
  return (
    <div className="bo-aff-scroll">
      <table className="bo-aff-table">
        <caption className="bo-sr-only">{t("affiliates.section.links")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("affiliates.link.btag")}</th>
            {names ? <th scope="col">{t("affiliates.affiliate")}</th> : null}
            <th scope="col">{t("affiliates.campaign")}</th>
            <th scope="col">{t("affiliates.link.landing")}</th>
            <th scope="col" data-numeric>
              {t("affiliates.link.clicks")}
            </th>
            <th scope="col" data-numeric>
              {t("affiliates.figure.registrations")}
            </th>
            <th scope="col">{t("affiliates.created")}</th>
          </tr>
        </thead>
        <tbody>
          {links.map((l) => (
            <tr key={l.btag} data-link={l.btag}>
              <th scope="row">
                <code>{l.btag}</code>
              </th>
              {names ? <td>{names.get(l.affiliateId) ?? l.affiliateId}</td> : null}
              <td>{l.campaign ?? ""}</td>
              <td>
                <code>{l.landingUrl ?? l.landingPath ?? ""}</code>
              </td>
              <td data-numeric>{counts.format(l.clicks)}</td>
              <td data-numeric>{counts.format(l.registrations)}</td>
              <td>{l.createdAt ? <DateTime instant={l.createdAt} zone={tz} /> : null}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function NewLink({ done }: { done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const accounts = useActiveAccounts();
  const ids = { aff: useId(), campaign: useId(), landing: useId(), source: useId(), medium: useId(), utm: useId(), subs: useId() };
  const [f, setF] = useState({ affiliateId: "", campaign: "", landingPath: "", utmSource: "", utmMedium: "", utmCampaign: "", subIds: "" });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const text = (v: string) => (v.trim() === "" ? undefined : v.trim());
  const body = {
    affiliateId: f.affiliateId,
    campaign: text(f.campaign),
    landingPath: text(f.landingPath),
    utmSource: text(f.utmSource),
    utmMedium: text(f.utmMedium),
    utmCampaign: text(f.utmCampaign),
    subIds: f.subIds.split(",").map((s) => s.trim()).filter(Boolean),
  };
  const chosen = accounts.data?.items.find((r) => r.account.affiliateId === f.affiliateId);
  return (
    <div className="bo-aff-form">
      <div className="bo-field">
        <label htmlFor={ids.aff}>{t("affiliates.affiliate")}</label>
        <select id={ids.aff} value={f.affiliateId} onChange={set("affiliateId")}>
          <option value="">{t("affiliates.choose")}</option>
          {accounts.data?.items.map((r) => (
            <option key={r.account.affiliateId} value={r.account.affiliateId}>
              {accountName(r.account)}
            </option>
          ))}
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={ids.campaign}>{t("affiliates.campaign")}</label>
        <input id={ids.campaign} value={f.campaign} onChange={set("campaign")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.landing}>{t("affiliates.link.landingPath")}</label>
        <input id={ids.landing} value={f.landingPath} onChange={set("landingPath")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.subs}>{t("affiliates.link.subIds")}</label>
        <input id={ids.subs} value={f.subIds} onChange={set("subIds")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.source}>{t("affiliates.link.utmSource")}</label>
        <input id={ids.source} value={f.utmSource} onChange={set("utmSource")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.medium}>{t("affiliates.link.utmMedium")}</label>
        <input id={ids.medium} value={f.utmMedium} onChange={set("utmMedium")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.utm}>{t("affiliates.link.utmCampaign")}</label>
        <input id={ids.utm} value={f.utmCampaign} onChange={set("utmCampaign")} />
      </div>
      <div className="bo-aff-actions bo-field-wide">
        <Action
          action={Actions["programme.link.create"]}
          me={me}
          bags={bags}
          ready={f.affiliateId !== ""}
          subject={chosen ? <p>{t("affiliates.link.for", { name: accountName(chosen.account) })}</p> : undefined}
          run={() => programme.postTrackingLinks({ body })}
          onDone={() => {
            setF({ affiliateId: "", campaign: "", landingPath: "", utmSource: "", utmMedium: "", utmCampaign: "", subIds: "" });
            done();
          }}
        />
      </div>
    </div>
  );
}

export function LinksSection() {
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const q = useQuery({ queryKey: ["programme", "links"], queryFn: () => json<Link[]>(programme.getTrackingLinks()) });
  const accounts = useActiveAccounts();
  const names = new Map((accounts.data?.items ?? []).map((r) => [r.account.affiliateId, accountName(r.account)]));
  return (
    <div>
      <h2>{t("affiliates.section.links")}</h2>
      <ZoneNote zone={zone()} />
      <Read q={q}>{(links) => <LinksTable links={links} names={names} />}</Read>
      {readOnly ? null : (
        <>
          <h3>{t("affiliates.link.new")}</h3>
          <NewLink done={() => void client.invalidateQueries({ queryKey: ["programme", "links"] })} />
        </>
      )}
    </div>
  );
}
