// The catalogue's word for an owner's value (`<prefix>.<value>`), or the value as the owner gave it
// when the catalogue has none (FR-040a: what an owner returns as data is shown as given).
import { has, t } from "../../shared/messages/index.ts";

export function word(prefix: string, value: string): string {
  const key = `${prefix}.${value}`;
  return has(key) ? t(key) : value;
}
