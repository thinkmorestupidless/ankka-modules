// A list as every area shows one: a table at 36 px rows, 13 px text and tabular figures
// (contracts/design.md); its place (filters, sort, page, scroll) in the URL, restored on return
// (FR-010); its total and whether it is complete (FR-009); its rows kept while it refetches, never
// blanked or dimmed, and the rows that changed marked (FR-012); its freshness and whether it is
// provisional (FR-011). It is given its rows; it fetches nothing.
import { useEffect, useRef, type KeyboardEvent, type ReactNode } from "react";
import "../components.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { Glyph } from "../glyph.tsx";
import { FreshnessNote, type Freshness } from "./freshness.tsx";
import type { ListControl, ListState } from "./state.ts";

export interface Column<Row> {
  id: string;
  header: MessageKey;
  cell(row: Row): ReactNode;
  /** The owner's sort name for this column, when the owner sorts by it. */
  sort?: string;
  numeric?: boolean;
}

export interface ListProps<Row> {
  caption: MessageKey;
  columns: readonly Column<Row>[];
  /** This page's rows; `undefined` while the first answer, or a refetch without one, is awaited. */
  rows: readonly Row[] | undefined;
  rowKey(row: Row): string;
  /** What makes a row changed; the row as JSON unless given. */
  rowVersion?(row: Row): string;
  /** The owner's total; `undefined` for an owner that answers only whether there is more. */
  total?: number;
  /** Whether no page follows this one (`completeOf`, or the owner's `hasMore` negated). */
  complete: boolean;
  fetching?: boolean;
  freshness?: Freshness;
  zone: string;
  list: ListControl;
  onOpen?(row: Row): void;
  /** The row the operator last opened, by its key: marked current and focused on return (FR-010). */
  current?: string;
}

const counts = new Intl.NumberFormat("en-GB");

/** The place a list's rows belong to: rows of another place are not compared for changes. */
const placeOf = (s: ListState) => JSON.stringify([s.filters, s.sort ?? null, s.page]);

interface Seen<Row> {
  place?: string;
  rows?: readonly Row[];
  versions: Map<string, string>;
  changed: Set<string>;
}

/** The rows to show and those changed since the last answer for the same place. */
function useKeptRows<Row>(rows: readonly Row[] | undefined, place: string, key: (r: Row) => string, version: (r: Row) => string) {
  const seen = useRef<Seen<Row>>({ versions: new Map(), changed: new Set() });
  const s = seen.current;
  if (rows && rows !== s.rows) {
    const versions = new Map(rows.map((r) => [key(r), version(r)]));
    const changed = new Set<string>();
    if (s.rows && s.place === place) for (const [k, v] of versions) if (s.versions.get(k) !== v) changed.add(k);
    seen.current = { place, rows, versions, changed };
  }
  return { shown: rows ?? seen.current.rows, changed: seen.current.changed };
}

function nextSort(current: string | undefined, name: string): string | undefined {
  if (current === name) return `-${name}`;
  if (current === `-${name}`) return undefined;
  return name;
}

export function List<Row>(props: ListProps<Row>) {
  const { columns, rowKey, list, onOpen } = props;
  const { state } = list;
  const { shown, changed } = useKeptRows(props.rows, placeOf(state), rowKey, props.rowVersion ?? ((r) => JSON.stringify(r)));

  // The scroll offset: restored once rows are there to scroll, kept as the operator scrolls.
  const scroller = useRef<HTMLDivElement>(null);
  const restored = useRef(false);
  const hasRows = (shown?.length ?? 0) > 0;
  useEffect(() => {
    if (restored.current || !hasRows || !scroller.current) return;
    scroller.current.scrollTop = state.scroll;
    restored.current = true;
  }, [hasRows, state.scroll]);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const onScroll = () => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => scroller.current && list.setScroll(scroller.current.scrollTop), 200);
  };
  // The row last opened, focused once its row is there, so the operator is back where they were.
  const focused = useRef(false);
  useEffect(() => {
    if (focused.current || props.current === undefined || !hasRows || !scroller.current) return;
    const tr = [...scroller.current.querySelectorAll<HTMLElement>("tr[data-row]")].find((r) => r.dataset.row === props.current);
    if (!tr) return;
    focused.current = true;
    tr.focus({ preventScroll: state.scroll > 0 });
  }, [hasRows, props.current, state.scroll]);
  const open = (row: Row) => {
    if (!onOpen) return;
    clearTimeout(timer.current);
    if (scroller.current) list.setScroll(scroller.current.scrollTop);
    onOpen(row);
  };
  const onKey = (ev: KeyboardEvent, row: Row) => {
    if (ev.key === "Enter") open(row);
  };

  return (
    <section className="bo-list" aria-busy={props.fetching ?? false}>
      <div className="bo-list-head">
        <span data-total>
          <span>{t("list.total")}</span> <span>{props.total === undefined ? t("list.totalUnknown") : counts.format(props.total)}</span>
        </span>
        <span className="bo-status" data-complete={props.complete}>
          <Glyph name={props.complete ? "check" : "minus"} />
          <span>{props.complete ? t("list.complete") : t("list.more")}</span>
        </span>
        {props.freshness && <FreshnessNote freshness={props.freshness} zone={props.zone} />}
        <span aria-live="polite">{props.fetching && shown !== undefined ? t("list.refreshing") : null}</span>
      </div>
      <div className="bo-list-scroll" ref={scroller} onScroll={onScroll}>
        <table>
          <caption className="bo-sr-only">{t(props.caption)}</caption>
          <thead>
            <tr>
              <th scope="col" aria-label={t("list.changed")} />
              {columns.map((c) => {
                const dir = state.sort === c.sort ? "ascending" : state.sort === `-${c.sort}` ? "descending" : undefined;
                return (
                  <th key={c.id} scope="col" data-numeric={c.numeric || undefined} aria-sort={c.sort ? (dir ?? "none") : undefined}>
                    {c.sort ? (
                      <button type="button" onClick={() => list.setSort(nextSort(state.sort, c.sort!))}>
                        <span>{t(c.header)}</span>
                        {dir && <Glyph name={dir === "ascending" ? "up" : "down"} />}
                      </button>
                    ) : (
                      t(c.header)
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {shown === undefined && (
              <tr>
                <td colSpan={columns.length + 1}>{t("list.loading")}</td>
              </tr>
            )}
            {shown?.length === 0 && (
              <tr>
                <td colSpan={columns.length + 1}>{t("list.empty")}</td>
              </tr>
            )}
            {shown?.map((row) => {
              const k = rowKey(row);
              const isChanged = changed.has(k);
              return (
                <tr
                  key={k}
                  data-row={k}
                  data-changed={isChanged || undefined}
                  aria-current={props.current === k ? "true" : undefined}
                  data-openable={onOpen ? true : undefined}
                  tabIndex={onOpen ? 0 : undefined}
                  onClick={onOpen ? () => open(row) : undefined}
                  onKeyDown={onOpen ? (ev) => onKey(ev, row) : undefined}
                >
                  <td>
                    {isChanged && (
                      <span className="bo-status" title={t("list.changed")}>
                        <Glyph name="changed" />
                        <span className="bo-sr-only">{t("list.changed")}</span>
                      </span>
                    )}
                  </td>
                  {columns.map((c) => (
                    <td key={c.id} data-numeric={c.numeric || undefined}>
                      {c.cell(row)}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <nav className="bo-list-foot" aria-label={t("list.pages")}>
        <span>
          <span>{t("list.page")}</span> <span>{counts.format(state.page)}</span>
        </span>
        <button type="button" className="bo-button" disabled={state.page <= 1} onClick={() => list.setPage(state.page - 1)}>
          {t("list.previous")}
        </button>
        <button type="button" className="bo-button" disabled={props.complete} onClick={() => list.setPage(state.page + 1)}>
          {t("list.next")}
        </button>
      </nav>
    </section>
  );
}
