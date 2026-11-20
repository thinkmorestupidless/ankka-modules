// @vitest-environment jsdom
// A report the operator lacks the permission for names a role that would give it, as an action does (FR-005).
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import type { Me } from "../../src/shared/access.ts";
import { rolesGiving } from "../../src/shared/access.ts";
import { ReportView } from "../../src/web/areas/reports/ReportsPage.tsx";
import { MeContext } from "../../src/web/frame/context.ts";
import { OperatorBags } from "./bags.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const support: Me = {
  subject: "op-s",
  name: "Sam",
  roles: ["brand_goldvibe", "brand_support"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["export.player"],
};

describe("a report the operator may not read", () => {
  it("names the roles that would give its permission", async () => {
    window.scrollTo = () => {};
    vi.stubGlobal("fetch", vi.fn(async (url: string) => (url === "/api/permissions" ? Response.json({ roles: OperatorBags }) : Response.json({ error: "forbidden" }, { status: 403 }))));
    const root = createRootRoute();
    const page = createRoute({
      getParentRoute: () => root,
      path: "/reports/revenue",
      component: () => (
        <MeContext.Provider value={support}>
          <ReportView report="revenue" search={{}} set={() => undefined} zone="America/Sao_Paulo" />
        </MeContext.Provider>
      ),
    });
    const router = createRouter({ routeTree: root.addChildren([page]), history: createMemoryHistory({ initialEntries: ["/reports/revenue"] }) });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    const roles = rolesGiving("report.read", OperatorBags);
    expect(roles.length).toBeGreaterThan(0);
    expect((await screen.findByText(new RegExp(`Needs a role that gives it: ${roles.join(", ")}`))).hasAttribute("data-needs")).toBe(true);
  });
});
