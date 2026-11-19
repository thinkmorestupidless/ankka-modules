// @vitest-environment jsdom
import { playerSite } from "@eitheror/player-sdk";
import { doubles, doubleTransport, handClock } from "@eitheror/player-sdk/testing";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { LanguageProvider } from "../src/app/language.tsx";
import { Register } from "../src/pages/Register.tsx";

// The registration form asks for a country only when the visitor's address names none (spec 027):
// players opens a deposit with the country registration found.
async function shown(country: string | null) {
  const clock = handClock();
  const d = doubles({ clock });
  d.script("country", { status: 200, body: { country, dialCode: null } });
  const site = playerSite({ transport: doubleTransport(d), clock });
  await site.ready;
  render(
    <LanguageProvider offered={["fr", "en"]} currency="EUR">
      <Register site={site} />
    </LanguageProvider>,
  );
  await act(async () => void (await site.session.visitorCountry()));
  return { d, clock };
}

afterEach(cleanup);

describe("the registration form", () => {
  it("asks no country when the address names one", async () => {
    await shown("GF");
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("asks for a country when the address names none, and sends the one chosen", async () => {
    const { d, clock } = await shown(null);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "BR" } });
    fireEvent.change(screen.getByLabelText(/email|e-mail/i), { target: { value: "zoe@example.com" } });
    fireEvent.change(screen.getByLabelText(/password|mot de passe/i), { target: { value: "a long enough password 7" } });
    fireEvent.submit(screen.getByRole("combobox").closest("form")!);
    await act(async () => void (await clock.settle()));
    expect(d.brand.players.find((p) => p.email === "zoe@example.com")).toBeTruthy();
    expect(d.askedOf("register").at(-1)?.body).toMatchObject({ country: "BR" });
  });
});
