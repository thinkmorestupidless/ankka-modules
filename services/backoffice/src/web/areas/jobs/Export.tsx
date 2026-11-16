// An export started from the screen it exports (FR-029, FR-033): the owner renders it under a
// reference number, which is shown as soon as the owner gives it, then its state as the jobs list reads
// it, and the download once ready. The operator may leave: the job's end is announced wherever they are.
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import type { Refusal } from "../../../shared/refusals.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { JobLine } from "./JobsPage.tsx";
import { startJob, useJobs, type Job, type JobKind, type Started } from "./jobs.ts";
import { useZone } from "../../components/date.tsx";

export interface ExportProps {
  kind: JobKind;
  params: Record<string, string>;
  /** The button's words; "Export" when not given. */
  label?: string;
  /** Why it cannot be started now, shown instead of the button. */
  unavailable?: string;
  /** What a refusal's body may say beyond its code (an incomplete period's first day), for the screen. */
  onRefused?: (status: number, body: string) => void;
  start?: (kind: JobKind, params: Record<string, string>) => Promise<Started>;
}

export function Export({ kind, params, label, unavailable, onRefused, start = startJob }: ExportProps) {
  const client = useQueryClient();
  const jobs = useJobs();
  const [state, setState] = useState<{ at: "idle" | "working" } | { at: "started"; job: Job; kept: boolean } | { at: "refused"; refusal: Refusal }>({ at: "idle" });
  const run = async () => {
    setState({ at: "working" });
    const r = await start(kind, params);
    if (!r.ok) {
      onRefused?.(r.refusal.status, r.body);
      return setState({ at: "refused", refusal: r.refusal });
    }
    setState({ at: "started", job: r.job, kept: r.kept });
    await Promise.all([client.invalidateQueries({ queryKey: ["jobs"] }), client.invalidateQueries({ queryKey: ["me"] })]);
  };
  const started = state.at === "started" ? (jobs.data?.find((j) => j.reference === state.job.reference) ?? state.job) : undefined;
  const zone = useZone();
  return (
    <div className="bo-export" data-export={kind}>
      {unavailable ? (
        <span className="bo-muted">{unavailable}</span>
      ) : (
        <button type="button" className="bo-button" data-variant="primary" disabled={state.at === "working"} onClick={() => void run()}>
          {state.at === "working" ? t("action.working") : (label ?? t("reports.export"))}
        </button>
      )}
      {started ? <JobLine job={started} zone={zone} /> : null}
      {state.at === "started" && !state.kept ? <span className="bo-muted">{t("jobs.notKept")}</span> : null}
      {state.at === "refused" ? <RefusalNotice refusal={state.refusal} /> : null}
    </div>
  );
}
