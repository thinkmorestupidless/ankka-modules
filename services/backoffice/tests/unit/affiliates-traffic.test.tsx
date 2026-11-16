// @vitest-environment jsdom
// DR-19: the programme dashboard's traffic and acquisition, from the programme's `traffic` (spec 030 A3):
// the counts, the original's rates and their nulls, and the change on the month before.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { DashboardSection } from "../../src/web/areas/affiliates/dashboard.tsx";
import { change, monthBefore, rates, thisMonth, type Traffic } from "../../src/web/areas/affiliates/programme.ts";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const manager: Me = {
  subject: "op-m",
  name: "Manager",
  roles: ["brand_goldvibe", "affiliate_manager"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["programme.manage"],
};

const traffic = (period: string, clickCount: number, registrationCount: number, ftdCount: number, qftdCount: number): Traffic => ({
  brandId: "goldvibe",
  period,
  clickCount,
  registrationCount,
  ftdCount,
  qftdCount,
  affiliates: [],
});

function dashboard(now: Traffic, before: Traffic) {
  const asked: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      asked.push(url);
      if (url.startsWith("/api/s/affiliate-programme/traffic")) {
        const period = new URL(url, "http://x").searchParams.get("period");
        return Response.json(period === now.period ? now : before);
      }
      if (url.startsWith("/api/s/affiliate-programme/revenue/")) return Response.json([]);
      if (url.startsWith("/api/s/affiliate-programme/revenue")) return Response.json({ period: now.period, figures: [] });
      if (url.startsWith("/api/s/affiliate-programme/accounts")) return Response.json({ items: [], total: 0, page: 1, pageSize: 1 });
      return Response.json([]);
    }),
  );
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={manager}>
        <ReadOnlyContext.Provider value={false}>
          <DashboardSection />
        </ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
  return asked;
}

const card = (heading: string) => screen.getByRole("heading", { name: heading }).closest(".bo-aff-card") as HTMLElement;

describe("the programme dashboard's traffic", () => {
  it("shows the period's counts and rates with the change on the month before, read for both months", async () => {
    const period = thisMonth("America/Sao_Paulo");
    const asked = dashboard(traffic(period, 200, 40, 10, 6), traffic(monthBefore(period), 100, 40, 12, 6));
    await screen.findByRole("group", { name: t("affiliates.traffic.heading") });
    const clicks = card(t("affiliates.traffic.clicks"));
    expect(within(clicks).getByText("200")).toBeTruthy();
    expect(within(clicks).getByText(t("affiliates.traffic.up", { rate: t("chart.percent", { value: 100 }) }))).toBeTruthy();
    expect(within(card(t("affiliates.traffic.registrations"))).getByText(t("affiliates.traffic.level"))).toBeTruthy();
    expect(within(card(t("affiliates.traffic.ftds"))).getByText(t("affiliates.traffic.down", { rate: t("chart.percent", { value: 17 }) }))).toBeTruthy();
    expect(within(card(t("affiliates.traffic.qftds"))).getByText("6")).toBeTruthy();
    const conversion = card(t("affiliates.traffic.conversion"));
    expect(within(conversion).getByText(t("chart.percent", { value: "5.0" }))).toBeTruthy();
    expect(within(conversion).getByText(t("affiliates.traffic.ratio", { count: "10", total: "200" }))).toBeTruthy();
    expect(within(card(t("affiliates.traffic.registrationToFtd"))).getByText(t("chart.percent", { value: "25.0" }))).toBeTruthy();
    expect(screen.getByText(t("affiliates.traffic.notCounted"))).toBeTruthy();
    expect(asked).toContain(`/api/s/affiliate-programme/traffic?period=${period}`);
    expect(asked).toContain(`/api/s/affiliate-programme/traffic?period=${monthBefore(period)}`);
  });

  it("shows no rate where the clicks are fewer than what they led to, and no change against nothing", async () => {
    const period = thisMonth("America/Sao_Paulo");
    dashboard(traffic(period, 3, 5, 1, 0), traffic(monthBefore(period), 0, 0, 0, 0));
    await screen.findByRole("group", { name: t("affiliates.traffic.heading") });
    const conversion = card(t("affiliates.traffic.conversion"));
    expect(within(conversion).getByText("—")).toBeTruthy();
    expect(within(card(t("affiliates.traffic.clickToRegistration"))).getByText("—")).toBeTruthy();
    expect(document.querySelectorAll(".bo-change").length).toBe(0);
  });
});

describe("the traffic's arithmetic", () => {
  it("names the month before across a year", () => {
    expect([monthBefore("2026-01"), monthBefore("2026-11")]).toEqual(["2025-12", "2026-10"]);
  });
  it("is the original's: no change against zero, level within 1%", () => {
    expect(change(5, 0)).toBeNull();
    expect(change(1005, 1000)).toEqual({ trend: "level", percent: 0 });
    expect(change(50, 100)).toEqual({ trend: "down", percent: 50 });
    expect(rates.conversion({ clickCount: 10, registrationCount: 11, ftdCount: 1, qftdCount: 0 })).toBeNull();
    expect(rates.registrationToFtd({ clickCount: 0, registrationCount: 4, ftdCount: 1, qftdCount: 0 })).toBe(25);
  });
});
