// A refusal as the page shows it (FR-006): the owner's code mapped to a catalogue message and, where
// one exists, its next step; a code the backoffice does not know shown as given. The backoffice adds no
// rule of its own here (FR-007): it only names what the owner said.
import { has, type Message, type MessageKey } from "./messages/index.ts";
import { rolesGiving, type RoleBags } from "./access.ts";

export interface Refusal {
  status: number;
  /** The owner's code, as it gave it. */
  code: string;
  message: Message;
  next?: Message;
}

/** The code and the owner's own words from a refusal's body: `{error, message}` or `code: words`. */
export function readRefusal(status: number, body: string): { code: string; given: string; source?: string } {
  try {
    const j = JSON.parse(body) as { error?: unknown; code?: unknown; message?: unknown; source?: unknown };
    const code = String(j.error ?? j.code ?? status);
    const given = typeof j.message === "string" ? j.message : code;
    return { code: code.toLowerCase(), given, source: typeof j.source === "string" ? j.source : undefined };
  } catch {
    const m = /^([a-z_]+):\s*(.*)$/s.exec(body.trim());
    return m ? { code: m[1]!, given: body.trim() } : { code: String(status), given: body.trim() || String(status) };
  }
}

const permissionNamed = (given: string) => /the ([a-z]+\.[A-Za-z]+) permission is needed/.exec(given)?.[1];

/** What the page says for a refusal; `bags` (from `/api/permissions`) names the roles that would give a permission. */
export function explain(status: number, body: string, bags?: RoleBags): Refusal {
  const { code, given, source } = readRefusal(status, body);
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
  if (has(key)) return { status, code, message: { key }, next: next(`${key}.next`) };
  return { status, code, message: { key: "refusal.unknown", values: { code, given } } };
}
