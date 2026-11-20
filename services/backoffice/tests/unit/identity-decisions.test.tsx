// @vitest-environment jsdom
// T070: a player's identity tab lists kyc's decisions on the player (amendment K3) in kyc's order, each
// with what it changed, who made it and when, in the catalogue's words.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { PlayerIdentity } from "../../src/web/areas/players/identity.tsx";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const compliance: Me = {
  subject: "op-c",
  name: "Compliance",
  roles: ["brand_goldvibe", "brand_compliance"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["player.read", "identity.read", "identity.review", "protection.read", "protection.change", "fraud.review"],
};

const identity = {
  playerId: "p-ana", status: "verified", mode: "manual", readyAt: null, sof: "unverified", requiredOfPlayer: [], deadline: null, depositsByCurrency: {}, flags: [], missingProfileFields: [], other: [],
  categories: [],
};

const decisions = [
  { kind: "status_changed", actor: { kind: "system", id: null }, at: "2026-11-10T09:00:00Z", from: "unverified", to: "pending", cause: "document" },
  { kind: "document_reviewed", actor: { kind: "operator", id: "op-first" }, at: "2026-11-10T10:00:00Z", documentId: "d-1", category: "identity_front", outcome: "rejected", reasonCode: "document_expired" },
  { kind: "mode_changed", actor: { kind: "operator", id: "op-second" }, at: "2026-11-11T10:00:00Z", from: "automatic", to: "manual" },
  { kind: "required_changed", actor: { kind: "operator", id: "op-second" }, at: "2026-11-11T10:05:00Z", from: [], to: ["proof_of_address"] },
];

function wrap(children: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={compliance}>
        <ReadOnlyContext.Provider value={false}>{children}</ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
}

function kyc(answer: () => Response) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/permissions") return Response.json({ roles: { brand_compliance: compliance.permissions } });
      if (url === "/api/s/kyc/p-ana") return Response.json(identity);
      if (url === "/api/s/kyc/p-ana/decisions") return answer();
      return Response.json({});
    }),
  );
}

describe("a player's identity decisions", () => {
  it("are listed in kyc's order with what each changed, who made it and when", async () => {
    kyc(() => Response.json({ decisions }));
    wrap(<PlayerIdentity player="p-ana" />);
    const table = await screen.findByRole("table", { name: t("identity.decisions") });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.getAttribute("data-decision"))).toEqual(["status_changed", "document_reviewed", "mode_changed", "required_changed"]);
    expect(rows[0]!.textContent).toContain(t("identity.decisions.actor.system"));
    expect(rows[0]!.textContent).toContain(t("identity.decisions.change", { from: t("status.unverified"), to: t("status.pending") }));
    expect(rows[1]!.textContent).toContain(t("identity.decisions.kind.document_reviewed"));
    expect(rows[1]!.textContent).toContain(t("identity.category.identity_front"));
    expect(rows[1]!.textContent).toContain(t("identity.reason.document_expired"));
    expect(rows[1]!.textContent).toContain("op-first");
    expect(rows[1]!.querySelector("time")?.getAttribute("datetime")).toBe("2026-11-10T10:00:00.000Z");
    expect(rows[2]!.textContent).toContain(t("identity.decisions.change", { from: t("identity.mode.automatic"), to: t("identity.mode.manual") }));
    expect(rows[3]!.textContent).toContain(t("identity.decisions.change", { from: t("identity.missing.none"), to: t("identity.category.proof_of_address") }));
  });

  it("say when there are none", async () => {
    kyc(() => Response.json({ decisions: [] }));
    wrap(<PlayerIdentity player="p-ana" />);
    expect(await screen.findByText(t("identity.decisions.none"))).toBeTruthy();
  });

  it("show kyc's refusal in their own section and leave the rest of the tab", async () => {
    kyc(() => Response.json({ error: "forbidden", message: "forbidden: the identity.read permission is needed" }, { status: 403 }));
    wrap(<PlayerIdentity player="p-ana" />);
    const section = (await screen.findByRole("heading", { name: t("identity.decisions") })).closest("section")!;
    expect(await within(section).findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("heading", { name: t("identity.player.title") })).toBeTruthy();
    expect(within(section).queryByRole("table")).toBeNull();
  });
});
