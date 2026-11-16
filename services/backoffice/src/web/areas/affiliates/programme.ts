// The affiliate programme as the area reads it (US10): the shapes spec 013's OperatorEndpoint answers
// (Answers.scala, BackofficeAnswers, SettlementAnswers, RecordAnswers, TrackingAnswers, NetworkAnswers,
// ManagerAnswers), and one read that turns a refusal into the page's. Money is in minor units with its
// currency, a figure per brand and currency, never summed across currencies.
import { parseMoney } from "../../../shared/money.ts";
import { explain, type Refusal } from "../../../shared/refusals.ts";

/** An owner's answer as JSON, or its refusal as the page shows it. */
export async function json<T>(answer: Promise<Response>): Promise<T> {
  const r = await answer;
  if (!r.ok) throw explain(r.status, await r.text());
  return (await r.json()) as T;
}

/** The refusal a failed read carries, for `RefusalNotice`. */
export const refusalOf = (e: unknown): Refusal => e as Refusal;

/** The browser's time zone, which the page names wherever it shows a time (the brand's is not readable to a manager). */
export const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/** This month as the programme names a period, `yyyy-MM`, in the browser's zone. */
export function thisMonth(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", timeZone: zone() }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}`;
}

export const isPeriod = (s: string) => /^\d{4}-(0[1-9]|1[0-2])$/.test(s);

/** An amount typed in `currency` as the programme takes it, a whole number of minor units at or above zero; undefined if not one. */
export function amountOf(text: string, currency: string): number | undefined {
  let minor: bigint | undefined;
  try {
    minor = parseMoney(text, currency);
  } catch {
    return undefined;
  }
  if (minor === undefined || minor < 0n || minor > BigInt(Number.MAX_SAFE_INTEGER)) return undefined;
  return Number(minor);
}

export const isCurrency = (s: string) => /^[A-Z]{3}$/.test(s);

/** A percentage the programme takes (0 to 100, as `"5"` or `5.5`); undefined if not one. */
export function percentOf(text: string): number | undefined {
  const s = text.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return undefined;
  const n = Number(s);
  return n >= 0 && n <= 100 ? n : undefined;
}

export interface Membership {
  brandId: string;
  status: string;
  planId: string | null;
  revShareOverride: number | null;
  appliedAt: string | null;
  since: string | null;
  rejectionReason: string | null;
}

export interface Account {
  affiliateId: string;
  email: string | null;
  name: string | null;
  displayName: string | null;
  companyName: string | null;
  website: string | null;
  trafficSource: string | null;
  paymentMethod: string | null;
  parentAffiliateId: string | null;
  overridePercent: number;
  postbackUrl: string | null;
  minimumPayout: Record<string, number>;
  managerId: string | null;
  group: string | null;
  tags: string[];
  createdAt: string | null;
  firstLoginAt: string | null;
  lastLoginAt: string | null;
  closedAt: string | null;
  /** At this brand. */
  status: string | null;
  planId: string | null;
  memberships: Membership[];
  brandId?: string | null;
  keycloakUserId?: string | null;
}

/** A revenue figure for one brand and currency (Revenue.scala `Figure`); a brand with registrations and no money has no currency. */
export interface Figure {
  brandId: string;
  currency: string | null;
  ggr: number;
  excludedGgr: number;
  bonusCosts: number;
  chargebacks: number;
  adminFees: number;
  paymentFees: number;
  providerFees: number;
  ngr: number;
  cpaCommission: number;
  revShareCommission: number;
  overrideCommission: number;
  adjustments: number;
  totalCommission: number;
  registrationCount: number;
  ftdCount: number;
  depositCount: number;
  deposit2Count: number;
  deposit3Count: number;
  totalDepositAmount: number;
  fraudFlagCount: number;
}

export interface Revenue {
  affiliateId?: string | null;
  period: string;
  figures: Figure[];
}

export interface AccountRow {
  account: Account;
  currentMonth: Figure[];
}

export interface AccountsPage {
  items: AccountRow[];
  total: number;
  page: number;
  pageSize: number;
}

export interface Plan {
  planId: string;
  brandId: string;
  planName: string;
  planType: string;
  cpaAmount: number;
  cpaCurrency: string;
  revSharePercent: number;
  minimumDeposit: number;
  negativeCarryover: boolean;
  status: string;
}

export interface Link {
  btag: string;
  tag: string | null;
  landingUrl: string | null;
  affiliateId: string;
  brandId: string;
  campaign: string | null;
  landingPath: string | null;
  utmSource: string | null;
  utmMedium: string | null;
  utmCampaign: string | null;
  subIds: string[];
  clicks: number;
  registrations: number;
  createdAt: string | null;
  revokedAt: string | null;
}

export interface Creative {
  creativeId: string;
  brand: string;
  name: string;
  creativeType: string | null;
  fileUrl: string | null;
  dimensions: string | null;
  description: string | null;
  status: string;
  createdBy: string;
  createdAt: string | null;
  deletedAt: string | null;
}

export interface Payout {
  payoutId: string;
  affiliateId: string;
  brandId: string;
  currency: string;
  period: string;
  status: string;
  cpaCommission: number;
  revShareCommission: number;
  overrideCommission: number;
  adjustments: number;
  totalPayable: number;
  createdAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  rejectedBy: string | null;
  rejectionReason: string | null;
  paidBy: string | null;
  paidAt: string | null;
}

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

export interface Settings {
  brandId: string;
  origin: string;
  adminFeePercent: number;
  paymentFeePercent: number;
  providerFeePercent: number;
  bonusCostThreshold: number;
  chargebackThreshold: number;
  payoutCycleDay: number | null;
  reportingZone: string;
  ggrSource: string;
  ggrSourceFrom: string | null;
  previousGgrSource: string;
  senderRegion: string | null;
  senderMessages: Record<string, string>;
  defaultPlanId: string | null;
  retiredAt: string | null;
  customerIoKeySet: boolean;
}

export interface ProgrammePage {
  brandId: string;
  name: string | null;
  terms: string | null;
  termsVersion: string | null;
  contact: string | null;
  description: string | null;
  payoutCycleDays: number | null;
  minimumPayoutAmount: number | null;
  defaultPlanId: string | null;
}

export interface Manager {
  keycloakUserId: string;
  email: string;
  name: string;
  pending: boolean;
}

export interface Note {
  noteId: string;
  affiliateId: string;
  brandId: string;
  text: string;
  authorId: string;
  createdAt: string;
}

export interface AuditEntry {
  auditId: string;
  affiliateId: string;
  brandId: string;
  event: string;
  actorId: string;
  recordedAt: string;
  details: Record<string, string>;
}

export interface Held {
  brandId: string;
  status: string;
  planId: string | null;
}

export interface Sub {
  affiliateId: string;
  displayName: string;
  status: string;
  overridePercent: number;
  planId: string;
  createdAt: string | null;
  memberships: Held[];
}

export interface NetworkHealth {
  totalSubs: number;
  activeSubs: number;
  pendingApprovals: number;
  suspendedSubs: number;
}

/** The name an account is shown by: its display name, its name, its email, its id. */
export const accountName = (a: Pick<Account, "affiliateId" | "displayName" | "name" | "email">) => a.displayName ?? a.name ?? a.email ?? a.affiliateId;

/** Who a record names as its actor: the operator signed in by their name, anyone else by the subject the programme keeps. */
export const actorName = (subject: string | null, me: { subject: string | null; name: string | null }) =>
  subject !== null && subject === me.subject && me.name ? me.name : (subject ?? "");
