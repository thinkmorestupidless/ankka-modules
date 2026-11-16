// @vitest-environment jsdom
// T109 to T111: the configuration's pages draw each owner's answer into a form that marks what differs from
// the brand's default, and send each owner the body it takes.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { rewardBody, whyOff, WithdrawalSettingsPage } from "../../src/web/areas/configuration/brand.tsx";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

export const admin: Me = {
  subject: "op-a",
  name: "Admin",
  roles: ["brand_goldvibe", "brand_admin"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null },
  preferences: null,
  permissions: ["config.brand", "config.games", "config.crm", "config.agent", "config.attribution", "callback.resolve", "send.release", "fact.redeliver", "modules.read", "modules.request"],
};

/** The owners behind the page's server, by path; anything else answers 404. */
export function owners(answers: Record<string, unknown>) {
  const calls: { method: string; url: string; body: unknown }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      calls.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined });
      if (url === "/api/permissions") return Response.json({ roles: { brand_admin: admin.permissions } });
      const key = `${method} ${url.split("?")[0]}`;
      return key in answers ? Response.json(answers[key]) : Response.json({ error: "not_found" }, { status: 404 });
    }),
  );
  return calls;
}

export function within(node: ReactNode, me: Me = admin) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={me}>
        <ReadOnlyContext.Provider value={false}>{node}</ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
}

describe("the withdrawal settings", () => {
  it("mark a turnover multiplier other than the wallet's default, and each currency's limits", async () => {
    owners({
      "GET /api/s/wallet/wallet-config": { turnover: { multiplier: 3, definition: "gross_wagered" } },
      "GET /api/s/payouts/withdrawal-config": { limits: { EUR: { weekly: 500000, monthly: null } } },
      "GET /api/s/players/currency-config": { offered: ["BRL", "EUR"], defaultCurrency: "BRL" },
    });
    within(<WithdrawalSettingsPage />);
    const multiplier = await screen.findByLabelText(t("config.withdrawals.multiplier"));
    expect(multiplier.getAttribute("aria-describedby")).toBeTruthy();
    expect(document.getElementById(multiplier.getAttribute("aria-describedby")!)!.textContent).toContain(t("config.form.differsFromDefault", { value: "1" }));
    const weekly = await screen.findByLabelText(`${t("config.withdrawals.weekly")} (EUR)`);
    expect((weekly as HTMLInputElement).value).toBe("5000.00");
    expect(weekly.closest("[data-field]")!.querySelector('[data-differs="default"]')).not.toBeNull();
    const brl = screen.getByLabelText(`${t("config.withdrawals.weekly")} (BRL)`);
    expect(brl.closest("[data-field]")!.querySelector("[data-differs]")).toBeNull();
  });
});

describe("the reward settings' body (wallet W3)", () => {
  it("sends every game type's rate when one changes, and the other fields as changed", () => {
    const next = { "rate.slots": 100, "rate.table": 10, "rate.live_dealer": 20, "rate.poker": 0, "rate.bingo": 100, "rate.sports": 0, bonusWageringCounts: false, partialSplitsAllowed: true, maxConcurrentAwards: null };
    expect(rewardBody({ "rate.table": 10, bonusWageringCounts: false }, next)).toEqual({
      contributionRateByGameType: { slots: 100, table: 10, live_dealer: 20, poker: 0, bingo: 100, sports: 0 },
      bonusWageringCounts: false,
    });
    expect(rewardBody({ maxConcurrentAwards: null }, next)).toEqual({ maxConcurrentAwards: null });
  });
});

describe("why a module is off", () => {
  it("names its state at the vendor, or that the brand is not entitled to it", () => {
    expect(whyOff({ module: "casino", status: "disabled" }, false)).toBe(t("config.modules.why.disabled"));
    expect(whyOff(undefined, false)).toBe(t("config.modules.why.notEntitled"));
    expect(whyOff(undefined, true)).toBe(t("config.modules.why.requested"));
  });
});
