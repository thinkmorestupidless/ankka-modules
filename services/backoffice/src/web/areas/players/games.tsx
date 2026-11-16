// What a player plays (T114; spec 030 P9): per game and currency, the settled rounds, what was staked and
// won, the net and when last played, all time unless a period is given (dates are whole days, in UTC, as
// players reads them). Figures are never summed across currencies. players says when the rounds it summed
// reached its limit, so the figures may not be all.
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import "../payments/payments.css";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money } from "../../components/money.tsx";
import { read } from "../configuration/page.tsx";

export interface GamePlayed {
  gameId: string;
  currency: string;
  rounds: number;
  stakedMinorUnits: number;
  wonMinorUnits: number;
  netMinorUnits: number;
  lastPlayedAt: string;
}


export function PlayerGamesPlayed({ player }: { player: string }) {
  const tz = useZone();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const query: Record<string, string> = {};
  if (from) query.from = from;
  if (to) query.to = to;
  const q = useQuery({
    queryKey: ["players", "games", player, query],
    queryFn: () => read<{ games: GamePlayed[]; truncated: boolean }>(players.getPlayersGames({ playerId: player }, { query })),
  });
  const m = (minor: number, currency: string) => <Money value={{ minor, currency }} />;
  return (
    <section aria-labelledby="player-played">
      <h2 id="player-played">{t("players.played.title")}</h2>
      <div className="bo-pay-filters">
        <div className="bo-field">
          <label htmlFor="played-from">{t("players.played.from")}</label>
          <input id="played-from" type="date" className="bo-input" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="bo-field">
          <label htmlFor="played-to">{t("players.played.to")}</label>
          <input id="played-to" type="date" className="bo-input" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <p className="bo-muted">{from || to ? t("players.played.period", { from: from || "…", to: to || "…" }) : t("players.played.allTime")}</p>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {q.data ? (
        <>
          {q.data.truncated ? <p role="status">{t("players.played.truncated")}</p> : null}
          <table className="bo-pay-table">
            <caption>{t("players.played.caption")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("players.played.game")}</th>
                <th scope="col">{t("players.played.currency")}</th>
                <th scope="col" data-numeric="">
                  {t("players.played.rounds")}
                </th>
                <th scope="col" data-numeric="">
                  {t("players.played.staked")}
                </th>
                <th scope="col" data-numeric="">
                  {t("players.played.won")}
                </th>
                <th scope="col" data-numeric="">
                  {t("players.played.net")}
                </th>
                <th scope="col">{t("players.played.last")}</th>
              </tr>
            </thead>
            <tbody>
              {q.data.games.length === 0 ? (
                <tr>
                  <td colSpan={7} className="bo-muted">
                    {t("players.played.none")}
                  </td>
                </tr>
              ) : (
                q.data.games.map((g) => (
                  <tr key={`${g.gameId}/${g.currency}`}>
                    <td>{g.gameId}</td>
                    <td>{g.currency}</td>
                    <td data-numeric="">{g.rounds}</td>
                    <td data-numeric="">{m(g.stakedMinorUnits, g.currency)}</td>
                    <td data-numeric="">{m(g.wonMinorUnits, g.currency)}</td>
                    <td data-numeric="">{m(g.netMinorUnits, g.currency)}</td>
                    <td>
                      <DateTime instant={g.lastPlayedAt} zone={tz} />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </>
      ) : q.isError ? null : (
        <p role="status">{t("list.loading")}</p>
      )}
    </section>
  );
}
