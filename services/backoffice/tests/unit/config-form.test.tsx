// @vitest-environment jsdom
// T108 (FR-034): a configuration form marks each held value that differs from the brand's default or its
// licence profile, marks an edited one "changed, not saved", and sends only the fields changed, through an
// Action whose effect is stated first.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import { t } from "../../src/shared/messages/index.ts";
import { Actions } from "../../src/web/actions/index.ts";
import { ConfigForm, parse, same, type ConfigField } from "../../src/web/components/config-form.tsx";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const admin: Me = {
  subject: "op-a",
  name: "Admin",
  roles: ["brand_goldvibe", "brand_admin"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["config.brand"],
};

const fields: ConfigField[] = [
  { name: "multiplier", label: "config.form.none", kind: "whole", default: 1 },
  { name: "limit", label: "config.form.yes", kind: "money", currency: "EUR", optional: true, default: null, profile: 100000 },
  { name: "on", label: "config.form.no", kind: "boolean" },
];

function form(save = vi.fn(async () => Response.json({})), me: Me = admin, readOnly = false) {
  vi.stubGlobal("fetch", vi.fn(async () => Response.json({ roles: { brand_admin: ["config.brand"] } })));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={me}>
        <ReadOnlyContext.Provider value={readOnly}>
          <ConfigForm fields={fields} values={{ multiplier: 3, limit: 100000, on: true }} action={Actions["config.protectionSettings"]} save={save} />
        </ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
  return save;
}

describe("the configuration form", () => {
  it("marks a value that differs from the default, and one that differs from the licence profile", () => {
    form();
    expect(screen.getByLabelText(t("config.form.none"))).toHaveProperty("value", "3");
    const multiplier = screen.getByLabelText(t("config.form.none")).closest("[data-field]") as HTMLElement;
    expect(within(multiplier).getByText(t("config.form.differsFromDefault", { value: "1" }))).toBeTruthy();
    const limit = screen.getByLabelText(`${t("config.form.yes")} (EUR)`).closest("[data-field]") as HTMLElement;
    // the limit's default is none: a limit set is not marked against it (DR-11); it is the profile's
    expect(limit.querySelector('[data-differs="default"]')).toBeNull();
    expect(limit.querySelector('[data-differs="profile"]')).toBeNull();
    expect(multiplier.querySelector('[data-differs="default"]')!.getAttribute("data-tone")).toBe("neutral");
    const on = screen.getByLabelText(t("config.form.no")).closest("[data-field]") as HTMLElement;
    expect(on.querySelector("[data-differs]")).toBeNull();
  });

  it("sends only the fields changed, after stating the effect", async () => {
    const save = form();
    fireEvent.change(screen.getByLabelText(t("config.form.none")), { target: { value: "5" } });
    expect(screen.getByText(t("config.unsaved"))).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: t("action.config.protectionSettings.label") }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(t("action.config.protectionSettings.effect"))).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: t("action.confirm") }));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0]).toEqual([{ multiplier: 5 }, { multiplier: 5, limit: 100000, on: true }]);
  });

  it("offers no change below the editing width", () => {
    form(undefined, admin, true);
    expect(screen.queryByRole("button", { name: t("action.config.protectionSettings.label") })).toBeNull();
    expect(screen.getByLabelText(t("config.form.none"))).toHaveProperty("disabled", true);
  });

  it("reads amounts in the currency's minor units and lists as sets", () => {
    expect(parse(fields[1]!, "1000.50")).toBe(100050);
    expect(parse(fields[1]!, "")).toBeNull();
    expect(parse(fields[0]!, "1.5")).toBeUndefined();
    expect(same(["EUR", "BRL"], ["BRL", "EUR"])).toBe(true);
  });
});
