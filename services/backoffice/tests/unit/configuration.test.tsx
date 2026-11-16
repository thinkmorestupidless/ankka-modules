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
import { overridden, toggled } from "../../src/web/areas/configuration/games.tsx";
import { CrmPage, crmBody, crmFields } from "../../src/web/areas/configuration/crm.tsx";
import { AgentPage, agentFields } from "../../src/web/areas/configuration/agent.tsx";
import { AttributionPage, networkBody } from "../../src/web/areas/configuration/attribution.tsx";
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

describe("the catalogue's configuration (game-provider)", () => {
  const held = { disabledDevelopers: ["pragmatic"], disabledCategories: [], gameOverrides: { sweet_bonanza: false } };
  it("turns a developer or category on and off", () => {
    expect(toggled(held, "disabledDevelopers", "pragmatic", true).disabledDevelopers).toEqual([]);
    expect(toggled(held, "disabledCategories", "live", false).disabledCategories).toEqual(["live"]);
  });
  it("keys a game's own setting `<provider>/<code>`, dropping the bare code the original wrote", () => {
    expect(overridden(held, { provider: "st8", code: "sweet_bonanza" }, true).gameOverrides).toEqual({ "st8/sweet_bonanza": true });
    expect(overridden(held, { provider: "st8", code: "sweet_bonanza" }, undefined).gameOverrides).toEqual({});
  });
});

describe("engagement's, support-agent's and attribution's bodies", () => {
  it("sends a trigger's message as engagement nests it, and the connection's fields as they are", () => {
    expect(crmBody({ region: "eu", "trigger.KycApproved": "17" })).toEqual({ region: "eu", triggers: { KycApproved: { transactionalId: "17" } } });
    expect(crmBody({ fromEmail: "a@b.c" })).toEqual({ fromEmail: "a@b.c" });
  });
  it("labels each trigger's field with its trigger and gives it no message by default", () => {
    const f = crmFields({ siteId: "", region: "us", fromEmail: "", trackApiKey: "", appApiKey: "", webhookSigningKey: "", triggers: { KycApproved: { transactionalId: "" } } });
    expect(f.at(-1)).toMatchObject({ name: "trigger.KycApproved", labelValues: { name: "KycApproved" }, default: "" });
  });
  it("marks Motoko's fields against version 0, and none when it is not answered", () => {
    expect(agentFields({ enabled: false, model: "claude-sonnet-4-6" }).find((f) => f.name === "model")?.default).toBe("claude-sonnet-4-6");
    expect(agentFields(undefined).every((f) => f.default === undefined)).toBe(true);
  });
  it("clears a network's emptied document with an empty string", () => {
    expect(networkBody({ codeDocument: null, roundStream: true })).toEqual({ codeDocument: "", roundStream: true });
  });
});

describe("the CRM, Motoko's and attribution's pages", () => {
  it("mark a region other than engagement's default and label each trigger's message", async () => {
    owners({
      "GET /api/s/engagement/crm-config": { siteId: "s1", region: "eu", fromEmail: "", trackApiKey: "abc***", appApiKey: "", webhookSigningKey: "", triggers: { KycApproved: { transactionalId: "7" } } },
      "GET /api/s/engagement/engagement/status": { configured: true, processor: "Customer.io", parked: { count: 2 }, lastHour: { accepted: 4 } },
    });
    within(<CrmPage />);
    const region = await screen.findByLabelText(t("config.crm.region"));
    expect(document.getElementById(region.getAttribute("aria-describedby")!)!.textContent).toContain(t("config.form.differsFromDefault", { value: t("config.crm.region.us") }));
    expect((screen.getByLabelText(t("config.crm.transactionalId", { name: "KycApproved" })) as HTMLInputElement).value).toBe("7");
  });
  it("mark Motoko's settings against support-agent's version 0", async () => {
    owners({
      "GET /api/s/support-agent/config": { version: 2, config: { enabled: true, model: "claude-sonnet-4-6", turnsPerMinute: 5 }, recentChanges: [] },
      "GET /api/s/support-agent/config/versions/0": { enabled: false, model: "claude-sonnet-4-6", turnsPerMinute: 10 },
      "GET /api/s/support-agent/config/status": { enabled: true, realMoney: true, safeguarding: "on", judged: "off", budget: { used: 1, limit: 2, spent: false }, conversations: { open: 0, awaiting: 0, lastDay: 0 } },
    });
    within(<AgentPage />);
    const turns = await screen.findByLabelText(t("config.agent.turnsPerMinute"));
    await screen.findByText(t("config.form.differsFromDefault", { value: "10" }));
    expect(turns.closest("[data-field]")!.querySelector('[data-differs="default"]')).not.toBeNull();
    expect(screen.getByLabelText(t("config.agent.model")).closest("[data-field]")!.querySelector("[data-differs]")).toBeNull();
  });
  it("draw attribution's windows, networks, webhooks and keys", async () => {
    owners({
      "GET /api/s/attribution/settings": { attributionWindowDays: 14, bonusCodeWindowDays: 30 },
      "GET /api/s/attribution/networks": { networks: [{ id: "net-a", keyDocument: "https://a/k", revocationList: "https://a/r", roundStream: false, pseudonymKeyMade: true, documents: [] }] },
      "GET /api/s/attribution/webhooks": { subscriptions: [{ id: "w1", network: "net-a", address: "https://a/hook", active: true }] },
      "GET /api/s/attribution/signing-keys": { keys: [{ kid: "k1", publicKey: "p", madeAt: "2026-11-01T00:00:00Z" }] },
    });
    within(<AttributionPage />, { ...admin, permissions: admin.permissions.filter((p) => p !== "fact.redeliver") });
    const window = await screen.findByLabelText(t("config.attribution.window"));
    expect(window.closest("[data-field]")!.querySelector('[data-differs="default"]')).not.toBeNull();
    expect(await screen.findByText("https://a/hook")).toBeTruthy();
    expect(await screen.findByText("k1")).toBeTruthy();
  });
});
