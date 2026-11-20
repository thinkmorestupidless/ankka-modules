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

/** The wall clock in `zone` at `instant`, as `YYYY-MM-DDTHH:mm:ss`. */
function wallAt(zone: string, instant: number): string {
  return new Date(Math.floor(instant / 1000) * 1000 + offsetAt(zone, instant)).toISOString().slice(0, 19);
}

/**
 * The first instant at or after which the wall clock in `zone` reads `wall` or later (`YYYY-MM-DDTHH:mm:ss`).
 * Each of the offsets either side is tried; a wall time that occurs twice is its first occurrence, and one
 * that the clocks skip is the instant they skip to.
 */
function firstAtOrAfter(wall: string, zone: string): number {
  const asUtc = Date.parse(`${wall}Z`);
  const day = 86_400_000;
  const tried = [offsetAt(zone, asUtc - day), offsetAt(zone, asUtc), offsetAt(zone, asUtc + day)].map((o) => asUtc - o);
  const reached = tried.filter((t) => wallAt(zone, t) >= wall).sort((a, b) => a - b);
  let hi = reached[0] ?? asUtc;
  if (wallAt(zone, hi) === wall) return hi;
  // In a gap the earliest candidate can sit past the change; search back to the change itself.
  let lo = hi - 3 * 3_600_000;
  if (wallAt(zone, lo) >= wall) return hi;
  while (hi - lo > 1) {
    const mid = Math.floor((lo + hi) / 2);
    if (wallAt(zone, mid) >= wall) hi = mid;
    else lo = mid;
  }
  return hi;
}

/** The first instant of a calendar day in `zone`, in epoch milliseconds; where the clocks skip midnight, the instant they skip to. */
export function startOfDay(day: string, zone: string): number {
  if (!calendarDate(day)) throw new RangeError(`${day} is not a date`);
  return firstAtOrAfter(`${day}T00:00:00`, zone);
}

/** Today's calendar date in `zone`, `YYYY-MM-DD`. */
export function todayIn(zone: string, now: number = Date.now()): string {
  return wallAt(zone, now).slice(0, 10);
}

/** The calendar day `n` days from `day`. */
export function addDays(day: string, n: number): string {
  const d = calendarDate(day)!;
  return new Date(Date.UTC(d.year, d.month - 1, d.day + n)).toISOString().slice(0, 10);
}

/**
 * A `datetime-local` text (`YYYY-MM-DDTHH:mm`, seconds optional) read as a wall time in the brand's zone,
 * not the browser's, as an ISO instant; nothing for text that is not one.
 */
export function instantOfLocal(text: string, zone: string): string | undefined {
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(text.trim());
  if (!m || !calendarDate(m[1]!) || Number(m[2]) > 23 || Number(m[3]) > 59 || Number(m[4] ?? 0) > 59) return undefined;
  return new Date(firstAtOrAfter(`${m[1]}T${m[2]}:${m[3]}:${m[4] ?? "00"}`, zone)).toISOString();
}

/** An instant as the brand's zone's wall time for a `datetime-local` field, `YYYY-MM-DDTHH:mm`; "" for none. */
export function localOfInstant(instant: string | null | undefined, zone: string): string {
  if (!instant) return "";
  const ms = Date.parse(instant);
  return Number.isNaN(ms) ? "" : wallAt(zone, ms).slice(0, 16);
}

/** The last instant of a calendar day in `zone`: the end of a date filter includes its whole day. */
export function endOfDay(day: string, zone: string): number {
  if (!calendarDate(day)) throw new RangeError(`${day} is not a date`);
  return startOfDay(addDays(day, 1), zone) - 1;
}

/** A filter's two ends, as the owners take them. */
export function dayRange(from: string, to: string, zone: string): { from: string; to: string } {
  return { from: new Date(startOfDay(from, zone)).toISOString(), to: new Date(endOfDay(to, zone)).toISOString() };
}

/** An instant as a person reads it, in the brand's zone, the zone named. */
export function formatInstant(instant: string | number, zone: string, locale = "en-GB"): string {
  // one that does not parse (an owner's answer) is shown as given, never thrown on
  if (Number.isNaN(new Date(instant).getTime())) return String(instant);
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
