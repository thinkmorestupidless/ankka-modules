// The notice a job leaves when it ends, wherever the operator is (FR-029): `/api/events` says
// `job_done`, `frame/events.ts` announces it here, and the frame shows it until dismissed, with the
// download when the document is ready. A polite live region: it is read without moving focus.
import { useSyncExternalStore } from "react";
import { t } from "../../../shared/messages/index.ts";
import { Glyph } from "../../components/glyph.tsx";
import { downloadHref, kindLabel } from "./jobs.ts";
import "./jobs.css";

export interface JobNotice {
  reference: string;
  kind: string;
  state: string;
}

let notices: readonly JobNotice[] = [];
const listeners = new Set<() => void>();
const changed = () => listeners.forEach((l) => l());

export function announce(n: JobNotice): void {
  notices = [n, ...notices.filter((x) => x.reference !== n.reference)].slice(0, 5);
  changed();
}

export function dismiss(reference: string): void {
  notices = notices.filter((x) => x.reference !== reference);
  changed();
}

/** `job_done`'s data, when it is what the server sends. */
export function noticeOf(data: unknown): JobNotice | undefined {
  try {
    const d = JSON.parse(String(data)) as Partial<JobNotice>;
    return typeof d.reference === "string" && typeof d.kind === "string" && typeof d.state === "string"
      ? { reference: d.reference, kind: d.kind, state: d.state }
      : undefined;
  } catch {
    return undefined;
  }
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => void listeners.delete(l);
}

export function useJobNotices(): readonly JobNotice[] {
  return useSyncExternalStore(subscribe, () => notices, () => notices);
}

export function JobNotices() {
  const shown = useJobNotices();
  return (
    <div className="bo-job-notices" aria-live="polite">
      {shown.map((n) => {
        const ready = n.state === "ready";
        return (
          <p key={n.reference} className="bo-notice" data-job={n.reference}>
            <Glyph name={ready ? "check" : "cross"} />
            <span>{t(ready ? "jobs.notice.ready" : "jobs.notice.failed", { kind: t(kindLabel(n.kind)), reference: n.reference })}</span>
            {ready ? (
              <a className="bo-link" href={downloadHref(n.reference)} download>
                {t("jobs.download")}
              </a>
            ) : null}
            <button type="button" className="bo-button" onClick={() => dismiss(n.reference)}>
              {t("jobs.dismiss")}
            </button>
          </p>
        );
      })}
    </div>
  );
}
