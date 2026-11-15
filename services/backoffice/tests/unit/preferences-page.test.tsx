// @vitest-environment jsdom
// T053: the preferences page offers only the kinds the operator's roles admit, and writes what is chosen.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { kindsFor } from "../../src/shared/worklist.ts";
import { chosenOf, PreferencesPage, save } from "../../src/web/areas/preferences/PreferencesPage.tsx";
import { MeContext } from "../../src/web/frame/context.ts";

afterEach(cleanup);

const me: Me = {
  subject: "op-1",
  name: "Ana",
  roles: ["brand_goldvibe", "brand_finance"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: ["core", "backoffice"] },
  preferences: { theme: "dark", worklistKinds: null, onlineNotices: false, jobs: [] },
  permissions: ["withdrawal.review", "callback.resolve", "player.read"],
};

function page(write = vi.fn(async (_c: unknown) => undefined)) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MeContext.Provider value={me}>
        <PreferencesPage write={write} />
      </MeContext.Provider>
    </QueryClientProvider>,
  );
  return write;
}

describe("the worklist's kinds", () => {
  it("are those the roles admit, in a module the brand runs", () => {
    const ids = kindsFor(me).map((k) => k.id);
    expect(ids).toEqual(["withdrawals_pending", "payouts_needing_review", "undelivered_outcomes"]);
    expect(ids).not.toContain("unacknowledged_calls"); // callback.resolve is held, but casino is not run
  });
});

describe("the preferences page", () => {
  it("reads what the operator chose, defaulting what they did not", () => {
    expect(chosenOf(null)).toEqual({ theme: "dark", worklistKinds: null, onlineNotices: false });
    expect(chosenOf({ theme: "light", worklistKinds: ["fraud_cases"], onlineNotices: true, jobs: [] })).toEqual({ theme: "light", worklistKinds: ["fraud_cases"], onlineNotices: true });
  });

  it("writes the theme, the kinds chosen and the notices, never jobs", async () => {
    const write = page();
    fireEvent.click(screen.getByLabelText("Light"));
    fireEvent.click(screen.getByLabelText(/Every kind my roles admit/));
    fireEvent.click(screen.getByLabelText("Withdrawal payments needing review"));
    fireEvent.click(screen.getByLabelText(/comes online/));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(write).toHaveBeenCalledOnce());
    expect(write.mock.calls[0]![0]).toEqual({ theme: "light", worklistKinds: ["withdrawals_pending", "undelivered_outcomes"], onlineNotices: true });
    expect(await screen.findByRole("status")).toBeTruthy();
  });

  it("shows the refusal with its reason", async () => {
    page(vi.fn(async () => ({ status: 400, code: "invalid_preference", given: "theme is dark or light", message: { key: "refusal.unknown" as const, values: { code: "invalid_preference", given: "theme is dark or light" } } })) as never);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect((await screen.findByText(/theme is dark or light/)).textContent).toBeTruthy();
  });

  it("puts through the server with the page's header", async () => {
    const f = vi.fn(async () => new Response("{}", { status: 200 }));
    expect(await save({ theme: "dark", worklistKinds: null, onlineNotices: false }, f as never)).toBeUndefined();
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/preferences");
    expect(init.method).toBe("PUT");
    expect((init.headers as Record<string, string>)["X-Backoffice"]).toBe("1");
  });
});
