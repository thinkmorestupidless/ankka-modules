// The affiliate programme's fraud flags as its double keeps them, once a scenario seeds some: the brand's
// flags listed, and one dismissed with a reason, recording the caller's subject and the time as the
// programme does (`Records.dismiss`: a reason is required; a flag already dismissed answers as it is).
// The programme's own suites hold its rules; this keeps what the backoffice's scenarios read back.
import type { Answer } from "./owner.ts";

export interface FraudFlag {
  flagId: string;
  playerId: string;
  affiliateId: string;
  brandId: string;
  period: string;
  reason: string;
  description: string;
  flaggedAt: string | null;
  dismissed: boolean;
  dismissedBy: string | null;
  dismissedAt: string | null;
  dismissalReason: string | null;
}

export interface FlagSeed {
  flags: FraudFlag[];
}

const List = /^\/v1\/affiliates\/([^/]+)\/fraud-flags$/;
const Dismiss = /^\/v1\/affiliates\/([^/]+)\/fraud-flags\/([^/]+)\/dismiss$/;

export class ProgrammeFlags {
  private flags = new Map<string, FraudFlag>();

  reset(): void {
    this.flags.clear();
  }

  seed(s: FlagSeed): void {
    for (const f of s.flags) this.flags.set(f.flagId, structuredClone(f));
  }

  /** An answer for a fraud-flag route; undefined for any other path, or while nothing is seeded. */
  handle(method: string, path: string, body: string, subject: string | undefined): Answer | undefined {
    if (this.flags.size === 0) return undefined;
    const listed = List.exec(path);
    if (method === "GET" && listed) return { body: [...this.flags.values()].filter((f) => f.brandId === decodeURIComponent(listed[1]!)) };
    const m = Dismiss.exec(path);
    if (method === "PATCH" && m) {
      const f = this.flags.get(decodeURIComponent(m[2]!));
      if (!f || f.brandId !== decodeURIComponent(m[1]!)) return { status: 404, body: { error: "not_found" } };
      const reason = (() => {
        try {
          const r = (JSON.parse(body || "{}") as { reason?: unknown }).reason;
          return typeof r === "string" && r.trim() !== "" ? r.trim() : undefined;
        } catch {
          return undefined;
        }
      })();
      if (reason === undefined) return { status: 400, body: { error: "invalid_request", message: "reason is required" } };
      if (!f.dismissed) Object.assign(f, { dismissed: true, dismissedBy: subject ?? null, dismissedAt: new Date().toISOString(), dismissalReason: reason });
      return { body: { ok: true } };
    }
    return undefined;
  }
}
