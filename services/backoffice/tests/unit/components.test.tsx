// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Status } from "../../src/web/components/status.tsx";
import { Money } from "../../src/web/components/money.tsx";
import { DateTime, useZone, ZoneNote } from "../../src/web/components/date.tsx";
import { MeContext } from "../../src/web/frame/context.ts";
import { CountText } from "../../src/web/areas/affiliates/parts.tsx";
import type { Me } from "../../src/shared/access.ts";
import { hrefOf, NavigateContext, RecordLink, RecordLinks } from "../../src/web/components/links.tsx";
import { BirthDateInput } from "../../src/web/components/date-input.tsx";
import { formatMoney } from "../../src/shared/money.ts";
import { formatInstant } from "../../src/shared/dates.ts";

afterEach(cleanup);

describe("a status", () => {
  test("is a word and an icon, never colour alone", () => {
    const { container } = render(<Status value="pending_review" />);
    expect(screen.getByText("Pending review")).toBeTruthy();
    expect(container.querySelector("svg[data-glyph='clock']")).not.toBeNull();
    expect(container.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
  });

  test("a failure has its own icon as well as its tone", () => {
    const { container } = render(<Status value="rejected" />);
    expect(container.querySelector("[data-tone='negative'] svg[data-glyph='cross']")).not.toBeNull();
    expect(screen.getByText("Rejected")).toBeTruthy();
  });

  test("a status the catalogue lacks is shown as the owner gave it, with a mark", () => {
    const { container } = render(<Status value="on_hold_by_psp" />);
    expect(screen.getByText("on_hold_by_psp")).toBeTruthy();
    expect(container.querySelector("svg[data-glyph='dot']")).not.toBeNull();
  });
});

describe("an amount", () => {
  test("is shown by formatMoney, in the currency's own minor units", () => {
    const kwd = render(<Money value={{ minor: "-1234500", currency: "KWD" }} />).container.querySelector(".bo-money")!;
    expect(kwd.textContent).toBe(formatMoney({ minor: "-1234500", currency: "KWD" }));
    expect(kwd.textContent).toMatch(/1,234\.500/);
    expect(kwd.getAttribute("data-sign")).toBe("negative");
    const brl = render(<Money value={{ minor: 1050, currency: "BRL" }} />).container.querySelector(".bo-money")!;
    expect(brl.textContent).toBe(formatMoney({ minor: 1050, currency: "BRL" }));
  });

  test("a currency without a minor-unit count is shown as minor units, not guessed", () => {
    render(<Money value={{ minor: "12", currency: "XYZ" }} />);
    expect(screen.getByText("12 minor units of XYZ")).toBeTruthy();
  });
});

describe("a date", () => {
  test("is shown by formatInstant in the brand's zone, the zone named", () => {
    const at = "2026-11-15T12:00:00Z";
    const { container } = render(<DateTime instant={at} zone="America/Sao_Paulo" />);
    const time = container.querySelector("time")!;
    expect(time.textContent).toBe(formatInstant(at, "America/Sao_Paulo"));
    expect(time.textContent).toMatch(/GMT-3/);
    expect(time.getAttribute("title")).toBe("Times are in America/Sao_Paulo.");
    expect(time.getAttribute("datetime")).toBe("2026-11-15T12:00:00.000Z");
  });

  test("a page's dates are in the brand's zone from /api/me, not the browser's (FR-015, DR-01)", () => {
    const browser = process.env.TZ;
    process.env.TZ = "Europe/London";
    try {
      expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe("Europe/London");
      const me: Me = { subject: "op", name: null, roles: [], brand: { slug: "goldvibe", name: "goldvibe", modules: null, zone: "Asia/Tokyo" }, preferences: null, permissions: [] };
      function Page() {
        const zone = useZone();
        return (
          <div>
            <ZoneNote zone={zone} />
            <DateTime instant="2026-11-15T12:00:00Z" zone={zone} />
          </div>
        );
      }
      const { container } = render(
        <MeContext.Provider value={me}>
          <Page />
        </MeContext.Provider>,
      );
      expect(screen.getByText("Times are in Asia/Tokyo.")).toBeTruthy();
      expect(container.querySelector("time")!.textContent).toBe(formatInstant("2026-11-15T12:00:00Z", "Asia/Tokyo"));
      expect(container.textContent).not.toMatch(/London|GMT(?![+-])/);
    } finally {
      if (browser === undefined) delete process.env.TZ;
      else process.env.TZ = browser;
    }
  });

  test("the zone note names the zone", () => {
    render(<ZoneNote zone="Europe/Lisbon" />);
    expect(screen.getByText("Times are in Europe/Lisbon.")).toBeTruthy();
  });
});

describe("a count", () => {
  test("is a number, or a dash when the owner gave none: never NaN (DR-16)", () => {
    expect(render(<CountText value={1234} />).container.textContent).toBe("1,234");
    cleanup();
    for (const missing of [undefined, null, Number.NaN, "4"]) {
      const { container } = render(<CountText value={missing} />);
      expect(container.textContent).toBe("—");
      cleanup();
    }
  });
});

describe("a record's links", () => {
  test("go to the records it names", () => {
    expect(hrefOf({ kind: "player", id: "p 1" })).toBe("/players/p%201");
    expect(hrefOf({ kind: "withdrawal", id: "w1" })).toBe("/withdrawals/w1");
    expect(hrefOf({ kind: "round", player: "p1", id: "r1" })).toBe("/players/p1/games?round=r1");
    expect(hrefOf({ kind: "payment", player: "p1", id: "pay1" })).toBe("/players/p1/wallets?payment=pay1");
    expect(hrefOf({ kind: "adjustment", player: "p1", id: "a1" })).toBe("/players/p1/wallets?adjustment=a1");
    expect(hrefOf({ kind: "fraudCase", id: "f1" })).toBe("/fraud?case=f1");
  });

  test("a deposit links to its payment and its player", () => {
    render(<RecordLinks refs={[{ kind: "payment", player: "p1", id: "pay1" }, { kind: "player", id: "p1" }]} />);
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/players/p1/wallets?payment=pay1", "/players/p1"]);
    expect(links[0]!.textContent).toBe("Payment pay1");
    expect(screen.getByRole("list", { name: "Records it names" })).toBeTruthy();
  });

  test("names nothing when the record names none", () => {
    const { container } = render(<RecordLinks refs={[]} />);
    expect(container.innerHTML).toBe("");
  });

  test("is followed by the frame's navigation when it gives one", () => {
    const navigate = vi.fn();
    render(
      <NavigateContext.Provider value={navigate}>
        <RecordLink to={{ kind: "withdrawal", id: "w1" }} />
      </NavigateContext.Provider>,
    );
    fireEvent.click(screen.getByRole("link"));
    expect(navigate).toHaveBeenCalledWith("/withdrawals/w1");
  });
});

describe("a birth date typed", () => {
  const setup = () => {
    const onChange = vi.fn();
    render(<BirthDateInput label="record.player" today="2026-11-15" onChange={onChange} />);
    return { onChange, input: screen.getByRole("textbox") as HTMLInputElement };
  };

  test("is read by parseBirthDate, day first", () => {
    const { onChange, input } = setup();
    fireEvent.change(input, { target: { value: "1/2/1990" } });
    expect(onChange).toHaveBeenLastCalledWith("1990-02-01");
    fireEvent.change(input, { target: { value: "1990-02-01" } });
    expect(onChange).toHaveBeenLastCalledWith("1990-02-01");
    fireEvent.blur(input);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  test("an impossible day is refused, in words, once the field is left", () => {
    const { onChange, input } = setup();
    fireEvent.change(input, { target: { value: "31/02/1990" } });
    expect(onChange).toHaveBeenLastCalledWith(undefined);
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.blur(input);
    expect(screen.getByRole("alert").textContent).toBe("That is not a day that exists and has passed.");
    expect(input.getAttribute("aria-invalid")).toBe("true");
  });

  test("a day to come is refused", () => {
    const { onChange, input } = setup();
    fireEvent.change(input, { target: { value: "16/11/2026" } });
    fireEvent.blur(input);
    expect(onChange).toHaveBeenLastCalledWith(undefined);
    expect(screen.getByRole("alert")).toBeTruthy();
  });

  test("is labelled", () => {
    setup();
    expect(screen.getByLabelText("Player")).toBeTruthy();
  });
});
