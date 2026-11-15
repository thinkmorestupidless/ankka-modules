// `/reports` and `/reports/{report}` (FR-033; spec 015): each report run for a period (the month to
// date when none is chosen, reporting's default), shown as reporting answers it, and exported by
// reporting under its reference number (`/api/jobs`): the backoffice builds no export itself. A period
// that starts before the figures are complete is refused by reporting naming the first complete day,
// which is shown with a way to run the report from it (spec 015 FR-004).
import { useState, type ReactNode } from "react";
import { Link, useNavigate, useParams, useSearch } from "@tanstack/react-router";
import "../dashboard/dashboard.css";
import "./reports.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { holds, runs, type Me } from "../../../shared/access.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime } from "../../components/date.tsx";
import { RecordLink } from "../../components/links.tsx";
import { useOperator } from "../../frame/context.ts";
import { Export } from "../jobs/Export.tsx";
import type { JobKind } from "../jobs/jobs.ts";
import { Amount, formatDay, useReportingStatus, ViewsFreshness } from "./figures.tsx";
import { useReporting, type Refused } from "./read.ts";

export type ReportId = "revenue" | "players" | "transactions" | "rewards" | "providers" | "day-end" | "compliance" | "disputes";

interface ReportDef {
  id: ReportId;
  label: MessageKey;
  about: MessageKey;
  permission: string;
  module?: string;
  views: string[];
}

export const Reports: readonly ReportDef[] = [
  { id: "revenue", label: "reports.revenue", about: "reports.revenue.about", permission: "report.read", views: ["days"] },
  { id: "players", label: "reports.players", about: "reports.players.about", permission: "report.read", views: ["players", "wallets"] },
  { id: "transactions", label: "reports.transactions", about: "reports.transactions.about", permission: "report.read", views: ["transactions"] },
  { id: "rewards", label: "reports.rewards", about: "reports.rewards.about", permission: "report.read", module: "casino", views: ["grants"] },
  { id: "providers", label: "reports.providers", about: "reports.providers.about", permission: "report.read", views: ["days"] },
  { id: "day-end", label: "reports.dayEnd", about: "reports.dayEnd.about", permission: "report.dayEnd", views: ["days"] },
  { id: "compliance", label: "reports.compliance", about: "reports.compliance.about", permission: "report.compliance", views: ["compliance"] },
  { id: "disputes", label: "reports.disputes", about: "reports.disputes.about", permission: "export.player", views: ["rounds", "sessions"] },
];

export function reportsFor(me: Pick<Me, "permissions" | "brand">): ReportDef[] {
  return Reports.filter((r) => holds(me, r.permission) && (r.module === undefined || runs(me, r.module)));
}

// ── what reporting answers ──────────────────────────────────────────────────────────────────────

interface Ggr {
  from: string; to: string; currency: string; currencies: string[];
  summary: Record<"totalBets" | "totalWins" | "ggr" | "depositPoolGgr" | "bonusCosts" | "chargebacks" | "ngr" | "totalDeposits" | "totalWithdrawals" | "netCashFlow", string> & { ngrBasis: string };
  dailyBreakdown: { date: string; bets: string; wins: string; ggr: string; depositPoolGgr: string; bonusCosts: string; ngr: string }[];
}
interface PlayersReport {
  from: string; to: string; currency: string; currencies: string[]; totalCount: number;
  players: { playerId: string; email: string; country: string; registrationDate: string; status: string; kycStatus: string; lifetimeDeposits: string; lifetimeWagered: string; lifetimeGgr: string; lastLoginAt: string; isTestAccount: boolean; currency: string }[];
}
interface Ledger {
  from: string; to: string; page: number; pageSize: number; totalCount: number; totalPages: number;
  transactions: { transactionId: string; timestamp: string; playerId: string; truncatedEmail: string; transactionType: string; amount: string; currency: string; reference: string }[];
}
interface Bonus {
  from: string; to: string; currency: string; currencies: string[];
  summary: { totalGrantsCount: number; totalGrantedValue: string; convertedCount: number; conversionRatePct: number; forfeitedCount: number; cancelledCount: number; activeCount: number; currency: string };
  offers: { offerId: string; offerName: string; offerType: string; grantsCount: number; totalGrantedValue: string; convertedCount: number; convertedValue: string; forfeitedCount: number; cancelledCount: number; conversionRatePct: number; currency: string }[];
}
interface Psp {
  from: string; to: string; currency: string; currencies: string[];
  summary: { depositAttempts: number; completedDeposits: number; depositValue: string; depositSuccessRatePct: number | null; withdrawalRequests: number; completedWithdrawals: number; avgWithdrawalHours: number | null; currency: string };
  pspBreakdown: { pspName: string; depositAttempts: number; completedDeposits: number; depositSuccessRatePct: number | null; depositValue: string; withdrawalCount: number; withdrawalRequests: number; avgWithdrawalHours: number | null; belowThreshold: boolean }[];
}
interface DayEnd {
  day: string; zone: string; closedAt: string; reference: string;
  currencies: Record<string, {
    deposits: { provider: string; count: number; amount: string }[];
    withdrawals: { gateway: string; count: number; amount: string }[];
    games: { provider: string; gameId: string; gameName: string; providerGgr: string; walletGgr: string }[];
    walletGgr: string; depositPoolGgr: string; bonusCost: string; chargebacks: string; liability: string; pendingHolds: string;
  }>;
  differences: { roundId: string; provider: string; status: string }[];
  corrections: unknown[];
}
interface Rows<T> { rows: T[] }
interface RgRow { playerId: string; type: string; openedAt: string | null; closedAt: string | null; outcome: string; reason: string }
interface KycRow { playerId: string; category: string; submittedAt: string | null; decidedAt: string | null; outcome: string; reason: string }

// ── the pieces every report shares ──────────────────────────────────────────────────────────────

type Search = Record<string, string | undefined>;

function Field({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = `f-${label.replace(/\W+/g, "-").toLowerCase()}`;
  return (
    <div className="bo-field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  );
}

/** The period, and a report's own filters, kept in the URL; applied when the operator says. */
function Filters({ search, fields, onApply }: { search: Search; fields: { key: string; label: string; type?: string; options?: { value: string; label: string }[] }[]; onApply: (s: Search) => void }) {
  const [draft, setDraft] = useState<Search>(search);
  return (
    <form
      className="bo-report-filters"
      onSubmit={(e) => {
        e.preventDefault();
        onApply(draft);
      }}
    >
      {fields.map((f) => (
        <Field key={f.key} label={f.label}>
          {(id) =>
            f.options ? (
              <select id={id} className="bo-input" value={draft[f.key] ?? ""} onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value || undefined })}>
                {f.options.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            ) : (
              <input id={id} className="bo-input" type={f.type ?? "text"} value={draft[f.key] ?? ""} onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value || undefined })} />
            )
          }
        </Field>
      ))}
      <button type="submit" className="bo-button">
        {t("reports.run")}
      </button>
    </form>
  );
}

const periodFields = () => [
  { key: "from", label: t("reports.from"), type: "date" },
  { key: "to", label: t("reports.to"), type: "date" },
];

/** A refusal, an incomplete period with its first complete day and a way to run from it. */
function Refusal({ error, onFrom }: { error: Refused; onFrom?: (day: string) => void }) {
  const day = error.completeFrom;
  if (day !== undefined)
    return (
      <div className="bo-refusal" role="alert" data-refusal="incomplete_period">
        <span>{t("reports.incomplete", { day: formatDay(day) })}</span>
        {onFrom ? (
          <button type="button" className="bo-button" onClick={() => onFrom(day)}>
            {t("reports.runFrom", { day: formatDay(day) })}
          </button>
        ) : null}
      </div>
    );
  return <RefusalNotice refusal={error.refusal} />;
}

function Answered<T>({ q, onFrom, children }: { q: { data?: T; error: Refused | null; isPending: boolean }; onFrom?: (day: string) => void; children: (d: T) => ReactNode }) {
  if (q.error) return <Refusal error={q.error} onFrom={onFrom} />;
  if (q.isPending || q.data === undefined) return <p className="bo-muted">{t("list.loading")}</p>;
  return <>{children(q.data)}</>;
}

function Period({ from, to }: { from: string; to: string }) {
  return <p className="bo-muted">{t("reports.period", { from: formatDay(from), to: formatDay(to) })}</p>;
}

function Currencies({ answer, chosen, onChoose }: { answer: { currency: string; currencies: string[] }; chosen: string | undefined; onChoose: (c: string) => void }) {
  const all = [...new Set([answer.currency, ...answer.currencies])].sort();
  if (all.length < 2) return null;
  return (
    <div role="group" aria-label={t("dashboard.currency")} className="bo-pills">
      {all.map((c) => (
        <button key={c} type="button" className="bo-pill" aria-pressed={c === (chosen ?? answer.currency)} onClick={() => onChoose(c)}>
          {c}
        </button>
      ))}
    </div>
  );
}

type Cell = ReactNode;

function Table({ caption, head, rows, numeric = [] }: { caption: string; head: string[]; rows: { key: string; cells: Cell[] }[]; numeric?: number[] }) {
  return (
    <div className="bo-report-scroll">
      <table className="bo-chart-table">
        <caption>{caption}</caption>
        <thead>
          <tr>
            {head.map((h, i) => (
              <th key={h} scope="col" data-numeric={numeric.includes(i) || undefined}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={head.length}>{t("list.empty")}</td>
            </tr>
          ) : (
            rows.map((r) => (
              <tr key={r.key}>
                {r.cells.map((c, i) => (
                  <td key={i} data-numeric={numeric.includes(i) || undefined}>
                    {c}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

function Summary({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="bo-figures">
      {items.map(([label, value]) => (
        <div key={label} className="bo-figure">
          <dt>{label}</dt>
          <dd>
            <span className="bo-figure-value">{value}</span>
          </dd>
        </div>
      ))}
    </dl>
  );
}

const pct = (v: number | null) => (v === null ? t("dashboard.noAttempts") : t("chart.percent", { value: String(v) }));
const hrs = (v: number | null) => (v === null ? t("dashboard.noWithdrawals") : t("dashboard.hours", { hours: String(v) }));
const when = (iso: string | null | undefined, zone: string) => (iso ? <DateTime instant={iso} zone={zone} /> : t("reports.notKnown"));
const player = (id: string, shown?: string) => <RecordLink to={{ kind: "player", id }}>{shown || id}</RecordLink>;
const period = (s: Search) => ({ from: s.from, to: s.to });
const defined = (o: Record<string, string | undefined>): Record<string, string> =>
  Object.fromEntries(Object.entries(o).filter((e): e is [string, string] => e[1] !== undefined && e[1] !== ""));

interface ReportProps {
  search: Search;
  set: (s: Search) => void;
  zone: string;
}

function ExportFor({ kind, params, label }: { kind: JobKind; params: Record<string, string | undefined>; label?: string }) {
  return <Export kind={kind} params={defined(params)} label={label} />;
}

// ── the reports ─────────────────────────────────────────────────────────────────────────────────

function Revenue({ search, set }: ReportProps) {
  const q = useReporting<Ggr>("/reports/ggr", { ...period(search), currency: search.currency });
  return (
    <>
      <Filters search={search} fields={periodFields()} onApply={set} />
      <ExportFor kind="revenue" params={{ ...period(search), currency: search.currency }} />
      <Answered q={q} onFrom={(from) => set({ ...search, from })}>
        {(g) => (
          <>
            <Period from={g.from} to={g.to} />
            <Currencies answer={g} chosen={search.currency} onChoose={(currency) => set({ ...search, currency })} />
            <Summary
              items={[
                [t("reports.bets"), <Amount text={g.summary.totalBets} currency={g.currency} />],
                [t("reports.wins"), <Amount text={g.summary.totalWins} currency={g.currency} />],
                [t("dashboard.ggr"), <Amount text={g.summary.ggr} currency={g.currency} />],
                [t("reports.poolGgr"), <Amount text={g.summary.depositPoolGgr} currency={g.currency} />],
                [t("reports.rewardCosts"), <Amount text={g.summary.bonusCosts} currency={g.currency} />],
                [t("reports.chargebacks"), <Amount text={g.summary.chargebacks} currency={g.currency} />],
                [t("reports.ngrBasis", { basis: g.summary.ngrBasis }), <Amount text={g.summary.ngr} currency={g.currency} />],
                [t("reports.depositsTotal"), <Amount text={g.summary.totalDeposits} currency={g.currency} />],
                [t("reports.withdrawalsTotal"), <Amount text={g.summary.totalWithdrawals} currency={g.currency} />],
                [t("reports.netCashFlow"), <Amount text={g.summary.netCashFlow} currency={g.currency} />],
              ]}
            />
            <Table
              caption={t("reports.byDay", { currency: g.currency })}
              head={[t("reports.day"), t("reports.bets"), t("reports.wins"), t("dashboard.ggr"), t("reports.poolGgr"), t("reports.rewardCosts"), t("dashboard.ngr")]}
              numeric={[1, 2, 3, 4, 5, 6]}
              rows={g.dailyBreakdown.map((d) => ({
                key: d.date,
                cells: [formatDay(d.date), ...[d.bets, d.wins, d.ggr, d.depositPoolGgr, d.bonusCosts, d.ngr].map((v, i) => <Amount key={i} text={v} currency={g.currency} />)],
              }))}
            />
          </>
        )}
      </Answered>
    </>
  );
}

function Players({ search, set, zone }: ReportProps) {
  const q = useReporting<PlayersReport>("/reports/players", { ...period(search), currency: search.currency, country: search.country, status: search.status });
  return (
    <>
      <Filters search={search} fields={[...periodFields(), { key: "country", label: t("reports.countryCode") }, { key: "status", label: t("reports.status") }]} onApply={set} />
      <ExportFor kind="players" params={{ ...period(search), currency: search.currency, country: search.country, status: search.status }} />
      <Answered q={q} onFrom={(from) => set({ ...search, from })}>
        {(p) => (
          <>
            <Period from={p.from} to={p.to} />
            <Currencies answer={p} chosen={search.currency} onChoose={(currency) => set({ ...search, currency })} />
            <p className="figures">{t("reports.registered", { count: p.totalCount })}</p>
            <Table
              caption={t("reports.playersIn", { currency: p.currency })}
              head={[t("dashboard.player"), t("chart.country"), t("reports.registeredAt"), t("reports.status"), t("reports.identity"), t("reports.lifetimeDeposits"), t("reports.lifetimeWagered"), t("reports.lifetimeGgr"), t("reports.lastLogin"), t("reports.testAccount")]}
              numeric={[5, 6, 7]}
              rows={p.players.map((r) => ({
                key: r.playerId,
                cells: [player(r.playerId, r.email), r.country, when(r.registrationDate, zone), r.status, r.kycStatus, <Amount text={r.lifetimeDeposits} currency={r.currency} />, <Amount text={r.lifetimeWagered} currency={r.currency} />, <Amount text={r.lifetimeGgr} currency={r.currency} />, when(r.lastLoginAt, zone), r.isTestAccount ? t("reports.yes") : t("reports.no")],
              }))}
            />
          </>
        )}
      </Answered>
    </>
  );
}

function Transactions({ search, set, zone }: ReportProps) {
  const page = search.page ?? "0";
  const q = useReporting<Ledger>("/reports/transactions", { ...period(search), type: search.type, playerEmail: search.playerEmail, page, pageSize: "100" });
  return (
    <>
      <Filters search={search} fields={[...periodFields(), { key: "type", label: t("reports.type") }, { key: "playerEmail", label: t("reports.email") }]} onApply={(s) => set({ ...s, page: undefined })} />
      <ExportFor kind="transactions" params={{ ...period(search), type: search.type, playerEmail: search.playerEmail }} />
      <Answered q={q} onFrom={(from) => set({ ...search, from, page: undefined })}>
        {(l) => (
          <>
            <Period from={l.from} to={l.to} />
            <p className="figures">{t("reports.pageOf", { page: l.page + 1, pages: Math.max(1, l.totalPages), count: l.totalCount })}</p>
            <Table
              caption={t("reports.transactions")}
              head={[t("reports.at"), t("dashboard.player"), t("reports.type"), t("reports.amount"), t("reports.reference"), t("reports.transaction")]}
              numeric={[3]}
              rows={l.transactions.map((r) => ({
                key: r.transactionId,
                cells: [when(r.timestamp, zone), player(r.playerId, r.truncatedEmail), r.transactionType, <Amount text={r.amount} currency={r.currency} />, r.reference, r.transactionId],
              }))}
            />
            <nav aria-label={t("list.pages")} className="bo-report-pages">
              <button type="button" className="bo-button" disabled={l.page <= 0} onClick={() => set({ ...search, page: String(l.page - 1) })}>
                {t("list.previous")}
              </button>
              <button type="button" className="bo-button" disabled={l.page + 1 >= l.totalPages} onClick={() => set({ ...search, page: String(l.page + 1) })}>
                {t("list.next")}
              </button>
            </nav>
          </>
        )}
      </Answered>
    </>
  );
}

function Rewards({ search, set }: ReportProps) {
  const q = useReporting<Bonus>("/reports/bonus", { ...period(search), currency: search.currency, offerId: search.offerId });
  return (
    <>
      <Filters search={search} fields={[...periodFields(), { key: "offerId", label: t("reports.offer") }]} onApply={set} />
      <ExportFor kind="rewards" params={{ ...period(search), currency: search.currency, offerId: search.offerId }} />
      <Answered q={q} onFrom={(from) => set({ ...search, from })}>
        {(b) => (
          <>
            <Period from={b.from} to={b.to} />
            <Currencies answer={b} chosen={search.currency} onChoose={(currency) => set({ ...search, currency })} />
            <Summary
              items={[
                [t("reports.grants"), <span className="figures">{b.summary.totalGrantsCount}</span>],
                [t("reports.granted"), <Amount text={b.summary.totalGrantedValue} currency={b.summary.currency} />],
                [t("reports.converted"), <span className="figures">{t("reports.convertedOf", { count: b.summary.convertedCount, rate: b.summary.conversionRatePct })}</span>],
                [t("reports.forfeited"), <span className="figures">{b.summary.forfeitedCount}</span>],
                [t("reports.cancelled"), <span className="figures">{b.summary.cancelledCount}</span>],
                [t("reports.active"), <span className="figures">{b.summary.activeCount}</span>],
              ]}
            />
            <Table
              caption={t("reports.offers", { currency: b.currency })}
              head={[t("reports.offer"), t("reports.type"), t("reports.grants"), t("reports.granted"), t("reports.converted"), t("reports.convertedValue"), t("reports.forfeited"), t("reports.cancelled")]}
              numeric={[2, 3, 4, 5, 6, 7]}
              rows={b.offers.map((o) => ({
                key: o.offerId,
                cells: [o.offerName || o.offerId, o.offerType, o.grantsCount, <Amount text={o.totalGrantedValue} currency={o.currency} />, t("reports.convertedOf", { count: o.convertedCount, rate: o.conversionRatePct }), <Amount text={o.convertedValue} currency={o.currency} />, o.forfeitedCount, o.cancelledCount],
              }))}
            />
          </>
        )}
      </Answered>
    </>
  );
}

function Providers({ search, set }: ReportProps) {
  const q = useReporting<Psp>("/reports/psp", { ...period(search), currency: search.currency });
  return (
    <>
      <Filters search={search} fields={periodFields()} onApply={set} />
      <ExportFor kind="providers" params={{ ...period(search), currency: search.currency }} />
      <Answered q={q} onFrom={(from) => set({ ...search, from })}>
        {(p) => (
          <>
            <Period from={p.from} to={p.to} />
            <Currencies answer={p} chosen={search.currency} onChoose={(currency) => set({ ...search, currency })} />
            <Summary
              items={[
                [t("reports.depositAttempts"), <span className="figures">{p.summary.depositAttempts}</span>],
                [t("reports.depositsCompleted"), <span className="figures">{p.summary.completedDeposits}</span>],
                [t("reports.depositValue"), <Amount text={p.summary.depositValue} currency={p.summary.currency} />],
                [t("dashboard.depositSuccess"), pct(p.summary.depositSuccessRatePct)],
                [t("reports.withdrawalRequests"), <span className="figures">{p.summary.withdrawalRequests}</span>],
                [t("reports.withdrawalsCompleted"), <span className="figures">{p.summary.completedWithdrawals}</span>],
                [t("dashboard.withdrawalHours"), hrs(p.summary.avgWithdrawalHours)],
              ]}
            />
            <Table
              caption={t("reports.byProvider", { currency: p.currency })}
              head={[t("dashboard.provider"), t("reports.depositAttempts"), t("reports.depositsCompleted"), t("dashboard.depositSuccess"), t("reports.depositValue"), t("reports.withdrawalRequests"), t("reports.withdrawalsCompleted"), t("dashboard.withdrawalHours"), t("reports.threshold")]}
              numeric={[1, 2, 3, 4, 5, 6, 7]}
              rows={p.pspBreakdown.map((r) => ({
                key: r.pspName,
                cells: [r.pspName, r.depositAttempts, r.completedDeposits, pct(r.depositSuccessRatePct), <Amount text={r.depositValue} currency={p.currency} />, r.withdrawalRequests, r.withdrawalCount, hrs(r.avgWithdrawalHours), r.belowThreshold ? t("reports.belowThreshold") : t("reports.atThreshold")],
              }))}
            />
          </>
        )}
      </Answered>
    </>
  );
}

/** Yesterday in this browser's calendar: the latest day that can have closed. */
const yesterday = () => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);

function DayEndReport({ search, set, zone }: ReportProps) {
  const day = search.day ?? yesterday();
  const q = useReporting<DayEnd>(`/day-end/${encodeURIComponent(day)}`);
  return (
    <>
      <Filters search={{ day, ...search }} fields={[{ key: "day", label: t("reports.day"), type: "date" }]} onApply={set} />
      <ExportFor kind="day_end" params={{ day }} />
      <Answered q={q}>
        {(d) => (
          <>
            <p className="bo-muted">{t("reports.dayEndOf", { day: formatDay(d.day), zone: d.zone })} {when(d.closedAt, zone)}</p>
            <p className="figures">{t("reports.reference", { reference: d.reference })}</p>
            {Object.entries(d.currencies).map(([c, x]) => (
              <section key={c} className="bo-card" aria-label={t("reports.dayEndIn", { currency: c })}>
                <h2>{t("reports.dayEndIn", { currency: c })}</h2>
                <Summary
                  items={[
                    [t("reports.walletGgr"), <Amount text={x.walletGgr} currency={c} />],
                    [t("reports.poolGgr"), <Amount text={x.depositPoolGgr} currency={c} />],
                    [t("reports.rewardCosts"), <Amount text={x.bonusCost} currency={c} />],
                    [t("reports.chargebacks"), <Amount text={x.chargebacks} currency={c} />],
                    [t("reports.liability"), <Amount text={x.liability} currency={c} />],
                    [t("reports.pendingHolds"), <Amount text={x.pendingHolds} currency={c} />],
                  ]}
                />
                <Table caption={t("reports.depositsBy")} head={[t("dashboard.provider"), t("reports.count"), t("reports.amount")]} numeric={[1, 2]} rows={x.deposits.map((r) => ({ key: r.provider, cells: [r.provider, r.count, <Amount text={r.amount} currency={c} />] }))} />
                <Table caption={t("reports.withdrawalsBy")} head={[t("reports.gateway"), t("reports.count"), t("reports.amount")]} numeric={[1, 2]} rows={x.withdrawals.map((r) => ({ key: r.gateway, cells: [r.gateway, r.count, <Amount text={r.amount} currency={c} />] }))} />
                <Table caption={t("reports.gamesBy")} head={[t("dashboard.provider"), t("dashboard.game"), t("reports.providerGgr"), t("reports.walletGgr")]} numeric={[2, 3]} rows={x.games.map((r) => ({ key: `${r.provider}/${r.gameId}`, cells: [r.provider, r.gameName || r.gameId, <Amount text={r.providerGgr} currency={c} />, <Amount text={r.walletGgr} currency={c} />] }))} />
              </section>
            ))}
            <Table caption={t("reports.differences")} head={[t("reports.round"), t("dashboard.provider"), t("reports.status")]} rows={d.differences.map((r) => ({ key: r.roundId, cells: [r.roundId, r.provider, r.status] }))} />
            {d.corrections.length > 0 ? <p>{t("reports.corrections", { count: d.corrections.length })}</p> : null}
          </>
        )}
      </Answered>
    </>
  );
}

function Compliance({ search, set, zone }: ReportProps) {
  const rg = useReporting<Rows<RgRow>>("/compliance/rg-cases", period(search));
  const kyc = useReporting<Rows<KycRow>>("/compliance/kyc-decisions", period(search));
  const from = (f: string) => set({ ...search, from: f });
  return (
    <>
      <Filters search={search} fields={periodFields()} onApply={set} />
      <section className="bo-card" aria-labelledby="rep-rg">
        <h2 id="rep-rg">{t("reports.rgCases")}</h2>
        <ExportFor kind="rg_cases" params={period(search)} />
        <Answered q={rg} onFrom={from}>
          {(r) => (
            <Table
              caption={t("reports.rgCases")}
              head={[t("dashboard.player"), t("reports.type"), t("reports.opened"), t("reports.closed"), t("reports.outcome"), t("reports.reason")]}
              rows={r.rows.map((x, i) => ({ key: `${x.playerId}-${i}`, cells: [player(x.playerId), x.type, when(x.openedAt, zone), when(x.closedAt, zone), x.outcome, x.reason] }))}
            />
          )}
        </Answered>
      </section>
      <section className="bo-card" aria-labelledby="rep-kyc">
        <h2 id="rep-kyc">{t("reports.kycDecisions")}</h2>
        <ExportFor kind="kyc_decisions" params={period(search)} />
        <Answered q={kyc} onFrom={from}>
          {(r) => (
            <Table
              caption={t("reports.kycDecisions")}
              head={[t("dashboard.player"), t("reports.category"), t("reports.submitted"), t("reports.decided"), t("reports.outcome"), t("reports.reason")]}
              rows={r.rows.map((x, i) => ({ key: `${x.playerId}-${i}`, cells: [player(x.playerId), x.category, when(x.submittedAt, zone), when(x.decidedAt, zone), x.outcome, x.reason] }))}
            />
          )}
        </Answered>
      </section>
    </>
  );
}

/** The dispute report is a document only: a player, a session or a period, and a format. */
function Disputes({ search, set }: ReportProps) {
  const ready = !!search.playerId && (!!search.sessionId || (!!search.fromDate && !!search.toDate));
  return (
    <>
      <p className="bo-muted">{t("reports.disputes.how")}</p>
      <Filters
        search={{ format: "pdf", ...search }}
        fields={[
          { key: "playerId", label: t("reports.playerId") },
          { key: "sessionId", label: t("reports.sessionId") },
          { key: "fromDate", label: t("reports.from"), type: "date" },
          { key: "toDate", label: t("reports.to"), type: "date" },
          { key: "format", label: t("reports.format"), options: [{ value: "pdf", label: t("reports.format.pdf") }, { value: "html", label: t("reports.format.html") }, { value: "csv", label: t("reports.format.csv") }] },
        ]}
        onApply={set}
      />
      {ready ? (
        <Export
          kind="dispute_report"
          params={defined({ playerId: search.playerId, sessionId: search.sessionId, fromDate: search.sessionId ? undefined : search.fromDate, toDate: search.sessionId ? undefined : search.toDate, format: search.format ?? "pdf" })}
        />
      ) : (
        <p className="bo-muted">{t("reports.disputes.needs")}</p>
      )}
    </>
  );
}

const Bodies: Record<ReportId, (p: ReportProps) => ReactNode> = {
  revenue: (p) => <Revenue {...p} />,
  players: (p) => <Players {...p} />,
  transactions: (p) => <Transactions {...p} />,
  rewards: (p) => <Rewards {...p} />,
  providers: (p) => <Providers {...p} />,
  "day-end": (p) => <DayEndReport {...p} />,
  compliance: (p) => <Compliance {...p} />,
  disputes: (p) => <Disputes {...p} />,
};

export function ReportView({ report, search, set, zone }: ReportProps & { report: string }) {
  const me = useOperator();
  const status = useReportingStatus().data;
  const def = Reports.find((r) => r.id === report);
  if (def === undefined) return <p role="alert">{t("frame.notFound")}</p>;
  return (
    <section className="bo-report" data-report={def.id}>
      <header className="bo-dashboard-head">
        <h1>{t(def.label)}</h1>
        <ViewsFreshness status={status} views={def.views} />
      </header>
      <p className="bo-muted">{t(def.about)}</p>
      {!holds(me, def.permission) ? <p className="bo-muted">{t("action.needsPermission", { permission: def.permission })}</p> : null}
      {/* Each report keeps its own drafts: a new search or report starts them again. */}
      <div key={JSON.stringify(search)} className="bo-report-body">
        {Bodies[def.id]({ search, set, zone })}
      </div>
    </section>
  );
}

export function ReportsIndex() {
  const me = useOperator();
  const mine = reportsFor(me);
  return (
    <section className="bo-report">
      <h1>{t("area.reports")}</h1>
      {mine.length === 0 ? <p>{t("reports.noneForYou")}</p> : null}
      <ul className="bo-report-list">
        {mine.map((r) => (
          <li key={r.id} className="bo-card">
            <Link className="bo-link" to="/reports/$report" params={{ report: r.id }}>
              {t(r.label)}
            </Link>
            <span className="bo-muted">{t(r.about)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function strings(search: Record<string, unknown>): Search {
  return Object.fromEntries(Object.entries(search).flatMap(([k, v]) => (v === undefined || v === null || v === "" ? [] : [[k, String(v)]])));
}

export function ReportPage() {
  const { report } = useParams({ strict: false }) as { report?: string };
  const search = strings(useSearch({ strict: false }) as Record<string, unknown>);
  const navigate = useNavigate();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (report === undefined) return <ReportsIndex />;
  const set = (s: Search) => void navigate({ to: "/reports/$report", params: { report }, search: defined(s) });
  return <ReportView report={report} search={search} set={set} zone={zone} />;
}
