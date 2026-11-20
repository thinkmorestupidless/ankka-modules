// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import {
  completeOf,
  fromSearch,
  List,
  memoryStore,
  Provisional,
  toSearch,
  useListState,
  useRouterListStore,
  type Column,
  type ListProps,
  type ListStore,
} from "../../src/web/components/list/index.ts";

afterEach(cleanup);

interface Row {
  id: string;
  status: string;
}

const columns: Column<Row>[] = [
  { id: "id", header: "record.withdrawal", cell: (r) => r.id, sort: "id" },
  { id: "status", header: "record.case", cell: (r) => r.status },
];

type Given = Omit<ListProps<Row>, "list" | "columns" | "rowKey" | "caption" | "zone">;

function Harness({ store, ...given }: Given & { store: ListStore }) {
  const list = useListState(store);
  return <List caption="area.withdrawals" columns={columns} rowKey={(r) => r.id} zone="UTC" list={list} {...given} />;
}

const a: Row[] = [
  { id: "w1", status: "pending_review" },
  { id: "w2", status: "approved" },
];

const rowsShown = () => Array.from(document.querySelectorAll("tbody tr[data-row]")).map((tr) => tr.getAttribute("data-row"));

describe("refreshing a list", () => {
  test("keeps the rows shown, neither blanked nor dimmed, and says it is refreshing", () => {
    const store = memoryStore();
    const { rerender } = render(<Harness store={store} rows={a} total={2} complete />);
    rerender(<Harness store={store} rows={undefined} total={2} complete fetching />);
    expect(rowsShown()).toEqual(["w1", "w2"]);
    expect(screen.getByText("Refreshing…")).toBeTruthy();
    const style = document.querySelector("tbody")!.getAttribute("style") ?? "";
    expect(style).not.toMatch(/opacity/);
  });

  test("marks the rows that changed, and only those", () => {
    const store = memoryStore();
    const { rerender } = render(<Harness store={store} rows={a} total={2} complete />);
    expect(document.querySelectorAll("tr[data-changed]")).toHaveLength(0);
    rerender(<Harness store={store} rows={[a[0]!, { id: "w2", status: "completed" }, { id: "w3", status: "pending_review" }]} total={3} complete />);
    expect(Array.from(document.querySelectorAll("tr[data-changed]")).map((tr) => tr.getAttribute("data-row"))).toEqual(["w2", "w3"]);
    // the mark is a glyph and words, not colour alone
    const mark = document.querySelector("tr[data-changed='true'] td")!;
    expect(mark.querySelector("svg[data-glyph='changed']")).not.toBeNull();
    expect(mark.textContent).toBe("Changed since the last refresh");
  });

  test("the marks stay through a render that brings no new answer", () => {
    const store = memoryStore();
    const { rerender } = render(<Harness store={store} rows={a} total={2} complete />);
    const b = [a[0]!, { id: "w2", status: "completed" }];
    rerender(<Harness store={store} rows={b} total={2} complete />);
    rerender(<Harness store={store} rows={b} total={2} complete fetching />);
    expect(document.querySelectorAll("tr[data-changed]")).toHaveLength(1);
  });

  test("another page's rows are not marked as changed", () => {
    const store = memoryStore();
    const { rerender } = render(<Harness store={store} rows={a} total={4} complete={false} />);
    act(() => fireEvent.click(screen.getByRole("button", { name: "Next" })));
    rerender(<Harness store={store} rows={[{ id: "w3", status: "open" }]} total={4} complete />);
    expect(document.querySelectorAll("tr[data-changed]")).toHaveLength(0);
  });
});

describe("a list's total and whether it is complete", () => {
  test("shows the total and complete", () => {
    render(<Harness store={memoryStore()} rows={a} total={1234} complete />);
    expect(document.querySelector("[data-total]")!.textContent).toBe("Total 1,234");
    expect(screen.getByText("Complete")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).disabled).toBe(true);
  });

  test("shows that there is more", () => {
    render(<Harness store={memoryStore()} rows={a} total={60} complete={false} />);
    expect(screen.getByText("More on later pages")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).disabled).toBe(false);
  });

  test("an owner that gives no total says so", () => {
    render(<Harness store={memoryStore()} rows={a} complete={false} />);
    expect(document.querySelector("[data-total]")!.textContent).toBe("Total not given");
  });

  test("complete is a page reaching the total", () => {
    expect(completeOf({ page: 2, pageSize: 25, total: 50 })).toBe(true);
    expect(completeOf({ page: 1, pageSize: 25, total: 26 })).toBe(false);
    expect(completeOf({ page: 1, pageSize: 25, total: 0 })).toBe(true);
  });
});

describe("a view that reaches only part of an incomplete list", () => {
  test("offers no Next on its last page, and still says there is more", () => {
    render(<Harness store={memoryStore()} rows={a} total={300} complete={false} lastPage />);
    expect(screen.getByRole("button", { name: "Next" })).toHaveProperty("disabled", true);
    expect(document.querySelector("[data-complete]")!.getAttribute("data-complete")).toBe("false");
  });
});

describe("the table's scroll", () => {
  test("follows the place: a page turned is shown from its top", async () => {
    const store = memoryStore({ scroll: 120 });
    const { rerender } = render(<Harness store={store} rows={a} total={300} complete={false} />);
    const scroller = document.querySelector<HTMLDivElement>(".bo-list-scroll")!;
    expect(scroller.scrollTop).toBe(120);
    scroller.scrollTop = 400;
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Next" })));
    rerender(<Harness store={store} rows={[{ id: "w9", status: "approved" }]} total={300} complete={false} />);
    expect(store.get().page).toBe(2);
    expect(scroller.scrollTop).toBe(0);
  });
});

describe("freshness and provisional", () => {
  // the tests run from services/backoffice (package.json's `test`)
  const css = readFileSync(join(process.cwd(), "src/web/components/components.css"), "utf8");

  test("a list shows when its views last caught up, in the brand's zone", () => {
    render(<Harness store={memoryStore()} rows={a} total={2} complete freshness={{ at: "2026-11-15T12:00:00Z", provisional: false }} />);
    expect(document.querySelector("[data-freshness='current']")!.textContent).toMatch(/^Updated 15 Nov 2026, 12:00 UTC$/);
    expect(document.querySelector(".bo-provisional")).toBeNull();
  });

  test("provisional is a word and a dashed underline", () => {
    render(<Harness store={memoryStore()} rows={a} total={2} complete freshness={{ at: "2026-11-15T12:00:00Z", provisional: true }} />);
    const p = document.querySelector("[data-freshness='provisional'] .bo-provisional")!;
    expect(p.textContent).toBe("Provisional: the views are catching up");
    expect(css).toMatch(/\.bo-provisional\s*\{[^}]*text-decoration:\s*underline dashed/);
  });

  test("a provisional figure is marked in words and underlined; a current one is itself", () => {
    const { container, rerender } = render(<Provisional provisional>{"12"}</Provisional>);
    expect(container.querySelector(".bo-provisional")!.textContent).toBe("12 (provisional)");
    rerender(<Provisional provisional={false}>{"12"}</Provisional>);
    expect(container.innerHTML).toBe("12");
  });
});

describe("a list's place", () => {
  test("sorting, paging and filtering are kept in the store; a new sort or filter starts at page 1", () => {
    const store = memoryStore({ page: 3, scroll: 400 });
    let control: ReturnType<typeof useListState> | undefined;
    function Capture() {
      control = useListState(store);
      return <List caption="area.withdrawals" columns={columns} rowKey={(r) => r.id} zone="UTC" list={control} rows={a} total={100} complete={false} />;
    }
    render(<Capture />);
    act(() => fireEvent.click(screen.getByRole("button", { name: "Withdrawal" })));
    expect(store.get()).toEqual({ filters: {}, sort: "id", page: 1, scroll: 0 });
    expect(screen.getByRole("columnheader", { name: "Withdrawal" }).getAttribute("aria-sort")).toBe("ascending");
    act(() => fireEvent.click(screen.getByRole("button", { name: "Withdrawal" })));
    expect(store.get().sort).toBe("-id");
    act(() => fireEvent.click(screen.getByRole("button", { name: "Next" })));
    expect(store.get().page).toBe(2);
    act(() => control!.setFilter("status", "pending_review"));
    expect(store.get()).toEqual({ filters: { status: "pending_review" }, sort: "-id", page: 1, scroll: 0 });
    act(() => control!.setFilter("status", undefined));
    expect(store.get().filters).toEqual({});
  });

  test("the scroll offset is restored on return and kept, as a replacement, when a row is opened", () => {
    const store = memoryStore({ page: 2, scroll: 360 });
    const onOpen = vi.fn();
    render(<Harness store={store} rows={a} total={100} complete={false} onOpen={onOpen} />);
    const scroller = document.querySelector(".bo-list-scroll")!;
    expect(scroller.scrollTop).toBe(360);
    scroller.scrollTop = 480;
    act(() => fireEvent.click(screen.getByText("w2")));
    expect(onOpen).toHaveBeenCalledWith(a[1]);
    expect(store.history.at(-1)).toEqual({ state: { filters: {}, page: 2, scroll: 480 }, replace: true });
  });

  test("a row opens with the keyboard too", () => {
    const onOpen = vi.fn();
    render(<Harness store={memoryStore()} rows={a} total={2} complete onOpen={onOpen} />);
    fireEvent.keyDown(document.querySelector("tr[data-row='w1']")!, { key: "Enter" });
    expect(onOpen).toHaveBeenCalledWith(a[0]);
  });

  test("is written to search parameters and read back", () => {
    const s = { filters: { status: "pending_review", from: "2026-11-01" }, sort: "-createdAt", page: 3, scroll: 720 };
    expect(toSearch(s)).toEqual({ "f.status": "pending_review", "f.from": "2026-11-01", sort: "-createdAt", page: 3, y: 720 });
    expect(fromSearch(toSearch(s))).toEqual(s);
    expect(fromSearch({ page: "x", y: -4, other: "kept elsewhere" })).toEqual({ filters: {}, sort: undefined, page: 1, scroll: 0 });
    expect(toSearch({ filters: {}, page: 1, scroll: 0 })).toEqual({});
  });

  test("in the router, the place is the URL's search and other parameters are kept", async () => {
    window.scrollTo = () => {}; // the router restores the window's scroll; jsdom has none
    const root = createRootRoute();
    const seen: string[] = [];
    function Page() {
      const control = useListState(useRouterListStore());
      seen.push(JSON.stringify(control.state));
      return (
        <button type="button" onClick={() => control.setPage(control.state.page + 1)}>
          {"next"}
        </button>
      );
    }
    const page = createRoute({ getParentRoute: () => root, path: "/withdrawals", component: Page });
    const history = createMemoryHistory({ initialEntries: ["/withdrawals?f.status=pending_review&page=2&tab=queue"] });
    const router = createRouter({ routeTree: root.addChildren([page]), history });
    render(<RouterProvider router={router} />);
    await screen.findByRole("button", { name: "next" });
    expect(JSON.parse(seen.at(-1)!)).toEqual({ filters: { status: "pending_review" }, page: 2, scroll: 0 });
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "next" })));
    await vi.waitFor(() => expect(router.state.location.search).toEqual({ "f.status": "pending_review", page: 3, tab: "queue" }));
    expect(JSON.parse(seen.at(-1)!).page).toBe(3);
  });
});
