// A player's sessions (T076; FR-023, P5): each player session with its game sessions, and a gap players
// found in a game session's data (what was wagered against what its rounds staked) named with both
// figures. A game session opened (`?gameSession=`) shows its rounds; an active session can be closed.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useListState, useRouterListStore, completeOf } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { PlayerRounds } from "./rounds.tsx";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
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

const amount = (minor: number | null | undefined, currency: string | null | undefined) => (minor != null && currency ? <Money value={{ minor, currency }} /> : null);

export function PlayerSessions({ player }: { player: string }) {
  const tz = zone();
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
  const open = (id: string) => void navigate({ to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, gameSession: id }) } as never);
  return (
    <section aria-labelledby="sessions-title">
      <h2 id="sessions-title">{t("players.tab.sessions")}</h2>
      <ZoneNote zone={tz} />
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
              onDone={() => void client.invalidateQueries({ queryKey: ["players", "player-sessions", player] })}
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
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </article>
      ))}
      {q.data && !completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) ? (
        <button type="button" className="bo-button" onClick={() => list.setPage(list.state.page + 1)}>
          {t("list.next")}
        </button>
      ) : null}
      {typeof opened === "string" && opened !== "" ? (
        <section aria-label={t("sessions.gameRounds")}>
          <h3>{t("sessions.gameRounds")}</h3>
          <PlayerRounds player={player} sessionId={opened} />
        </section>
      ) : null}
    </section>
  );
}
