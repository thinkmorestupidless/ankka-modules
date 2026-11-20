// responsible-gambling's answers as the protection screens read them (services/responsible-gambling
// api/Answers.scala, `Render.full`), and the reads, through the page's server as the operator.
import { responsibleGambling as rg } from "../../../shared/owners/index.ts";
import { refused } from "../../../shared/refusals.ts";

export interface Limit {
  limitType: string;
  /** Minor units of `currency` for a money limit; whole minutes for SESSION_MINUTES. */
  value: number;
  currency: string | null;
}

export interface PendingChange {
  limitType: string;
  appliesAt: string;
  currency: string | null;
  /** The value it will take, as text; null for a removal. */
  pendingValue: string | null;
}

export interface Case {
  caseId: string;
  caseType: string;
  trigger: string;
  status: "open" | "closed";
  openedAt: string;
  closesAt: string | null;
  closedAt: string | null;
  closeReason: string | null;
  closeNote: string | null;
  permanent: boolean | null;
  notes: { at: string; operatorId: string; text: string }[];
}

export interface Protection {
  limits: Limit[];
  pendingChanges: PendingChange[];
  /** Major units, as the original showed them (Render.major). */
  usedAmounts: { limitType: string; currency: string; usedAmount: number }[];
  isCoolingOff: boolean;
  coolingOffUntil: string | null;
  activeRestrictions: string[];
  realityCheckIntervalMinutes: number | null;
  exclusion: { permanent: boolean; until: string | null; since: string; trigger: string } | null;
  returnRequest: { requestedAt: string; reinstatesAt: string; actor: string } | null;
  cases: Case[];
  history: unknown[];
}

export interface Settings {
  minSessionMinutes: number;
  maxBreakDays: number;
}

/** The seven limit types (Limits.scala), money first. */
export const LimitTypes = ["DEPOSIT_DAILY", "DEPOSIT_WEEKLY", "DEPOSIT_MONTHLY", "LOSS_DAILY", "LOSS_WEEKLY", "LOSS_MONTHLY", "SESSION_MINUTES"] as const;
export const isMoney = (limitType: string) => limitType !== "SESSION_MINUTES";
/** The case types an operator may open (RgCase.Types). */
export const CaseTypes = ["self_exclusion", "cool_off", "deposit_limit", "loss_limit", "session_limit", "reality_check", "account_block"] as const;
/** The shortest self-exclusion with an end (Periods.MinimumDays). */
export const MinimumExclusionDays = 183;

async function read<T>(r: Promise<Response>): Promise<T> {
  const answer = await r;
  if (!answer.ok) throw refused(answer.status, await answer.text());
  return (await answer.json()) as T;
}

export const readProtection = (player: string) => read<Protection>(rg.getByPlayer({ player }));
export const readSettings = () => read<Settings>(rg.getSettings());

/** A limit's key as responsible-gambling keys it (LimitKey.id): its type and, for money, its currency. */
export const keyOf = (l: { limitType: string; currency: string | null }) => (l.currency ? `${l.limitType}/${l.currency}` : l.limitType);
