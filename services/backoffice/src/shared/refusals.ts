// A refusal as the page shows it (FR-006): the owner's code mapped to a catalogue message and, where
// one exists, its next step; a code the backoffice does not know shown as given. The backoffice adds no
// rule of its own here (FR-007): it only names what the owner said.
import { has, t, type Message, type MessageKey } from "./messages/index.ts";
import { rolesGiving, type RoleBags } from "./access.ts";

export interface Refusal {
  status: number;
  /** The owner's code, as it gave it. */
  code: string;
  message: Message;
  next?: Message;
}

/**
 * The codes rewards answers beside a refused grant's reason (`allowedBy`, spec 030 RW2; `AllowedBy` in
 * rewards' Decline.scala): what would allow the same grant, each worded as `refusal.allowedBy.<code>`.
 */
export const AllowedByCodes = [
  "duplicate_check_ended",
  "check_window_passed",
  "no_deposit_block_cleared",
  "fraud_flag_cleared",
  "brand_detection_off",
  "offer_requiring_deposit",
  "open_reward_ended",
  "offer_limit_raised",
  "brand_limit_raised",
  "conflicting_reward_ended",
  "valid_code",
  "larger_deposit",
  "operator_award",
  "wallet_opened",
] as const;

/**
 * The code and the owner's own words from a refusal's body: `{error, message}`, rewards' ineligible grant
 * `{status: "ineligible", reason, allowedBy}` (the reason is the code), or `code: words`.
 */
export function readRefusal(status: number, body: string): { code: string; given: string; source?: string; allowedBy?: string[] } {
  try {
    const j = JSON.parse(body) as { error?: unknown; code?: unknown; reason?: unknown; message?: unknown; source?: unknown; allowedBy?: unknown };
    const code = String(j.error ?? j.code ?? (typeof j.reason === "string" && j.reason !== "" ? j.reason : status));
    const given = typeof j.message === "string" ? j.message : code;
    const allowedBy = Array.isArray(j.allowedBy) ? j.allowedBy.filter((c): c is string => typeof c === "string") : undefined;
    return { code: code.toLowerCase(), given, source: typeof j.source === "string" ? j.source : undefined, ...(allowedBy ? { allowedBy } : {}) };
  } catch {
    const m = /^([a-z_]+):\s*(.*)$/s.exec(body.trim());
    return m ? { code: m[1]!, given: body.trim() } : { code: String(status), given: body.trim() || String(status) };
  }
}

/** What would allow a refused grant, each code in the catalogue's words, a code it lacks as given. */
function allowing(codes: readonly string[]): Message {
  const ways = codes.map((c) => {
    const k = `refusal.allowedBy.${c.toLowerCase()}`;
    return has(k) ? t(k) : c;
  });
  return { key: "refusal.allowedBy", values: { ways: ways.join("; ") } };
}

const permissionNamed = (given: string) => /the ([a-z]+\.[A-Za-z]+) permission is needed/.exec(given)?.[1];

/** What the page says for a refusal; `bags` (from `/api/permissions`) names the roles that would give a permission. */
export function explain(status: number, body: string, bags?: RoleBags): Refusal {
  const { code, given, source, allowedBy } = readRefusal(status, body);
  const next = (key: string): Message | undefined => (has(key) ? { key: key as MessageKey } : undefined);

  if (code === "forbidden") {
    const permission = permissionNamed(given);
    if (!permission) return { status, code, message: { key: "refusal.forbidden.unnamed" } };
    const roles = bags ? rolesGiving(permission, bags) : [];
    return {
      status,
      code,
      message: { key: "refusal.forbidden", values: { permission } },
      next: bags === undefined ? undefined : roles.length ? { key: "refusal.forbidden.next", values: { roles: roles.join(", ") } } : { key: "refusal.forbidden.none" },
    };
  }
  if (code === "source_unavailable")
    return { status, code, message: { key: "refusal.source_unavailable", values: { source: source ?? "A service" } }, next: next("refusal.source_unavailable.next") };
  if (code === "invalid_body") return { status, code, message: { key: "refusal.invalid_body", values: { detail: given } } };

  const key = `refusal.${code}`;
  const allowed = allowedBy?.length ? allowing(allowedBy) : undefined;
  if (has(key)) return { status, code, message: { key }, next: allowed ?? next(`${key}.next`) };
  return { status, code, message: { key: "refusal.unknown", values: { code, given } }, next: allowed };
}
