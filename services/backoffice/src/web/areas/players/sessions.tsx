// A player's sessions (T076; FR-023, P5): each player session with its game sessions, and a gap players
// found in a game session's data (what was wagered against what its rounds staked) named with both
// figures. A game session opened (`?gameSession=`) shows its bets (P6) with their totals per currency; an
// active session can be closed, an active game session ended, and every open game session ended at once.
// `/players/sessions` (`BrandSessionsPage`): the brand's player sessions (P5), filtered by status and the
// start's period, each linking to its player and closed from there by force.
import { useId, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { RecordLink } from "../../components/links.tsx";
import { completeOf, List, memoryStore, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status, statusWord } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { BetTotals, betColumns, OpenRound, useOpenRound, type Bet, type BetSummary } from "./rounds.tsx";

const PageSize = 25;

interface GameSession {
  sessionId: string;
  gameId?: string | null;
  status: string;
  launchedAt?: string | null;
  endedAt?: string | null;
  rounds?: number | null;
  stakedMinorUnits?: number | null;
  wonMinorUnits?: number | null;
  netMinorUnits?: number | null;
  currency?: string | null;
  gap?: { wageredMinorUnits: number; roundStakeMinorUnits: number; currency: string } | null;
}

interface PlayerSession {
  sessionId: string;
  status: string;
  startedAt?: string | null;
  endedAt?: string | null;
  reason?: string | null;
  gameSessions: GameSession[];
}

/** A game session's bets (P6): its rounds as players read them, newest first, with what they staked, won and netted. */
function SessionBets({ player, sessionId, tz }: { player: string; sessionId: string; tz: string }) {
  const store = useMemo(() => memoryStore(), []);
  const list = useListState(store);
  const open = useOpenRound();
  const opened = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).round });
  const q = useQuery({
    queryKey: ["players", "session-bets", player, sessionId, list.state.page],
    queryFn: () =>
      readJson<{ bets: Bet[]; summary: BetSummary; total: number }>(
        players.getPlayersSessionsBets({ playerId: player, sessionId }, { query: { page: String(list.state.page), pageSize: String(BetsPageSize) } }),
      ),
    placeholderData: (prev) => prev,
  });
  return (
    <section aria-labelledby="session-bets-title" data-session-bets={sessionId}>
      <h3 id="session-bets-title">{t("sessions.gameRounds")}</h3>
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <BetTotals summary={q.data?.summary} />
      <List
        caption="sessions.bets"
        columns={betColumns(tz)}
        rows={q.data?.bets}
        rowKey={(b) => b.roundId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: BetsPageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
        onOpen={(b) => open(b.roundId)}
        current={typeof opened === "string" ? opened : undefined}
      />
      {typeof opened === "string" && opened !== "" ? <OpenRound id={opened} tz={tz} /> : null}
    </section>
  );
}

const BetsPageSize = 50;

const amount = (minor: number | null | undefined, currency: string | null | undefined) => (minor != null && currency ? <Money value={{ minor, currency }} /> : null);

export function PlayerSessions({ player }: { player: string }) {
  const tz = useZone();
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const navigate = useNavigate();
  const opened = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).gameSession });
  const q = useQuery({
    queryKey: ["players", "player-sessions", player, list.state.page],
    queryFn: () => readJson<{ sessions: PlayerSession[]; total: number }>(players.getPlayersPlayerSessions({ playerId: player }, { query: { page: String(list.state.page), pageSize: String(PageSize) } })),
    enabled: player !== "",
  });
  const [ended, setEnded] = useState<number | undefined>(undefined);
  const open = (id: string) => void navigate({ to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, gameSession: id, round: undefined }) } as never);
  const refresh = () => void client.invalidateQueries({ queryKey: ["players", "player-sessions", player] });
  const playing = q.data?.sessions.some((s) => s.gameSessions.some((g) => g.status === "active")) ?? false;
  return (
    <section aria-labelledby="sessions-title">
      <h2 id="sessions-title">{t("players.tab.sessions")}</h2>
      <ZoneNote zone={tz} />
      {readOnly || !playing ? null : (
        <Action
          action={Actions["player.forceEndAllGameSessions"]}
          me={me}
          bags={bags}
          run={() => players.postPlayersSessionsForceEndAll({ playerId: player })}
          onDone={(answer) => {
            void answer
              .json()
              .then((j: { ended?: number }) => setEnded(j.ended ?? 0))
              .catch(() => setEnded(undefined));
            refresh();
          }}
        />
      )}
      {ended !== undefined ? <p role="status">{t("sessions.endedCount", { count: ended })}</p> : null}
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : !q.data ? <p role="status">{t("list.loading")}</p> : null}
      {q.data && q.data.sessions.length === 0 ? <p className="bo-muted">{t("sessions.none")}</p> : null}
      {q.data?.sessions.map((s) => (
        <article key={s.sessionId} className="bo-session" data-session={s.sessionId}>
          <h3>
            {t("sessions.session")} <Status value={s.status} /> {s.startedAt ? <DateTime instant={s.startedAt} zone={tz} /> : null}
            {s.endedAt ? (
              <>
                {" – "}
                <DateTime instant={s.endedAt} zone={tz} />
              </>
            ) : null}
          </h3>
          {readOnly ? null : (
            <Action
              action={Actions["player.forceCloseSession"]}
              me={me}
              bags={bags}
              state={s.status}
              run={() => players.postPlayersPlayerSessionsForceClose({ playerId: player, sessionId: s.sessionId })}
              onDone={refresh}
            />
          )}
          {s.gameSessions.length === 0 ? <p className="bo-muted">{t("sessions.noGames")}</p> : (
            <table className="bo-table">
              <caption className="bo-visually-hidden">{t("sessions.games")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("sessions.game")}</th>
                  <th scope="col">{t("sessions.launched")}</th>
                  <th scope="col" className="figures">{t("sessions.rounds")}</th>
                  <th scope="col" className="figures">{t("rounds.staked")}</th>
                  <th scope="col" className="figures">{t("rounds.won")}</th>
                  <th scope="col" className="figures">{t("rounds.net")}</th>
                  <th scope="col">{t("sessions.gap")}</th>
                  <th scope="col">{t("rounds.status")}</th>
                  <th scope="col">{t("identity.document.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {s.gameSessions.map((g) => (
                  <tr key={g.sessionId} data-game-session={g.sessionId} aria-current={String(opened) === g.sessionId ? "true" : undefined}>
                    <td>
                      <button type="button" className="bo-link" onClick={() => open(g.sessionId)}>
                        {g.gameId ?? g.sessionId}
                      </button>
                    </td>
                    <td>{g.launchedAt ? <DateTime instant={g.launchedAt} zone={tz} /> : null}</td>
                    <td className="figures">{g.rounds ?? ""}</td>
                    <td className="figures">{amount(g.stakedMinorUnits, g.currency)}</td>
                    <td className="figures">{amount(g.wonMinorUnits, g.currency)}</td>
                    <td className="figures">{amount(g.netMinorUnits, g.currency)}</td>
                    <td data-gap={g.gap ? "" : undefined}>
                      {g.gap ? (
                        <>
                          {t("sessions.gapFound")} {amount(g.gap.wageredMinorUnits, g.gap.currency)} / {amount(g.gap.roundStakeMinorUnits, g.gap.currency)}
                        </>
                      ) : null}
                    </td>
                    <td>
                      <Status value={g.status} />
                    </td>
                    <td>
                      {readOnly ? null : (
                        <Action
                          action={Actions["player.forceEndGameSession"]}
                          me={me}
                          bags={bags}
                          state={g.status}
                          subject={<p>{g.gameId ?? g.sessionId}</p>}
                          run={() => players.postPlayersSessionsForceEnd({ playerId: player, sessionId: g.sessionId })}
                          onDone={refresh}
                        />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </article>
      ))}
      {q.data && (list.state.page > 1 || !completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total })) ? (
        <nav className="bo-list-foot" aria-label={t("list.pages")}>
          <button type="button" className="bo-button" disabled={list.state.page <= 1} onClick={() => list.setPage(list.state.page - 1)}>
            {t("list.previous")}
          </button>
          <button type="button" className="bo-button" disabled={completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total })} onClick={() => list.setPage(list.state.page + 1)}>
            {t("list.next")}
          </button>
        </nav>
      ) : null}
      {typeof opened === "string" && opened !== "" ? (
        <SessionBets key={opened} player={player} sessionId={opened} tz={tz} />
      ) : null}
    </section>
  );
}

interface BrandSession {
  sessionId: string;
  playerId: string;
  status: string;
  startedAt: string;
  endedAt?: string | null;
  reason?: string | null;
}

/** The statuses players filters the brand's sessions by (OperatorSessionRoutes.SessionFilters). */
export const SessionStatuses = ["active", "ended", "completed", "expired", "forced", "suspended", "superseded"] as const;

/** `/players/sessions`: the brand's player sessions, newest first (P5), the filters and page in the URL. */
export function BrandSessionsPage() {
  const tz = useZone();
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const freshness = useFreshness(["players"]);
  const statusId = useId();
  const fromId = useId();
  const toId = useId();
  const f = list.state.filters;
  const query: Record<string, string> = { page: String(list.state.page), pageSize: String(PageSize) };
  for (const k of ["status", "from", "to"]) if (f[k]) query[k] = f[k];
  const q = useQuery({
    queryKey: ["players", "brand-sessions", query],
    queryFn: () => readJson<{ sessions: BrandSession[]; total: number }>(players.getSessions({ query })),
    placeholderData: (prev) => prev,
  });
  const columns: Column<BrandSession>[] = [
    { id: "player", header: "sessions.player", cell: (s) => <RecordLink to={{ kind: "player", id: s.playerId }}>{s.playerId}</RecordLink> },
    { id: "started", header: "sessions.started", cell: (s) => <DateTime instant={s.startedAt} zone={tz} /> },
    { id: "ended", header: "sessions.ended", cell: (s) => (s.endedAt ? <DateTime instant={s.endedAt} zone={tz} /> : null) },
    { id: "status", header: "rounds.status", cell: (s) => <Status value={s.status} /> },
    {
      id: "actions",
      header: "identity.document.actions",
      cell: (s) =>
        readOnly ? null : (
          <Action
            action={Actions["player.forceCloseSession"]}
            me={me}
            bags={bags}
            state={s.status}
            subject={<p>{s.playerId}</p>}
            run={() => players.postPlayersPlayerSessionsForceClose({ playerId: s.playerId, sessionId: s.sessionId })}
            onDone={() => void client.invalidateQueries({ queryKey: ["players", "brand-sessions"] })}
          />
        ),
    },
  ];
  return (
    <section>
      <h1>{t("sessions.brand.title")}</h1>
      <div role="search" className="bo-players-filters">
        <div className="bo-field">
          <label htmlFor={statusId}>{t("sessions.filter.status")}</label>
          <select id={statusId} value={f.status ?? ""} onChange={(e) => list.setFilter("status", e.target.value)}>
            <option value="">{t("players.filter.any")}</option>
            {SessionStatuses.map((s) => (
              <option key={s} value={s}>
                {statusWord(s)}
              </option>
            ))}
          </select>
        </div>
        <div className="bo-field">
          <label htmlFor={fromId}>{t("sessions.filter.from")}</label>
          <input id={fromId} type="date" className="bo-input" value={f.from ?? ""} onChange={(e) => list.setFilter("from", e.target.value)} />
        </div>
        <div className="bo-field">
          <label htmlFor={toId}>{t("sessions.filter.to")}</label>
          <input id={toId} type="date" className="bo-input" value={f.to ?? ""} onChange={(e) => list.setFilter("to", e.target.value)} />
        </div>
      </div>
      <p className="bo-muted">{t("bets.daysNote")}</p>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="sessions.brand.caption"
        columns={columns}
        rows={q.data?.sessions}
        rowKey={(s) => s.sessionId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
      />
    </section>
  );
}
