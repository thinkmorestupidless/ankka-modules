import { amount, catalogue, format, type RefusalCode, type RefusalWords } from "@eitheror/player-sdk";
import * as en from "./en.ts";
import * as fr from "./fr.ts";
import type { Texts } from "./fr.ts";

/** The site's languages, French first: a text missing in English is shown in French. */
export const refusalWords = catalogue<RefusalWords>({ language: "fr", words: fr.refusals }, { en: en.refusals });
export const textWords = catalogue<Texts>({ language: "fr", words: fr.texts }, { en: en.texts });
export const languages = textWords.languages;

export type TextKey = keyof Texts;

/** The value of a field, written for the page: an amount by `format`, a time as a date in the language, the rest as text. */
function written(value: unknown, language: string, currency: string): string {
  if (typeof value === "bigint") return format(amount(value, currency), language);
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    const at = new Date(value);
    // the site's English is British (the brand voice): 25 November, not November 25
    if (!Number.isNaN(at.getTime())) return new Intl.DateTimeFormat(language === "en" ? "en-GB" : language, { dateStyle: "long", timeStyle: "short" }).format(at);
  }
  if (Array.isArray(value)) return value.join(", ");
  return String(value);
}

/** A text with its `{field}`s written; a field not given is left out. */
export function fill(text: string, fields: Readonly<Record<string, unknown>>, language: string, currency = "EUR"): string {
  return text.replace(/\{([A-Za-z]+)\}/g, (_, name: string) => (fields[name] === undefined ? "" : written(fields[name], language, currency))).replace(/ {2,}/g, " ").trim();
}

export const words = {
  text: (key: TextKey, language: string, fields: Readonly<Record<string, unknown>> = {}, currency?: string) => fill(textWords.in(language)[key], fields, language, currency),
  refusal: (code: RefusalCode, language: string, fields: Readonly<Record<string, unknown>> = {}, currency?: string) => fill(refusalWords.in(language)[code], fields, language, currency),
};
