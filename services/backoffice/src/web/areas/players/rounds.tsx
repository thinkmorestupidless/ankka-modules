// A player's rounds (T076; FR-023, FR-024): game-provider's rounds of the player, newest opened first, a
// page at a time with its total; a round opened (`?round=`) shows its bets and wins, each with its
// transaction, the total staked, won and the net, and is marked unsettled until the provider settles
// it. The bets and wins are the round's own transactions' movements as game-provider recorded them.
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { gameProvider } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
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

function OpenRound({ id, tz }: { id: string; tz: string }) {
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

export function PlayerRounds({ player, sessionId }: { player: string; sessionId?: string }) {
  const tz = zone();
  const list = useListState(useRouterListStore());
  const navigate = useNavigate();
  const opened = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).round });
  const query: Record<string, string> = { ...(sessionId ? { sessionId } : { playerId: player }), page: String(list.state.page), pageSize: String(PageSize) };
  const q = useQuery({
    queryKey: ["game-provider", "rounds", player, sessionId ?? "", list.state.page],
    queryFn: () => readJson<{ rounds: RoundRow[]; total: number; page: number; pageSize: number }>(gameProvider.getRounds({ query })),
    enabled: player !== "",
  });
  const open = (r: RoundRow) => void navigate({ to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, round: r.roundId }) } as never);
  return (
    <section aria-labelledby="rounds-title">
      <h2 id="rounds-title">{t("players.tab.games")}</h2>
      <ZoneNote zone={tz} />
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
        onOpen={open}
        current={typeof opened === "string" ? opened : undefined}
      />
    </section>
  );
}
