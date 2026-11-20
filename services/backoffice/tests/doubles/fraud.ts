// players' fraud cases as the players double keeps them, once a scenario seeds some: listed by status,
// reviewed and dismissed, and a dismissal clearing the suspected duplicate flag on every player the case
// links, as players does (`FraudRoutes.clear`), so a player's profile read after it answers no flag.
// players' own rules about who may act are held by its own suites; this keeps only what the
// backoffice's scenarios read back.
import type { Answer } from "./owner.ts";

export interface FraudCase {
  caseId: string;
  playerIds: string[];
  signals: string[];
  status: "OPEN" | "REVIEWED" | "DISMISSED";
  detectedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNotes: string | null;
}

export interface FraudSeed {
  cases: FraudCase[];
}

const List = /^\/v1\/casino\/backoffice\/[^/]+\/fraud-cases$/;
const Decide = /^\/v1\/casino\/backoffice\/[^/]+\/fraud-cases\/([^/]+)\/(review|dismiss)$/;
const Profile = /^\/v1\/casino\/backoffice\/[^/]+\/players\/([^/]+)\/profile$/;

export class FraudCases {
  private cases = new Map<string, FraudCase>();
  /** Each flagged player and the cases that flag them. */
  private flags = new Map<string, Set<string>>();

  reset(): void {
    this.cases.clear();
    this.flags.clear();
  }

  seed(s: FraudSeed): void {
    for (const c of s.cases) {
      this.cases.set(c.caseId, structuredClone(c));
      if (c.status !== "DISMISSED") for (const p of c.playerIds) (this.flags.get(p) ?? this.flags.set(p, new Set()).get(p)!).add(c.caseId);
    }
  }

  /** An answer for a fraud-case route; undefined for any other path, or while nothing is seeded. */
  handle(method: string, path: string, query: URLSearchParams, body: string, subject: string | undefined): Answer | undefined {
    if (this.cases.size === 0) return undefined;
    if (method === "GET" && List.test(path)) {
      const wanted = (query.get("status") ?? "").toUpperCase();
      const rows = [...this.cases.values()].filter((c) => wanted === "" || wanted === "ALL" || c.status === wanted);
      return { body: { cases: rows } };
    }
    const m = Decide.exec(path);
    if (method === "PATCH" && m) {
      const [, id, what] = m;
      const c = this.cases.get(decodeURIComponent(id!));
      if (!c) return { status: 404, body: { error: "case_not_found", message: "No such fraud case" } };
      const at = new Date().toISOString();
      if (what === "review") {
        if (c.status === "DISMISSED") return { status: 409, body: { error: "case_closed", message: "case closed" } };
        if (c.status === "OPEN") Object.assign(c, { status: "REVIEWED", reviewedBy: subject ?? null, reviewedAt: at });
        return { body: { caseId: c.caseId, status: c.status, reviewedBy: c.reviewedBy, reviewedAt: c.reviewedAt } };
      }
      if (c.status !== "DISMISSED") {
        const notes = (() => {
          try {
            const n = (JSON.parse(body || "{}") as { notes?: unknown }).notes;
            return typeof n === "string" && n.trim() !== "" ? n : null;
          } catch {
            return null;
          }
        })();
        Object.assign(c, { status: "DISMISSED", reviewedBy: subject ?? null, reviewedAt: at, reviewNotes: notes });
        for (const p of c.playerIds) this.flags.get(p)?.delete(c.caseId);
      }
      return { body: { caseId: c.caseId, status: c.status, reviewedBy: c.reviewedBy, reviewedAt: c.reviewedAt, reviewNotes: c.reviewNotes } };
    }
    return undefined;
  }

  /** A player's profile as players answers it: the flag only while a case still sets it. */
  profile(path: string, answer: Answer): Answer {
    const m = Profile.exec(path);
    if (!m || this.cases.size === 0 || (answer.status ?? 200) !== 200 || typeof answer.body !== "object" || answer.body === null) return answer;
    const player = decodeURIComponent(m[1]!);
    const cases = [...(this.flags.get(player) ?? [])];
    // players answers `{player, wallet, activeSessions, pinnedNotes}`; the flag is the player's
    const outer = answer.body as Record<string, unknown>;
    const body: Record<string, unknown> = { ...((outer.player ?? {}) as Record<string, unknown>) };
    delete body.suspectedDuplicate;
    delete body.linkedPlayerIds;
    delete body.duplicateSignals;
    if (cases.length > 0) {
      const linked = cases.flatMap((id) => this.cases.get(id)!.playerIds).filter((p) => p !== player);
      body.suspectedDuplicate = true;
      body.linkedPlayerIds = [...new Set(linked)].sort();
      body.duplicateSignals = [...new Set(cases.flatMap((id) => this.cases.get(id)!.signals))].sort();
    }
    return { ...answer, body: { ...outer, player: body } };
  }
}
