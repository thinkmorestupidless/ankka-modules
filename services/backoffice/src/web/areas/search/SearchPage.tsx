// The one search's results (T074; FR-019, SC-002; contracts/sources.md, *Search*): what was pasted in the
// top bar, asked by the server of the resolvers its shape can match, and every match listed by kind
// with the player it belongs to. None is chosen silently: a value two things answer to lists both. A
// source that did not answer is named, so "nothing" is never said of a search that was not finished.
import { useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import "./search.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { RecordLink, type RecordRef } from "../../components/links.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";

export interface SearchMatch {
  kind: string;
  id: string;
  player?: string;
  summary: Record<string, string | number | boolean | null>;
}

export interface SearchAnswer {
  matches: SearchMatch[];
  asked: string[];
  unavailable: string[];
}

export async function readSearch(q: string, fetchFn: typeof fetch = fetch): Promise<SearchAnswer> {
  return readJson<SearchAnswer>(fetchFn(`/api/search?q=${encodeURIComponent(q)}`, { headers: { Accept: "application/json" } }));
}

/** The kinds in the order they are listed; a kind the server answers that is not here is listed last. */
const Kinds = ["player", "withdrawal", "payment", "round", "reward", "document"] as const;
const kindLabel = (kind: string): MessageKey => (Kinds as readonly string[]).includes(kind) ? (`search.kind.${kind}` as MessageKey) : "search.kind.other";

/** Where a match is shown: its own page, or the player's page at the record. */
export function refOf(m: SearchMatch): RecordRef | undefined {
  switch (m.kind) {
    case "player":
      return { kind: "player", id: m.id };
    case "withdrawal":
      return { kind: "withdrawal", id: m.id };
    case "payment":
      return m.player ? { kind: "payment", player: m.player, id: m.id } : undefined;
    case "round":
      return m.player ? { kind: "round", player: m.player, id: m.id } : undefined;
    case "reward":
      return m.player ? { kind: "reward", player: m.player, id: m.id } : undefined;
    default:
      return undefined;
  }
}

const text = (v: unknown) => (typeof v === "string" && v !== "" ? v : undefined);

function Summary({ m }: { m: SearchMatch }) {
  const s = m.summary;
  const status = text(s.status) ?? text(s.accountStatus) ?? text(s.state);
  const currency = text(s.currency);
  const minor = typeof s.amount === "number" ? s.amount : typeof s.amountCents === "number" ? s.amountCents : undefined;
  const named = text(s.username) ?? text(s.email) ?? text(s.gameName) ?? text(s.offerName) ?? text(s.gameCode) ?? text(s.psp) ?? text(s.kind);
  return (
    <span className="bo-search-summary">
      {named ? <span>{named}</span> : null}
      {minor !== undefined && currency ? <Money value={{ minor, currency }} /> : null}
      {status ? <Status value={status} /> : null}
    </span>
  );
}

function Match({ m }: { m: SearchMatch }) {
  const ref = refOf(m);
  return (
    <li data-match={m.kind} data-id={m.id}>
      {ref ? (
        <RecordLink to={ref} />
      ) : (
        <span>
          {t(kindLabel(m.kind))} {m.id}
        </span>
      )}{" "}
      <Summary m={m} />
      {m.player && m.kind !== "player" ? (
        <span className="bo-muted">
          {" "}
          {t("search.ofPlayer")} <RecordLink to={{ kind: "player", id: m.player }}>{m.player}</RecordLink>
        </span>
      ) : null}
    </li>
  );
}

export function SearchResults({ q, answer }: { q: string; answer: SearchAnswer }) {
  const kinds = [...new Set([...Kinds.filter((k) => answer.matches.some((m) => m.kind === k)), ...answer.matches.map((m) => m.kind)])];
  return (
    <div className="bo-search-results">
      {answer.unavailable.length > 0 ? (
        <p role="alert" data-unavailable>
          {t("search.unavailable", { source: answer.unavailable.join(", ") })}
        </p>
      ) : null}
      {answer.matches.length === 0 ? (
        <p role="status" data-nothing>
          {answer.unavailable.length > 0 ? t("search.nothingYet", { value: q }) : t("search.nothing", { value: q })}
        </p>
      ) : (
        <>
          <p role="status">{t("search.found", { count: answer.matches.length, value: q })}</p>
          {kinds.map((k) => (
            <section key={k} aria-label={t(kindLabel(k))} data-kind={k}>
              <h2>{t(kindLabel(k))}</h2>
              <ul className="bo-search-list">
                {answer.matches.filter((m) => m.kind === k).map((m) => (
                  <Match key={`${m.kind}/${m.id}`} m={m} />
                ))}
              </ul>
            </section>
          ))}
        </>
      )}
    </div>
  );
}

export function SearchPage() {
  const q = String((useSearch({ strict: false }) as Record<string, unknown>).q ?? "").trim();
  const answer = useQuery({ queryKey: ["search", q], queryFn: () => readSearch(q), enabled: q !== "" });
  return (
    <section>
      <h1>{t("area.search")}</h1>
      {q === "" ? (
        <p className="bo-muted">{t("search.hint")}</p>
      ) : answer.isError ? (
        <RefusalNotice refusal={refusalOf(answer.error)} />
      ) : !answer.data ? (
        <p role="status">{t("list.loading")}</p>
      ) : (
        <SearchResults q={q} answer={answer.data} />
      )}
    </section>
  );
}
