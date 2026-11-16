// A player's timeline (T076; FR-021, FR-022, FR-024): every source's lines merged by the page's server,
// newest first, each with who caused it (the player, an operator by name, the system or a provider),
// its cause where the fact carries one, and links to the records it names. Filtered by kind and period,
// kept in the URL; older lines a page at a time from the server's cursor. A source that could not be
// read is named with the period it is missing, and the timeline then says it is not complete. The
// export is players' activity export, a job of the owner's.
import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { has, t, type MessageKey } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { RecordLink, type RecordRef } from "../../components/links.tsx";
import { Money } from "../../components/money.tsx";
import { explain, type Refusal } from "../../../shared/refusals.ts";
import { refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";
import { actorName, readTimeline, TimelineKinds, type Line, type TimelinePage } from "./read.ts";

export const Periods = ["1d", "7d", "30d", "90d", "365d"] as const;
const Day = 86_400_000;

/** A line's words: the catalogue's message for its key, or its kind and event as given. */
export function lineText(l: Line): string {
  if (has(l.summary.key)) return t(l.summary.key, l.summary.values);
  return t("timeline.line.other", { kind: word("timeline.kind", l.kind), event: String(l.summary.values.event ?? l.summary.key.split(".").pop() ?? "") });
}

export function actorText(l: Line): string {
  const a = l.actor;
  if (a.kind === "operator") {
    const n = actorName(a);
    return n ? t("timeline.actor.operatorNamed", { operator: n }) : t("timeline.actor.operator");
  }
  return t(`timeline.actor.${a.kind}` as MessageKey);
}

function refOf(player: string, link: { kind: string; id: string }): RecordRef | undefined {
  switch (link.kind) {
    case "withdrawal":
      return { kind: "withdrawal", id: link.id };
    case "round":
    case "payment":
    case "adjustment":
    case "reward":
    case "document":
      return { kind: link.kind, player, id: link.id };
    default:
      return undefined;
  }
}

function TimelineLine({ player, l, tz }: { player: string; l: Line; tz: string }) {
  const refs = l.links.map((x) => refOf(player, x)).filter((r): r is RecordRef => r !== undefined);
  return (
    <li className="bo-timeline-line" data-line={l.id} data-kind={l.kind} data-at={l.at} data-actor={l.actor.kind}>
      <DateTime instant={l.at} zone={tz} />
      <span className="bo-chip">{word("timeline.kind", l.kind)}</span>
      <span>{lineText(l)}</span>
      {l.amount ? <Money value={l.amount} /> : null}
      <span className="bo-muted" data-actor-text>
        {actorText(l)}
      </span>
      {l.cause ? <span className="bo-muted">{t("timeline.cause", { kind: word("corrections.cause", l.cause.kind), reference: l.cause.ref })}</span> : null}
      {refs.map((r) => (
        <RecordLink key={`${r.kind}/${r.id}`} to={r} />
      ))}
    </li>
  );
}

export function Gaps({ gaps, tz }: { gaps: TimelinePage["gaps"]; tz: string }) {
  if (gaps.length === 0) return null;
  return (
    <div role="alert" className="bo-timeline-gaps" data-gaps>
      <p>{t("timeline.incomplete")}</p>
      <ul>
        {gaps.map((g) => (
          <li key={g.source} data-gap={g.source}>
            {t("timeline.gap", { source: word("timeline.source", g.source) })} <DateTime instant={g.from} zone={tz} /> {t("timeline.gap.to")} <DateTime instant={g.to} zone={tz} />{" "}
            <span className="bo-muted">({word("players.reason", g.reason)})</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function PlayerTimeline({ player }: { player: string }) {
  const tz = useZone();
  const navigate = useNavigate();
  const search = useRouterState({ select: (s) => s.location.search as Record<string, unknown> });
  const period = (Periods as readonly string[]).includes(String(search.period)) ? String(search.period) : "30d";
  const kinds = typeof search.kinds === "string" && search.kinds !== "" ? search.kinds.split(",").filter((k) => (TimelineKinds as readonly string[]).includes(k)) : [...TimelineKinds];
  const [now] = useState(() => Date.now());
  const from = new Date(now - parseInt(period, 10) * Day).toISOString();
  const to = new Date(now).toISOString();
  const [exported, setExported] = useState<string | undefined>(undefined);
  const [exportRefusal, setExportRefusal] = useState<Refusal | undefined>(undefined);

  const q = useInfiniteQuery({
    queryKey: ["players", "timeline", player, period, kinds.join(",")],
    queryFn: ({ pageParam }) => readTimeline(player, { from, to, kinds, after: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
    enabled: player !== "",
  });
  const pages = q.data?.pages ?? [];
  const lines = pages.flatMap((p) => p.lines);
  const gaps = [...new Map(pages.flatMap((p) => p.gaps).map((g) => [g.source, g])).values()];
  const complete = pages.length > 0 && !q.hasNextPage && gaps.length === 0;

  const set = (next: { period?: string; kinds?: string[] }) =>
    void navigate({
      to: ".",
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        period: next.period ?? period,
        kinds: (next.kinds ?? kinds).length === TimelineKinds.length ? undefined : (next.kinds ?? kinds).join(","),
      }),
      replace: true,
    } as never);

  const exportIt = async () => {
    setExportRefusal(undefined);
    const r = await fetch(`/api/players/${encodeURIComponent(player)}/timeline/export`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Backoffice": "1" },
      body: JSON.stringify({ from, to }),
    }).catch(() => undefined);
    if (r?.ok) setExported(((await r.json()) as { reference: string }).reference);
    else setExportRefusal(r ? explain(r.status, await r.text()) : refusalOf(undefined));
  };

  return (
    <section className="bo-timeline" aria-labelledby="timeline-title">
      <h2 id="timeline-title">{t("players.tab.timeline")}</h2>
      <div className="bo-timeline-filters">
        <fieldset>
          <legend>{t("timeline.period")}</legend>
          {Periods.map((p) => (
            <button key={p} type="button" className="bo-pill" aria-pressed={p === period} onClick={() => set({ period: p })}>
              {t(`timeline.period.${p}` as MessageKey)}
            </button>
          ))}
        </fieldset>
        <fieldset>
          <legend>{t("timeline.kinds")}</legend>
          {TimelineKinds.map((k) => (
            <label key={k} className="bo-timeline-kind">
              <input type="checkbox" checked={kinds.includes(k)} onChange={(e) => set({ kinds: e.target.checked ? [...kinds, k] : kinds.filter((x) => x !== k) })} />
              {word("timeline.kind", k)}
            </label>
          ))}
        </fieldset>
        <button type="button" className="bo-button" onClick={() => void exportIt()}>
          {t("timeline.export")}
        </button>
      </div>
      {exported ? <p role="status">{t("timeline.exported", { reference: exported })}</p> : null}
      {exportRefusal ? <RefusalNotice refusal={exportRefusal} /> : null}
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <Gaps gaps={gaps} tz={tz} />
      {!q.data && !q.isError ? <p role="status">{t("list.loading")}</p> : null}
      {q.data && lines.length === 0 ? <p className="bo-muted">{t("timeline.empty")}</p> : null}
      <ol className="bo-timeline-lines" aria-label={t("players.tab.timeline")}>
        {lines.map((l) => (
          <TimelineLine key={l.id} player={player} l={l} tz={tz} />
        ))}
      </ol>
      {q.hasNextPage ? (
        <button type="button" className="bo-button" disabled={q.isFetchingNextPage} onClick={() => void q.fetchNextPage()}>
          {t("timeline.older")}
        </button>
      ) : null}
      {complete ? <p className="bo-muted" data-complete>{t("timeline.complete")}</p> : null}
    </section>
  );
}
