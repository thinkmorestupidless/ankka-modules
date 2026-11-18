import { format, type PlayerSite, type SessionStep } from "@eitheror/player-sdk";
import { useBehaviour, usePlayer } from "@eitheror/player-sdk/react";
import { Link, Outlet } from "@tanstack/react-router";
import { useFunds } from "../app/hooks.ts";
import { useLanguage } from "../app/language.tsx";
import { IdleWarning } from "./IdleWarning.tsx";
import { Button } from "./ui.tsx";

/** The frame of every page: the brand, the language, the balance and the way in or out. */
export function Frame({ site, overlays }: { site: PlayerSite; overlays?: React.ReactNode }) {
  const { t, language, choose } = useLanguage();
  const session: SessionStep = useBehaviour(site.session);
  const player = usePlayer(site);
  const funds = useFunds(site);
  return (
    <div className="min-h-dvh">
      <header className="bg-canopy text-white">
        <nav className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
          <Link to="/" className="mr-auto text-xl font-extrabold tracking-tight">
            {t("site.name")}
          </Link>
          {player ? (
            <>
              {funds.real ? (
                <span className="amount" title={t("balance.real")}>
                  <span className="sr-only">{t("balance.real")} </span>
                  {format(funds.real, language)}
                  {funds.restricted && funds.restricted.minor > 0n ? <span className="ml-2 text-sm text-white/70">+ {format(funds.restricted, language)} {t("balance.restricted")}</span> : null}
                </span>
              ) : null}
              <Unread site={site} />
              <Button tone="money" onClick={() => site.deposit()}>
                {t("nav.deposit")}
              </Button>
              <Button tone="quiet" className="text-white" onClick={() => site.session.actions.signOut()}>
                {t("nav.signOut")}
              </Button>
            </>
          ) : (
            <>
              <Link to="/sign-in" className="hover:underline">
                {t("nav.signIn")}
              </Link>
              <Link to="/register" className="rounded-md bg-white px-3 py-1.5 font-semibold text-canopy">
                {t("nav.register")}
              </Link>
            </>
          )}
          <label className="text-sm">
            <span className="sr-only">{t("nav.language")}</span>
            <select value={language} onChange={(e) => choose(e.target.value)} className="rounded bg-canopy text-white">
              {(site.facts?.languages ?? ["fr", "en"]).map((l) => (
                <option key={l} value={l}>
                  {l === "fr" ? "Français" : l === "en" ? "English" : l}
                </option>
              ))}
            </select>
          </label>
        </nav>
      </header>
      {session.kind === "signed_out" && session.reason && session.reason !== "signed_out" ? (
        <p role="status" className="bg-white px-4 py-2 text-center">
          {t(session.reason === "idle" ? "signedOut.idle" : "signedOut.refresh_expired")}
        </p>
      ) : null}
      <main>
        <Outlet />
      </main>
      {session.kind === "idle_warning" ? <IdleWarning site={site} step={session} /> : null}
      {overlays}
    </div>
  );
}

/** The unread count, from the inbox behaviour, where the brand runs engagement. */
function Unread({ site }: { site: PlayerSite }) {
  const { t } = useLanguage();
  const step = useBehaviour(site.inbox);
  const count = step && "unread" in step ? (step.unread ?? 0) : 0;
  if (count === 0) return null;
  return (
    <span role="status" className="rounded-full bg-white px-2 py-0.5 text-sm font-semibold text-canopy">
      {t("inbox.unread", { count })}
    </span>
  );
}
