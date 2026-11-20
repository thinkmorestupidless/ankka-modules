// features/backoffice/dashboard.feature (T098): the dashboard shows each currency's figures apart and
// players by country on a map; a report exported from the backoffice carries the reference number
// reporting gave it. reporting is doubled from `fixtures/reporting.json`; each scenario adds its own.
import { expect, Given, Then, When } from "../world.ts";
import { t } from "../../../src/shared/messages/index.ts";
import { countryName } from "../../../src/web/components/charts/WorldMap.tsx";
import { formatMoney } from "../../../src/shared/money.ts";

const R = "/v1/casino/brands/{brand}/reporting";

/** The KPIs' answer in one currency: GGR and NGR as given, the rest small. */
function kpis(currency: string, ggr: string, ngr: string, currencies: string[]) {
  const amount = (value: string) => ({ value, yesterday: "0.00", trend: "up", currency });
  const count = (value: number) => ({ value, yesterday: value, trend: "flat" });
  return {
    body: {
      activeSessions: count(4), activeGames: count(2), playersOnline: count(3), registrationsToday: count(1),
      ggrToday: amount(ggr), ngrToday: amount(ngr),
      depositsToday: { amount: "0.00", count: 0, yesterdayAmount: "0.00", trend: "flat", currency },
      withdrawalsToday: { amount: "0.00", count: 0, yesterdayAmount: "0.00", trend: "flat", currency },
      currency, currencies,
    },
  };
}

/** GGR in each currency, chosen so that a sum of any two would be a figure of neither. */
const Ggr: Record<string, { ggr: string; ngr: string; minor: number }> = {
  EUR: { ggr: "1234.56", ngr: "1000.10", minor: 123456 },
  BRL: { ggr: "7890.12", ngr: "6000.20", minor: 789012 },
};

Given("a brand with players in {string} and in {string}", async ({ world }, a: string, b: string) => {
  const both = [a, b];
  await world.doubles.fixtures("reporting", {
    [`GET ${R}/dashboard/kpis`]: {
      by: "?currency",
      answers: Object.fromEntries(both.map((c) => [c, kpis(c, Ggr[c]!.ggr, Ggr[c]!.ngr, both)])),
      otherwise: kpis(a, Ggr[a]!.ggr, Ggr[a]!.ngr, both),
    },
  });
});

When("an operator opens the dashboard", async ({ world }) => {
  await world.signInAs("brand_support", { to: "/dashboard" });
  await expect(world.page.getByRole("heading", { level: 1, name: t("area.dashboard") })).toBeVisible();
});

Then("the operator sees one set of figures in {string} and one in {string}", async ({ world }, a: string, b: string) => {
  for (const c of [a, b]) {
    const set = world.page.getByRole("region", { name: t("dashboard.figuresIn", { currency: c }), exact: true });
    await expect(set).toBeVisible();
    await expect(set.getByText(formatMoney({ minor: Ggr[c]!.minor, currency: c }), { exact: true })).toBeVisible();
    // each set holds its own currency's amounts only
    const other = c === a ? b : a;
    await expect(set.getByText(formatMoney({ minor: Ggr[other]!.minor, currency: other }), { exact: true })).toHaveCount(0);
  }
  await expect(world.page.getByRole("region", { name: /^Figures in / })).toHaveCount(2);
  // each currency's figures were read in that currency alone: the brand's default (a) by the first read,
  // which names none and is answered in it, and each other by naming it
  const asked = await world.doubles.calls("reporting", { method: "GET", path: `/v1/casino/brands/${world.brand.slug}/reporting/dashboard/kpis` });
  const named = asked.map((x) => new URLSearchParams(x.query).get("currency"));
  expect(named.some((c) => c === a || c === null), `kpis asked in ${a}`).toBe(true);
  expect(named.includes(b), `kpis asked in ${b}`).toBe(true);
});

Then("no figure sums the two", async ({ world }) => {
  const main = world.page.getByRole("main");
  const sum = Ggr.EUR!.minor + Ggr.BRL!.minor;
  const text = (await main.innerText()).replace(/\s+/g, " ");
  for (const c of ["EUR", "BRL"]) {
    // the sum of the two GGRs, in either currency, is shown nowhere
    expect(text).not.toContain(formatMoney({ minor: sum, currency: c }));
  }
  expect(text).not.toContain(String(sum / 100));
});

const Five = ["BR", "PT", "MX", "AR", "CL"];

Given("a brand with players in five countries this month", async ({ world }) => {
  await world.doubles.fixtures("reporting", {
    [`GET ${R}/dashboard/geo`]: { body: { countries: Five.map((countryCode, i) => ({ countryCode, playerCount: 50 - i * 9 })) } },
  });
});

When("an operator opens the dashboard for this month", async ({ world }) => {
  await world.signInAs("brand_support", { to: "/dashboard?range=30d" });
  await expect(world.page.getByRole("button", { name: t("dashboard.range.30d"), pressed: true })).toBeVisible();
});

Then("the map shows players in those five countries and no other", async ({ world }) => {
  const table = world.page.getByRole("table", { name: t("dashboard.mapTable") });
  await expect(table).toBeVisible();
  const rows = table.locator("tbody tr");
  await expect(rows).toHaveCount(5);
  await expect(table.getByRole("rowheader")).toHaveText(Five.map(countryName));
  const filled = world.page.locator('svg[data-chart="map"] path[data-players]');
  await expect(filled).toHaveCount(5);
  expect((await filled.evaluateAll((ps) => ps.map((p) => p.getAttribute("data-country")))).sort()).toEqual([...Five].sort());
});

const Reference = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

/** Last month in the brand's days, as `YYYY-MM-DD`. */
function lastMonth(): { from: string; to: string } {
  const now = new Date();
  const first = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  const last = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return { from: first.toISOString().slice(0, 10), to: last.toISOString().slice(0, 10) };
}

Given("a brand's revenue report for last month", async ({ world }) => {
  const { from, to } = lastMonth();
  const s = { totalBets: "1000.00", totalWins: "800.00", ggr: "200.00", depositPoolGgr: "0.00", bonusCosts: "20.00", chargebacks: "0.00", ngr: "180.00", ngrBasis: "before programme fees", totalDeposits: "1500.00", totalWithdrawals: "300.00", netCashFlow: "1200.00" };
  await world.doubles.fixtures("reporting", {
    [`GET ${R}/reports/ggr`]: {
      by: "?format",
      answers: {
        csv: { status: 202, body: { reference: Reference, status: "generating" } },
      },
      otherwise: { body: { from, to, currency: "BRL", currencies: ["BRL"], summary: s, dailyBreakdown: [{ date: from, bets: "1000.00", wins: "800.00", ggr: "200.00", depositPoolGgr: "0.00", bonusCosts: "20.00", ngr: "180.00" }] } },
    },
    [`GET ${R}/documents`]: { body: { documents: [{ reference: Reference, kind: "report-export", format: "csv", parameters: { report: "ggr" }, from, to, askedBy: "op", generatedAt: new Date().toISOString(), readyAt: new Date().toISOString(), expiresAt: null, status: "ready", endedBy: null, rows: 2 }] } },
  });
});

When("a finance operator exports it", async ({ world }) => {
  const { from, to } = lastMonth();
  await world.signInAs("brand_finance", { to: `/reports/revenue?from=${from}&to=${to}` });
  await expect(world.page.getByRole("heading", { level: 1, name: t("reports.revenue") })).toBeVisible();
  await world.page.getByRole("button", { name: t("reports.export"), exact: true }).click();
});

Then("the export carries the reference number the reporting service gave it", async ({ world }) => {
  const { from, to } = lastMonth();
  const main = world.page.getByRole("main");
  await expect(main.getByText(t("jobs.reference", { reference: Reference }), { exact: true })).toBeVisible();
  // the document is reporting's, downloaded from it under its reference
  await expect(main.getByRole("link", { name: t("jobs.download") })).toHaveAttribute("href", `/api/s/reporting/documents/${Reference}`, { timeout: 10_000 });
  // reporting made the export, as the finance operator, for the period asked
  const asked = (await world.doubles.calls("reporting", { method: "GET", path: `/v1/casino/brands/${world.brand.slug}/reporting/reports/ggr` }))
    .filter((c) => new URLSearchParams(c.query).get("format") === "csv");
  expect(asked).toHaveLength(1);
  expect(Object.fromEntries(new URLSearchParams(asked[0]!.query))).toMatchObject({ from, to, format: "csv" });
  await world.doubles.expectAuthorizedAs("reporting", world.operator!.sub, { path: `/v1/casino/brands/${world.brand.slug}/reporting/reports/ggr` });
  // and the job is the operator's, with its reference, wherever they go
  await world.open("/jobs");
  await expect(world.page.getByRole("row", { name: new RegExp(Reference) })).toBeVisible();
});
