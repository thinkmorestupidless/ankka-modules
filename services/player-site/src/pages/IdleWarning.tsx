import type { PlayerSite, SessionStep } from "@eitheror/player-sdk";
import { useEffect, useState } from "react";
import { useLanguage } from "../app/language.tsx";
import { Button } from "./ui.tsx";

export function IdleWarning({ site, step }: { site: PlayerSite; step: Extract<SessionStep, { kind: "idle_warning" }> }) {
  const { t } = useLanguage();
  const left = () => Math.max(0, Math.ceil((step.signsOutAt - site.clock.now()) / 1000));
  const [seconds, setSeconds] = useState(left);
  useEffect(() => {
    const id = setInterval(() => setSeconds(left()), 1000);
    return () => clearInterval(id);
  });
  return (
    <div role="alertdialog" aria-labelledby="idle-title" className="fixed inset-0 grid place-items-center bg-ink/50 p-4">
      <div className="w-full max-w-sm rounded-lg bg-white p-6">
        <h2 id="idle-title" className="text-xl font-bold">
          {t("idle.title")}
        </h2>
        <p className="mt-2">{t("idle.body", { seconds })}</p>
        <div className="mt-4 flex gap-3">
          <Button autoFocus onClick={() => site.session.actions.stay()}>
            {t("idle.stay")}
          </Button>
          <Button tone="quiet" onClick={() => site.session.actions.signOut()}>
            {t("nav.signOut")}
          </Button>
        </div>
      </div>
    </div>
  );
}
