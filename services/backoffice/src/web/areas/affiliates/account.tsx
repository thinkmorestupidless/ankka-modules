// One affiliate account at the brand (parity *Affiliate detail* and *View as affiliate*; the programme's
// accounts/{a} routes): its facts and its membership here (another brand's is not this backoffice's);
// approve (with a plan, a parent and an override, the only place a parent is set: the account's PATCH
// takes none), reject, suspend and reactivate; its details and plan changed; an invitation sent again; a
// link to set a new password (the programme emails it: no password is seen, set or shown here); its email
// changed; notes, the audit, its figures, its network; an adjustment to its commission; and what the
// affiliate's portal shows, read by the manager through `portal/{view}`, which the programme records in
// the account's audit as VIEWED_AS with the manager's name. The page says so before and while it shows it.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { useEffect, useId, useState, type ReactNode } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { ProgrammeHeading, SectionNav } from "./area.tsx";
import { LinksTable } from "./links.tsx";
import { FiguresTable, PeriodField, Read } from "./parts.tsx";
import { PayoutsTable } from "./payouts.tsx";
import { usePlans } from "./plans.tsx";
import {
  accountName,
  actorName,
  amountOf,
  isCurrency,
  json,
  percentOf,
  thisMonth,
  type Account,
  type AuditEntry,
  type Link,
  type NetworkHealth,
  type Note,
  type Payout,
  type Revenue,
  type Sub,
} from "./programme.ts";

const PortalViews = ["dashboard", "stats", "commission", "links", "payouts", "notifications", "postbacks"] as const;
type PortalView = (typeof PortalViews)[number];
const periodic: readonly PortalView[] = ["dashboard", "stats", "commission"];

function Fact({ term, children }: { term: MessageKey; children: ReactNode }) {
  return (
    <>
      <dt>{t(term)}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Facts({ a }: { a: Account }) {
  const tz = useZone();
  const plans = usePlans();
  const plan = a.planId ? (plans.data?.find((p) => p.planId === a.planId)?.planName ?? a.planId) : "";
  return (
    <dl className="bo-aff-facts" data-account-facts>
      <Fact term="affiliates.account.status">{a.status ? <Status value={a.status} /> : null}</Fact>
      <Fact term="affiliates.email">{a.email ?? ""}</Fact>
      <Fact term="affiliates.account.name">{a.name ?? ""}</Fact>
      <Fact term="affiliates.account.displayName">{a.displayName ?? ""}</Fact>
      <Fact term="affiliates.account.company">{a.companyName ?? ""}</Fact>
      <Fact term="affiliates.account.website">{a.website ?? ""}</Fact>
      <Fact term="affiliates.account.trafficSource">{a.trafficSource ?? ""}</Fact>
      <Fact term="affiliates.account.paymentMethod">{a.paymentMethod ?? ""}</Fact>
      <Fact term="affiliates.account.plan">{plan}</Fact>
      <Fact term="affiliates.account.parent">{a.parentAffiliateId ? <code>{a.parentAffiliateId}</code> : ""}</Fact>
      <Fact term="affiliates.account.override">{t("affiliates.percent", { value: a.overridePercent })}</Fact>
      <Fact term="affiliates.account.group">{a.group ?? ""}</Fact>
      <Fact term="affiliates.account.tags">{a.tags.join(", ")}</Fact>
      <Fact term="affiliates.account.postbackUrl">{a.postbackUrl ? <code>{a.postbackUrl}</code> : ""}</Fact>
      <Fact term="affiliates.created">{a.createdAt ? <DateTime instant={a.createdAt} zone={tz} /> : ""}</Fact>
      <Fact term="affiliates.account.lastLogin">{a.lastLoginAt ? <DateTime instant={a.lastLoginAt} zone={tz} /> : t("affiliates.account.never")}</Fact>
    </dl>
  );
}

/** Approve, reject, suspend and reactivate at the brand, each offered in the membership's state. */
function StatusActions({ a, done }: { a: Account; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const plans = usePlans();
  const id = { plan: useId(), parent: useId(), override: useId() };
  const [f, setF] = useState({ planId: "", parentAffiliateId: a.parentAffiliateId ?? "", overridePercent: "" });
  const state = a.status ?? undefined;
  const name = accountName(a);
  const override = f.overridePercent.trim() === "" ? undefined : percentOf(f.overridePercent);
  const approve = {
    ...(f.planId ? { planId: f.planId } : {}),
    ...(f.parentAffiliateId.trim() ? { parentAffiliateId: f.parentAffiliateId.trim() } : {}),
    ...(override !== undefined ? { overridePercent: override } : {}),
  };
  return (
    <div>
      {state === "pending" ? (
        <div className="bo-aff-form" data-approve>
          <div className="bo-field">
            <label htmlFor={id.plan}>{t("affiliates.account.plan")}</label>
            <select id={id.plan} value={f.planId} onChange={(e) => setF((x) => ({ ...x, planId: e.target.value }))}>
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
            <input id={id.parent} value={f.parentAffiliateId} onChange={(e) => setF((x) => ({ ...x, parentAffiliateId: e.target.value }))} />
          </div>
          <div className="bo-field">
            <label htmlFor={id.override}>{t("affiliates.account.override")}</label>
            <input id={id.override} inputMode="decimal" value={f.overridePercent} onChange={(e) => setF((x) => ({ ...x, overridePercent: e.target.value }))} />
          </div>
        </div>
      ) : null}
      <div className="bo-aff-actions">
        <Action
          action={Actions["programme.account.approve"]}
          me={me}
          bags={bags}
          state={state}
          ready={f.overridePercent.trim() === "" || override !== undefined}
          subject={<p>{name}</p>}
          run={() => programme.postAccountsApprove({ affiliate: a.affiliateId }, { body: approve })}
          onDone={done}
        />
        <Action
          action={Actions["programme.account.reject"]}
          me={me}
          bags={bags}
          state={state}
          subject={<p>{name}</p>}
          run={(reason) => programme.postAccountsReject({ affiliate: a.affiliateId }, { body: reason ? { reason } : {} })}
          onDone={done}
        />
        <Action
          action={Actions["programme.account.suspend"]}
          me={me}
          bags={bags}
          state={state}
          subject={<p>{name}</p>}
          run={() => programme.postNetworkSuspend({ sub: a.affiliateId })}
          onDone={done}
        />
        <Action
          action={Actions["programme.account.reactivate"]}
          me={me}
          bags={bags}
          state={state}
          subject={<p>{name}</p>}
          run={() => programme.postNetworkReactivate({ sub: a.affiliateId })}
          onDone={done}
        />
        <Action
          action={Actions["programme.account.resendInvitation"]}
          me={me}
          bags={bags}
          state={state}
          subject={<p>{t("affiliates.account.to", { name, email: a.email ?? "" })}</p>}
          run={() => programme.postAccountsResendInvitation({ affiliate: a.affiliateId })}
          onDone={done}
        />
        <Action
          action={Actions["programme.account.resetPassword"]}
          me={me}
          bags={bags}
          subject={<p>{t("affiliates.account.to", { name, email: a.email ?? "" })}</p>}
          run={() => programme.postAccountsResetPassword({ affiliate: a.affiliateId })}
          onDone={done}
        />
      </div>
    </div>
  );
}

const Editable = ["name", "displayName", "companyName", "website", "trafficSource", "paymentMethod", "postbackUrl", "group"] as const;
const editLabels: Record<(typeof Editable)[number], MessageKey> = {
  name: "affiliates.account.name",
  displayName: "affiliates.account.displayName",
  companyName: "affiliates.account.company",
  website: "affiliates.account.website",
  trafficSource: "affiliates.account.trafficSource",
  paymentMethod: "affiliates.account.paymentMethod",
  postbackUrl: "affiliates.account.postbackUrl",
  group: "affiliates.account.group",
};

/** The account's details, plan and RevShare override (AccountPatch), only the fields changed sent. */
function EditAccount({ a, done }: { a: Account; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const plans = usePlans();
  const base = useId();
  const current = a.memberships.find((m) => m.brandId === me.brand.slug);
  const [f, setF] = useState({
    ...Object.fromEntries(Editable.map((k) => [k, a[k] ?? ""])),
    tags: a.tags.join(", "),
    planId: a.planId ?? "",
    revShareOverride: current?.revShareOverride === null || current?.revShareOverride === undefined ? "" : String(current.revShareOverride),
  } as Record<string, string>);
  const change: Record<string, unknown> = {};
  for (const k of Editable) if ((f[k] ?? "").trim() !== (a[k] ?? "")) change[k] = (f[k] ?? "").trim();
  const tags = (f.tags ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  if (tags.join(",") !== a.tags.join(",")) change.tags = tags;
  if (f.planId && f.planId !== (a.planId ?? "")) change.planId = f.planId;
  const was = current?.revShareOverride ?? null;
  const typed = (f.revShareOverride ?? "").trim();
  const revShare = typed === "" ? null : percentOf(typed);
  if (revShare !== undefined && revShare !== was) change.revShareOverride = revShare;
  const fields = Object.keys(change);
  const set = (k: string) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  return (
    <div className="bo-aff-form" data-edit-account>
      {Editable.map((k) => (
        <div className="bo-field" key={k}>
          <label htmlFor={`${base}-${k}`}>{t(editLabels[k])}</label>
          <input id={`${base}-${k}`} value={f[k] ?? ""} onChange={set(k)} />
        </div>
      ))}
      <div className="bo-field">
        <label htmlFor={`${base}-tags`}>{t("affiliates.account.tags")}</label>
        <input id={`${base}-tags`} value={f.tags ?? ""} onChange={set("tags")} />
      </div>
      <div className="bo-field">
        <label htmlFor={`${base}-plan`}>{t("affiliates.account.plan")}</label>
        <select id={`${base}-plan`} value={f.planId ?? ""} onChange={set("planId")}>
          <option value="">{t("affiliates.choose")}</option>
          {(plans.data ?? []).map((p) => (
            <option key={p.planId} value={p.planId}>
              {p.planName}
            </option>
          ))}
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={`${base}-rs`}>{t("affiliates.account.revShareOverride")}</label>
        <input id={`${base}-rs`} inputMode="decimal" value={f.revShareOverride ?? ""} onChange={set("revShareOverride")} />
        <span className="bo-field-hint">{t("affiliates.account.revShareOverrideHint")}</span>
      </div>
      {revShare === undefined ? (
        <p className="bo-field-error bo-field-wide" role="status">
          {t("affiliates.invalid", { name: t("affiliates.account.revShareOverride") })}
        </p>
      ) : null}
      <div className="bo-aff-actions bo-field-wide">
        <Action
          action={Actions["programme.account.edit"]}
          me={me}
          bags={bags}
          ready={fields.length > 0 && revShare !== undefined}
          subject={<p>{fields.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: fields.join(", ") })}</p>}
          run={() => programme.patchAccountsByAffiliate({ affiliate: a.affiliateId }, { body: change })}
          onDone={done}
        />
      </div>
    </div>
  );
}

function EmailChange({ a, done }: { a: Account; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const id = useId();
  const [email, setEmail] = useState("");
  return (
    <div className="bo-aff-form">
      <div className="bo-field">
        <label htmlFor={id}>{t("affiliates.account.newEmail")}</label>
        <input id={id} type="email" autoComplete="off" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>
      <div className="bo-aff-actions">
        <Action
          action={Actions["programme.account.updateEmail"]}
          me={me}
          bags={bags}
          ready={/^\S+@\S+$/.test(email.trim()) && email.trim() !== a.email}
          subject={<p>{t("affiliates.account.emailFromTo", { from: a.email ?? "", to: email.trim() })}</p>}
          run={() => programme.postAccountsUpdateEmail({ affiliate: a.affiliateId }, { body: { email: email.trim() } })}
          onDone={() => {
            setEmail("");
            done();
          }}
        />
      </div>
    </div>
  );
}

const newKey = () => `programme-adjust/${crypto.randomUUID()}`;

/**
 * An adjustment of the affiliate's balance, sent under an `Idempotency-Key` of its own (the programme's
 * Settlement.adjust): the key is kept while the programme's answer is not known, so trying the same
 * adjustment again cannot make it twice; an answer, or a change to what is composed, takes a new key.
 */
function Adjustment({ a, done }: { a: Account; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const id = { type: useId(), amount: useId(), currency: useId() };
  const [f, setFields] = useState({ type: "CREDIT", amount: "", currency: "" });
  const [key, setKey] = useState(newKey);
  const setF = (change: (x: typeof f) => typeof f) => {
    setFields(change);
    setKey(newKey());
  };
  const currency = f.currency.trim().toUpperCase();
  const amount = isCurrency(currency) ? amountOf(f.amount, currency) : undefined;
  return (
    <div className="bo-aff-form" data-adjustment>
      <div className="bo-field">
        <label htmlFor={id.type}>{t("affiliates.adjustment.type")}</label>
        <select id={id.type} value={f.type} onChange={(e) => setF((x) => ({ ...x, type: e.target.value }))}>
          <option value="CREDIT">{t("affiliates.adjustment.credit")}</option>
          <option value="DEBIT">{t("affiliates.adjustment.debit")}</option>
        </select>
      </div>
      <div className="bo-field">
        <label htmlFor={id.currency}>{t("affiliates.figure.currency")}</label>
        <input id={id.currency} value={f.currency} maxLength={3} onChange={(e) => setF((x) => ({ ...x, currency: e.target.value }))} />
      </div>
      <div className="bo-field">
        <label htmlFor={id.amount}>{t("affiliates.adjustment.amount")}</label>
        <input id={id.amount} inputMode="decimal" value={f.amount} onChange={(e) => setF((x) => ({ ...x, amount: e.target.value }))} />
      </div>
      <div className="bo-aff-actions bo-field-wide">
        <Action
          action={Actions["programme.account.adjust"]}
          me={me}
          bags={bags}
          ready={amount !== undefined && amount > 0}
          subject={<p>{t(f.type === "CREDIT" ? "affiliates.adjustment.crediting" : "affiliates.adjustment.debiting", { amount: f.amount.trim(), currency, name: accountName(a) })}</p>}
          run={async (reason) => {
            const answer = await programme.postAccountsAdjustments({ affiliate: a.affiliateId }, { body: { type: f.type, amount, currency, reason: reason ?? "" }, idempotencyKey: key });
            // the programme answered (made or refused): the next adjustment is another; not answered: the same key again
            if (answer.status !== 503) setKey(newKey());
            return answer;
          }}
          onDone={() => {
            setF((x) => ({ ...x, amount: "" }));
            done();
          }}
        />
      </div>
    </div>
  );
}

function Notes({ a }: { a: Account }) {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const id = useId();
  const [text, setText] = useState("");
  const key = ["programme", "account", a.affiliateId, "notes"];
  const q = useQuery({ queryKey: key, queryFn: () => json<Note[]>(programme.getAccountsNotes({ affiliate: a.affiliateId })) });
  return (
    <div data-notes>
      <Read q={q}>
        {(notes) =>
          notes.length === 0 ? (
            <p className="bo-muted">{t("affiliates.note.none")}</p>
          ) : (
            <ul className="bo-aff-notes">
              {notes.map((n) => (
                <li key={n.noteId}>
                  <p>{n.text}</p>
                  <p className="bo-muted">
                    <span>{actorName(n.authorId, me)}</span> <DateTime instant={n.createdAt} zone={tz} />
                  </p>
                </li>
              ))}
            </ul>
          )
        }
      </Read>
      {readOnly ? null : (
        <div className="bo-aff-form">
          <div className="bo-field bo-field-wide">
            <label htmlFor={id}>{t("affiliates.note.new")}</label>
            <textarea id={id} rows={2} value={text} onChange={(e) => setText(e.target.value)} />
          </div>
          <div className="bo-aff-actions bo-field-wide">
            <Action
              action={Actions["programme.account.note"]}
              me={me}
              bags={bags}
              ready={text.trim() !== ""}
              subject={<p>{text.trim()}</p>}
              run={() => programme.postAccountsNotes({ affiliate: a.affiliateId }, { body: { text: text.trim() } })}
              onDone={() => {
                setText("");
                void client.invalidateQueries({ queryKey: key });
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function Audit({ a }: { a: Account }) {
  const me = useOperator();
  const tz = useZone();
  const q = useQuery({ queryKey: ["programme", "account", a.affiliateId, "audit"], queryFn: () => json<AuditEntry[]>(programme.getAccountsAudit({ affiliate: a.affiliateId })) });
  return (
    <Read q={q}>
      {(entries) =>
        entries.length === 0 ? (
          <p className="bo-muted">{t("affiliates.audit.none")}</p>
        ) : (
          <div className="bo-aff-scroll">
            <table className="bo-aff-table" data-audit>
              <caption className="bo-sr-only">{t("affiliates.account.audit")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.audit.at")}</th>
                  <th scope="col">{t("affiliates.audit.event")}</th>
                  <th scope="col">{t("affiliates.audit.by")}</th>
                  <th scope="col">{t("affiliates.audit.details")}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.auditId}>
                    <td>
                      <DateTime instant={e.recordedAt} zone={tz} />
                    </td>
                    <td>{word("affiliates.auditEvent", e.event)}</td>
                    <td>{actorName(e.actorId, me)}</td>
                    <td>
                      {Object.entries(e.details)
                        .map(([k, v]) => `${k}: ${v}`)
                        .join("; ")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      }
    </Read>
  );
}

function Network({ a }: { a: Account }) {
  const health = useQuery({ queryKey: ["programme", "account", a.affiliateId, "health"], queryFn: () => json<NetworkHealth>(programme.getAccountsNetworkHealth({ affiliate: a.affiliateId })) });
  const subs = useQuery({ queryKey: ["programme", "account", a.affiliateId, "subs"], queryFn: () => json<Sub[]>(programme.getAccountsSubAffiliates({ affiliate: a.affiliateId })) });
  return (
    <div data-network>
      <Read q={health}>
        {(h) => (
          <dl className="bo-aff-facts">
            <Fact term="affiliates.network.total">{h.totalSubs}</Fact>
            <Fact term="affiliates.network.active">{h.activeSubs}</Fact>
            <Fact term="affiliates.network.pending">{h.pendingApprovals}</Fact>
            <Fact term="affiliates.network.suspended">{h.suspendedSubs}</Fact>
          </dl>
        )}
      </Read>
      <Read q={subs}>
        {(rows) =>
          rows.length === 0 ? null : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.network.subs")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.affiliate")}</th>
                  <th scope="col">{t("affiliates.account.status")}</th>
                  <th scope="col" data-numeric>
                    {t("affiliates.account.override")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((s) => (
                  <tr key={s.affiliateId}>
                    <th scope="row">{s.displayName}</th>
                    <td>
                      <Status value={s.status} />
                    </td>
                    <td data-numeric>{t("affiliates.percent", { value: s.overridePercent })}</td>
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

/** An answer of the portal the programme gives as data, shown as given (FR-040a). */
function AsGiven({ value }: { value: unknown }): ReactNode {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="bo-muted">{t("affiliates.view.empty")}</span>;
    return (
      <ul>
        {value.map((v, i) => (
          <li key={i}>
            <AsGiven value={v} />
          </li>
        ))}
      </ul>
    );
  }
  if (typeof value === "object")
    return (
      <dl className="bo-aff-facts">
        {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>
              <AsGiven value={v} />
            </dd>
          </div>
        ))}
      </dl>
    );
  return String(value);
}

/** What the affiliate's portal shows, as the manager reads it: recorded VIEWED_AS by the programme, named on the page. */
function ViewAsAffiliate({ a }: { a: Account }) {
  const me = useOperator();
  const id = useId();
  const [view, setView] = useState<PortalView>("dashboard");
  const zone = useZone();
  const [period, setPeriod] = useState(() => thisMonth(zone));
  const [asked, setAsked] = useState<{ view: PortalView; period: string } | undefined>(undefined);
  const q = useQuery({
    queryKey: ["programme", "account", a.affiliateId, "portal", asked],
    enabled: asked !== undefined,
    queryFn: () =>
      json<unknown>(programme.getAccountsPortalByView({ affiliate: a.affiliateId, view: asked!.view }, periodic.includes(asked!.view) ? { query: { period: asked!.period } } : {})),
    staleTime: Infinity,
  });
  // the programme recorded the read in the account's audit: the audit shown is read again
  const client = useQueryClient();
  useEffect(() => {
    if (q.dataUpdatedAt) void client.invalidateQueries({ queryKey: ["programme", "account", a.affiliateId, "audit"] });
  }, [q.dataUpdatedAt, client, a.affiliateId]);
  const shown = (data: unknown): ReactNode => {
    switch (asked?.view) {
      case "dashboard":
      case "stats":
        return <FiguresTable figures={(data as Revenue).figures ?? []} caption="affiliates.account.view" />;
      case "links":
        return <LinksTable links={data as Link[]} />;
      case "payouts":
        return <PayoutsTable payouts={data as Payout[]} names={new Map()} done={() => undefined} />;
      default:
        return <AsGiven value={data} />;
    }
  };
  return (
    <div data-view-as>
      <p className="bo-aff-recorded" role="note">
        {t("affiliates.view.recorded", { name: me.name ?? me.subject ?? "" })}
      </p>
      <div className="bo-aff-filters">
        <div className="bo-field">
          <label htmlFor={id}>{t("affiliates.view.page")}</label>
          <select id={id} value={view} onChange={(e) => setView(e.target.value as PortalView)}>
            {PortalViews.map((v) => (
              <option key={v} value={v}>
                {word("affiliates.portal", v)}
              </option>
            ))}
          </select>
        </div>
        {periodic.includes(view) ? <PeriodField value={period} onChange={setPeriod} /> : null}
        <button type="button" className="bo-button" onClick={() => setAsked({ view, period })}>
          {t("affiliates.view.open")}
        </button>
      </div>
      {asked ? (
        <section className="bo-aff-detail" data-portal={asked.view} aria-label={t("affiliates.view.showing", { view: word("affiliates.portal", asked.view) })}>
          <p className="bo-muted">{t("affiliates.view.showing", { view: word("affiliates.portal", asked.view) })}</p>
          <Read q={q}>{shown}</Read>
        </section>
      ) : null}
    </div>
  );
}

function Figures({ a }: { a: Account }) {
  const zone = useZone();
  const [period, setPeriod] = useState(() => thisMonth(zone));
  const q = useQuery({ queryKey: ["programme", "account", a.affiliateId, "revenue", period], queryFn: () => json<Revenue>(programme.getAccountsRevenue({ affiliate: a.affiliateId }, { query: { period } })) });
  return (
    <div data-figures>
      <PeriodField value={period} onChange={setPeriod} />
      <Read q={q}>{(r) => <FiguresTable figures={r.figures} caption="affiliates.account.figures" />}</Read>
    </div>
  );
}

function Part({ heading, children }: { heading: MessageKey; children: ReactNode }) {
  return (
    <section className="bo-aff-part">
      <h2>{t(heading)}</h2>
      {children}
    </section>
  );
}

export function AccountPage() {
  const zone = useZone();
  const { affiliate } = useParams({ strict: false }) as { affiliate: string };
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const key = ["programme", "account", affiliate];
  const q = useQuery({ queryKey: [...key, "facts"], queryFn: () => json<Account>(programme.getAccountsByAffiliate({ affiliate })) });
  const done = () => {
    void client.invalidateQueries({ queryKey: key });
    void client.invalidateQueries({ queryKey: ["programme", "accounts"] });
  };
  return (
    <section className="bo-aff" data-section="accounts" data-account={affiliate}>
      <ProgrammeHeading />
      <SectionNav current="accounts" />
      <ZoneNote zone={zone} />
      <Read q={q}>
        {(a) => (
          <>
            <h2>{accountName(a)}</h2>
            <Facts a={a} />
            {readOnly ? null : (
              <Part heading="affiliates.account.status">
                <StatusActions key={a.status ?? ""} a={a} done={done} />
              </Part>
            )}
            {readOnly ? null : (
              <Part heading="affiliates.account.edit">
                <EditAccount key={JSON.stringify(a)} a={a} done={done} />
              </Part>
            )}
            {readOnly ? null : (
              <Part heading="affiliates.account.emailChange">
                <EmailChange a={a} done={done} />
              </Part>
            )}
            <Part heading="affiliates.account.figures">
              <Figures a={a} />
            </Part>
            {readOnly ? null : (
              <Part heading="affiliates.account.adjustment">
                <Adjustment a={a} done={done} />
              </Part>
            )}
            <Part heading="affiliates.account.network">
              <Network a={a} />
            </Part>
            <Part heading="affiliates.account.notes">
              <Notes a={a} />
            </Part>
            <Part heading="affiliates.account.view">
              <ViewAsAffiliate a={a} />
            </Part>
            <Part heading="affiliates.account.audit">
              <Audit key={a.status ?? ""} a={a} />
            </Part>
          </>
        )}
      </Read>
    </section>
  );
}
