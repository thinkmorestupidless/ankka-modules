// @vitest-environment jsdom
// An adjustment of an affiliate's commission (review: account.tsx:311, :338, :342): under the corrections
// screens' rule, an answer that is not known (a 502, a 504, a 500 or none) keeps the key and the adjustment
// first sent and locks the fields, so confirming again sends the same under the same key; the effect shows
// the amount as the brand's money rule writes it.
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { Adjustment } from "../../src/web/areas/affiliates/account.tsx";
import type { Account } from "../../src/web/areas/affiliates/programme.ts";
import { moneyText } from "../../src/web/components/money.tsx";
import { MeContext } from "../../src/web/frame/context.ts";

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

const account = { affiliateId: "aff-1", email: "a@example.com" } as unknown as Account;

/** The adjustments the page sent: each one's key and body. */
function programme(statuses: number[]) {
  const sent: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    if (!String(url).includes("/adjustments")) return new Response("null", { status: 404 });
    sent.push({ key: new Headers(init?.headers).get("idempotency-key"), body: JSON.parse(String(init?.body)) });
    return new Response("{}", { status: statuses.shift() ?? 201 });
  }));
  return sent;
}

const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement;

async function confirm(reason: string) {
  // An unknown answer leaves the dialog open, to be confirmed again; otherwise it is opened from the action.
  if (screen.queryByRole("dialog") === null)
    await act(() => fireEvent.click(screen.getByRole("button", { name: t("action.programme.account.adjust.label") })));
  const dialog = screen.getByRole("dialog");
  const box = screen.queryByLabelText("Reason (recorded with the action)") as HTMLTextAreaElement | null;
  if (box && box.value === "") fireEvent.change(box, { target: { value: reason } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Confirm" })));
  return dialog;
}

describe("an affiliate's adjustment", () => {
  test("states its amount by the money rule, keeps its key and locks its fields while the answer is unknown", async () => {
    const sent = programme([502]);
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MeContext.Provider value={manager}>
          <Adjustment a={account} done={() => undefined} />
        </MeContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.change(field(t("affiliates.figure.currency")), { target: { value: "EUR" } });
    fireEvent.change(field(t("affiliates.adjustment.amount")), { target: { value: "12.5" } });

    const dialog = await confirm("late fact");
    expect(dialog.textContent).toContain(moneyText({ minor: 1250, currency: "EUR" }));
    expect(sent).toHaveLength(1);
    expect(screen.getByText(t("affiliates.adjustment.unknown"))).toBeTruthy();
    expect(field(t("affiliates.adjustment.amount")).disabled).toBe(true);
    expect(field(t("affiliates.figure.currency")).disabled).toBe(true);

    await confirm("late fact");
    expect(sent).toHaveLength(2);
    expect(sent[1]!.key).toBe(sent[0]!.key);
    expect(sent[1]!.body).toEqual(sent[0]!.body);
    expect(sent[0]!.body).toMatchObject({ type: "CREDIT", amount: 1250, currency: "EUR" });
    expect(screen.queryByText(t("affiliates.adjustment.unknown"))).toBeNull();
    expect(field(t("affiliates.adjustment.amount")).disabled).toBe(false);
  });
});
