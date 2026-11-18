import type { PlayerSite, SessionStep } from "@eitheror/player-sdk";
import { useBehaviour } from "@eitheror/player-sdk/react";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy, Button, Field, Page } from "./ui.tsx";

export function SignIn({ site }: { site: PlayerSite }) {
  const { t } = useLanguage();
  const navigate = useNavigate();
  const step: SessionStep = useBehaviour(site.session);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  useEffect(() => {
    if (step.kind === "signed_in" && !site.currentDeposit) void navigate({ to: "/" });
  }, [step.kind, site, navigate]);
  return (
    <Page title={t("signIn.title")}>
      {step.kind === "refused" && step.during === "signing_in" ? (
        <div className="mb-4">
          <Refused refusal={step.refusal} />
        </div>
      ) : null}
      {step.kind === "signed_out" && step.resetRequested ? <p className="mb-4">{t("signIn.resetRequested")}</p> : null}
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (step.kind === "refused") site.session.actions.back();
          site.session.actions.signIn({ email, password });
        }}
      >
        <Field label={t("signIn.email")} type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label={t("signIn.password")} type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        {step.kind === "signing_in" ? (
          <Busy text={t("signIn.working")} />
        ) : (
          <div className="flex items-center justify-between">
            <Button type="submit">{t("signIn.submit")}</Button>
            <Button type="button" tone="quiet" disabled={!email} onClick={() => site.session.actions.requestPasswordReset(email)}>
              {t("signIn.forgot")}
            </Button>
          </div>
        )}
      </form>
    </Page>
  );
}
