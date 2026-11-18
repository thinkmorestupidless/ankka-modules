/**
 * Every refusal code a behaviour can meet (FR-039), by behaviour, from the services' own refusal
 * tables: `specs/008-players`, `009-kyc`, `010-responsible-gambling`, `003-game-provider`,
 * `006-ingest`, `017-support-agent` (`contracts/routes.md` each), the amended deposit start, the
 * platform's, and the SDK's own. A site's words are typed `Record<RefusalCode, string>`, so a code
 * with no words in a language does not compile (SC-006).
 */

/** The SDK's own: what it answers when no service did, or when what a service answered does not fit. */
export const sdkCodes = [
  "unknown",
  "malformed_answer",
  "malformed_amount",
  "service_unavailable",
  "not_named",
  "module_not_run",
  "wrong_origin",
  "signed_out",
  "blocked_in_territory",
  "no_terminals",
  "timed_out",
  "payment_failed",
  "payment_expired",
] as const;

/** The platform's, on any route of a brand. */
export const platformCodes = ["wrong_brand", "unauthenticated", "brand_not_found", "player_id_mismatch", "forbidden"] as const;

export const sessionCodes = [
  "invalid_credentials",
  "email_not_verified",
  "account_restricted",
  "cooling_off",
  "missing_fields",
  "email_already_exists",
  "password_too_weak",
  "missing_token",
  "invalid_or_expired_token",
  "rate_limited",
  "refresh_expired",
  "missing_refresh_token",
  "invalid_country",
] as const;

export const depositCodes = [
  // the deposit gate (kyc)
  "DEPOSIT_BLOCKED_KYC",
  "DEPOSIT_BLOCKED_KYC_CURRENCY",
  "DEPOSIT_BLOCKED_KYC_THRESHOLD",
  "DEPOSIT_BLOCKED_SOF",
  // responsible gambling's may-deposit
  "over_limit",
  "excluded",
  // players' own
  "invalid_amount",
  "invalid_currency",
  "invalid_request_id",
  "player_not_found",
  "no_fields_provided",
  // the cashier (ingest) and merchant behind it
  "REQUEST_ID_REUSED",
  "TOO_MANY_REQUESTS",
  "TRY_AGAIN",
  "INVALID_REQUEST",
  "NO_ROUTING_TREE",
  "NO_ROUTING_MATCH",
  "WRONG_BRAND",
  "INVALID_AMOUNT",
  "SUB_BRAND_NOT_FOUND",
  "SUB_BRAND_INACTIVE",
  "SESSION_NOT_FOUND",
  "PAYMENT_NOT_FOUND",
  "terminal_not_found",
  "missing_crypto_currency",
  "PAYMENT_REJECTED",
  "psp_error",
  "OUTCOME_PENDING",
  "body_too_large",
  "not_json",
] as const;

export const playCodes = [
  "provider_required",
  "free_spins_grant_invalid",
  "PLAY_BLOCKED_KYC",
  "cooling_off_active",
  "session_limit_reached",
  "RATE_LIMITED",
  "st8_not_configured",
  "launch_failed",
  "game_not_found",
  "session_not_found",
] as const;

export const chatCodes = [
  "agent_unavailable",
  "session_not_yours",
  "Session already active",
  "conversation_ended",
  "too_many_turns",
  "invalid_action",
  "no_pending_action",
  "already_decided",
] as const;

export const refusalCodes = [
  ...new Set([...sdkCodes, ...platformCodes, ...sessionCodes, ...depositCodes, ...playCodes, ...chatCodes]),
] as readonly RefusalCode[];

export type RefusalCode =
  | (typeof sdkCodes)[number]
  | (typeof platformCodes)[number]
  | (typeof sessionCodes)[number]
  | (typeof depositCodes)[number]
  | (typeof playCodes)[number]
  | (typeof chatCodes)[number];

/** The codes each behaviour can meet: what a site must have words for. */
export const codesByBehaviour = {
  session: [...sdkCodes, ...platformCodes, ...sessionCodes],
  deposit: [...sdkCodes, ...platformCodes, ...depositCodes, "account_restricted", "cooling_off", "missing_fields"],
  play: [...sdkCodes, ...platformCodes, ...playCodes, "account_restricted", "service_unavailable"],
  inbox: [...sdkCodes, ...platformCodes],
  chat: [...sdkCodes, ...platformCodes, ...chatCodes, "missing_fields"],
} as const satisfies Record<string, readonly RefusalCode[]>;

/**
 * The named fields of the codes that carry any, under one name per thing whatever the service
 * called it: a break's end is `until` (from `until`, `endsAt`, `coolingOffUntil`), a wait is
 * `retryAfterSeconds` (from `retry_after_seconds`, `retryAfterSeconds`, `retryAfter`). Amounts are
 * minor units as `bigint`.
 */
export interface RefusalFieldsByCode {
  account_restricted: { reason?: string };
  cooling_off: { until?: string };
  cooling_off_active: { until?: string };
  missing_fields: { missing?: string[] };
  rate_limited: { retryAfterSeconds?: number };
  RATE_LIMITED: { action?: string; retryAfterSeconds?: number };
  TOO_MANY_REQUESTS: { retryAfterSeconds?: number };
  TRY_AGAIN: { retryAfterSeconds?: number };
  too_many_turns: { retryAfterSeconds?: number };
  DEPOSIT_BLOCKED_KYC: { kycStatus?: string };
  DEPOSIT_BLOCKED_KYC_CURRENCY: { kycStatus?: string };
  DEPOSIT_BLOCKED_KYC_THRESHOLD: { kycStatus?: string };
  PLAY_BLOCKED_KYC: { kycStatus?: string };
  over_limit: { period?: string; limit?: bigint; used?: bigint; remaining?: bigint };
  session_limit_reached: { limitMinutes?: number; usedMinutes?: number };
  REQUEST_ID_REUSED: { requestId?: string };
  INVALID_REQUEST: { field?: string };
  "Session already active": { approvalId?: string; action?: string };
  payment_failed: { failureCode?: string };
  payment_expired: { failureCode?: string };
  wrong_brand: { brand?: string };
}

export type RefusalFields<C extends RefusalCode> = C extends keyof RefusalFieldsByCode ? RefusalFieldsByCode[C] : Record<string, never>;

export function isRefusalCode(code: string): code is RefusalCode {
  return (refusalCodes as readonly string[]).includes(code);
}
