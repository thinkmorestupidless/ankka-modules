import type { PlayBehaviour, PlayerSite, PlayStep } from "@eitheror/player-sdk";
import { useBehaviour } from "@eitheror/player-sdk/react";
import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy, Button } from "./ui.tsx";

/** `/game/{code}`: the open games as a tray, the shown one in a frame, and its play behaviour's step over it. */
export function GamePage({ site, code }: { site: PlayerSite; code: string }) {
  const games = site.games;
  const step = useBehaviour(games);
  const navigate = useNavigate();
  const { t } = useLanguage();
  // arriving at a game's address opens it, as picking it in the lobby does (a game already open is only shown)
  useEffect(() => {
    if (games && games.step().shown?.game !== code) games.actions.pick(code);
  }, [games, code]);
  // leaving the page for another hides the games; their sessions stay open until closed
  useEffect(() => () => void games?.actions.hideAll(), [games]);
  if (!games || !step) return null;
  return (
    <section className="mx-auto max-w-6xl px-4 py-4">
      {step.open.length > 1 ? (
        <nav aria-label={t("play.open")} className="mb-3 flex flex-wrap gap-2">
          {step.open.map((g) => (
            <button
              key={g.game}
              aria-current={g === step.shown}
              onClick={() => void navigate({ to: "/game/$code", params: { code: g.game } })}
              className="rounded-full border border-river px-3 py-1 text-sm aria-current:border-canopy aria-current:bg-canopy aria-current:text-white"
            >
              {g.game}
            </button>
          ))}
        </nav>
      ) : null}
      {step.open.map((g) => (
        <Play key={g.game} play={g} shown={g === step.shown} site={site} onLeave={() => void navigate({ to: step.open.length > 1 ? "/game/$code" : "/", params: { code: step.open.find((o) => o !== g)?.game ?? code } })} />
      ))}
    </section>
  );
}

/** One game: kept in the page while open, so a hidden game keeps its frame and its session. */
function Play({ play, shown, site, onLeave }: { play: PlayBehaviour; shown: boolean; site: PlayerSite; onLeave: () => void }) {
  const { t } = useLanguage();
  const step: PlayStep = useBehaviour(play);
  const address = useRef<string | null>(null);
  if (step.kind === "playing") address.current = step.address;
  const can = (name: string) => (step.allowed as readonly string[]).includes(name);
  return (
    <div hidden={!shown}>
      {address.current && (step.kind === "playing" || step.kind === "reality_check") ? (
        <div className="relative">
          <iframe title={play.game} src={address.current} className="aspect-video max-h-[75dvh] w-full rounded-lg bg-ink" allow="fullscreen; autoplay" />
          {step.kind === "reality_check" ? (
            <div role="alertdialog" aria-labelledby={`reality-${play.game}`} className="absolute inset-0 grid place-items-center rounded-lg bg-ink/80 p-4">
              <div className="max-w-sm rounded-lg bg-white p-6">
                <h2 id={`reality-${play.game}`} className="text-xl font-bold">
                  {t("reality.title")}
                </h2>
                <p className="mt-2">{t("reality.body", { minutes: Math.floor(step.played / 60) })}</p>
                <div className="mt-4 flex gap-3">
                  <Button autoFocus onClick={() => play.actions.keepOn()}>
                    {t("reality.keepOn")}
                  </Button>
                  <Button tone="quiet" onClick={() => play.actions.stop()}>
                    {t("reality.stop")}
                  </Button>
                </div>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Status step={step} play={play} site={site} />
        {can("close") ? (
          <Button
            tone="quiet"
            className="ml-auto"
            onClick={() => {
              play.actions.close();
              onLeave();
            }}
          >
            {t("play.close")}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

function Status({ step, play, site }: { step: PlayStep; play: PlayBehaviour; site: PlayerSite }) {
  const { t } = useLanguage();
  switch (step.kind) {
    case "needs_registration":
      return (
        <p>
          {t("play.needs_registration")}{" "}
          <Link to="/register" className="font-semibold text-leaf underline">
            {t("nav.register")}
          </Link>
        </p>
      );
    case "needs_sign_in":
      return (
        <p>
          {t("play.needs_sign_in")}{" "}
          <Link to="/sign-in" className="font-semibold text-leaf underline">
            {t("nav.signIn")}
          </Link>
        </p>
      );
    case "needs_deposit":
      return (
        <p className="flex items-center gap-3">
          {t("play.needs_deposit")}
          <Button tone="money" onClick={() => site.deposit()}>
            {t("nav.deposit")}
          </Button>
          <Button tone="quiet" onClick={() => play.actions.launch()}>
            {t("lobby.play")}
          </Button>
        </p>
      );
    case "launching":
      return <Busy text={t("play.launching")} />;
    case "refused":
      return (
        <div className="space-y-2">
          <Refused refusal={step.refusal} />
          <Button tone="quiet" onClick={() => play.actions.dismiss()}>
            {t("action.dismiss")}
          </Button>
        </div>
      );
    case "ended":
      return <p>{t("play.ended")}</p>;
    default:
      return null;
  }
}
