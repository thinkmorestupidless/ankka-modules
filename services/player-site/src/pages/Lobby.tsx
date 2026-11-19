import type { Game, PlayerSite } from "@eitheror/player-sdk";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy } from "./ui.tsx";

/** A tile's band, from the game's name: the same game always wears the same colour. */
const bands = ["#12382b", "#2f7a57", "#1d4f5c", "#5b4a1f", "#3b2f57", "#7a2f3a"];
const band = (code: string) => bands[[...code].reduce((h, c) => Math.imul(h ^ c.charCodeAt(0), 16777619) >>> 0, 2166136261) % bands.length];

type Read = { games: Game[] } | { refusal: Parameters<typeof Refused>[0]["refusal"] } | null;

/** The lobby: the catalogue, shown to a visitor and to a player alike (FR-056). */
export function Lobby({ site }: { site: PlayerSite }) {
  const { t } = useLanguage();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [read, setRead] = useState<Read>(null);
  useEffect(() => {
    if (!site.games) return;
    let current = true;
    const id = setTimeout(() => {
      void site.casino.games(search.trim() ? { search: search.trim(), limit: 60 } : { limit: 60 }).then((r) => {
        if (current) setRead(r.ok ? { games: r.value.games } : { refusal: r.refusal });
      });
    }, 200);
    return () => {
      current = false;
      clearTimeout(id);
    };
  }, [site, search]);
  if (!site.games) return null;
  const open = (code: string, funMode = false) => {
    site.games!.actions.pick(code, { funMode });
    void navigate({ to: "/game/$code", params: { code } });
  };
  return (
    <section className="mx-auto max-w-6xl px-4 py-8">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <h1 className="text-4xl font-extrabold tracking-tight">{t("lobby.title")}</h1>
        <label className="w-full sm:w-72">
          <span className="sr-only">{t("lobby.search")}</span>
          <input type="search" placeholder={t("lobby.search")} value={search} onChange={(e) => setSearch(e.target.value)} className="w-full rounded-md border border-river bg-white px-3 py-2" />
        </label>
      </div>
      {read === null ? <Busy text={t("lobby.loading")} /> : "refusal" in read ? <Refused refusal={read.refusal} /> : read.games.length === 0 ? <p>{t("lobby.empty")}</p> : (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {read.games.map((g) => (
            <li key={g.gameId} className="flex flex-col overflow-hidden rounded-lg bg-white">
              <div className="flex min-h-32 items-end p-3 text-white" style={{ background: band(g.gameId) }}>
                <h2 className="text-2xl leading-none font-black tracking-tight break-words">{g.name ?? g.gameId}</h2>
              </div>
              <div className="flex items-center justify-between gap-2 p-3">
                <button onClick={() => open(g.gameId)} className="rounded-md bg-cayenne px-3 py-1.5 font-semibold text-white">
                  {t("lobby.play")}
                </button>
                <button onClick={() => open(g.gameId, true)} className="text-leaf hover:underline">
                  {t("lobby.fun")}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
