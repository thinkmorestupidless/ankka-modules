// A list's place, kept in the URL so the operator returns to it (FR-010): its filters, sort, page and
// scroll offset. The list reads and writes it through a `ListStore`; the frame's router gives one
// (`router.ts`), a test gives `memoryStore`.
import { useCallback, useEffect, useMemo, useReducer } from "react";

export interface ListState {
  filters: Readonly<Record<string, string>>;
  /** A column's sort name, `-` first for descending; none is the owner's own order (FR-009). */
  sort?: string;
  /** From 1, as the owners page. */
  page: number;
  /** The table's scroll offset in pixels. */
  scroll: number;
}

export interface ListStore {
  get(): ListState;
  /** `replace` for a change that is not a step back can return to (the scroll offset). */
  set(next: ListState, how?: { replace?: boolean }): void;
  subscribe?(listener: () => void): () => void;
}

export const emptyListState: ListState = { filters: {}, page: 1, scroll: 0 };

/** The search parameters a list's place is written as: `f.<name>`, `sort`, `page`, `y`. */
const Filter = "f.";
export const listSearchKeys = (search: Record<string, unknown>) =>
  Object.keys(search).filter((k) => k.startsWith(Filter) || k === "sort" || k === "page" || k === "y");

export function toSearch(s: ListState): Record<string, string | number> {
  const out: Record<string, string | number> = {};
  for (const [k, v] of Object.entries(s.filters)) if (v !== "") out[`${Filter}${k}`] = v;
  if (s.sort) out.sort = s.sort;
  if (s.page > 1) out.page = s.page;
  if (s.scroll > 0) out.y = Math.round(s.scroll);
  return out;
}

const whole = (v: unknown, min: number, fallback: number) => {
  const n = typeof v === "number" ? v : typeof v === "string" && /^\d+$/.test(v) ? Number(v) : NaN;
  return Number.isSafeInteger(n) && n >= min ? n : fallback;
};

/** A list's place from search parameters; anything not a list's, or not well formed, is left out. */
export function fromSearch(search: Record<string, unknown>): ListState {
  const filters: Record<string, string> = {};
  for (const [k, v] of Object.entries(search))
    if (k.startsWith(Filter) && (typeof v === "string" || typeof v === "number") && String(v) !== "") filters[k.slice(Filter.length)] = String(v);
  const sort = typeof search.sort === "string" && search.sort !== "" ? search.sort : undefined;
  return { filters, sort, page: whole(search.page, 1, 1), scroll: whole(search.y, 0, 0) };
}

/** A store held in memory, with every place it was set to (for tests, and a list with no URL). */
export function memoryStore(initial: Partial<ListState> = {}): ListStore & { history: { state: ListState; replace: boolean }[] } {
  let state: ListState = { ...emptyListState, ...initial };
  const listeners = new Set<() => void>();
  const history: { state: ListState; replace: boolean }[] = [];
  return {
    history,
    get: () => state,
    set(next, how) {
      state = next;
      history.push({ state: next, replace: how?.replace ?? false });
      listeners.forEach((l) => l());
    },
    subscribe(l) {
      listeners.add(l);
      return () => void listeners.delete(l);
    },
  };
}

export interface ListControl {
  state: ListState;
  /** A filter changed starts the list again at its first page, at the top. */
  setFilter(name: string, value: string | undefined): void;
  setSort(sort: string | undefined): void;
  setPage(page: number): void;
  setScroll(scroll: number): void;
}

export function useListState(store: ListStore): ListControl {
  const [, rerender] = useReducer((n: number) => n + 1, 0);
  useEffect(() => store.subscribe?.(rerender), [store]);
  const state = store.get();

  const setFilter = useCallback(
    (name: string, value: string | undefined) => {
      const s = store.get();
      const filters = { ...s.filters };
      if (value === undefined || value === "") delete filters[name];
      else filters[name] = value;
      store.set({ ...s, filters, page: 1, scroll: 0 });
    },
    [store],
  );
  const setSort = useCallback((sort: string | undefined) => store.set({ ...store.get(), sort, page: 1, scroll: 0 }), [store]);
  const setPage = useCallback((page: number) => store.set({ ...store.get(), page: Math.max(1, page), scroll: 0 }), [store]);
  const setScroll = useCallback(
    (scroll: number) => {
      const s = store.get();
      if (Math.round(scroll) !== s.scroll) store.set({ ...s, scroll: Math.round(scroll) }, { replace: true });
    },
    [store],
  );
  return useMemo(() => ({ state, setFilter, setSort, setPage, setScroll }), [state, setFilter, setSort, setPage, setScroll]);
}

/** Whether a page of `pageSize` from 1 reaches the end of `total`: no page after it. */
export function completeOf({ page, pageSize, total }: { page: number; pageSize: number; total: number }): boolean {
  return page * pageSize >= total;
}
