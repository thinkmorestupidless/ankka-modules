// The payments hierarchy (T112; spec 004 FR-001, FR-002; the original's hierarchy page): the holdings,
// companies and sub-brands merchant answers this operator, under their level. A holding and a company are
// made and their status changed at the installation's level only (merchant refuses anyone else
// `INSUFFICIENT_SCOPE`); a sub-brand is made under a company the operator's level covers, a casino's naming
// its brand. Statuses are merchant's: Active, Suspended, Archived. Choosing a row works at it.
//
// Where payments work is done (T112, FR-004): the level picker and its banner, kept to the payments area
// only. merchant admits an operator at their own level and answers only the holdings, companies and
// sub-brands under it; the picker narrows the area's pages to one of those, as the original's scope
// selector did, and the banner says which one every page acts on until it is cleared. The choice is the
// URL's (`?holding=`, `?company=`, `?subBrand=`), so a link or a return keeps it. With nothing chosen, the
// sub-brand that names this brand is the one worked on, when there is exactly one.
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { read } from "../configuration/page.tsx";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, useZone } from "../../components/date.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Choice, Field, Rows, useChanged, type Col } from "./parts.tsx";

export interface Holding {
  holdingId: string;
  name: string;
  status: string;
  createdAt: string;
}
export interface Company {
  companyId: string;
  holdingId?: string;
  name: string;
  status: string;
  createdAt: string;
}
export interface SubBrand {
  subBrandId?: string;
  /** Some answers (and the doubles) name it `id`. */
  id?: string;
  companyId?: string;
  holdingId?: string;
  name: string;
  type?: string;
  status: string;
  brand?: string | null;
  createdAt?: string;
}

export const subBrandIdOf = (s: SubBrand): string => s.subBrandId ?? s.id ?? "";

/** The level a routing tree is kept at, as merchant names it. */
export type Level = "holding" | "company" | "subBrand";

export interface Scope {
  level: Level | null;
  id: string | null;
  name: string | null;
  holding?: string;
  company?: string;
  subBrand?: string;
  /** Whether the operator chose it, rather than the brand's own sub-brand being taken. */
  chosen: boolean;
}

/** The hierarchy merchant answers this operator: each list, or why it did not answer. */
export function useHierarchy() {
  const me = useOperator();
  const enabled = holds(me, "payments.read");
  const holdings = useQuery({ queryKey: ["payments", "holdings"], queryFn: () => read<{ holdings: Holding[] }>(merchant.getHoldings()), enabled, retry: false });
  const companies = useQuery({ queryKey: ["payments", "companies"], queryFn: () => read<{ companies: Company[] }>(merchant.getCompanies()), enabled, retry: false });
  const subBrands = useQuery({ queryKey: ["payments", "sub-brands"], queryFn: () => read<{ subBrands: SubBrand[] }>(merchant.getSubBrands()), enabled, retry: false });
  return { holdings, companies, subBrands };
}

const str = (v: unknown) => (typeof v === "string" && v !== "" ? v : undefined);

/** The scope the area works at: the URL's choice, else the brand's own sub-brand. */
export function useScope(): Scope {
  const me = useOperator();
  const search = useRouterState({ select: (s) => s.location.search as Record<string, unknown> });
  const { holdings, companies, subBrands } = useHierarchy();
  const holding = str(search.holding);
  const company = str(search.company);
  const subBrand = str(search.subBrand);
  const nameOf = <T,>(rows: T[] | undefined, id: string, key: (r: T) => string, name: (r: T) => string) => {
    const r = rows?.find((x) => key(x) === id);
    return r ? name(r) : id;
  };
  if (subBrand)
    return { level: "subBrand", id: subBrand, name: nameOf(subBrands.data?.subBrands, subBrand, subBrandIdOf, (s) => s.name), holding, company, subBrand, chosen: true };
  if (company) return { level: "company", id: company, name: nameOf(companies.data?.companies, company, (c) => c.companyId, (c) => c.name), holding, company, chosen: true };
  if (holding) return { level: "holding", id: holding, name: nameOf(holdings.data?.holdings, holding, (h) => h.holdingId, (h) => h.name), holding, chosen: true };
  const own = (subBrands.data?.subBrands ?? []).filter((s) => s.brand === me.brand.slug);
  if (own.length === 1) {
    const s = own[0]!;
    return { level: "subBrand", id: subBrandIdOf(s), name: s.name, subBrand: subBrandIdOf(s), holding: s.holdingId, company: s.companyId, chosen: false };
  }
  return { level: null, id: null, name: null, chosen: false };
}

/** Sets the scope in the URL, keeping the page's other search parameters. */
export function useSetScope(): (next: { holding?: string; company?: string; subBrand?: string }) => void {
  const router = useRouter();
  return (next) => {
    const current = router.state.location.search as Record<string, unknown>;
    const kept = Object.fromEntries(Object.entries(current).filter(([k]) => !["holding", "company", "subBrand", "page"].includes(k)));
    const chosen = Object.fromEntries(Object.entries(next).filter(([, v]) => v !== undefined && v !== ""));
    void router.navigate({ to: router.state.location.pathname, search: { ...kept, ...chosen } });
  };
}

/** The picker: a holding, then a company under it, then a sub-brand under that; each list as merchant answers it. */
export function LevelPicker() {
  const id = useId();
  const scope = useScope();
  const set = useSetScope();
  const { holdings, companies, subBrands } = useHierarchy();
  const allCompanies = companies.data?.companies ?? [];
  const allSubBrands = subBrands.data?.subBrands ?? [];
  const shownCompanies = scope.holding ? allCompanies.filter((c) => c.holdingId === scope.holding) : allCompanies;
  const shownSubBrands = scope.company
    ? allSubBrands.filter((s) => s.companyId === scope.company)
    : scope.holding
      ? allSubBrands.filter((s) => s.holdingId === scope.holding)
      : allSubBrands;
  const unanswered = [holdings.isError ? t("payments.scope.holdings") : null, companies.isError ? t("payments.scope.companies") : null].filter(Boolean);
  return (
    <fieldset className="bo-pay-picker" aria-describedby={`${id}-note`}>
      <legend>{t("payments.scope.legend")}</legend>
      <div className="bo-field">
        <label htmlFor={`${id}-h`}>{t("payments.scope.holding")}</label>
        <select id={`${id}-h`} value={scope.holding ?? ""} onChange={(e) => set({ holding: e.target.value })}>
          <option value="">{t("payments.scope.any")}</option>
          {(holdings.data?.holdings ?? []).map((h) => (
            <option key={h.holdingId} value={h.holdingId}>
              {h.name}
            </option>
          ))}
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={`${id}-c`}>{t("payments.scope.company")}</label>
        <select id={`${id}-c`} value={scope.company ?? ""} onChange={(e) => set({ holding: scope.holding, company: e.target.value })}>
          <option value="">{t("payments.scope.any")}</option>
          {shownCompanies.map((c) => (
            <option key={c.companyId} value={c.companyId}>
              {c.name}
            </option>
          ))}
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={`${id}-s`}>{t("payments.scope.subBrand")}</label>
        <select id={`${id}-s`} value={scope.subBrand ?? ""} onChange={(e) => set({ holding: scope.holding, company: scope.company, subBrand: e.target.value })}>
          <option value="">{t("payments.scope.any")}</option>
          {shownSubBrands.map((s) => (
            <option key={subBrandIdOf(s)} value={subBrandIdOf(s)}>
              {s.name}
            </option>
          ))}
        </select>
      </div>
      <p id={`${id}-note`} className="bo-muted">
        {t("payments.scope.note")} {unanswered.length > 0 ? t("payments.scope.unanswered", { name: unanswered.join(", ") }) : null}
      </p>
    </fieldset>
  );
}

/** What every page of the area acts on, until cleared. */
export function ScopeBanner() {
  const scope = useScope();
  const set = useSetScope();
  if (scope.level === null) return <p className="bo-pay-banner" role="status">{t("payments.scope.none")}</p>;
  return (
    <p className="bo-pay-banner" role="status" data-level={scope.level}>
      <span>{t("payments.scope.workingAt", { kind: t(`payments.level.${scope.level}`), name: scope.name ?? "" })}</span>
      {scope.chosen ? null : <span className="bo-muted"> {t("payments.scope.ownSubBrand")}</span>}
      {scope.chosen ? (
        <button type="button" className="bo-button" onClick={() => set({})}>
          {t("payments.scope.clear")}
        </button>
      ) : null}
    </p>
  );
}

/** A page that needs one sub-brand says so when the scope names none. */
export function NeedsSubBrand() {
  return <p role="status">{t("payments.scope.chooseSubBrand")}</p>;
}

const Statuses = ["Active", "Suspended", "Archived"] as const;
const SubBrandTypes = ["Casino", "Combined", "Payments"] as const;

function StatusChange({ current, run }: { current: string; run(status: string): Promise<Response> }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged();
  const [status, setStatus] = useState(Statuses.find((s) => s !== current) ?? "Active");
  return (
    <Action
      action={Actions["payments.status"]}
      me={me}
      bags={bags}
      ready={status !== current}
      subject={<Choice labelKey="payments.hierarchy.newStatus" value={status} set={(v) => setStatus(v as (typeof Statuses)[number])} choices={Statuses} />}
      run={() => run(status)}
      onDone={done}
    />
  );
}

function Create({ kind, run, extra }: { kind: "holding" | "company" | "subBrand"; run(name: string, extra: Record<string, string>): Promise<Response>; extra?: "company" | "subBrand" }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged();
  const scope = useScope();
  const { holdings, companies } = useHierarchy();
  const [name, setName] = useState("");
  const [parent, setParent] = useState("");
  const [type, setType] = useState<string>("Casino");
  const [brand, setBrand] = useState(me.brand.slug);
  const parents = extra === "company" ? (holdings.data?.holdings ?? []).map((h) => h.holdingId) : extra === "subBrand" ? (companies.data?.companies ?? []).map((c) => c.companyId) : [];
  const parentName = (id: string) =>
    extra === "company" ? (holdings.data?.holdings.find((h) => h.holdingId === id)?.name ?? id) : (companies.data?.companies.find((c) => c.companyId === id)?.name ?? id);
  const chosenParent = parent || (extra === "company" ? (scope.holding ?? parents[0] ?? "") : (scope.company ?? parents[0] ?? ""));
  return (
    <Action
      action={Actions[kind === "holding" ? "payments.createHolding" : kind === "company" ? "payments.createCompany" : "payments.createSubBrand"]}
      me={me}
      bags={bags}
      ready={name.trim() !== "" && (extra === undefined || chosenParent !== "") && (kind !== "subBrand" || type === "Payments" || brand.trim() !== "")}
      subject={
        <>
          <Field labelKey="payments.hierarchy.name" value={name} set={setName} />
          {extra ? <Choice labelKey={extra === "company" ? "payments.hierarchy.underHolding" : "payments.hierarchy.underCompany"} value={chosenParent} set={setParent} choices={parents} word={parentName} /> : null}
          {kind === "subBrand" ? (
            <>
              <Choice labelKey="payments.hierarchy.type" value={type} set={setType} choices={SubBrandTypes} />
              {type === "Payments" ? null : <Field labelKey="payments.hierarchy.brand" value={brand} set={setBrand} hint={t("payments.hierarchy.brandHint")} />}
            </>
          ) : null}
        </>
      }
      run={() => run(name.trim(), { parent: chosenParent, type, brand: brand.trim() })}
      onDone={() => {
        setName("");
        done();
      }}
    />
  );
}

export function HierarchySection() {
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const set = useSetScope();
  const { holdings, companies: all, subBrands } = useHierarchy();
  const scope = useScope();
  const holding = scope.chosen ? scope.holding : undefined;
  // a chosen holding's companies, as merchant answers them under it
  const under = useQuery({
    queryKey: ["payments", "holding-companies", holding],
    queryFn: () => read<{ companies: Company[] }>(merchant.getHoldingsCompanies({ id: holding ?? "" })),
    enabled: holding !== undefined,
    retry: false,
  });
  const companies = holding !== undefined ? under : all;
  const act = !readOnly;
  const work = (label: string, go: () => void) => (
    <button type="button" className="bo-button" onClick={go}>
      {t("payments.hierarchy.workHere", { name: label })}
    </button>
  );

  const holdingCols: Col<Holding>[] = [
    { header: "payments.hierarchy.name", cell: (h) => h.name },
    { header: "payments.hierarchy.status", cell: (h) => <Status value={h.status} /> },
    { header: "payments.hierarchy.created", cell: (h) => (h.createdAt ? <DateTime instant={h.createdAt} zone={tz} /> : null) },
    {
      header: "payments.act",
      cell: (h) => (
        <span className="bo-actions">
          {work(h.name, () => set({ holding: h.holdingId }))}
          {act ? <StatusChange current={h.status} run={(status) => merchant.putHoldingsStatus({ id: h.holdingId }, { body: { status } })} /> : null}
        </span>
      ),
    },
  ];
  const companyCols: Col<Company>[] = [
    { header: "payments.hierarchy.name", cell: (c) => c.name },
    { header: "payments.hierarchy.holding", cell: (c) => holdings.data?.holdings.find((h) => h.holdingId === c.holdingId)?.name ?? c.holdingId ?? "" },
    { header: "payments.hierarchy.status", cell: (c) => <Status value={c.status} /> },
    {
      header: "payments.act",
      cell: (c) => (
        <span className="bo-actions">
          {work(c.name, () => set({ holding: c.holdingId, company: c.companyId }))}
          {act ? <StatusChange current={c.status} run={(status) => merchant.putCompaniesStatus({ id: c.companyId }, { body: { status } })} /> : null}
        </span>
      ),
    },
  ];
  const subBrandCols: Col<SubBrand>[] = [
    { header: "payments.hierarchy.name", cell: (s) => s.name },
    { header: "payments.hierarchy.type", cell: (s) => s.type ?? "" },
    { header: "payments.hierarchy.brand", cell: (s) => s.brand ?? t("payments.hierarchy.noBrand") },
    { header: "payments.hierarchy.company", cell: (s) => companies.data?.companies.find((c) => c.companyId === s.companyId)?.name ?? s.companyId ?? "" },
    { header: "payments.hierarchy.status", cell: (s) => <Status value={s.status} /> },
    {
      header: "payments.act",
      cell: (s) => (
        <span className="bo-actions">
          {work(s.name, () => set({ holding: s.holdingId, company: s.companyId, subBrand: subBrandIdOf(s) }))}
          {act ? <StatusChange current={s.status} run={(status) => merchant.putSubBrandsStatus({ id: subBrandIdOf(s) }, { body: { status } })} /> : null}
        </span>
      ),
    },
  ];

  return (
    <div className="bo-pay-hierarchy">
      <p className="bo-muted">{t("payments.hierarchy.note")}</p>
      <section aria-labelledby="pay-holdings">
        <h2 id="pay-holdings">{t("payments.hierarchy.holdings")}</h2>
        {holdings.isError ? <RefusalNotice refusal={holdings.error} /> : null}
        {holdings.data ? <Rows caption="payments.hierarchy.holdings" cols={holdingCols} rows={holdings.data.holdings} rowKey={(h) => h.holdingId} empty="payments.hierarchy.none" /> : null}
        {act ? <Create kind="holding" run={(name) => merchant.postHoldings({ body: { name } })} /> : null}
      </section>
      <section aria-labelledby="pay-companies">
        <h2 id="pay-companies">{t("payments.hierarchy.companies")}</h2>
        {companies.isError ? <RefusalNotice refusal={companies.error} /> : null}
        {companies.data ? <Rows caption="payments.hierarchy.companies" cols={companyCols} rows={companies.data.companies} rowKey={(c) => c.companyId} empty="payments.hierarchy.none" /> : null}
        {act ? <Create kind="company" extra="company" run={(name, x) => merchant.postCompanies({ body: { name, holdingId: x.parent } })} /> : null}
      </section>
      <section aria-labelledby="pay-sub-brands">
        <h2 id="pay-sub-brands">{t("payments.hierarchy.subBrands")}</h2>
        {subBrands.isError ? <RefusalNotice refusal={subBrands.error} /> : null}
        {subBrands.data ? <Rows caption="payments.hierarchy.subBrands" cols={subBrandCols} rows={subBrands.data.subBrands} rowKey={subBrandIdOf} empty="payments.hierarchy.none" /> : null}
        {act ? (
          <Create
            kind="subBrand"
            extra="subBrand"
            run={(name, x) => merchant.postSubBrands({ body: { name, companyId: x.parent, type: x.type, ...(x.type === "Payments" ? {} : { brand: x.brand }) } })}
          />
        ) : null}
      </section>
    </div>
  );
}
