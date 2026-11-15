// A message is a catalogue key and its values (data-model.md), rendered by `intl-messageformat`.
import { IntlMessageFormat } from "intl-messageformat";
import { en, type MessageKey } from "./en.ts";

export type { MessageKey } from "./en.ts";
export type Values = Record<string, string | number | bigint>;

export interface Message {
  key: MessageKey;
  values?: Values;
}

const locale = "en-GB";
const formats = new Map<MessageKey, IntlMessageFormat>();

export function has(key: string): key is MessageKey {
  return Object.hasOwn(en, key);
}

/** The text of `key` with `values`; a value a message does not name is ignored. */
export function t(key: MessageKey, values: Values = {}): string {
  let f = formats.get(key);
  if (!f) {
    f = new IntlMessageFormat(en[key], locale);
    formats.set(key, f);
  }
  const plain = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, typeof v === "bigint" ? v.toString() : v]));
  return String(f.format(plain));
}

export function render(m: Message): string {
  return t(m.key, m.values);
}
