import table from "./currencies.v1.json" with { type: "json" };

/** An amount: a whole number of the currency's minor units (FR-037). Never a floating-point number. */
export interface Amount {
  readonly minor: bigint;
  readonly currency: string;
}

/** What reading an amount answers: the amount, or `malformed_amount` naming what was given. */
export type AmountRead = { ok: true; amount: Amount } | { ok: false; code: "malformed_amount"; given: string };

const exponents: Readonly<Record<string, number>> = table;
const Currency = /^[A-Z]{3}$/;
const Decimal = /^(-?)(\d+)(?:\.(\d+))?$/;

/** Minor units per major unit, from the contract's table (`currencies.v1.json`); 2 for a code it lacks, as the services. */
export function exponent(currency: string): number {
  return exponents[currency] ?? 2;
}

export function amount(minor: bigint, currency: string): Amount {
  if (!Currency.test(currency)) throw new Error(`not a currency: ${currency}`);
  return { minor, currency };
}

/**
 * Major units written as text, read exactly by the currency's exponent: `"1234.50"` EUR is 123450.
 * Digits finer than the exponent are refused unless they are zeros (`"1.00"` JPY is 1), as the
 * contract's `Amounts.parse`; anything but plain decimal digits (`"1e3"`, `"ten"`) is refused.
 */
export function readMajor(text: string, currency: string): AmountRead {
  const refused = { ok: false, code: "malformed_amount", given: text } as const;
  const m = Decimal.exec(text.trim());
  if (!m || !Currency.test(currency)) return refused;
  const [, sign, whole = "", fraction = ""] = m;
  const exp = exponent(currency);
  const kept = fraction.slice(0, exp);
  if (/[^0]/.test(fraction.slice(exp))) return refused;
  const minor = BigInt(whole + kept.padEnd(exp, "0"));
  return { ok: true, amount: { minor: sign === "-" ? -minor : minor, currency } };
}

/** A site's own figure in major units, a decimal text or a whole number (a fraction as a number is refused: it is not exact). */
export function fromMajor(major: string | number, currency: string): AmountRead {
  if (typeof major === "number") {
    if (!Number.isSafeInteger(major)) return { ok: false, code: "malformed_amount", given: String(major) };
    return { ok: true, amount: { minor: BigInt(major) * 10n ** BigInt(exponent(currency)), currency } };
  }
  return readMajor(major, currency);
}

/** The amount as decimal text in major units, by the exponent: 123450 EUR is `"1234.50"`, 100 JPY `"100"`. */
export function majorText(a: Amount): string {
  const exp = exponent(a.currency);
  const negative = a.minor < 0n;
  const digits = (negative ? -a.minor : a.minor).toString().padStart(exp + 1, "0");
  const whole = digits.slice(0, digits.length - exp);
  const fraction = digits.slice(digits.length - exp);
  return `${negative ? "-" : ""}${whole}${exp > 0 ? `.${fraction}` : ""}`;
}

/** The amount for a person, in the page's language, with the currency's own decimals. */
export function format(a: Amount, language: string): string {
  const exp = exponent(a.currency);
  const formatter = new Intl.NumberFormat(language, {
    style: "currency",
    currency: a.currency,
    minimumFractionDigits: exp,
    maximumFractionDigits: exp,
  });
  // A decimal string is formatted exactly (Intl.NumberFormat v3); no amount passes through a number.
  return formatter.format(majorText(a) as unknown as number);
}

/**
 * The minor units as a JSON number, for a service that takes them so (players' deposit start).
 * Refused past 2^53, where a number is no longer exact; no deposit comes near it.
 */
export function minorOnWire(a: Amount): number {
  if (a.minor > BigInt(Number.MAX_SAFE_INTEGER) || a.minor < BigInt(Number.MIN_SAFE_INTEGER)) throw new RangeError(`not exact as a number: ${a.minor}`);
  return Number(a.minor);
}

export function sameAmount(a: Amount, b: Amount): boolean {
  return a.minor === b.minor && a.currency === b.currency;
}
