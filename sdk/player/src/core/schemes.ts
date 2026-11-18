import { amount, fromMajor, type Amount } from "../amount.ts";
import type { CustomerDetails, Terminal } from "./client.ts";

/*
 * The deposit's rules for a terminal (contracts/behaviours.md, *schemes.ts*), from the original:
 * the scheme sets and the checks of `components/cashier/DepositModal.tsx` (`handleTerminalSelect`,
 * `handleDetailsSubmit`), `lib/phoneUtils.ts`, `lib/canadianProvinces.ts`, and of
 * `lib/schemeUtils.ts` only what is not words for a player (a coin's network, which terminals
 * share a scheme). Nothing here is shown to a player as it stands.
 */

// ── lib/phoneUtils.ts, as the original ──────────────────────────────────────
// E.164: "+" followed by 7–15 digits.
export const E164 = /^\+\d{7,15}$/;

// Longest national significant number we accept. E.164 allows up to 15 digits in total, but no
// real number has more than 12 after the dial code; anything longer is a doubled prefix.
const MAX_NATIONAL_DIGITS = 12;
const MIN_NATIONAL_DIGITS = 4;

// ISO 3166-1 alpha-2 → dial code. Mirrors GeoLookupService.dialCodes in casino-service; keep
// the two in step.
export const DIAL_CODE_BY_COUNTRY: Record<string, string> = {
  AD: "+376", AE: "+971", AF: "+93", AG: "+1", AL: "+355", AM: "+374", AO: "+244", AR: "+54",
  AT: "+43", AU: "+61", AZ: "+994", BA: "+387", BB: "+1", BD: "+880", BE: "+32", BF: "+226",
  BG: "+359", BH: "+973", BJ: "+229", BN: "+673", BO: "+591", BR: "+55", BS: "+1", BW: "+267",
  CA: "+1", CH: "+41", CL: "+56", CM: "+237", CN: "+86", CO: "+57", CR: "+506", CY: "+357",
  CZ: "+420", DE: "+49", DK: "+45", DO: "+1", DZ: "+213", EC: "+593", EE: "+372", EG: "+20",
  ES: "+34", ET: "+251", FI: "+358", FJ: "+679", FR: "+33", GA: "+241", GB: "+44", GG: "+44",
  GH: "+233", GR: "+30", GT: "+502", HK: "+852", HN: "+504", HR: "+385", HU: "+36", ID: "+62",
  IE: "+353", IL: "+972", IM: "+44", IN: "+91", IQ: "+964", IS: "+354", IT: "+39", JE: "+44",
  JM: "+1", JO: "+962", JP: "+81", KE: "+254", KH: "+855", KR: "+82", KW: "+965", KZ: "+7",
  LB: "+961", LK: "+94", LT: "+370", LU: "+352", LV: "+371", MA: "+212", MD: "+373", ME: "+382",
  MK: "+389", MT: "+356", MU: "+230", MX: "+52", MY: "+60", MZ: "+258", NA: "+264", NG: "+234",
  NI: "+505", NL: "+31", NO: "+47", NP: "+977", NZ: "+64", OM: "+968", PA: "+507", PE: "+51",
  PH: "+63", PK: "+92", PL: "+48", PR: "+1", PT: "+351", PY: "+595", QA: "+974", RE: "+262",
  RO: "+40", RS: "+381", RU: "+7", SA: "+966", SE: "+46", SG: "+65", SI: "+386", SK: "+421",
  SN: "+221", TH: "+66", TN: "+216", TR: "+90", TT: "+1", TW: "+886", TZ: "+255", UA: "+380",
  UG: "+256", US: "+1", UY: "+598", UZ: "+998", VE: "+58", VI: "+1", VN: "+84", WS: "+685",
  XK: "+383", ZA: "+27", ZM: "+260", ZW: "+263",
};

// Distinct dial codes, longest first, so prefix matching picks "351" before "35".
const DIAL_CODES: string[] = Array.from(new Set(Object.values(DIAL_CODE_BY_COUNTRY).map((c) => c.slice(1))))
  .sort((a, b) => b.length - a.length || a.localeCompare(b));

export function dialCodeForCountry(country: string | null | undefined): string {
  return country ? (DIAL_CODE_BY_COUNTRY[country.toUpperCase()] ?? "") : "";
}

function matchDialCode(digits: string): string | null {
  return DIAL_CODES.find((c) => digits.startsWith(c)) ?? null;
}

// A national part that starts with the dial code again, with a plausible number left after it,
// is the player (or the browser's autofill) having typed the international form into the
// national box. Strip the repeat. Only a long value qualifies: a real 7-digit number under +1
// may legitimately start with 1, but no national number runs to 10+ digits and repeats its
// country's prefix.
function stripRepeatedDialCode(cc: string, nn: string): string {
  const repeated = nn.length >= 10 && nn.startsWith(cc) && nn.length - cc.length >= 6;
  return repeated ? nn.slice(cc.length) : nn;
}

// Repeat strip, then one trunk zero when the part is still longer than any real national number
// (Italian numbers keep their zero), then the repeat check again for "+49 0 49 1791…".
function nationalPart(cc: string, nn: string): string {
  const once = stripRepeatedDialCode(cc, nn);
  const trunkless = once.length > 10 && once.startsWith("0") ? once.slice(1) : once;
  return stripRepeatedDialCode(cc, trunkless);
}

// Builds an E.164 number from the dial-code and national-number inputs, or null when either part
// is implausible. Strips a repeated dial code and one national trunk prefix ("07700…" with +44 →
// "+447700…").
export function composePhone(dialCode: string, national: string): string | null {
  const cc = dialCode.replace(/\D/g, "");
  if (cc.length < 1 || cc.length > 3) return null;
  const nn = stripRepeatedDialCode(cc, stripRepeatedDialCode(cc, national.replace(/\D/g, "")).replace(/^0/, ""));
  if (nn.length < MIN_NATIONAL_DIGITS || nn.length > MAX_NATIONAL_DIGITS) return null;
  const phone = `+${cc}${nn}`;
  return E164.test(phone) ? phone : null;
}

// Splits a stored phone back into the dial-code and national boxes. The dial code comes from the
// number itself whenever it can; `fallbackDialCode` (the player's country, or the IP geo lookup)
// is only used for a stored value with no international marker. Never guesses from the IP alone:
// that is how "+420" ended up in front of "420704249940".
export function splitPhone(
  stored: string | null | undefined,
  fallbackDialCode: string,
): { dialCode: string; national: string } {
  const raw = (stored ?? "").trim();
  if (!raw) return { dialCode: fallbackDialCode, national: "" };
  const international = raw.startsWith("+") || raw.startsWith("00");
  const digits = raw.replace(/\D/g, "");
  if (international) {
    const d = raw.startsWith("00") ? digits.slice(2) : digits;
    const cc = matchDialCode(d);
    if (cc) return { dialCode: `+${cc}`, national: nationalPart(cc, d.slice(cc.length)) };
    return { dialCode: fallbackDialCode, national: raw };
  }
  // No marker. The player's own dial code first ("420704249940" on a CZ account), then any dial
  // code when the value is long enough to be international (a British mobile saved from a
  // Finnish account). Otherwise show it as-is for the player to fix.
  const fb = fallbackDialCode.replace(/\D/g, "");
  if (fb && digits.startsWith(fb) && digits.length - fb.length >= 6) {
    return { dialCode: `+${fb}`, national: nationalPart(fb, digits.slice(fb.length)) };
  }
  const foreign = digits.length >= 11 ? matchDialCode(digits) : null;
  if (foreign && digits.length - foreign.length >= 7) {
    return { dialCode: `+${foreign}`, national: nationalPart(foreign, digits.slice(foreign.length)) };
  }
  return { dialCode: fallbackDialCode, national: raw };
}

// ── lib/canadianProvinces.ts, as the original ───────────────────────────────
/** Canadian provinces and territories, ISO 3166-2:CA subdivision codes.
 *
 * PSPs take the billing state/province as a 2-3 character code (EuPaymentz rejects anything
 * else with T0005 "Field [cust_billing_state] must have 2 or 3 caracters"), so a Canadian
 * player picks from this list instead of typing. Order is alphabetical by name.
 */
export const CANADIAN_PROVINCES: ReadonlyArray<{ code: string; name: string }> = [
  { code: "AB", name: "Alberta" },
  { code: "BC", name: "British Columbia" },
  { code: "MB", name: "Manitoba" },
  { code: "NB", name: "New Brunswick" },
  { code: "NL", name: "Newfoundland and Labrador" },
  { code: "NT", name: "Northwest Territories" },
  { code: "NS", name: "Nova Scotia" },
  { code: "NU", name: "Nunavut" },
  { code: "ON", name: "Ontario" },
  { code: "PE", name: "Prince Edward Island" },
  { code: "QC", name: "Quebec" },
  { code: "SK", name: "Saskatchewan" },
  { code: "YT", name: "Yukon" },
];

/** Resolves a stored province value to its code, or null when it is not a Canadian province.
 *
 * Accepts the code in any case and the English name (so a profile that already holds
 * "Ontario" from the old free-text field pre-selects the right option). Anything else, including
 * an empty string, is null so the caller forces a choice.
 */
export function toCanadianProvinceCode(value: string | null | undefined): string | null {
  const v = (value ?? "").trim();
  if (!v) return null;
  const upper = v.toUpperCase();
  const byCode = CANADIAN_PROVINCES.find((p) => p.code === upper);
  if (byCode) return byCode.code;
  const lower = v.toLowerCase().replace(/\s+/g, " ");
  const byName = CANADIAN_PROVINCES.find(
    (p) => p.name.toLowerCase() === lower || (p.code === "QC" && lower === "québec"),
  );
  return byName ? byName.code : null;
}

// ── the schemes (DepositModal.tsx) ──────────────────────────────────────────

/** Card schemes that need full contact details (name, phone, address, state). */
export const contactSchemes: ReadonlySet<string> = new Set(["VISA", "MASTERCARD", "CARD_VISA", "CARD_MASTERCARD", "PAY_ID", "INTERAC"]);
/** Wallet schemes that need the first and last name. */
export const identitySchemes: ReadonlySet<string> = new Set(["SKRILL", "NETELLER", "PAYSAFECARD", "RAPID"]);
/** Wallet schemes that need the name and a telephone number in international form. */
export const payerSchemes: ReadonlySet<string> = new Set(["APPLE_PAY", "GOOGLE_PAY"]);
/** Interac schemes that need a 10-digit Canadian mobile number and a province. */
export const interacSchemes: ReadonlySet<string> = new Set(["INTERAC_ETRANSFER"]);

/** A detail a terminal may need, under the SDK's name. */
export type DetailName = "firstName" | "lastName" | "phone" | "mobile" | "province" | "address" | "city" | "postCode" | "state";

/** What choosing a terminal leads to. */
export type Need =
  | { readonly kind: "coin"; readonly coins: readonly string[] }
  | { readonly kind: "none"; readonly details?: CustomerDetails }
  | { readonly kind: "details"; readonly needs: readonly DetailName[]; readonly known: Readonly<Partial<Record<DetailName, string>>> };

const text = (v: unknown) => (typeof v === "string" ? v.trim() : "");

/** Whether a scheme needs the player profile read before its submit. */
export function readsProfile(scheme: string): boolean {
  return identitySchemes.has(scheme) || payerSchemes.has(scheme) || interacSchemes.has(scheme) || contactSchemes.has(scheme);
}

/**
 * What a terminal needs, given the player profile (`handleTerminalSelect`): a coin for crypto,
 * else the details the profile lacks or holds malformed, else nothing. `country` is the profile's,
 * else the player's.
 */
export function need(terminal: Terminal, profile: Readonly<Record<string, unknown>> | null, country: string): Need {
  const { scheme } = terminal;
  if (scheme === "CRYPTO") return { kind: "coin", coins: terminal.currencies ?? [] };
  if (!readsProfile(scheme)) return { kind: "none" };
  const p = profile ?? {};
  const c = (text(p.country) || country).toUpperCase();
  const known: Partial<Record<DetailName, string>> = {};
  const missing: DetailName[] = [];
  const take = (name: DetailName, value: string, valid: boolean) => {
    if (value) known[name] = value;
    if (!valid) missing.push(name);
  };
  const first = text(p.firstName);
  const last = text(p.lastName);
  take("firstName", first, first !== "");
  take("lastName", last, last !== "");
  if (payerSchemes.has(scheme) || contactSchemes.has(scheme)) {
    const phone = text(p.phone);
    take("phone", phone, E164.test(phone));
  }
  if (interacSchemes.has(scheme)) {
    const mobile = text(p.mobile).replace(/\D/g, "");
    const province = text(p.province).toUpperCase();
    take("mobile", mobile, mobile.length === 10);
    take("province", province, province.length === 2);
  }
  if (contactSchemes.has(scheme)) {
    const state = c === "CA" ? (toCanadianProvinceCode(text(p.stateProv)) ?? "") : text(p.stateProv);
    take("address", text(p.addressLine1), text(p.addressLine1) !== "");
    if (text(p.city)) known.city = text(p.city);
    take("state", state, state !== "");
    if (text(p.postalCode)) known.postCode = text(p.postalCode);
  }
  if (missing.length > 0) return { kind: "details", needs: missing, known };
  // Interac's mobile and province go with the submit, as the original sends them.
  if (interacSchemes.has(scheme)) return { kind: "none", details: { mobile: known.mobile!, province: known.province! } };
  return { kind: "none" };
}

/** What details given for a terminal come to (`handleDetailsSubmit`). */
export type Checked =
  | { readonly ok: true; readonly profile: Readonly<Record<string, string>>; readonly submit: CustomerDetails }
  | { readonly ok: false; readonly invalid: readonly DetailName[] };

/**
 * Checks the details a player gave, beside what the profile held: each a scheme needs present and
 * well formed, a telephone composed into international form with `dialCode` (else the country's),
 * a Canadian's state a province code. Answers what to save to the profile under its names and what
 * to submit under the cashier's.
 */
export function checkDetails(scheme: string, given: Readonly<Partial<Record<DetailName, string>>> & { readonly dialCode?: string }, country: string): Checked {
  const v = (n: DetailName) => text(given[n]);
  const invalid: DetailName[] = [];
  const need = (n: DetailName, ok: boolean) => {
    if (!ok) invalid.push(n);
  };
  const first = v("firstName");
  const last = v("lastName");
  need("firstName", first !== "");
  need("lastName", last !== "");
  if (interacSchemes.has(scheme)) {
    const mobile = v("mobile").replace(/\D/g, "");
    const province = v("province").toUpperCase();
    need("mobile", mobile.length === 10);
    need("province", province.length === 2);
    if (invalid.length) return { ok: false, invalid };
    return { ok: true, profile: { firstName: first, lastName: last, mobile, province }, submit: { firstName: first, lastName: last, mobile, province } };
  }
  if (identitySchemes.has(scheme)) {
    if (invalid.length) return { ok: false, invalid };
    return { ok: true, profile: { firstName: first, lastName: last }, submit: { firstName: first, lastName: last } };
  }
  const raw = v("phone");
  const phone = E164.test(raw) ? raw : composePhone(given.dialCode ?? dialCodeForCountry(country), raw);
  need("phone", phone !== null);
  if (payerSchemes.has(scheme)) {
    if (invalid.length) return { ok: false, invalid };
    return { ok: true, profile: { firstName: first, lastName: last, phone: phone! }, submit: { firstName: first, lastName: last, phone: phone! } };
  }
  const isCanada = country.toUpperCase() === "CA";
  const state = isCanada ? (toCanadianProvinceCode(v("state")) ?? "") : v("state");
  const address = v("address");
  need("address", address !== "");
  need("state", state !== "");
  if (invalid.length) return { ok: false, invalid };
  const city = v("city");
  const postCode = v("postCode");
  return {
    ok: true,
    profile: { firstName: first, lastName: last, phone: phone!, addressLine1: address, ...(city ? { city } : {}), stateProv: state, ...(postCode ? { postalCode: postCode } : {}) },
    submit: { firstName: first, lastName: last, phone: phone!, address, ...(city ? { city } : {}), ...(postCode ? { postCode } : {}), state },
  };
}

// ── lib/schemeUtils.ts: the rules, not the words ────────────────────────────

const networks: Readonly<Record<string, string>> = { USDT: "ERC-20", USDTRX: "TRC-20", USDC: "ERC-20", USDTSOL: "Solana", USDCSOL: "Solana" };

/** A coin's network, for a coin sent on more than one (`schemeNetwork`). */
export function coinNetwork(coin: string): string | undefined {
  return networks[coin];
}

/**
 * The terminals whose scheme another terminal of the list shares, which a page names beside the
 * scheme so a player can tell them apart (`disambiguatedTerminalLabels`, ClickUp 869ebne95).
 */
export function sharingScheme(terminals: readonly Pick<Terminal, "id" | "scheme">[]): ReadonlySet<string> {
  const counts = new Map<string, number>();
  for (const t of terminals) if (t.scheme) counts.set(t.scheme, (counts.get(t.scheme) ?? 0) + 1);
  return new Set(terminals.filter((t) => t.scheme && (counts.get(t.scheme) ?? 0) > 1).map((t) => t.id));
}

// ── the preset amounts (resolvePresetAmounts) ───────────────────────────────

/**
 * A site's preset amounts in a currency, as amounts: only those within the bounds, else the
 * minimum alone (`resolvePresetAmounts`). The figures are major units, as a site writes them.
 */
export function presetAmounts(presets: readonly (string | number)[], currency: string, bounds: { minimum?: string | number; maximum?: string | number } = {}): Amount[] {
  const read = (x: string | number): Amount | null => {
    const r = fromMajor(x, currency);
    return r.ok ? r.amount : null;
  };
  const min = bounds.minimum !== undefined ? read(bounds.minimum) : null;
  const max = bounds.maximum !== undefined ? read(bounds.maximum) : null;
  const all = presets.map(read).filter((a): a is Amount => a !== null && a.minor > 0n);
  const valid = all.filter((a) => (min === null || a.minor >= min.minor) && (max === null || a.minor <= max.minor));
  if (valid.length > 0) return valid;
  return min ? [amount(min.minor, currency)] : [];
}
