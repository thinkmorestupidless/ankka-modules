// @vitest-environment jsdom
// The player's timeline as the page shows it: the export offered only with export.player (FR-005), a
// source missing in two periods named for each (FR-022), and the kinds never all unticked.
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import type { Me } from "../../src/shared/access.ts";
import { PlayerTimeline } from "../../src/web/areas/players/timeline.tsx";
import { MeContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const me = (permissions: string[]): Me => ({
  subject: "op-a",
  name: "Ana",
  roles: ["brand_goldvibe", "brand_support"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions,
});

const bags = { brand_support: ["player.read"], brand_compliance: ["player.read", "export.player"] };

function serve(gaps: { source: string; from: string; to: string; reason: string }[] = []) {
  const calls: { method: string; url: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      calls.push({ method, url });
      if (url === "/api/permissions") return Response.json({ roles: bags });
      if (method === "POST" && url.endsWith("/timeline/export")) return Response.json({ reference: "exp-42" }, { status: 202 });
      if (url.includes("/timeline")) return Response.json({ lines: [], next: null, gaps, from: "2026-10-01T00:00:00Z", to: "2026-11-01T00:00:00Z" });
      return Response.json({}, { status: 404 });
    }),
  );
  return calls;
}

async function timeline(operator: Me, search = "") {
  const root = createRootRoute();
  const page = createRoute({
    getParentRoute: () => root,
    path: "/players/$player",
    component: () => (
      <MeContext.Provider value={operator}>
        <PlayerTimeline player="p-1" />
      </MeContext.Provider>
    ),
  });
  const router = createRouter({ routeTree: root.addChildren([page]), history: createMemoryHistory({ initialEntries: [`/players/p-1${search}`] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  await screen.findByText("Nothing happened in this period.");
}

describe("the timeline's export", () => {
  test("is shown disabled, with the role that gives it, to an operator without export.player", async () => {
    const calls = serve();
    await timeline(me(["player.read"]));
    const button = await screen.findByRole("button", { name: "Export" });
    expect(button).toHaveProperty("disabled", true);
    await waitFor(() => expect(document.getElementById(button.getAttribute("aria-describedby")!)!.textContent).toMatch(/brand_compliance/));
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  test("with export.player, states its effect, then starts players' export and shows its reference", async () => {
    const calls = serve();
    await timeline(me(["player.read", "export.player"]));
    await act(async () => fireEvent.click(await screen.findByRole("button", { name: "Export" })));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toMatch(/activity over the period shown/);
    await act(async () => fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" })));
    expect((await screen.findByText(/exp-42/)).getAttribute("role")).toBe("status");
    expect(calls.filter((c) => c.method === "POST").map((c) => c.url)).toEqual(["/api/players/p-1/timeline/export"]);
  });
});

describe("the timeline's gaps and kinds", () => {
  test("a source missing in two periods is named for each", async () => {
    serve([
      { source: "rounds", from: "2026-10-01T00:00:00Z", to: "2026-10-02T00:00:00Z", reason: "unavailable" },
      { source: "rounds", from: "2026-10-20T00:00:00Z", to: "2026-10-21T00:00:00Z", reason: "unavailable" },
    ]);
    await timeline(me(["player.read"]));
    expect(screen.getAllByText(/Rounds are missing from/)).toHaveLength(2);
  });

  test("the last kind ticked cannot be unticked", async () => {
    serve();
    await timeline(me(["player.read"]), "?kinds=notes");
    const boxes = screen.getAllByRole("checkbox");
    const ticked = boxes.filter((b) => (b as HTMLInputElement).checked);
    expect(ticked).toHaveLength(1);
    expect(ticked[0]).toHaveProperty("disabled", true);
    expect(boxes.filter((b) => !(b as HTMLInputElement).checked).every((b) => !(b as HTMLInputElement).disabled)).toBe(true);
  });
});
