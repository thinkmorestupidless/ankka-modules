import type { PlayerSite, SessionStep } from "@eitheror/player-sdk";
import { useBehaviour } from "@eitheror/player-sdk/react";
import { Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useLanguage } from "../app/language.tsx";
import { Refused } from "./Refused.tsx";
import { Busy, Page } from "./ui.tsx";

/** The link `players` mails: `/verify-email?token=…`. */
export function VerifyEmail({ site, token }: { site: PlayerSite; token: string }) {
  const { t } = useLanguage();
  const step: SessionStep = useBehaviour(site.session);
  useEffect(() => {
    if (token) site.session.actions.verifyEmail(token);
  }, [site, token]);
  return (
    <Page title={t("verify.title")}>
      {step.kind === "refused" ? <Refused refusal={step.refusal} /> : step.kind === "signed_out" && step.verified ? (
        <p>
          {t("verify.done")}{" "}
          <Link to="/sign-in" className="font-semibold text-leaf underline">
            {t("nav.signIn")}
          </Link>
        </p>
      ) : (
        <Busy text={t("verify.working")} />
      )}
    </Page>
  );
}
