// A player's rounds (T076; FR-023, FR-024): game-provider's rounds of the player, newest opened first, a
// page at a time with its total; a round opened (`?round=`) shows its bets and wins, each with its
// transaction, the total staked, won and the net, and is marked unsettled until the provider settles
// it. The bets and wins are the round's own transactions' movements as game-provider recorded them.
// Beside the rounds (`?view=bets`), players' bet and win search (P10): by round id (its own or its
// provider's), transaction id, type, status and period, with what the matching rounds staked, won and
// netted per currency, never summed across currencies.
import { useId, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useRouter, useRouterState } from "@tanstack/react-router";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { gameProvider, players } from "../../../shared/owners/index.ts";
import { useFreshness } from "../../components/freshness.tsx";
import { listSearchKeys } from "../../components/list/state.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";

const PageSize = 50;

export interface RoundRow {
  provider: string;
  roundId: string;
  playerId: string;
  sessionId?: string | null;
  gameCode?: string | null;
  currency: string;
  totalStake?: number | null;
  win?: number | null;
  outcome?: string | null;
  status: string;
  openedAt?: string | null;
  settledAt?: string | null;
}

interface Movement {
  action: string;
  amount: number;
  walletTransactionId?: string | null;
}

export interface RoundState extends RoundRow {
  gameName?: string | null;
  transactions: { transactionId: string; kind: string; amount: number; movements: Movement[]; receivedAt?: string | null }[];
  openBets: { amount: number; transactionId: string }[];
}

export interface Play {
  transactionId: string;
  action: "bet" | "win" | "rollback";
  amount: number;
  at?: string | null;
}

/** A round's bets, wins and rollbacks, and its totals: staked is its bets less what was rolled back, net is won less staked. */
export function playsOf(r: RoundState): { plays: Play[]; staked: number; won: number; net: number; settled: boolean } {
  const plays: Play[] = [];
  for (const tx of r.transactions ?? [])
    for (const m of tx.movements ?? [])
      if (m.action === "bet" || m.action === "win" || m.action === "rollback") plays.push({ transactionId: tx.transactionId, action: m.action, amount: Math.abs(m.amount), at: tx.receivedAt });
  const sum = (a: Play["action"]) => plays.filter((p) => p.action === a).reduce((s, p) => s + p.amount, 0);
  const staked = sum("bet") - sum("rollback");
  const won = sum("win");
  return { plays, staked, won, net: won - staked, settled: r.status === "settled" };
}

export function RoundDetail({ round, tz }: { round: RoundState; tz: string }) {
  const { plays, staked, won, net, settled } = playsOf(round);
  const c = round.currency;
  return (
    <section className="bo-round" data-round={round.roundId} data-settled={settled ? "true" : "false"} aria-labelledby={`round-${round.roundId}`}>
      <h3 id={`round-${round.roundId}`}>
        {t("rounds.round", { id: round.roundId })} <Status value={settled ? "settled" : round.status === "cancelled" ? "cancelled" : "unsettled"} />
      </h3>
      <p className="bo-muted">
        {round.gameName ?? round.gameCode ?? ""} · {round.provider}
        {round.openedAt ? (
          <>
            {" · "}
            <DateTime instant={round.openedAt} zone={tz} />
          </>
        ) : null}
      </p>
      <table className="bo-table">
        <caption className="bo-visually-hidden">{t("rounds.plays")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("rounds.play")}</th>
            <th scope="col">{t("rounds.transaction")}</th>
            <th scope="col" className="figures">{t("rounds.amount")}</th>
          </tr>
        </thead>
        <tbody>
          {plays.map((p, i) => (
            <tr key={`${p.transactionId}/${p.action}/${i}`} data-play={p.action}>
              <td>{word("rounds.action", p.action)}</td>
              <td>{p.transactionId}</td>
              <td className="figures">
                <Money value={{ minor: p.amount, currency: c }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="bo-standing-fields" data-totals>
        <div data-total="staked">
          <dt>{t("rounds.staked")}</dt>
          <dd><Money value={{ minor: staked, currency: c }} /></dd>
        </div>
        <div data-total="won">
          <dt>{t("rounds.won")}</dt>
          <dd><Money value={{ minor: won, currency: c }} /></dd>
        </div>
        <div data-total="net">
          <dt>{t("rounds.net")}</dt>
          <dd><Money value={{ minor: net, currency: c }} /></dd>
        </div>
      </dl>
      {!settled ? <p data-unsettled>{t("rounds.unsettled")}</p> : null}
    </section>
  );
}

export function OpenRound({ id, tz }: { id: string; tz: string }) {
  const q = useQuery({ queryKey: ["game-provider", "round", id], queryFn: () => readJson<RoundState>(gameProvider.getRoundsByRoundByRoundId({ roundId: id })) });
  if (q.isError) return <RefusalNotice refusal={refusalOf(q.error)} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  return <RoundDetail round={q.data} tz={tz} />;
}

const columns = (tz: string): Column<RoundRow>[] => [
  { id: "opened", header: "rounds.opened", cell: (r) => (r.openedAt ? <DateTime instant={r.openedAt} zone={tz} /> : null) },
  { id: "game", header: "rounds.game", cell: (r) => r.gameCode ?? "" },
  { id: "stake", header: "rounds.stake", numeric: true, cell: (r) => (r.totalStake != null ? <Money value={{ minor: r.totalStake, currency: r.currency }} /> : null) },
  { id: "win", header: "rounds.win", numeric: true, cell: (r) => (r.win != null ? <Money value={{ minor: r.win, currency: r.currency }} /> : null) },
  { id: "status", header: "rounds.status", cell: (r) => <Status value={r.status === "settled" ? "settled" : r.status === "cancelled" ? "cancelled" : "unsettled"} /> },
];

/** A round as players answers it in a bet search or a session's bets (OperatorSessionRoutes.bet). */
export interface Bet {
  betId: string;
  roundId: string;
  providerRoundId?: string | null;
  provider?: string | null;
  gameId?: string | null;
  sessionId?: string | null;
  status: string;
  outcome?: string | null;
  currency: string;
  stakeMinorUnits: number;
  winMinorUnits: number;
  netMinorUnits: number;
  freeRounds?: boolean;
  transactionIds?: string[];
  providerTransactionIds?: string[];
  openedAt?: string | null;
  settledAt?: string | null;
  createdAt: string;
}

/** What the matching rounds staked, won and netted, per currency (`totals`); never summed across currencies. */
export interface BetSummary {
  totals: { currency: string; rounds: number; staked: number; won: number; net: number }[];
}

const roundStatus = (status: string) => (status === "settled" ? "settled" : status === "cancelled" ? "cancelled" : "unsettled");

export const betColumns = (tz: string): Column<Bet>[] => [
  { id: "opened", header: "rounds.opened", cell: (b) => <DateTime instant={b.openedAt ?? b.createdAt} zone={tz} /> },
  { id: "game", header: "rounds.game", cell: (b) => b.gameId ?? "" },
  { id: "round", header: "bets.round", cell: (b) => b.providerRoundId ?? b.roundId },
  { id: "stake", header: "rounds.stake", numeric: true, cell: (b) => <Money value={{ minor: b.stakeMinorUnits, currency: b.currency }} /> },
  { id: "win", header: "rounds.win", numeric: true, cell: (b) => <Money value={{ minor: b.winMinorUnits, currency: b.currency }} /> },
  { id: "net", header: "rounds.net", numeric: true, cell: (b) => <Money value={{ minor: b.netMinorUnits, currency: b.currency }} /> },
  { id: "status", header: "rounds.status", cell: (b) => <Status value={roundStatus(b.status)} /> },
  { id: "transactions", header: "bets.transactions", cell: (b) => [...(b.transactionIds ?? []), ...(b.providerTransactionIds ?? [])].join(", ") },
];

/** The totals of the matching rounds, one line per currency. */
export function BetTotals({ summary }: { summary: BetSummary | undefined }) {
  if (!summary) return null;
  if (summary.totals.length === 0) return <p className="bo-muted">{t("bets.noTotals")}</p>;
  return (
    <table className="bo-table" data-totals>
      <caption>{t("bets.totals")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("bets.currency")}</th>
          <th scope="col" className="figures">{t("sessions.rounds")}</th>
          <th scope="col" className="figures">{t("rounds.staked")}</th>
          <th scope="col" className="figures">{t("rounds.won")}</th>
          <th scope="col" className="figures">{t("rounds.net")}</th>
        </tr>
      </thead>
      <tbody>
        {summary.totals.map((x) => (
          <tr key={x.currency} data-currency={x.currency}>
            <th scope="row">{x.currency}</th>
            <td className="figures">{x.rounds}</td>
            <td className="figures"><Money value={{ minor: x.staked, currency: x.currency }} /></td>
            <td className="figures"><Money value={{ minor: x.won, currency: x.currency }} /></td>
            <td className="figures"><Money value={{ minor: x.net, currency: x.currency }} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const useSearch = () => useRouterState({ select: (s) => s.location.search as Record<string, unknown> });

/** Opens a round (`?round=`), keeping the rest of the page's place. */
export function useOpenRound() {
  const navigate = useNavigate();
  return (roundId: string) => void navigate({ to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, round: roundId }) } as never);
}

function TextFilter({ name, labelKey, value, onChange }: { name: string; labelKey: MessageKey; value: string; onChange(v: string | undefined): void }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(labelKey)}</label>
      <input
        id={id}
        name={name}
        className="bo-input"
        defaultValue={value}
        key={value}
        onBlur={(e) => {
          const v = e.currentTarget.value.trim();
          if (v !== value) onChange(v);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            onChange(e.currentTarget.value.trim());
          }
        }}
      />
    </div>
  );
}

function ChoiceFilter({ labelKey, value, choices, any, onChange }: { labelKey: MessageKey; value: string; choices: readonly string[]; any: MessageKey; onChange(v: string): void }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(labelKey)}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t(any)}</option>
        {choices.map((c) => (
          <option key={c} value={c}>
            {word("bets.choice", c)}
          </option>
        ))}
      </select>
    </div>
  );
}

function DateFilter({ labelKey, value, onChange }: { labelKey: MessageKey; value: string; onChange(v: string): void }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(labelKey)}</label>
      <input id={id} type="date" className="bo-input" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

const BetFilters = ["roundId", "transactionId", "type", "status", "fromDate", "toDate"] as const;

/** players' bet and win search of the player (P10): the filters in the URL, the totals of every matching round. */
function BetSearch({ player, tz }: { player: string; tz: string }) {
  const list = useListState(useRouterListStore());
  const freshness = useFreshness(["players"]);
  const open = useOpenRound();
  const f = list.state.filters;
  const query: Record<string, string> = { limit: String(PageSize), offset: String((list.state.page - 1) * PageSize) };
  for (const k of BetFilters) if (f[k]) query[k] = f[k];
  const q = useQuery({
    queryKey: ["players", "bets", player, query],
    queryFn: () => readJson<{ bets: Bet[]; summary: BetSummary; total: number }>(players.getPlayersBets({ playerId: player }, { query })),
    enabled: player !== "",
    placeholderData: (prev) => prev,
  });
  const opened = useSearch().round;
  return (
    <section aria-labelledby="bets-title">
      <h3 id="bets-title">{t("bets.title")}</h3>
      <div role="search" className="bo-players-filters">
        <TextFilter name="roundId" labelKey="bets.filter.roundId" value={f.roundId ?? ""} onChange={(v) => list.setFilter("roundId", v)} />
        <TextFilter name="transactionId" labelKey="bets.filter.transactionId" value={f.transactionId ?? ""} onChange={(v) => list.setFilter("transactionId", v)} />
        <ChoiceFilter labelKey="bets.filter.type" value={f.type ?? ""} choices={["bet", "win"]} any="bets.filter.anyType" onChange={(v) => list.setFilter("type", v)} />
        <ChoiceFilter labelKey="bets.filter.status" value={f.status ?? ""} choices={["open", "settled", "cancelled", "all"]} any="bets.filter.ended" onChange={(v) => list.setFilter("status", v)} />
        <DateFilter labelKey="bets.filter.from" value={f.fromDate ?? ""} onChange={(v) => list.setFilter("fromDate", v)} />
        <DateFilter labelKey="bets.filter.to" value={f.toDate ?? ""} onChange={(v) => list.setFilter("toDate", v)} />
      </div>
      <p className="bo-muted">{t("bets.daysNote")}</p>
      {typeof opened === "string" && opened !== "" ? <OpenRound id={opened} tz={tz} /> : null}
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <BetTotals summary={q.data?.summary} />
      <List
        caption="bets.caption"
        columns={betColumns(tz)}
        rows={q.data?.bets}
        rowKey={(b) => b.roundId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={(b) => open(b.roundId)}
        current={typeof opened === "string" ? opened : undefined}
      />
    </section>
  );
}

function RoundsList({ player, sessionId, tz }: { player: string; sessionId?: string; tz: string }) {
  const list = useListState(useRouterListStore());
  const open = useOpenRound();
  const opened = useSearch().round;
  const query: Record<string, string> = { ...(sessionId ? { sessionId } : { playerId: player }), page: String(list.state.page), pageSize: String(PageSize) };
  const q = useQuery({
    queryKey: ["game-provider", "rounds", player, sessionId ?? "", list.state.page],
    queryFn: () => readJson<{ rounds: RoundRow[]; total: number; page: number; pageSize: number }>(gameProvider.getRounds({ query })),
    enabled: player !== "",
  });
  return (
    <>
      {typeof opened === "string" && opened !== "" ? <OpenRound id={opened} tz={tz} /> : null}
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="rounds.caption"
        columns={columns(tz)}
        rows={q.data?.rounds}
        rowKey={(r) => r.roundId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
        onOpen={(r) => open(r.roundId)}
        current={typeof opened === "string" ? opened : undefined}
      />
    </>
  );
}

/** The rounds, or (`?view=bets`) the bet and win search; switching starts the other at its first page. */
function ViewSwitch({ view }: { view: "rounds" | "bets" }) {
  const router = useRouter();
  const go = (next: "rounds" | "bets") => {
    const current = router.state.location.search as Record<string, unknown>;
    const drop = new Set([...listSearchKeys(current), "round", "view"]);
    const kept = Object.fromEntries(Object.entries(current).filter(([k]) => !drop.has(k)));
    void router.navigate({ to: router.state.location.pathname, search: next === "bets" ? { ...kept, view: "bets" } : kept } as never);
  };
  const button = (v: "rounds" | "bets", label: MessageKey): ReactNode => (
    <button type="button" className="bo-button" aria-pressed={view === v} onClick={() => view !== v && go(v)}>
      {t(label)}
    </button>
  );
  return (
    <div role="group" aria-label={t("rounds.views")} className="bo-actions">
      {button("rounds", "rounds.view.rounds")}
      {button("bets", "rounds.view.bets")}
    </div>
  );
}

export function PlayerRounds({ player, sessionId }: { player: string; sessionId?: string }) {
  const tz = useZone();
  const view = useSearch().view === "bets" && !sessionId ? "bets" : "rounds";
  return (
    <section aria-labelledby="rounds-title">
      <h2 id="rounds-title">{t("players.tab.games")}</h2>
      <ZoneNote zone={tz} />
      {sessionId ? null : <ViewSwitch view={view} />}
      {view === "bets" ? <BetSearch player={player} tz={tz} /> : <RoundsList player={player} sessionId={sessionId} tz={tz} />}
    </section>
  );
}
