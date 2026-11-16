// `/jobs` (FR-029): the exports the operator started, newest first, each with its reference number,
// its state as the owner says it now (a generating one's progress), and the download of a ready one.
// The page keeps none of them: the server keeps the references, the owner the documents.
import type { ReactNode } from "react";
import "./jobs.css";
import { t, render, type MessageKey } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, useZone } from "../../components/date.tsx";
import { Glyph, type GlyphName } from "../../components/glyph.tsx";
import type { Refusal } from "../../../shared/refusals.ts";
import { downloadHref, kindLabel, useJobs, type Job } from "./jobs.ts";

const glyph: Record<Job["state"], GlyphName> = { generating: "clock", ready: "check", failed: "cross", expired: "minus", unknown: "alert" };
const word: Record<Exclude<Job["state"], "generating">, MessageKey> = {
  ready: "jobs.state.ready",
  failed: "jobs.state.failed",
  expired: "jobs.state.expired",
  unknown: "jobs.state.unknown",
};

/** A job's state as a word and an icon (FR-040), a generating one's progress with it. */
export function JobStateWord({ job }: { job: Job }) {
  const words: ReactNode =
    job.state === "generating" ? t("jobs.state.generatingAt", { progress: job.progress }) : t(word[job.state]);
  return (
    <span className="bo-status" data-job-state={job.state} data-tone={job.state === "ready" ? "positive" : job.state === "failed" ? "negative" : "neutral"}>
      <Glyph name={glyph[job.state]} />
      <span>{words}</span>
    </span>
  );
}

/** The reference number and, when it is ready, its download. */
export function JobLine({ job, zone }: { job: Job; zone: string }) {
  return (
    <span className="bo-job-line">
      <span>{t("jobs.reference", { reference: job.reference })}</span>
      <JobStateWord job={job} />
      {job.state === "ready" ? (
        <a className="bo-link" href={downloadHref(job.reference)} download>
          {t("jobs.download")}
        </a>
      ) : null}
      {job.state === "ready" && job.expiresAt ? (
        <span className="bo-muted">
          {t("jobs.kept")} <DateTime instant={job.expiresAt} zone={zone} />
        </span>
      ) : null}
    </span>
  );
}

export function JobsPage() {
  const q = useJobs();
  const zone = useZone();
  const refusal = (q.error as { refusal?: Refusal } | null)?.refusal;
  return (
    <section className="bo-jobs">
      <h1>{t("area.jobs")}</h1>
      <p className="bo-muted">{t("jobs.intro")}</p>
      {refusal ? <RefusalNotice refusal={refusal} /> : null}
      {q.isPending ? <p>{t("list.loading")}</p> : null}
      {q.data && q.data.length === 0 ? <p>{t("jobs.none")}</p> : null}
      {q.data && q.data.length > 0 ? (
        <table className="bo-chart-table">
          <caption className="bo-sr-only">{t("area.jobs")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("jobs.what")}</th>
              <th scope="col">{t("jobs.started")}</th>
              <th scope="col">{t("jobs.referenceHeading")}</th>
              <th scope="col">{t("jobs.stateHeading")}</th>
              <th scope="col">{t("jobs.document")}</th>
            </tr>
          </thead>
          <tbody>
            {q.data.map((j) => (
              <tr key={j.reference} data-job={j.reference}>
                <th scope="row">{t(kindLabel(j.kind))}</th>
                <td>
                  <DateTime instant={j.startedAt} zone={zone} />
                </td>
                <td className="figures">{j.reference}</td>
                <td>
                  <JobStateWord job={j} />
                  {j.state === "unknown" ? <span className="bo-muted">{render({ key: j.reason === "not_listed" ? "jobs.notListed" : "jobs.unreadable" })}</span> : null}
                </td>
                <td>
                  {j.state === "ready" ? (
                    <a className="bo-link" href={downloadHref(j.reference)} download>
                      {t("jobs.download")}
                    </a>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}
