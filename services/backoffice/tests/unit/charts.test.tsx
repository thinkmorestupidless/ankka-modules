// @vitest-environment jsdom
// T100: every chart is drawn for the eye and is also a table a screen reader reads; the map fills the
// countries with players in one hue, and lists them beside it.
import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { CandleChart, countryName, Donut, DotGrid, LineChart, WorldMap } from "../../src/web/components/charts/index.ts";
import { countryPaths, fillOf } from "../../src/web/components/charts/WorldMap.tsx";

afterEach(cleanup);

describe("the world map", () => {
  const five = [
    { code: "BR", count: 120 },
    { code: "PT", count: 40 },
    { code: "MX", count: 12 },
    { code: "AR", count: 7 },
    { code: "CL", count: 3 },
  ];

  test("is drawn from the bundled map, every country with a code", () => {
    const paths = countryPaths();
    expect(paths.length).toBeGreaterThan(170);
    expect(paths.filter((p) => p.code === "BR")).toHaveLength(1);
    expect(paths.find((p) => p.code === "BR")!.d).toMatch(/^M/);
  });

  test("fills exactly the countries with players, more players more of the hue", () => {
    const { container } = render(<WorldMap title="Players by country" figure="Players" countries={[...five, { code: "DE", count: 0 }]} />);
    const filled = [...container.querySelectorAll("path[data-players]")].map((p) => p.getAttribute("data-country")).sort();
    expect(filled).toEqual(["AR", "BR", "CL", "MX", "PT"]);
    expect(fillOf(120, 120)).toContain("100%");
    expect(fillOf(3, 120)).toMatch(/var\(--primary\) (\d+)%/);
    expect(Number(/(\d+)%/.exec(fillOf(3, 120))![1])).toBeLessThan(50);
  });

  test("lists each country beside it by name, most players first, and none it has none in", () => {
    render(<WorldMap title="Players by country" figure="Players" countries={five} />);
    const table = screen.getByRole("table", { name: "Players by country" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getByRole("rowheader").textContent)).toEqual(["Brazil", "Portugal", "Mexico", "Argentina", "Chile"]);
    expect(within(rows[0]!).getByRole("cell").textContent).toBe("120");
    expect(countryName("XK")).not.toBe("");
  });
});

describe("a line chart", () => {
  test("draws each series and reads as a table of each point's own text", () => {
    const { container } = render(
      <LineChart
        title="GGR and NGR"
        series={[
          { name: "GGR", points: [{ label: "Mon", value: 10.5, text: "€10.50" }, { label: "Tue", value: -2, text: "-€2.00" }] },
          { name: "NGR", points: [{ label: "Mon", value: 8, text: "€8.00" }, { label: "Tue", value: -3, text: "-€3.00" }] },
        ]}
      />,
    );
    expect(container.querySelectorAll("polyline.bo-chart-line")).toHaveLength(2);
    expect(container.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
    const table = screen.getByRole("table", { name: "GGR and NGR" });
    expect(within(table).getByRole("row", { name: /Tue/ }).textContent).toContain("-€2.00");
  });
});

describe("a candle chart", () => {
  test("says whether each candle rose or fell, in words", () => {
    render(
      <CandleChart
        title="Balance"
        candles={[
          { label: "W1", open: 1, high: 3, low: 0, close: 2, text: { open: "1", high: "3", low: "0", close: "2" } },
          { label: "W2", open: 2, high: 2, low: 1, close: 1, text: { open: "2", high: "2", low: "1", close: "1" } },
        ]}
      />,
    );
    const table = screen.getByRole("table", { name: "Balance" });
    expect(within(table).getByRole("row", { name: /W1/ }).textContent).toContain("Rose");
    expect(within(table).getByRole("row", { name: /W2/ }).textContent).toContain("Fell");
  });
});

describe("a donut", () => {
  test("labels its slices outside and reads each one's value and share", () => {
    const { container } = render(<Donut title="Deposits by provider" slices={[{ name: "Pix", value: 75, text: "R$75.00" }, { name: "Card", value: 25, text: "R$25.00" }, { name: "Empty", value: 0, text: "R$0.00" }]} />);
    expect(container.querySelectorAll("path[data-series]")).toHaveLength(2);
    const table = screen.getByRole("table", { name: "Deposits by provider" });
    expect(within(table).getByRole("row", { name: /Pix/ }).textContent).toContain("75%");
    expect(within(table).getByRole("row", { name: /Empty/ }).textContent).toContain("0%");
  });
});

describe("a dot grid", () => {
  test("reads as counts by row and column", () => {
    render(<DotGrid title="Activity by hour" rows={["Registrations", "Awards"]} columns={["00", "01"]} counts={[[2, 0], [0, 5]]} />);
    const table = screen.getByRole("table", { name: "Activity by hour" });
    expect(within(table).getByRole("row", { name: /Awards/ }).textContent).toBe("Awards05");
  });
});
