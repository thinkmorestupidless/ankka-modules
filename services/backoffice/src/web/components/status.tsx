// A status as a word and an icon, never colour alone (FR-040). The word is the catalogue's for a status
// the backoffice knows (`status.<value>`); a status an owner answers that the catalogue lacks is shown
// as given (FR-040a: what an owner returns as data is shown as given), with a neutral mark.
import "./components.css";
import { has, t, type MessageKey } from "../../shared/messages/index.ts";
import { Glyph, type GlyphName } from "./glyph.tsx";

export type Tone = "positive" | "negative" | "waiting" | "warning" | "neutral";

const tones: Record<Tone, GlyphName> = {
  positive: "check",
  negative: "cross",
  waiting: "clock",
  warning: "alert",
  neutral: "dot",
};

/** The tone of each status the owners answer; one not named here is neutral. */
const toneOf: Record<string, Tone> = {
  // withdrawal requests (payouts)
  pending_review: "waiting",
  approved: "positive",
  awaiting_manual_payout: "waiting",
  completed: "positive",
  rejected: "negative",
  cancelled: "neutral",
  failed: "negative",
  reversed: "warning",
  // payouts' payout status
  initiated: "waiting",
  needs_review: "warning",
  resolved: "positive",
  // players' account status
  pending_email_verification: "waiting",
  active: "positive",
  suspended: "warning",
  self_excluded: "warning",
  blocked: "negative",
  closed: "neutral",
  erasure_pending: "waiting",
  erased: "neutral",
  // merchant's payments
  captured: "positive",
  expired: "neutral",
  // rounds, cases, documents
  settled: "positive",
  unsettled: "warning",
  open: "waiting",
  pending: "waiting",
  verified: "positive",
};

export function toneFor(value: string): Tone {
  return toneOf[value.toLowerCase()] ?? "neutral";
}

/** The word for a status: the catalogue's, or the owner's own value as given. */
export function statusWord(value: string): string {
  const key = `status.${value.toLowerCase()}`;
  return has(key) ? t(key as MessageKey) : value;
}

export function Status({ value, tone }: { value: string; tone?: Tone }) {
  const shown = tone ?? toneFor(value);
  return (
    <span className="bo-status" data-tone={shown} data-status={value}>
      <Glyph name={tones[shown]} />
      <span>{statusWord(value)}</span>
    </span>
  );
}
