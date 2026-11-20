// @vitest-environment jsdom
// T049: an operator whose roles do not name the brand is told the role and shown nothing of it; one who
// lost the brand is signed out of it and told why (edge case 1).
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { NotInBrand } from "../../src/web/areas/access/NotInBrand.tsx";
import { afterRead, readMe, type MeState } from "../../src/web/frame/session.ts";

afterEach(cleanup);

const refused = async () => Response.json({ error: "brand_not_in_roles", brand: "goldvibe" }, { status: 403 });

describe("an operator whose roles do not name the brand", () => {
  it("is not let in, and the brand is named", async () => {
    expect(await readMe(refused, vi.fn())).toEqual({ state: "notInBrand", brand: "goldvibe", lost: false });
  });

  it("is told the role for the brand is needed, and nothing else is shown", () => {
    render(<NotInBrand brand="goldvibe" lost={false} end={vi.fn()} />);
    expect(screen.getByRole("alert").textContent).toMatch(/brand_goldvibe/);
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(screen.queryByRole("main")).toBeNull();
  });
});

describe("an operator who loses the brand", () => {
  const signedIn: MeState = { state: "signedIn", me: { subject: "s", name: "n", roles: [], brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "America/Sao_Paulo" }, preferences: null, permissions: [] } };
  const notIn: MeState = { state: "notInBrand", brand: "goldvibe", lost: false };

  it("has lost it when refused after being let in", () => {
    expect(afterRead(signedIn, notIn)).toEqual({ ...notIn, lost: true });
    expect(afterRead({ ...notIn, lost: true }, notIn)).toEqual({ ...notIn, lost: true });
  });

  it("never had it when refused from the start", () => {
    expect(afterRead(undefined, notIn)).toEqual(notIn);
    expect(afterRead({ state: "loading" }, notIn)).toEqual(notIn);
  });

  it("is signed out and told why", async () => {
    const end = vi.fn(async () => undefined);
    render(<NotInBrand brand="goldvibe" lost end={end} />);
    expect(screen.getByRole("alert").textContent).toMatch(/no longer name goldvibe/);
    await waitFor(() => expect(end).toHaveBeenCalledOnce());
    expect(screen.getByRole("link").getAttribute("href")).toBe("/auth/sign-in");
  });
});
