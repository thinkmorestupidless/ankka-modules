// @vitest-environment jsdom
// The frame (T042): areas absent for a module the brand does not run or a permission not held; the
// brand always shown; read-only below 1280 px; signed out goes to the sign-in with the return path.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import type { Me } from "../../src/shared/access.ts";
import { areaOf, areasFor } from "../../src/web/frame/areas.ts";
import { Frame } from "../../src/web/frame/Frame.tsx";
import { ModulesPage } from "../../src/web/frame/pages.tsx";
import { readMe, signInHref } from "../../src/web/frame/session.ts";

const me = (over: Partial<Me> = {}): Me => ({
  subject: "op-1",
  name: "Ana",
  roles: ["support"],
  brand: { slug: "spinvibe", name: "spinvibe", modules: ["core", "backoffice"], zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["player.read", "reward.read", "withdrawal.read"],
  ...over,
});

afterEach(cleanup);

describe("the rail's areas", () => {
  it("omits an area whose module the brand does not run", () => {
    const ids = areasFor(me()).map((a) => a.id);
    expect(ids).toContain("players");
    expect(ids).not.toContain("rewards"); // casino is not run, though reward.read is held
  });

  it("shows the area once the brand runs its module", () => {
    expect(areasFor(me({ brand: { slug: "s", name: "s", modules: ["core", "casino"], zone: "America/Sao_Paulo" } })).map((a) => a.id)).toContain("rewards");
  });

  it("omits an area the operator holds no permission for", () => {
    const ids = areasFor(me()).map((a) => a.id);
    expect(ids).not.toContain("team");
    expect(ids).not.toContain("fraud");
  });

  it("shows a programme manager the affiliate programme and nothing else (FR-037)", () => {
    expect(areasFor(me({ roles: ["brand_spinvibe", "affiliate_manager"], permissions: ["programme.manage"] })).map((a) => a.id)).toEqual(["affiliates"]);
  });

  it("opens the worklist to an operator whose roles in the brand are payments roles only (FR-016)", () => {
    expect(areasFor(me({ roles: ["brand_spinvibe", "resource_admin"], permissions: ["payments.read", "payments.operate", "payments.admin"] })).map((a) => a.id)[0]).toBe("worklist");
  });

  it("shows every area held when the modules are not known", () => {
    expect(areasFor(me({ brand: { slug: "s", name: "s", modules: null, zone: "America/Sao_Paulo" } })).map((a) => a.id)).toContain("rewards");
  });

  it("finds the area a path is in", () => {
    expect(areaOf("/")?.id).toBe("worklist");
    expect(areaOf("/players/p-1")?.id).toBe("players");
    expect(areaOf("/adjustments/batch")?.id).toBe("adjustments");
    expect(areaOf("/adjustments/accounts")?.id).toBe("adjustments");
    expect(areaOf("/nowhere")).toBeUndefined();
  });
});

async function inFrame(operator: Me, readOnly: boolean, path = "/players", page: () => React.ReactElement = () => <p>page</p>) {
  const root = createRootRoute({ component: () => <Frame me={operator} readOnly={readOnly} /> });
  const routes = ["/", "/players", "/search", "/jobs", "/configuration/modules"].map((p) =>
    createRoute({ getParentRoute: () => root, path: p, component: page }),
  );
  const router = createRouter({ routeTree: root.addChildren(routes), history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  await screen.findByRole("main");
}

describe("the frame", () => {
  it("shows the brand and the operator", async () => {
    await inFrame(me(), false);
    expect(screen.getByText("spinvibe")).toBeTruthy();
    expect(screen.getByText(/Ana/)).toBeTruthy();
  });

  it("marks the current area and names every area for a screen reader", async () => {
    await inFrame(me(), false, "/players");
    const nav = screen.getByRole("navigation");
    const current = nav.querySelector('[aria-current="page"]');
    expect(current?.textContent).toMatch(/Players/);
    expect(nav.querySelectorAll("a").length).toBe(areasFor(me()).length);
  });

  it("says it is read-only below 1280 px, and not above", async () => {
    await inFrame(me(), true);
    expect(screen.getByRole("status")).toBeTruthy();
    cleanup();
    await inFrame(me(), false);
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("says on the modules page which modules the brand runs", async () => {
    await inFrame(me(), false, "/configuration/modules", ModulesPage);
    expect(screen.getByText(/core, backoffice/)).toBeTruthy();
    expect(screen.getAllByRole("listitem").some((li) => /casino/.test(li.textContent ?? ""))).toBe(true);
  });
});

describe("the session", () => {
  it("sends a signed-out operator to sign in, coming back to where they were", async () => {
    window.history.replaceState(null, "", "/players/p-1?tab=wallet");
    const go = vi.fn();
    const state = await readMe(async () => new Response(null, { status: 401 }), go);
    expect(state.state).toBe("loading");
    expect(go).toHaveBeenCalledWith("/auth/sign-in?return=%2Fplayers%2Fp-1%3Ftab%3Dwallet");
  });

  it("reads the operator", async () => {
    const state = await readMe(async () => Response.json(me()), vi.fn());
    expect(state.state === "signedIn" && state.me.brand.slug).toBe("spinvibe");
  });

  it("explains a refusal", async () => {
    const state = await readMe(async () => Response.json({ code: "brand_not_in_roles" }, { status: 403 }), vi.fn());
    expect(state.state).toBe("refused");
  });

  it("is unreachable when the server cannot be reached", async () => {
    const state = await readMe(async () => {
      throw new TypeError("network");
    }, vi.fn());
    expect(state.state).toBe("unreachable");
  });

  it("builds the sign-in address", () => {
    expect(signInHref({ pathname: "/", search: "" })).toBe("/auth/sign-in?return=%2F");
  });
});
