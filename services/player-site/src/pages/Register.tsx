import type { PlayerSite, SessionStep } from "@eitheror/player-sdk";
import { useBehaviour } from "@eitheror/player-sdk/react";
import { useEffect, useState } from "react";
import { COUNTRIES, countryName } from "../app/countries.ts";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy, Button, Field, Page } from "./ui.tsx";

export function Register({ site }: { site: PlayerSite }) {
  const { t, language } = useLanguage();
  const step: SessionStep = useBehaviour(site.session);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  // Asked only when the address names no country (players opens a deposit with the one it found).
  const [found, setFound] = useState<string | null | undefined>(undefined);
  const [country, setCountry] = useState("");
  useEffect(() => {
    let live = true;
    void site.session.visitorCountry().then((c) => live && setFound(c));
    return () => {
      live = false;
    };
  }, [site]);
  if (step.kind === "awaiting_verification")
    return (
      <Page title={t("register.title")}>
        <p>{t("register.sent", { email: step.email })}</p>
        {step.resent ? <p className="mt-2 text-ink/70">{t("register.resent")}</p> : null}
        <Button tone="quiet" className="mt-4 px-0" onClick={() => site.session.actions.resendVerification()}>
          {t("register.resend")}
        </Button>
      </Page>
    );
  return (
    <Page title={t("register.title")}>
      {step.kind === "refused" && step.during === "registering" ? (
        <div className="mb-4">
          <Refused refusal={step.refusal} />
        </div>
      ) : null}
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (step.kind === "refused") site.session.actions.back();
          site.session.actions.register({ email, password, currency: site.facts?.currency ?? "EUR", language, ...(country ? { country } : {}) });
        }}
      >
        <Field label={t("register.email")} type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label={t("register.password")} type="password" autoComplete="new-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        {found === null ? (
          <label className="block">
            <span className="mb-1 block text-sm font-medium">{t("register.country")}</span>
            <select className="w-full rounded-md border border-river bg-white px-3 py-2" required value={country} onChange={(e) => setCountry(e.target.value)}>
              <option value="" disabled>
                {t("register.countryPick")}
              </option>
              {COUNTRIES.map((c) => (
                <option key={c} value={c}>
                  {countryName(c, language)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {step.kind === "registering" ? <Busy text={t("register.working")} /> : <Button type="submit">{t("register.submit")}</Button>}
      </form>
    </Page>
  );
}
