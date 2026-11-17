// @vitest-environment jsdom
// The dashboard's panels that are read in the currency today's figures name: when those figures are
// refused, each says why it is not shown rather than loading for ever.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { DashboardView } from "../../src/web/areas/dashboard/DashboardPage.tsx";
import { MeContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const me: Me = {
  subject: "op-a",
  name: "Ana",
  roles: ["brand_goldvibe", "brand_finance"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["dashboard.read"],
};

describe("the dashboard, when today's figures are refused", () => {
  it("says of each panel read in their currency that it is not shown, not that it is loading", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url.startsWith("/api/s/reporting/dashboard/kpis")) return Response.json({ error: "source_unavailable", source: "reporting" }, { status: 503 });
        return Response.json({ error: "source_unavailable", source: "reporting" }, { status: 503 });
      }),
    );
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MeContext.Provider value={me}>
          <DashboardView range="7d" currency={undefined} onRange={() => undefined} onCurrency={() => undefined} zone="America/Sao_Paulo" />
        </MeContext.Provider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(document.querySelectorAll("[data-waits]").length).toBeGreaterThanOrEqual(4));
    expect(screen.getAllByText(t("dashboard.waitsOnFigures")).length).toBeGreaterThanOrEqual(4);
  });
});
