// Amounts from minor units by one rule (FR-013): the currency's own minor-unit count, from the
// contract's table (research R6), and its symbol; never converted, never summed across currencies,
// never through a float.
import table from "./generated/currencies.v1.json" with { type: "json" };

const exponents: Record<string, number> = table.currencies;

/** An amount as an owner answers it: minor units (a string in JSON, so no float), and its currency. */
export interface Money {
  minor: bigint | string | number;
  currency: string;
}

export class UnknownCurrency extends Error {
  constructor(currency: string) {
    super(`no minor-unit count for ${currency}`);
    this.name = "UnknownCurrency";
  }
}

export function exponentOf(currency: string): number {
  const e = exponents[currency];
  if (e === undefined) throw new UnknownCurrency(currency);
  return e;
}

function asBigInt(minor: bigint | string | number): bigint {
  if (typeof minor === "bigint") return minor;
  if (typeof minor === "number") {
    if (!Number.isSafeInteger(minor)) throw new RangeError(`${minor} is not a whole number of minor units`);
    return BigInt(minor);
  }
  if (!/^-?\d+$/.test(minor)) throw new RangeError(`${minor} is not a whole number of minor units`);
  return BigInt(minor);
}

/** The exact decimal text of `minor` in `currency`: `-1234.500` for KWD -1234500. */
export function decimalOf(money: Money): string {
  const e = exponentOf(money.currency);
  const n = asBigInt(money.minor);
  const negative = n < 0n;
  const abs = negative ? -n : n;
  const unit = 10n ** BigInt(e);
  const whole = (abs / unit).toString();
  const frac = e === 0 ? "" : `.${(abs % unit).toString().padStart(e, "0")}`;
  return `${negative ? "-" : ""}${whole}${frac}`;
}

/** The amount as a person reads it, with the currency's symbol and its own minor units. */
export function formatMoney(money: Money, locale = "en-GB"): string {
  const e = exponentOf(money.currency);
  const format = new Intl.NumberFormat(locale, {
    style: "currency",
    currency: money.currency,
    minimumFractionDigits: e,
    maximumFractionDigits: e,
  });
  // An exact decimal string is formatted as written: no float is made.
  return format.format(decimalOf(money) as unknown as number);
}

/** Typed text to minor units: refused when it has more decimals than the currency has minor units. */
export function parseMoney(text: string, currency: string): bigint | undefined {
  const e = exponentOf(currency);
  const m = /^\s*(-)?(\d+)(?:\.(\d*))?\s*$/.exec(text.replace(/[,  ](?=\d{3}\b)/g, ""));
  if (!m) return undefined;
  const frac = m[3] ?? "";
  if (frac.length > e) return undefined;
  const minor = BigInt(m[2]!) * 10n ** BigInt(e) + BigInt(frac.padEnd(e, "0") || "0");
  return m[1] ? -minor : minor;
}

/** The sum of amounts in one currency; amounts in two currencies are never summed. */
export function sumMoney(amounts: readonly Money[]): Money | undefined {
  if (amounts.length === 0) return undefined;
  const currency = amounts[0]!.currency;
  if (amounts.some((a) => a.currency !== currency)) throw new RangeError("amounts in different currencies are not summed");
  return { minor: amounts.reduce((s, a) => s + asBigInt(a.minor), 0n), currency };
}

/** The wallet's major-unit text (`"12.50"`) as minor units of the currency; undefined when it is not one. */
export function minorOfText(text: unknown, currency: string): string | undefined {
  const s = typeof text === "number" ? String(text) : typeof text === "string" ? text.trim() : "";
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(s);
  if (!m) return undefined;
  let e: number;
  try {
    e = exponentOf(currency);
  } catch {
    return undefined;
  }
  const frac = (m[3] ?? "").padEnd(e, "0");
  if (frac.length > e && /[1-9]/.test(frac.slice(e))) return undefined;
  const digits = `${m[2]}${frac.slice(0, e)}`.replace(/^0+(?=\d)/, "");
  return digits === "0" ? "0" : `${m[1]}${digits}`;
}
