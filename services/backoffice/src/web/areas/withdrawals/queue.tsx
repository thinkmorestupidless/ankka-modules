// The withdrawals queue (T081; US4, parity *Withdrawal queue by state, needs-review count, filters*):
// payouts' requests by state, pending review first as the original opened it, with the brand's two
// counts (requests pending review, payments needing review, the second a filter of its own); filtered by
// identity status, method and amount band, and searched by a player's id, name or email (the server
// resolves a name or an email to player ids, src/server/composite/withdrawals.ts). Newest first, as
// payouts orders by default, or oldest first by the time column. The place is kept in the URL.
import { useQuery } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useId, useState, type FormEvent } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column, type ListState } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { word } from "../word.ts";
import { readQueue, type Row } from "./read.ts";
import "./withdrawals.css";
import { RecordLink } from "../../components/links.tsx";

export const PageSize = 50;

/** payouts' states, in the original's order; `ALL` is every state. */
export const States = ["PENDING_REVIEW", "APPROVED", "AWAITING_MANUAL_PAYOUT", "COMPLETED", "REJECTED", "FAILED", "CANCELLED", "REVERSED", "ALL"] as const;
const Kyc = ["verified", "pending", "unverified", "rejected"] as const;
const Bands = ["lt500", "500to5000", "gt5000"] as const;

/** The query payouts is asked, from the list's place. */
export function queryOf(s: ListState, pageSize = PageSize): Record<string, string> {
  const f = s.filters;
  const needsReview = f.payout === "needs_review";
  const q: Record<string, string> = { status: needsReview ? "APPROVED" : (f.status ?? "PENDING_REVIEW"), page: String(s.page), pageSize: String(pageSize) };
  if (needsReview) q.payoutStatus = "NEEDS_REVIEW";
  if (f.kyc) q.kyc = f.kyc;
  if (f.method) q.method = f.method;
  if (f.band) q.amountRange = f.band;
  if (f.q) q.q = f.q;
  if (s.sort === "created") q.sort = "oldest";
  return q;
}

const columns = (tz: string): Column<Row>[] => [
  { id: "created", header: "withdrawals.queue.created", sort: "created", cell: (r) => <DateTime instant={r.createdAt} zone={tz} /> },
  { id: "player", header: "withdrawals.queue.player", cell: (r) => <RecordLink to={{ kind: "player", id: r.playerId }}>{r.playerId}</RecordLink> },
  { id: "kyc", header: "withdrawals.queue.kyc", cell: (r) => <Status value={r.kycStatus || "unverified"} /> },
  { id: "amount", header: "withdrawals.queue.amount", numeric: true, cell: (r) => <Money value={{ minor: r.amount, currency: r.currency }} /> },
  { id: "terminal", header: "withdrawals.queue.terminal", cell: (r) => r.terminalName },
  { id: "method", header: "withdrawals.queue.method", cell: (r) => r.method },
  { id: "destination", header: "withdrawals.queue.destination", cell: (r) => (r.destinationLastFour ? t("withdrawals.destination.lastFour", { lastFour: r.destinationLastFour }) : null) },
  {
    id: "status",
    header: "withdrawals.queue.status",
    cell: (r) => (
      <>
        <Status value={r.status.toLowerCase()} />
        {r.payoutStatus && r.payoutStatus.toLowerCase() !== "none" ? (
          <>
            {" "}
            <Status value={r.payoutStatus.toLowerCase()} />
          </>
        ) : null}
      </>
    ),
  },
];

/** Opening a row keeps the place (`at`, the row opened) in the queue's entry, then opens the request. */
export function useOpenRequest() {
  const router = useRouter();
  return (row: Row) =>
    void router
      .navigate({ to: router.state.location.pathname, search: (prev: Record<string, unknown>) => ({ ...prev, at: row.requestId }), replace: true } as never)
      .then(() => router.navigate({ to: "/withdrawals/$request", params: { request: row.requestId } } as never));
}

function Search({ value, onSearch }: { value: string; onSearch(q: string | undefined): void }) {
  const id = useId();
  const [typed, setTyped] = useState(value);
  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    onSearch(typed.trim() === "" ? undefined : typed.trim());
  };
  return (
    <form className="bo-field" role="search" onSubmit={submit}>
      <label htmlFor={id}>{t("withdrawals.queue.search")}</label>
      <input id={id} className="bo-input" type="search" value={typed} maxLength={256} onChange={(e) => setTyped(e.target.value)} />
      <button type="submit" className="bo-button">
        {t("withdrawals.queue.searchGo")}
      </button>
    </form>
  );
}

function Choice({ named, value, options, onChange }: { named: MessageKey; value: string | undefined; options: readonly [string, string][]; onChange(v: string | undefined): void }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(named)}</label>
      <select id={id} value={value ?? ""} onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}>
        <option value="">{t("withdrawals.queue.any")}</option>
        {options.map(([v, l]) => (
          <option key={v} value={v}>
            {l}
          </option>
        ))}
      </select>
    </div>
  );
}

export function WithdrawalsQueuePage() {
  const tz = useZone();
  const store = useRouterListStore();
  const list = useListState(store);
  const at = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).at });
  const open = useOpenRequest();
  const freshness = useFreshness(["payouts"]);
  const query = queryOf(list.state);
  const q = useQuery({ queryKey: ["payouts", "queue", query], queryFn: () => readQueue(query), refetchInterval: 30_000 });
  const f = list.state.filters;
  const needsReview = f.payout === "needs_review";
  const status = needsReview ? undefined : (f.status ?? "PENDING_REVIEW");
  const counts = q.data?.counts;
  // the state and the payments needing review are one choice: both filters set at once
  const choose = (next: { status?: string; payout?: string }) => {
    const s = store.get();
    const filters: Record<string, string> = Object.fromEntries(Object.entries(s.filters).filter(([k]) => k !== "status" && k !== "payout"));
    if (next.status) filters.status = next.status;
    if (next.payout) filters.payout = next.payout;
    store.set({ ...s, filters, page: 1, scroll: 0 });
  };
  const chooseState = (s: string) => choose(s === "PENDING_REVIEW" ? {} : { status: s });
  const seeNeedsReview = () => choose({ payout: "needs_review" });
  return (
    <section className="bo-withdrawals">
      <h1>{t("area.withdrawals")}</h1>
      <ul className="bo-withdrawal-counts" aria-label={t("withdrawals.queue.counts")}>
        <li data-count="pendingReview">
          <button type="button" className="bo-button" aria-pressed={status === "PENDING_REVIEW"} onClick={() => chooseState("PENDING_REVIEW")}>
            {counts ? t("withdrawals.queue.pendingCount", { count: counts.pendingReview }) : t("status.pending_review")}
          </button>
        </li>
        <li data-count="needsReview">
          <button type="button" className="bo-button" aria-pressed={needsReview} onClick={seeNeedsReview}>
            {counts ? t("withdrawals.queue.needsReviewCount", { count: counts.needsReview }) : t("status.needs_review")}
          </button>
        </li>
      </ul>
      <div className="bo-withdrawal-states" role="group" aria-label={t("withdrawals.queue.states")}>
        {States.map((s) => (
          <button key={s} type="button" className="bo-button" aria-pressed={status === s} onClick={() => chooseState(s)}>
            {s === "ALL" ? t("withdrawals.queue.all") : word("status", s.toLowerCase())}
          </button>
        ))}
      </div>
      <div className="bo-withdrawal-filters">
        <Search key={f.q ?? ""} value={f.q ?? ""} onSearch={(v) => list.setFilter("q", v)} />
        <Choice named="withdrawals.queue.kyc" value={f.kyc} options={Kyc.map((k) => [k, word("status", k)])} onChange={(v) => list.setFilter("kyc", v)} />
        <Choice
          named="withdrawals.queue.band"
          value={f.band}
          options={Bands.map((b) => [b, t(`withdrawals.band.${b}` as MessageKey)])}
          onChange={(v) => list.setFilter("band", v)}
        />
        <MethodFilter value={f.method} onChange={(v) => list.setFilter("method", v)} />
      </div>
      {q.data?.players?.more ? <p className="bo-muted" role="status">{t("withdrawals.queue.searchMore", { count: q.data.players.matched })}</p> : null}
      {q.data?.players?.matched === 0 ? <p className="bo-muted" role="status">{t("withdrawals.queue.searchNone")}</p> : null}
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="withdrawals.queue.caption"
        columns={columns(tz)}
        rows={q.data?.requests}
        rowKey={(r) => r.requestId}
        total={q.data?.total}
        complete={q.data ? completeOf(q.data) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={open}
        current={typeof at === "string" ? at : undefined}
      />
    </section>
  );
}

/** The method as payouts names it (the terminal's scheme), typed. */
function MethodFilter({ value, onChange }: { value: string | undefined; onChange(v: string | undefined): void }) {
  const id = useId();
  const [typed, setTyped] = useState(value ?? "");
  return (
    <form
      className="bo-field"
      onSubmit={(e) => {
        e.preventDefault();
        onChange(typed.trim() === "" ? undefined : typed.trim());
      }}
    >
      <label htmlFor={id}>{t("withdrawals.queue.method")}</label>
      <input id={id} className="bo-input" value={typed} onChange={(e) => setTyped(e.target.value)} onBlur={() => onChange(typed.trim() === "" ? undefined : typed.trim())} />
    </form>
  );
}
