// The deposits (T113; spec 004 FR-023, FR-029; spec 030 H1, H2; the original's transactions, transaction,
// analytics pages): merchant's payments of the sub-brand worked on (or every one the reader may read),
// newest first, paged, filtered by its status words and searched, or found by a reference exactly; one
// opened in full; the analytics (counts by status, captured volume per currency, never summed across
// currencies, by day and by provider); and the payments captured but not credited, each retried by a
// payments operator. A brand operator holding `wallet.read` reads the deposits of the sub-brands naming
// this brand; the uncredited are payments operators' alone.
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { read } from "../configuration/page.tsx";
import { word } from "../word.ts";
import { Rows, useChanged, usePayments, type Col } from "./parts.tsx";
import { useScope } from "./hierarchy.tsx";
import { RecordLink } from "../../components/links.tsx";

export interface DepositRow {
  paymentId: string;
  merchantId: string;
  customerRef: string;
  amountCents: number;
  currency: string;
  status: string;
  psp: string;
  pspTransactionId?: string | null;
  terminalId?: string | null;
  failureReason?: string | null;
  createdAt: string;
}

interface Page {
  payments: DepositRow[];
  total: number;
  page: number;
  pageSize: number;
  statusCounts: Record<string, number>;
}

interface Summary {
  totalCount: number;
  statusCounts: Record<string, number>;
  capturedVolume: { currency: string; amountCents: number }[];
  daily: { date: string; total: number; captured: number }[];
  psps: { psp: string; total: number; captured: number }[];
  capturedWithoutAmount: number;
}

interface Uncredited {
  paymentId: string;
  merchantId: string;
  capturedAt?: string | null;
  attempts: number;
  lastAnswer?: string | null;
  creditStatus?: string | null;
}

const StatusFilters = ["all", "pending", "captured", "failed", "expired"] as const;
const PageSize = 25;
const money = (minor: number | null | undefined, currency: string | null | undefined) =>
  typeof minor === "number" && currency ? <Money value={{ minor, currency }} /> : null;

/** A payment's fields as merchant answers them, each named. */
const DetailFields = [
  "paymentId", "merchantId", "customerRef", "status", "psp", "scheme", "terminalId", "pspTransactionId", "createdAt", "expiresAt",
  "capturedAt", "failedAt", "expiredAt", "failureCode", "failureReason", "waitingReason", "variance", "receivedCryptoAmount", "receivedConvertAmount", "receivedConvertCurrency",
] as const;

function Detail({ paymentId, close }: { paymentId: string; close(): void }) {
  const tz = useZone();
  const q = useQuery({ queryKey: ["payments", "deposit", paymentId], queryFn: () => read<Record<string, unknown>>(merchant.getByPaymentId({ paymentId })) });
  return (
    <section className="bo-pay-detail" aria-labelledby="pay-deposit">
      <h2 id="pay-deposit">{t("payments.deposits.detail", { id: paymentId })}</h2>
      <button type="button" className="bo-button" onClick={close}>
        {t("payments.deposits.close")}
      </button>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {q.data ? (
        <dl className="bo-pay-dl">
          <dt>{t("payments.deposits.amount")}</dt>
          <dd>{money(q.data.amountCents as number, q.data.currency as string)}</dd>
          {typeof q.data.originalAmountCents === "number" ? (
            <>
              <dt>{t("payments.deposits.originalAmount")}</dt>
              <dd>{money(q.data.originalAmountCents, q.data.currency as string)}</dd>
            </>
          ) : null}
          {DetailFields.filter((f) => q.data[f] !== null && q.data[f] !== undefined && q.data[f] !== "").map((f) => (
            <div key={f}>
              <dt>{word("payments.deposits.field", f)}</dt>
              <dd>{f.endsWith("At") && typeof q.data[f] === "string" ? <DateTime instant={q.data[f]} zone={tz} /> : String(q.data[f])}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </section>
  );
}

function Analytics({ merchantId }: { merchantId: string }) {
  const q = usePayments<Summary>(["summary", merchantId], () => merchant.getCashierSummary({ query: merchantId ? { merchantId } : {} }));
  if (q.isError) return <RefusalNotice refusal={q.error as never} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const s = q.data;
  return (
    <section aria-labelledby="pay-analytics" className="bo-pay-analytics">
      <h2 id="pay-analytics">{t("payments.deposits.analytics")}</h2>
      <dl className="bo-pay-figures">
        <div>
          <dt>{t("payments.deposits.total")}</dt>
          <dd className="figures">{s.totalCount}</dd>
        </div>
        {Object.entries(s.statusCounts).map(([k, n]) => (
          <div key={k}>
            <dt>{word("payments.deposits.status", k)}</dt>
            <dd className="figures">{n}</dd>
          </div>
        ))}
        {s.capturedVolume.map((v) => (
          <div key={v.currency}>
            <dt>{t("payments.deposits.captured", { currency: v.currency })}</dt>
            <dd>{money(v.amountCents, v.currency)}</dd>
          </div>
        ))}
      </dl>
      {s.capturedWithoutAmount > 0 ? <p className="bo-muted">{t("payments.deposits.withoutAmount", { count: s.capturedWithoutAmount })}</p> : null}
      <Rows
        caption="payments.deposits.byProvider"
        cols={[
          { header: "payments.deposits.provider", cell: (p: Summary["psps"][number]) => p.psp },
          { header: "payments.deposits.total", numeric: true, cell: (p) => p.total },
          { header: "payments.deposits.capturedCount", numeric: true, cell: (p) => p.captured },
        ]}
        rows={s.psps}
        rowKey={(p) => p.psp}
        empty="payments.deposits.none"
      />
      <Rows
        caption="payments.deposits.byDay"
        cols={[
          { header: "payments.deposits.day", cell: (d: Summary["daily"][number]) => d.date },
          { header: "payments.deposits.total", numeric: true, cell: (d) => d.total },
          { header: "payments.deposits.capturedCount", numeric: true, cell: (d) => d.captured },
        ]}
        rows={[...s.daily].reverse().slice(0, 31)}
        rowKey={(d) => d.date}
        empty="payments.deposits.none"
      />
    </section>
  );
}

function UncreditedList({ merchantId }: { merchantId: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const done = useChanged("uncredited");
  const q = usePayments<{ payments: Uncredited[] }>(["uncredited"], () => merchant.getPaymentsCashierUncredited());
  const rows = (q.data?.payments ?? []).filter((p) => !merchantId || p.merchantId === merchantId);
  const cols: Col<Uncredited>[] = [
    { header: "payments.deposits.capturedAt", cell: (p) => (p.capturedAt ? <DateTime instant={p.capturedAt} zone={tz} /> : null) },
    { header: "payments.deposits.payment", cell: (p) => p.paymentId },
    { header: "payments.deposits.attempts", numeric: true, cell: (p) => p.attempts },
    { header: "payments.deposits.lastAnswer", cell: (p) => p.lastAnswer ?? "" },
    { header: "payments.deposits.creditStatus", cell: (p) => (p.creditStatus ? <Status value={p.creditStatus} /> : null) },
    ...(readOnly
      ? []
      : [
          {
            header: "payments.act" as const,
            cell: (p: Uncredited) => (
              <Action
                action={Actions["payments.deposit.retryCredit"]}
                me={me}
                bags={bags}
                subject={<p>{t("payments.deposits.retrySubject", { id: p.paymentId })}</p>}
                run={() => merchant.postPaymentsCashierRetryCredit({ paymentId: p.paymentId })}
                onDone={done}
              />
            ),
          },
        ]),
  ];
  return (
    <section aria-labelledby="pay-uncredited">
      <h2 id="pay-uncredited">{t("payments.deposits.uncredited")}</h2>
      <p className="bo-muted">{t("payments.deposits.uncreditedNote")}</p>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {q.data ? <Rows caption="payments.deposits.uncredited" cols={cols} rows={rows} rowKey={(p) => p.paymentId} empty="payments.deposits.noneUncredited" /> : null}
    </section>
  );
}

export function DepositsSection() {
  const me = useOperator();
  const scope = useScope();
  const client = useQueryClient();
  const store = useRouterListStore();
  const list = useListState(store);
  const tz = useZone();
  const [opened, setOpened] = useState<string | undefined>(undefined);
  const merchantId = scope.level === "subBrand" ? (scope.id ?? "") : "";
  const f = list.state.filters;
  const status = f.status ?? "all";
  const query: Record<string, string> = { status, page: String(list.state.page), pageSize: String(PageSize) };
  if (merchantId) query.merchantId = merchantId;
  if (f.q) query.search = f.q;
  if (f.reference) query.reference = f.reference;
  const q = useQuery({ queryKey: ["payments", "deposits", query], queryFn: () => read<Page>(merchant.getCashier({ query })), refetchInterval: 30_000 });
  const [search, setSearch] = useState(f.q ?? "");
  const [reference, setReference] = useState(f.reference ?? "");
  const columns: Column<DepositRow>[] = [
    { id: "created", header: "payments.deposits.created", cell: (r) => <DateTime instant={r.createdAt} zone={tz} /> },
    { id: "amount", header: "payments.deposits.amount", numeric: true, cell: (r) => money(r.amountCents, r.currency) },
    { id: "status", header: "payments.deposits.statusHeader", cell: (r) => <Status value={r.status} /> },
    { id: "psp", header: "payments.deposits.provider", cell: (r) => r.psp },
    { id: "customer", header: "payments.deposits.customer", cell: (r) => (r.customerRef ? <RecordLink to={{ kind: "player", id: r.customerRef }}>{r.customerRef}</RecordLink> : null) },
    { id: "payment", header: "payments.deposits.payment", cell: (r) => r.paymentId },
    { id: "failure", header: "payments.deposits.failure", cell: (r) => r.failureReason ?? "" },
  ];
  return (
    <div>
      <ZoneNote zone={tz} />
      <form
        className="bo-pay-filters"
        onSubmit={(e) => {
          e.preventDefault();
          const filters: Record<string, string> = Object.fromEntries(Object.entries(f).filter(([k]) => k !== "q" && k !== "reference"));
          if (search.trim()) filters.q = search.trim();
          if (reference.trim()) filters.reference = reference.trim();
          store.set({ ...list.state, filters, page: 1, scroll: 0 });
        }}
      >
        <div className="bo-field">
          <label htmlFor="pay-status">{t("payments.deposits.statusHeader")}</label>
          <select id="pay-status" value={status} onChange={(e) => list.setFilter("status", e.target.value === "all" ? undefined : e.target.value)}>
            {StatusFilters.map((s) => (
              <option key={s} value={s}>
                {word("payments.deposits.status", s)}
                {q.data?.statusCounts[s] !== undefined ? ` (${q.data.statusCounts[s]})` : ""}
              </option>
            ))}
          </select>
        </div>
        <div className="bo-field">
          <label htmlFor="pay-search">{t("payments.deposits.search")}</label>
          <input id="pay-search" className="bo-input" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <div className="bo-field">
          <label htmlFor="pay-reference">{t("payments.deposits.reference")}</label>
          <input id="pay-reference" className="bo-input" value={reference} onChange={(e) => setReference(e.target.value)} />
        </div>
        <button type="submit" className="bo-button">
          {t("payments.deposits.find")}
        </button>
      </form>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="payments.deposits.caption"
        columns={columns}
        rows={q.data?.payments}
        rowKey={(r) => r.paymentId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
        onOpen={(r) => setOpened(r.paymentId)}
        current={opened}
      />
      {opened ? (
        <Detail
          paymentId={opened}
          close={() => {
            setOpened(undefined);
            void client.invalidateQueries({ queryKey: ["payments", "deposits"] });
          }}
        />
      ) : null}
      <Analytics merchantId={merchantId} />
      {holds(me, "payments.read") ? <UncreditedList merchantId={merchantId} /> : null}
    </div>
  );
}
