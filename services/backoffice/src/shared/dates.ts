// Instants in the brand's zone, with the zone named (FR-015); a date filter's end is the last instant
// of its day there; a birth date is a calendar date, typed, with no zone.

/** The zone's offset from UTC, in milliseconds, at `instant`. */
function offsetAt(zone: string, instant: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(instant));
  const n = (type: string) => Number(parts.find((p) => p.type === type)!.value);
  const asUtc = Date.UTC(n("year"), n("month") - 1, n("day"), n("hour"), n("minute"), n("second"));
  return asUtc - Math.floor(instant / 1000) * 1000;
}

/** The first instant of a calendar day in `zone`, in epoch milliseconds. */
export function startOfDay(day: string, zone: string): number {
  const d = calendarDate(day);
  if (!d) throw new RangeError(`${day} is not a date`);
  const guess = Date.UTC(d.year, d.month - 1, d.day);
  let t = guess - offsetAt(zone, guess);
  t = guess - offsetAt(zone, t); // again, for a change of offset between the guess and the answer
  return t;
}

/** The last instant of a calendar day in `zone`: the end of a date filter includes its whole day. */
export function endOfDay(day: string, zone: string): number {
  const d = calendarDate(day)!;
  const next = new Date(Date.UTC(d.year, d.month - 1, d.day + 1)).toISOString().slice(0, 10);
  return startOfDay(next, zone) - 1;
}

/** A filter's two ends, as the owners take them. */
export function dayRange(from: string, to: string, zone: string): { from: string; to: string } {
  return { from: new Date(startOfDay(from, zone)).toISOString(), to: new Date(endOfDay(to, zone)).toISOString() };
}

/** An instant as a person reads it, in the brand's zone, the zone named. */
export function formatInstant(instant: string | number, zone: string, locale = "en-GB"): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone: zone,
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    timeZoneName: "short",
  }).format(new Date(instant));
}

export interface CalendarDate {
  year: number;
  month: number;
  day: number;
}

/** `YYYY-MM-DD`, a day that exists. */
export function calendarDate(text: string): CalendarDate | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!m) return undefined;
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const back = new Date(Date.UTC(year, month - 1, day));
  return back.getUTCFullYear() === year && back.getUTCMonth() === month - 1 && back.getUTCDate() === day ? { year, month, day } : undefined;
}

/**
 * A birth date as typed (FR-015): `YYYY-MM-DD`, or day, month and year in that order separated by `/`,
 * `.`, `-` or a space. Answered as `YYYY-MM-DD`, or nothing for a day that does not exist or is to come.
 */
export function parseBirthDate(text: string, today: string): string | undefined {
  const t = text.trim();
  const iso = calendarDate(t) ? t : undefined;
  const dmy = /^(\d{1,2})[/.\- ](\d{1,2})[/.\- ](\d{4})$/.exec(t);
  const candidate = iso ?? (dmy ? `${dmy[3]}-${dmy[2]!.padStart(2, "0")}-${dmy[1]!.padStart(2, "0")}` : undefined);
  if (!candidate || !calendarDate(candidate) || candidate > today) return undefined;
  return candidate;
}
