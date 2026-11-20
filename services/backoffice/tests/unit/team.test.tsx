// @vitest-environment jsdom
// T054, T055: the brand's roles, changed with what each change gives and takes stated first, and given
// to an operator, a role the giver could not give shown disabled with the permission it needs.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "../../src/shared/access.ts";
import permissionsFile from "../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import { difference, PermissionGroups } from "../../src/shared/permissions.ts";
import { has } from "../../src/shared/messages/index.ts";
import { AssignPage, lacking } from "../../src/web/areas/team/assign.tsx";
import { TeamPage } from "../../src/web/areas/team/TeamPage.tsx";
import { MeContext, ReadOnlyContext } from "../../src/web/frame/context.ts";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const roles = [
  { name: "brand_admin", description: "Administrators", permissions: ["player.read", "roles.change", "roles.give", "roles.read", "adjustment.make"], default: true },
  { name: "brand_support", description: "Support", permissions: ["player.read", "note.write"], default: true },
  { name: "vip_host", description: "VIP hosts", permissions: ["player.read", "message.send"], default: false },
];

const admin: Me = {
  subject: "op-a",
  name: "Admin",
  roles: ["brand_goldvibe", "brand_admin"],
  brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" },
  preferences: null,
  permissions: ["player.read", "note.write", "message.send", "roles.read", "roles.change", "roles.give"],
};

/** players behind the page's server: the roles, and what each write answers. */
function players(answer: (method: string, url: string, body: unknown) => Response = () => Response.json({})) {
  const calls: { method: string; url: string; body: unknown; headers: Record<string, string> }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      const body = init.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ method, url, body, headers: (init.headers ?? {}) as Record<string, string> });
      if (method === "GET" && url === "/api/s/players/roles") return Response.json({ roles });
      return answer(method, url, body);
    }),
  );
  return calls;
}

function team(me: Me = admin, readOnly = false) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeContext.Provider value={me}>
        <ReadOnlyContext.Provider value={readOnly}>
          <TeamPage />
        </ReadOnlyContext.Provider>
      </MeContext.Provider>
    </QueryClientProvider>,
  );
}

describe("the permissions an administrator chooses from", () => {
  it("are every permission the contract names, each once, each described", () => {
    const listed = PermissionGroups.flatMap((g) => g.permissions);
    expect([...listed].sort()).toEqual([...permissionsFile.permissions].sort());
    for (const p of listed) expect(has(`permission.${p}`), p).toBe(true);
  });

  it("say what a change gives and takes away", () => {
    expect(difference(["a", "b"], ["b", "c"])).toEqual({ gained: ["c"], lost: ["a"] });
  });
});

describe("the brand's roles", () => {
  it("lists each role with what it carries, default or the brand's own", async () => {
    players();
    team();
    const row = (await screen.findByRole("rowheader", { name: /vip_host/ })).closest("tr")!;
    expect(row.textContent).toMatch(/The brand's own/);
    expect(row.textContent).toMatch(/2 permissions/);
  });

  it("does not create a role without a name, nor one whose name the brand already uses", async () => {
    players();
    team();
    fireEvent.click(await screen.findByRole("button", { name: "New role" }));
    fireEvent.click(screen.getByRole("button", { name: "Create the role" }));
    expect((within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "vip_host" } });
    expect(screen.getByText(/already has a role named vip_host/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Create the role" }));
    expect((within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("creates a role from permissions, stating what it carries before it is confirmed", async () => {
    const calls = players();
    team();
    fireEvent.click(await screen.findByRole("button", { name: "New role" }));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "vip_host_2" } });
    fireEvent.click(screen.getAllByRole("checkbox", { name: /Read a player:/ })[0]!);
    fireEvent.click(screen.getAllByRole("checkbox", { name: /Send an inbox item/ })[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Create the role" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toMatch(/vip_host_2 gains: message.send, player.read/);
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/s/players/roles/vip_host_2");
    expect(put.body).toEqual({ description: "", permissions: ["message.send", "player.read"] });
    expect(put.headers["X-Backoffice"]).toBe("1");
  });

  it("changes a role, saying who gains and who loses what", async () => {
    players();
    team();
    const row = (await screen.findByRole("rowheader", { name: /brand_support/ })).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    fireEvent.click(screen.getAllByRole("checkbox", { name: /Add, pin and unpin notes/ })[0]!);
    fireEvent.click(screen.getAllByRole("checkbox", { name: /Make an adjustment/ })[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Save the role" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toMatch(/holding brand_support gains: adjustment.make/);
    expect(dialog.textContent).toMatch(/holding brand_support loses: note.write/);
  });

  it("offers reset and not delete for a default role, delete for the brand's own", async () => {
    players();
    team();
    fireEvent.click(within((await screen.findByRole("rowheader", { name: /brand_support/ })).closest("tr")!).getByRole("button", { name: "Change" }));
    expect(screen.getByRole("button", { name: "Reset to default" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Delete the role" })).toBeNull();
    fireEvent.click(within(screen.getByRole("rowheader", { name: /vip_host/ }).closest("tr")!).getByRole("button", { name: "Change" }));
    expect(screen.getByRole("button", { name: "Delete the role" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Reset to default" })).toBeNull();
  });

  it("shows players' refusal with its reason", async () => {
    players(() => Response.json({ error: "last_role_changer", message: "the last role carrying roles.change cannot lose it" }, { status: 409 }));
    team();
    fireEvent.click(within((await screen.findByRole("rowheader", { name: /brand_admin/ })).closest("tr")!).getByRole("button", { name: "Change" }));
    fireEvent.click(screen.getAllByRole("checkbox", { name: /Create, change, reset and delete a role/ })[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Save the role" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm" }));
    expect(await screen.findByText(/cannot lose it/)).toBeTruthy();
  });

  it("offers no change below 1280 px", async () => {
    players();
    team(admin, true);
    await screen.findByRole("rowheader", { name: /vip_host/ });
    expect(screen.queryByRole("button", { name: "New role" })).toBeNull();
  });
});

describe("an operator's roles", () => {
  it("shows a role carrying a permission the giver lacks disabled, naming the permission", async () => {
    players();
    team();
    const box = await screen.findByRole("checkbox", { name: /^brand_admin/ });
    expect((box as HTMLInputElement).disabled).toBe(true);
    expect(box.closest("label")!.textContent).toMatch(/adjustment.make/);
    expect((screen.getByRole("checkbox", { name: "vip_host" }) as HTMLInputElement).disabled).toBe(false);
    expect(lacking(admin, roles[0]!)).toBe("adjustment.make");
  });

  it("gives the roles ticked, replacing what the brand gave before", async () => {
    const calls = players((method, url) => (method === "PUT" && url.includes("role-assignments") ? Response.json({ operator: "op-b", brand: "goldvibe", roles: ["vip_host"] }) : Response.json({})));
    team();
    fireEvent.change(await screen.findByLabelText("Operator"), { target: { value: "op-b" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "vip_host" }));
    fireEvent.click(screen.getByRole("button", { name: "Give these roles" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm" }));
    expect((await screen.findByText(/op-b now holds: vip_host/)).textContent).toBeTruthy();
    expect(calls.find((c) => c.method === "PUT")).toMatchObject({ url: "/api/s/players/role-assignments/op-b", body: { roles: ["vip_host"] } });
  });

  it("with nothing ticked, says before it is confirmed that every role the brand gave is taken away", async () => {
    players();
    team();
    fireEvent.change(await screen.findByLabelText("Operator"), { target: { value: "op-b" } });
    fireEvent.click(screen.getByRole("button", { name: "Give these roles" }));
    expect(within(await screen.findByRole("dialog")).getByText(/No role is ticked: every role this brand gave op-b is taken away/)).toBeTruthy();
  });

  it("opened for an operator from the list, starts from the roles they hold and says what is given and taken away", async () => {
    const calls = players();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MeContext.Provider value={admin}>
          <AssignPage operator="op-b" held={["brand_support", "brand_goldvibe", "payments_viewer"]} />
        </MeContext.Provider>
      </QueryClientProvider>,
    );
    expect(((await screen.findByRole("checkbox", { name: "brand_support" })) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: "brand_support" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "vip_host" }));
    fireEvent.click(screen.getByRole("button", { name: "Give these roles" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.querySelector("[data-gives]")!.textContent).toBe("op-b is given vip_host.");
    expect(dialog.querySelector("[data-takes]")!.textContent).toBe("op-b loses brand_support.");
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    // only the brand's roles are sent: merchant's other roles are not players' to set
    await waitFor(() => expect(calls.find((c) => c.method === "PUT")).toMatchObject({ body: { roles: ["vip_host"] } }));
  });

  it("starts from the brand's last giving, says what is lost against what the realm holds, and starts again for another operator", async () => {
    players((method, url) => {
      if (method === "GET" && url.endsWith("/role-assignments/op-b"))
        return Response.json({ brand: "goldvibe", roles: ["vip_host"], version: 2, state: "in_force", held: ["vip_host", "brand_support"] });
      if (method === "GET" && url.includes("/role-assignments/")) return Response.json({ brand: "goldvibe" });
      return Response.json({});
    });
    team();
    fireEvent.change(await screen.findByLabelText("Operator"), { target: { value: "op-b" } });
    await waitFor(() => expect((screen.getByRole("checkbox", { name: "vip_host" }) as HTMLInputElement).checked).toBe(true));
    expect((screen.getByRole("checkbox", { name: "brand_support" }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Give these roles" }));
    // brand_support was carried over, never asked: the realm holds it, so the giving takes it away
    expect((await screen.findByRole("dialog")).querySelector("[data-takes]")!.textContent).toBe("op-b loses brand_support.");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    fireEvent.change(screen.getByLabelText("Operator"), { target: { value: "op-c" } });
    await waitFor(() => expect((screen.getByRole("checkbox", { name: "vip_host" }) as HTMLInputElement).checked).toBe(false));
  });

  it("shows the refusal when the brand's roles cannot be read, not a loading line for ever", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Response.json({ error: "forbidden", message: "forbidden: the roles.read permission is needed" }, { status: 403 })));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MeContext.Provider value={admin}>
          <AssignPage />
        </MeContext.Provider>
      </QueryClientProvider>,
    );
    expect((await screen.findByRole("alert")).textContent).toMatch(/roles.read/);
  });

  it("is not offered to an operator who may not give roles", async () => {
    players();
    team({ ...admin, permissions: ["roles.read"] });
    await screen.findByRole("rowheader", { name: /vip_host/ });
    expect(screen.queryByLabelText("Operator")).toBeNull();
  });
});
