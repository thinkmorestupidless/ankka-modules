// kyc's answers as the identity screens read them (services/kyc api/Render.scala), and the reads, each
// through the page's server as the signed-in operator. A refusal is thrown as `explain` gives it.
import { kyc } from "../../../shared/owners/index.ts";
import { refused } from "../../../shared/refusals.ts";

export type Status = "unverified" | "pending" | "verified" | "rejected";

export interface QueueRow {
  playerId: string;
  status: string | null;
  mode: string | null;
  awaiting: number | null;
  oldestAwaitingAt: string | null;
  readyAt: string | null;
  flags: string[] | null;
  flaggedAt: string | null;
  sof: string | null;
  depositsByCurrency: Record<string, number> | null;
  updatedAt: string | null;
  sinceSeconds: number | null;
}

export interface Queue {
  rows: QueueRow[];
  counts: Record<string, number>;
}

export interface KycDocument {
  documentId: string;
  category: string;
  status: "pending" | "approved" | "rejected";
  uploadedAt: string;
  reviewedAt: string | null;
  reasonCode: string | null;
  submittedBy: "player" | "support";
  reviewedBy: string | null;
}

export interface Flag {
  reason: string;
  at: string;
  open: boolean;
  dismissedAt: string | null;
  dismissedBy: string | null;
  closedAt: string | null;
}

export interface PlayerIdentity {
  playerId: string;
  status: Status;
  mode: "automatic" | "manual";
  categories: { category: string; requiredByPolicy: boolean; requiredOfPlayer: boolean; document?: KycDocument | null }[];
  other?: KycDocument[];
  missingProfileFields: string[];
  readyAt: string | null;
  sof: "unverified" | "cleared";
  requiredOfPlayer: string[];
  deadline: { at: string; migrated: boolean; handled: boolean; suspensionRequested: boolean } | null;
  depositsByCurrency: Record<string, number>;
  flags: Flag[];
}

export interface Policy {
  depositGate: string;
  playGate: string;
  kycCurrency: string | null;
  depositThresholdMinorUnits: number | null;
  sofThresholdMinorUnits: number | null;
  kycWindowDays: number | null;
  requiredDocTypes: string[];
  autoSuspendOnWindowExpiry: boolean;
  licenceProfile: string;
  isDefault: boolean;
}

/** The categories a document is uploaded in (Category.Uploadable), and what a player may be asked for. */
export const Categories = ["identity_front", "identity_back", "proof_of_address", "payment_method", "bank_account_details", "other"] as const;
/** The rejection reason codes kyc takes (ReasonCode). */
export const ReasonCodes = ["document_unclear", "address_mismatch", "document_expired", "invalid_document", "other"] as const;
/** The queue's filters (KycQueue.Filter.of) and the count each is totalled by (KycQueue.counts). */
export const Filters = [
  ["awaiting", "awaiting"],
  ["ready", "ready"],
  ["sof", "sourceOfFunds"],
  ["flagged", undefined],
  ["unverified", "unverified"],
  ["pending", "pending"],
  ["verified", "verified"],
  ["rejected", "rejected"],
] as const;
/** The licence profiles kyc applies (Profiles.byName). */
export const Profiles = ["ANJOUAN", "CURACAO", "MGA", "UKGC"] as const;

async function read<T>(r: Promise<Response>): Promise<T> {
  const answer = await r;
  if (!answer.ok) throw refused(answer.status, await answer.text());
  return (await answer.json()) as T;
}

export const readQueue = (filter: string) => read<Queue>(kyc.getQueue({ query: { filter } }));
export const readIdentity = (playerId: string) => read<PlayerIdentity>(kyc.getByPlayerId({ playerId }));

/** One of kyc's decisions on a player's identity (amendment K3): the fields its kind names, the others absent. */
export interface KycDecision {
  kind: string;
  actor: { kind: string; id?: string | null };
  at: string;
  documentId?: string;
  category?: string;
  outcome?: string;
  reasonCode?: string;
  from?: string | string[];
  to?: string | string[];
  cause?: string;
}

export const readDecisions = (playerId: string) =>
  read<{ decisions?: KycDecision[] }>(kyc.getDecisions({ playerId })).then((d) => d.decisions ?? []);
export const readPolicy = () => read<Policy>(kyc.getPolicy());

/** Where a document is shown: the server's composite route, by its two ids and nothing else (FR-031). */
export const documentHref = (playerId: string, documentId: string) =>
  `/api/kyc/${encodeURIComponent(playerId)}/documents/${encodeURIComponent(documentId)}/view`;
