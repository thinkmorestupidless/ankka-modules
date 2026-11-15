import { describe, expect, test } from "vitest";
import { calendarDate, dayRange, endOfDay, formatInstant, parseBirthDate, startOfDay } from "../../src/shared/dates.ts";

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
