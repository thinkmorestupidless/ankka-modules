// The brand's affiliate accounts (parity *Affiliates*; the programme's accounts page, T116): paged by
// the programme (`{items, total, page, pageSize}`), filtered by status at this brand and a search over
// name and email, sorted by the programme's names; each row with this month's total commission per
// currency. An account is opened at /affiliates/accounts/<id>. A manager makes an account active at once
// (the programme's create): a new email is an account sent an invitation to set its own password; an
// email already an affiliate of another brand is invited to this one instead (202).
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { List, completeOf, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { usePlans } from "./plans.tsx";
import { accountName, json, percentOf, refusalOf, type AccountRow, type AccountsPage } from "./programme.ts";

const PageSize = 50;
const Statuses = ["pending", "active", "suspended", "rejected"] as const;

function NewAccount({ done }: { done(created: string | undefined): void }) {
  const me = useOperator();
  const bags = useBags();
  const plans = usePlans();
  const id = { email: useId(), name: useId(), display: useId(), company: useId(), site: useId(), plan: useId(), parent: useId(), override: useId() };
  const empty = { email: "", name: "", displayName: "", companyName: "", website: "", planId: "", parentAffiliateId: "", overridePercent: "" };
  const [f, setF] = useState(empty);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const text = (v: string) => (v.trim() === "" ? undefined : v.trim());
  const override = f.overridePercent.trim() === "" ? undefined : percentOf(f.overridePercent);
  const valid = /^\S+@\S+$/.test(f.email.trim()) && (f.overridePercent.trim() === "" || override !== undefined);
  const body = {
    email: f.email.trim(),
    name: text(f.name),
    displayName: text(f.displayName),
    companyName: text(f.companyName),
    website: text(f.website),
    planId: text(f.planId),
    parentAffiliateId: text(f.parentAffiliateId),
    overridePercent: override,
  };
  return (
    <div className="bo-aff-form" data-new-account>
      <div className="bo-field">
        <label htmlFor={id.email}>{t("affiliates.email")}</label>
        <input id={id.email} type="email" autoComplete="off" value={f.email} onChange={set("email")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.name}>{t("affiliates.account.name")}</label>
        <input id={id.name} value={f.name} onChange={set("name")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.display}>{t("affiliates.account.displayName")}</label>
        <input id={id.display} value={f.displayName} onChange={set("displayName")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.company}>{t("affiliates.account.company")}</label>
        <input id={id.company} value={f.companyName} onChange={set("companyName")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.site}>{t("affiliates.account.website")}</label>
        <input id={id.site} value={f.website} onChange={set("website")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.plan}>{t("affiliates.account.plan")}</label>
        <select id={id.plan} value={f.planId} onChange={set("planId")}>
          <option value="">{t("affiliates.account.defaultPlan")}</option>
          {(plans.data ?? []).map((p) => (
            <option key={p.planId} value={p.planId}>
              {p.planName}
            </option>
          ))}
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={id.parent}>{t("affiliates.account.parent")}</label>
        <input id={id.parent} value={f.parentAffiliateId} onChange={set("parentAffiliateId")} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.override}>{t("affiliates.account.override")}</label>
        <input id={id.override} inputMode="decimal" value={f.overridePercent} onChange={set("overridePercent")} />
      </div>
      <div className="bo-aff-actions bo-field-wide">
        <Action
          action={Actions["programme.account.create"]}
          me={me}
          bags={bags}
          ready={valid}
          subject={<p>{t("affiliates.account.creating", { email: f.email.trim() })}</p>}
          run={() => programme.postAccounts({ body })}
          onDone={(answer) => {
            setF(empty);
            void answer
              .clone()
              .json()
              .then((a: { affiliateId?: string }) => done(a.affiliateId))
              .catch(() => done(undefined));
          }}
        />
      </div>
    </div>
  );
}

export function AccountsSection() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const list = useListState(useRouterListStore());
  const ids = { status: useId(), search: useId() };
  const tz = useZone();
  const { filters, sort, page } = list.state;
  const [search, setSearch] = useState(filters.search ?? "");
  const [invited, setInvited] = useState(false);
  const query: Record<string, string> = { page: String(page), pageSize: String(PageSize) };
  if (filters.status) query.status = filters.status;
  if (filters.search) query.search = filters.search;
  if (sort) {
    query.sortBy = sort.replace(/^-/, "");
    query.sortDir = sort.startsWith("-") ? "desc" : "asc";
  }
  const q = useQuery({ queryKey: ["programme", "accounts", query], queryFn: () => json<AccountsPage>(programme.getAccounts({ query })), placeholderData: (prev) => prev });
  const plans = usePlans();
  const planName = (planId: string | null) => (planId ? (plans.data?.find((p) => p.planId === planId)?.planName ?? planId) : "");
  const columns: Column<AccountRow>[] = [
    { id: "name", header: "affiliates.account.name", sort: "name", cell: (r) => accountName(r.account) },
    { id: "email", header: "affiliates.email", sort: "email", cell: (r) => r.account.email ?? "" },
    { id: "status", header: "affiliates.account.status", sort: "status", cell: (r) => (r.account.status ? <Status value={r.account.status} /> : null) },
    { id: "plan", header: "affiliates.account.plan", cell: (r) => planName(r.account.planId) },
    {
      id: "commission",
      header: "affiliates.account.thisMonth",
      numeric: true,
      cell: (r) =>
        r.currentMonth.filter((f) => f.currency).map((f) => (
          <div key={f.currency!}>
            <Money value={{ minor: f.totalCommission, currency: f.currency! }} />
          </div>
        )),
    },
    { id: "created", header: "affiliates.created", sort: "createdAt", cell: (r) => (r.account.createdAt ? <DateTime instant={r.account.createdAt} zone={tz} /> : null) },
  ];
  return (
    <div>
      <h2>{t("affiliates.section.accounts")}</h2>
      <div className="bo-aff-filters">
        <div className="bo-field">
          <label htmlFor={ids.status}>{t("affiliates.account.status")}</label>
          <select id={ids.status} value={filters.status ?? ""} onChange={(e) => list.setFilter("status", e.target.value)}>
            <option value="">{t("affiliates.account.anyStatus")}</option>
            {Statuses.map((s) => (
              <option key={s} value={s}>
                {word("status", s)}
              </option>
            ))}
          </select>
        </div>
        <form
          className="bo-field"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            list.setFilter("search", search.trim());
          }}
        >
          <label htmlFor={ids.search}>{t("affiliates.account.search")}</label>
          <input id={ids.search} type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
        </form>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="affiliates.section.accounts"
        columns={columns}
        rows={q.data?.items}
        rowKey={(r) => r.account.affiliateId}
        total={q.data?.total}
        complete={q.data ? completeOf(q.data) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
        onOpen={(r) => void navigate({ to: "/affiliates/accounts/$affiliate", params: { affiliate: r.account.affiliateId } } as never)}
      />
      {readOnly ? null : (
        <>
          <h3>{t("affiliates.account.new")}</h3>
          <NewAccount
            done={(created) => {
              void client.invalidateQueries({ queryKey: ["programme", "accounts"] });
              if (created) void navigate({ to: "/affiliates/accounts/$affiliate", params: { affiliate: created } } as never);
              else setInvited(true);
            }}
          />
          {invited ? <p role="status">{t("affiliates.account.invited")}</p> : null}
        </>
      )}
    </div>
  );
}
