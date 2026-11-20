import { describe, expect, test } from "vitest";
import { addDays, calendarDate, dayRange, endOfDay, formatInstant, instantOfLocal, localOfInstant, parseBirthDate, startOfDay, todayIn } from "../../src/shared/dates.ts";

describe("a day in the brand's zone", () => {
  test("starts at its own midnight", () => {
    expect(new Date(startOfDay("2026-11-15", "America/Sao_Paulo")).toISOString()).toBe("2026-11-15T03:00:00.000Z");
    expect(new Date(startOfDay("2026-11-15", "Asia/Tokyo")).toISOString()).toBe("2026-11-14T15:00:00.000Z");
  });

  test("a filter's end is the last instant of its day there", () => {
    expect(new Date(endOfDay("2026-11-15", "America/Sao_Paulo")).toISOString()).toBe("2026-11-16T02:59:59.999Z");
  });

  test("across a change of offset, the day is the zone's (Europe/London, the clocks go forward)", () => {
    expect(new Date(startOfDay("2026-03-29", "Europe/London")).toISOString()).toBe("2026-03-29T00:00:00.000Z");
    expect(new Date(endOfDay("2026-03-29", "Europe/London")).toISOString()).toBe("2026-03-29T22:59:59.999Z");
  });

  test("where the clocks skip midnight, the day starts when they skip to (America/Santiago, 2026-09-06)", () => {
    expect(new Date(startOfDay("2026-09-06", "America/Santiago")).toISOString()).toBe("2026-09-06T04:00:00.000Z");
    expect(new Date(endOfDay("2026-09-05", "America/Santiago")).toISOString()).toBe("2026-09-06T03:59:59.999Z");
  });

  test("where the clocks go back at midnight, the day before keeps its repeated hour (America/Santiago, 2026-04-05)", () => {
    expect(new Date(startOfDay("2026-04-05", "America/Santiago")).toISOString()).toBe("2026-04-05T04:00:00.000Z");
    expect(new Date(endOfDay("2026-04-04", "America/Santiago")).toISOString()).toBe("2026-04-05T03:59:59.999Z");
    expect(new Date(startOfDay("2026-04-04", "America/Santiago")).toISOString()).toBe("2026-04-04T03:00:00.000Z");
  });

  test("today is the zone's calendar day, and days count by the calendar", () => {
    const late = Date.parse("2026-11-16T01:30:00Z"); // 22:30 on the 15th in São Paulo
    expect(todayIn("America/Sao_Paulo", late)).toBe("2026-11-15");
    expect(todayIn("UTC", late)).toBe("2026-11-16");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  test("a range from its first day's start to its last day's end", () => {
    expect(dayRange("2026-11-01", "2026-11-30", "UTC")).toEqual({ from: "2026-11-01T00:00:00.000Z", to: "2026-11-30T23:59:59.999Z" });
  });
});

describe("an instant shown", () => {
  test("in the brand's zone, the zone named", () => {
    const text = formatInstant("2026-11-15T12:00:00Z", "America/Sao_Paulo");
    expect(text).toContain("09:00");
    expect(text).toMatch(/GMT-3|BRT/);
  });
});

describe("a local date and time typed", () => {
  test("is read in the brand's zone, not the browser's", () => {
    expect(instantOfLocal("2026-11-15T12:00", "America/Sao_Paulo")).toBe("2026-11-15T15:00:00.000Z");
    expect(instantOfLocal("2026-11-15T12:00", "Africa/Johannesburg")).toBe("2026-11-15T10:00:00.000Z");
  });

  test("a time the clocks skip is the instant they skip to", () => {
    expect(instantOfLocal("2026-09-06T00:30", "America/Santiago")).toBe("2026-09-06T04:00:00.000Z");
  });

  test("text that is not a local date and time is none", () => {
    for (const text of ["", "2026-11-15", "2026-02-30T10:00", "2026-11-15T25:00", "soon"]) expect(instantOfLocal(text, "UTC")).toBeUndefined();
  });

  test("an instant is shown back as the zone's wall time", () => {
    expect(localOfInstant("2026-11-15T15:00:00Z", "America/Sao_Paulo")).toBe("2026-11-15T12:00");
    expect(localOfInstant(null, "UTC")).toBe("");
    expect(localOfInstant("not a time", "UTC")).toBe("");
    const typed = "2026-07-01T08:15";
    expect(localOfInstant(instantOfLocal(typed, "America/Santiago"), "America/Santiago")).toBe(typed);
  });
});

describe("a birth date typed", () => {
  const today = "2026-11-15";
  test.each([
    ["1990-01-02", "1990-01-02"],
    ["2/1/1990", "1990-01-02"],
    ["02.01.1990", "1990-01-02"],
    ["02-01-1990", "1990-01-02"],
    [" 2 1 1990 ", "1990-01-02"],
  ])("%s is %s", (text, date) => expect(parseBirthDate(text, today)).toBe(date));

  test.each(["31/02/1990", "1990-13-01", "2030-01-01", "yesterday", "1990/01/02"])("%s is refused", (text) =>
    expect(parseBirthDate(text, today)).toBeUndefined(),
  );

  test("a calendar date that does not exist is none", () => expect(calendarDate("2026-02-29")).toBeUndefined());
});
