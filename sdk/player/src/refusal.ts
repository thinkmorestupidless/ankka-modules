import { isRefusalCode, type RefusalCode, type RefusalFields } from "./codes.ts";

/**
 * A refusal, in one form whichever service wrote it (FR-039): the status, the code, the code's
 * named fields, the service that refused when it said so, and words only where the spec passes a
 * service's text on (the support agent's, a payment provider's rejection: FR-022, FR-054).
 */
export interface Refusal<C extends RefusalCode = RefusalCode> {
  readonly status: number;
  readonly code: C;
  readonly fields: RefusalFields<C>;
  readonly service?: string;
  readonly words?: string;
}

/** The refusal the SDK writes itself. */
export function refusal<C extends RefusalCode>(code: C, status = 0, fields?: RefusalFields<C>, service?: string): Refusal<C> {
  return { status, code, fields: (fields ?? {}) as RefusalFields<C>, ...(service ? { service } : {}) };
}

const Coded = /^([A-Za-z][A-Za-z0-9_]*):\s/;

/** Every name a service uses for the same thing, and the one the SDK gives it. */
const sameThing: Readonly<Record<string, string>> = {
  endsAt: "until",
  coolingOffUntil: "until",
  retry_after_seconds: "retryAfterSeconds",
  retryAfter: "retryAfterSeconds",
  transactionId: "requestId",
  action_name: "action",
  fields: "missing",
};

/** Fields that carry minor units, given to a page as `bigint`. */
const minorUnits = new Set(["limit", "used", "remaining"]);

/** What is never a field: the code itself, the service's own text, and the envelope. */
const notFields = new Set(["error", "code", "message", "status", "service", "details", "detail", "allowed", "decision"]);

/**
 * Reads a refusal from the three forms the services write (research R7): the platform's
 * `{status, error: "code: explanation", details?}`, a handler's `{error, message, …named fields}`
 * and a handler's `{error, …named fields}`. A code the SDK does not know is `unknown`, with the
 * status and nothing of the service's. `words` is passed only when the caller says the spec
 * passes this service's text on.
 */
export function readRefusal(status: number, body: unknown, options: { words?: boolean } = {}): Refusal {
  if (typeof body === "string") body = { error: body };
  if (typeof body !== "object" || body === null || Array.isArray(body)) return refusal("unknown", status);
  const b = body as Record<string, unknown>;
  const raw = typeof b.error === "string" ? b.error : typeof b.code === "string" ? b.code : typeof b.reason === "string" && b.decision !== undefined ? b.reason : undefined;
  const decision = typeof b.decision === "string" && b.decision !== "allowed" ? b.decision : undefined;
  const given = raw ?? decision;
  if (given === undefined) return refusal("unknown", status);
  const coded = Coded.exec(given);
  const code = coded ? coded[1]! : given.trim();
  if (!isRefusalCode(code)) return refusal("unknown", status);
  const fields: Record<string, unknown> = {};
  const take = (source: Record<string, unknown>) => {
    for (const [name, value] of Object.entries(source)) {
      if (notFields.has(name) || value === null || value === undefined) continue;
      const as = sameThing[name] ?? name;
      fields[as] = minorUnits.has(as) && typeof value === "number" && Number.isSafeInteger(value) ? BigInt(value) : value;
    }
  };
  take(b);
  if (typeof b.details === "object" && b.details !== null && !Array.isArray(b.details)) take(b.details as Record<string, unknown>);
  const service = typeof b.service === "string" ? b.service : undefined;
  const words = options.words && typeof b.message === "string" && b.message.trim() !== "" ? b.message : undefined;
  return {
    status,
    code,
    fields: fields as RefusalFields<typeof code>,
    ...(service ? { service } : {}),
    ...(words ? { words } : {}),
  };
}
