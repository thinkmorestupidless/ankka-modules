// @vitest-environment jsdom
// The programme's payouts table: its actions offered by the payout's state, and none at all where the
// table shows the affiliate's own view (the manager's "view as affiliate").
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { PayoutsTable } from "../../src/web/areas/affiliates/payouts.tsx";
import type { Payout } from "../../src/web/areas/affiliates/programme.ts";
import { MeContext } from "../../src/web/frame/context.ts";

afterEach(cleanup);

const manager: Me = {
  subject: "op-m",
  name: "Manager",
  roles: ["brand_goldvibe", "affiliate_manager"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["programme.manage"],
};

const pending: Payout = {
  payoutId: "po-1", affiliateId: "aff-1", brandId: "goldvibe", currency: "EUR", period: "2026-10", status: "pending",
  cpaCommission: 1000, revShareCommission: 0, overrideCommission: 0, adjustments: 0, totalPayable: 1000,
  createdAt: null, approvedBy: null, approvedAt: null, rejectedBy: null, rejectionReason: null, paidBy: null, paidAt: null,
};

function table(readOnly?: boolean) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MeContext.Provider value={manager}>
        <PayoutsTable payouts={[pending]} names={new Map()} done={() => undefined} readOnly={readOnly} />
      </MeContext.Provider>
    </QueryClientProvider>,
  );
}

describe("the payouts table", () => {
  it("offers a pending payout's actions", () => {
    table();
    expect(screen.queryAllByRole("button").length).toBeGreaterThan(0);
  });

  it("as the affiliate's own view, shows the payouts with no action on them", () => {
    table(true);
    expect(screen.getByText("aff-1")).toBeTruthy();
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });
});
