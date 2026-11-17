// The worklist (T064; US2, FR-016 to FR-018): the first page. The open work the operator's roles let
// them act on in this brand, one row per item, oldest first, with each kind's count from its owner; a
// kind whose source cannot be read is said to be unavailable, never shown as none. Opening an item
// shows it beside the list with a link to its investigation and, for a withdrawal, its decisions;
// acting on it closes it and keeps the operator on the worklist at the same place: the row that took
// the item's place is current and focused, the page and scroll unchanged. An item someone else acted on, read again from its owner, shows who did what and when, and
// offers nothing.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useContext, useEffect, useRef, useState, type MouseEvent } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { payouts } from "../../../shared/owners/index.ts";
import { explain } from "../../../shared/refusals.ts";
import { Kinds } from "../../../shared/worklist.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { NavigateContext, RecordLink } from "../../components/links.tsx";
import { List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { actionStateOf } from "../withdrawals/read.ts";
import { actedOn, everyKindPaging, investigationOf, keyOf, merged, placeAfter, readKindPage, readWorklist, stateOf, stillOpen, type Item, type KindAnswer, type Request } from "./read.ts";
import "./worklist.css";

const PageSize = 50;
const labelOf = (kind: string): MessageKey => Kinds.find((k) => k.id === kind)?.label ?? "area.worklist";
const reasonOf = (reason: string) => word("worklist.reason", reason);
const Withdrawals = new Set(["withdrawals_pending", "payouts_needing_review"]);

/** What the owner said of the item, in its words: a status as a status, the rest as given. */
function Detail({ item }: { item: Item }) {
  const d = item.detail;
  const status = typeof d.payoutStatus === "string" && item.kind === "payouts_needing_review" ? d.payoutStatus : typeof d.status === "string" ? d.status : undefined;
  const rest = Object.entries(d)
    .filter(([k, v]) => k !== "status" && k !== "payoutStatus" && v !== null && v !== "")
    .map(([k, v]) => word(`worklist.detail.${k}`, String(v)));
  return (
    <span className="bo-worklist-detail">
      {status ? <Status value={status.toLowerCase()} /> : null}
      {rest.length > 0 ? <span>{rest.join(" · ")}</span> : null}
    </span>
  );
}

const columns = (tz: string): Column<Item>[] => [
  { id: "since", header: "worklist.since", cell: (i) => <DateTime instant={i.since} zone={tz} /> },
  { id: "kind", header: "worklist.kind", cell: (i) => t(labelOf(i.kind)) },
  { id: "subject", header: "worklist.subject", cell: (i) => (i.subject.player ? <RecordLink to={{ kind: "player", id: i.subject.player }}>{i.subject.player}</RecordLink> : <code>{i.id}</code>) },
  { id: "amount", header: "worklist.amount", numeric: true, cell: (i) => (i.amount ? <Money value={i.amount} /> : null) },
  { id: "detail", header: "worklist.detail", cell: (i) => <Detail item={i} /> },
];

function KindsSummary({ kinds, only, choose }: { kinds: readonly KindAnswer[]; only: string | undefined; choose(kind: string | undefined): void }) {
  return (
    <ul className="bo-worklist-kinds" aria-label={t("worklist.kinds")}>
      <li>
        <button type="button" className="bo-button" aria-pressed={only === undefined} onClick={() => choose(undefined)}>
          {t("worklist.all")}
        </button>
      </li>
      {kinds.map((k) => (
        <li key={k.kind} data-kind={k.kind} data-state={k.state}>
          <button type="button" className="bo-button" aria-pressed={only === k.kind} disabled={k.state !== "ok"} onClick={() => choose(k.kind)}>
            {k.state === "ok"
              ? t("worklist.kindCount", { kind: t(labelOf(k.kind)), count: k.total })
              : t("worklist.kindUnavailable", { kind: t(labelOf(k.kind)) })}
          </button>
        </li>
      ))}
    </ul>
  );
}

/** A link followed by the frame's router, so returning keeps the worklist's place. */
function Investigate({ href }: { href: string }) {
  const navigate = useContext(NavigateContext);
  const follow = (ev: MouseEvent<HTMLAnchorElement>) => {
    if (!navigate || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
    ev.preventDefault();
    navigate(href);
  };
  return (
    <a className="bo-link" href={href} onClick={follow}>
      {t("worklist.investigate")}
    </a>
  );
}

async function readRequest(requestId: string): Promise<Request> {
  const r = await payouts.getWithdrawalRequestsByRequestId({ requestId });
  if (!r.ok) throw explain(r.status, await r.text());
  return (await r.json()) as Request;
}

/** A withdrawal item: payouts' request read again every few seconds, its decisions while it still waits. */
function WithdrawalItem({ item, onActed }: { item: Item; onActed(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const tz = useZone();
  const queryKey = ["payouts", "request", item.id];
  const q = useQuery({ queryKey, queryFn: () => readRequest(item.id), refetchInterval: 5_000 });
  const r = q.data;
  // a refusal because the request moved on is followed by a read of it (contracts/sources.md)
  const run = (call: () => Promise<Response>) => async () => {
    const answer = await call();
    if (answer.status === 409 || answer.status === 404) void client.invalidateQueries({ queryKey });
    return answer;
  };
  const done = () => {
    void client.invalidateQueries({ queryKey });
    onActed();
  };
  if (q.isError) return <RefusalNotice refusal={q.error as never} />;
  if (!r) return <p>{t("list.loading")}</p>;
  const open = stillOpen(r, item.kind);
  const acted = open ? undefined : actedOn(r);
  const state = stateOf(r);
  // the state the actions are offered in: an approved request whose payment needs review is `needs_review`
  const acting = actionStateOf(r);
  const id = { requestId: item.id };
  return (
    <>
      <dl className="bo-facts">
        <dt>{t("worklist.amount")}</dt>
        <dd>
          <Money value={{ minor: r.amount, currency: r.currency }} />
        </dd>
        <dt>{t("worklist.status")}</dt>
        <dd>
          <Status value={state} />
          {item.kind === "payouts_needing_review" ? <> <Status value={r.payoutStatus.toLowerCase()} /></> : null}
        </dd>
      </dl>
      {open ? (
        readOnly ? null : (
          <div className="bo-actions">
            {item.kind === "withdrawals_pending" ? (
              <>
                <Action action={Actions["withdrawal.approve"]} me={me} bags={bags} state={acting} run={run(() => payouts.postWithdrawalRequestsApprove(id))} onDone={done} />
                <Action action={Actions["withdrawal.reject"]} me={me} bags={bags} state={acting} run={(reason) => run(() => payouts.postWithdrawalRequestsReject(id, { body: { reason } }))()} onDone={done} />
              </>
            ) : (
              <>
                <Action action={Actions["withdrawal.confirmPayout"]} me={me} bags={bags} state={acting} run={(note) => run(() => payouts.postWithdrawalRequestsConfirmPayout(id, { body: note ? { note } : {} }))()} onDone={done} />
                <Action action={Actions["withdrawal.markPayoutFailed"]} me={me} bags={bags} state={acting} run={(reason) => run(() => payouts.postWithdrawalRequestsMarkPayoutFailed(id, { body: { reason } }))()} onDone={done} />
              </>
            )}
          </div>
        )
      ) : (
        <div className="bo-worklist-acted" data-acted role="status">
          {acted ? (
            <p>
              <span>{t(`worklist.acted.${acted.what}`, { operator: acted.operator === me.subject ? t("worklist.you") : acted.operator })}</span>{" "}
              <DateTime instant={acted.at} zone={tz} />
            </p>
          ) : (
            <p>
              {t("worklist.acted.moved")} <Status value={state} />
            </p>
          )}
          <p className="bo-muted">{t("worklist.noAction")}</p>
        </div>
      )}
    </>
  );
}

function ItemPanel({ item, onActed, onClose }: { item: Item; onActed(): void; onClose(): void }) {
  const tz = useZone();
  const href = investigationOf(item);
  return (
    <section className="bo-worklist-item" data-item={keyOf(item)} aria-labelledby={`worklist-${keyOf(item)}`}>
      <h2 id={`worklist-${keyOf(item)}`}>{t("worklist.itemTitle", { kind: t(labelOf(item.kind)), id: item.id })}</h2>
      <dl className="bo-facts">
        <dt>{t("worklist.since")}</dt>
        <dd>
          <DateTime instant={item.since} zone={tz} />
        </dd>
        {item.subject.player ? (
          <>
            <dt>{t("worklist.subject")}</dt>
            <dd>
              <RecordLink to={{ kind: "player", id: item.subject.player }} />
            </dd>
          </>
        ) : null}
        <dt>{t("worklist.detail")}</dt>
        <dd>
          <Detail item={item} />
        </dd>
      </dl>
      {Withdrawals.has(item.kind) ? <WithdrawalItem item={item} onActed={onActed} /> : null}
      <p className="bo-actions">
        {href ? <Investigate href={href} /> : null}
        <button type="button" className="bo-button" onClick={onClose}>
          {t("worklist.close")}
        </button>
      </p>
    </section>
  );
}

export function WorklistPage() {
  const me = useOperator();
  const router = useRouter();
  const client = useQueryClient();
  const tz = useZone();
  const list = useListState(useRouterListStore());
  const only = list.state.filters.kind;
  const search = useRouterState({ select: (s) => s.location.search as Record<string, unknown> });
  const current = typeof search.at === "string" ? search.at : undefined;
  const openedKey = typeof search.item === "string" ? search.item : undefined;
  const chosen = (me.preferences as { worklistKinds?: unknown } | null)?.worklistKinds;
  const kinds = Array.isArray(chosen) ? chosen.filter((k): k is string => typeof k === "string") : undefined;

  const all = useQuery({ queryKey: ["worklist", kinds ?? null], queryFn: () => readWorklist(kinds), refetchInterval: 30_000 });
  const one = useQuery({ queryKey: ["worklist", "kind", only, list.state.page], queryFn: () => readKindPage(only!, list.state.page), enabled: only !== undefined, refetchInterval: 30_000 });

  const answered = all.data?.kinds;
  const everyRow = answered ? merged(answered) : undefined;
  const oneKind = one.data?.state === "ok" ? one.data : undefined;
  const rows = only !== undefined ? oneKind?.items : everyRow?.slice((list.state.page - 1) * PageSize, list.state.page * PageSize);
  const total = only !== undefined ? oneKind?.total : answered?.reduce((n, k) => n + (k.state === "ok" ? k.total : 0), 0);
  const complete =
    only !== undefined
      ? oneKind !== undefined && oneKind.page * PageSize >= oneKind.total
      : answered !== undefined && everyRow !== undefined && list.state.page * PageSize >= everyRow.length && answered.every((k) => k.state === "ok" && k.total <= k.items.length);
  const paging = only === undefined && answered !== undefined ? everyKindPaging(answered, list.state.page, PageSize) : undefined;
  const unavailable = (answered ?? []).filter((k): k is Extract<KindAnswer, { state: "unavailable" }> => k.state === "unavailable");

  // the item opened is kept as last seen, so one that leaves the list (acted on elsewhere) stays shown
  const seen = useRef(new Map<string, Item>());
  for (const r of rows ?? []) seen.current.set(keyOf(r), r);
  const opened = openedKey ? (rows?.find((r) => keyOf(r) === openedKey) ?? seen.current.get(openedKey)) : undefined;

  // the place (`at`, the row last opened) and the item shown (`item`) are in the URL, so a return keeps both
  const place = (at: string | undefined, item: string | undefined) =>
    router.navigate({ to: router.state.location.pathname, search: (prev: Record<string, unknown>) => ({ ...prev, at, item }), replace: true } as never);

  // after acting, the row that took the item's place is focused once the list is read again
  const [refocus, setRefocus] = useState<string | undefined>(undefined);
  useEffect(() => {
    if (!refocus || !rows?.some((r) => keyOf(r) === refocus)) return;
    document.querySelector<HTMLElement>(`tr[data-row="${CSS.escape(refocus)}"]`)?.focus({ preventScroll: true });
    setRefocus(undefined);
  }, [refocus, rows]);

  const acted = (item: Item) => () => {
    const next = placeAfter(rows ?? [], keyOf(item));
    void place(next, undefined);
    setRefocus(next);
    void client.invalidateQueries({ queryKey: ["worklist"] });
  };

  const choose = (kind: string | undefined) => list.setFilter("kind", kind);
  const failed = only !== undefined ? (one.data?.state === "unavailable" ? one.data : undefined) : undefined;

  return (
    <section className="bo-worklist">
      <h1>{t("area.worklist")}</h1>
      <p className="bo-muted">{t("worklist.order")}</p>
      {answered && answered.length > 0 ? <KindsSummary kinds={answered} only={only} choose={choose} /> : null}
      {/* no kind admitted: the sentence alone, no empty list under it (DR-17) */}
      {answered && answered.length === 0 ? <p>{t("worklist.noKinds")}</p> : null}
      {unavailable.map((k) => (
        <p key={k.kind} className="bo-worklist-unavailable" role="status" data-unavailable={k.kind}>
          {t("worklist.unavailable", { kind: t(labelOf(k.kind)), reason: reasonOf(k.reason) })}
        </p>
      ))}
      {failed ? <p role="status">{t("worklist.unavailable", { kind: t(labelOf(failed.kind)), reason: reasonOf(failed.reason) })}</p> : null}
      {answered && answered.length === 0 ? null : (
        <>
          <ZoneNote zone={tz} />
          {all.isError ? <RefusalNotice refusal={all.error as never} /> : null}
          <div className="bo-worklist-body">
            <List
              caption="worklist.caption"
              columns={columns(tz)}
              rows={rows}
              rowKey={keyOf}
              total={total}
              complete={complete}
              lastPage={paging?.lastPage}
              fetching={all.isFetching || one.isFetching}
              zone={tz}
              list={list}
              onOpen={(i) => void place(keyOf(i), keyOf(i))}
              current={current}
            />
            {paging && paging.more.length > 0 ? (
              <ul className="bo-worklist-more" aria-label={t("worklist.more")}>
                {paging.more.map((m) => (
                  <li key={m.kind} data-more={m.kind}>
                    <button type="button" className="bo-link" onClick={() => choose(m.kind)}>
                      {t("worklist.moreIn", { count: m.count, kind: t(labelOf(m.kind)) })}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
            {opened ? <ItemPanel key={keyOf(opened)} item={opened} onActed={acted(opened)} onClose={() => void place(current, undefined)} /> : null}
          </div>
        </>
      )}
    </section>
  );
}
