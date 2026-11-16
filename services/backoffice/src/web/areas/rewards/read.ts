// rewards' rewards and offers as the rewards area reads them (RewardLists.scala, Bodies.render), the
// wallet's open restricted funds records beside a player's (WalletEntity.restrictedFundsStatus), and what
// the page makes of them: a record's state, its wagering progress and its open rounds. Every read goes
// through the page's server as the signed-in operator; a refusal is thrown as `explain` gives it.
import { explain } from "../../../shared/refusals.ts";
import { rewards, wallet } from "../../../shared/owners/index.ts";

/** Free rounds' part of a reward (RewardLists.freeSpins); amounts are minor units. */
export interface FreeSpins {
  grantId: string;
  gameId?: string | null;
  provider?: string | null;
  spinCount: number;
  spinsUsed: number;
  spinValue: number;
  winnings: number;
  grantStatus: string;
  providerStatus?: string | null;
  faceValue?: number | null;
}

/** A reward as rewards lists it (RewardLists.item, and `ofPlayer`'s additions). */
export interface Reward {
  rewardId: string;
  playerId: string;
  offerId: string;
  offerName?: string | null;
  type: string;
  status: string;
  faceValue: number;
  freeSpins?: FreeSpins | null;
  currency: string;
  wageringRequirement?: number | null;
  wageredAmount?: number | null;
  grantedAt?: string | null;
  expiresAt?: string | null;
  source?: string | null;
  isPrimary?: boolean | null;
}

export interface Page {
  items: Reward[];
  total: number;
  page: number;
  pageSize: number;
  unmatchedRounds?: unknown[];
}

/** One of the wallet's open restricted funds records (RecordStatus). */
export interface OpenRecord {
  recordId: string;
  grantRef: string;
  status: string;
  balance: number;
  wageringRequirement: number;
  releaseProgress: number;
  openRoundCount: number;
}

/** An offer as rewards answers it (Bodies.render); amounts are minor units, the match in basis points. */
export interface Offer {
  id: string;
  name: string;
  description?: string;
  type: string;
  payloadType?: string;
  active: boolean;
  validFrom?: string | null;
  validUntil?: string | null;
  bonusPercentage?: number;
  maxBonusValue?: number | null;
  minDepositAmount?: number | null;
  maxConvertibleAmount?: number | null;
  depositNumberTarget?: number | string | null;
  claimWindowDays?: number | null;
  wageringWindowDays?: number | null;
  freeSpinsExpiryDays?: number | null;
  wageringMultiplier?: number;
  maxClaimsPerPlayer?: number | null;
  maxConcurrentGrants?: number | null;
  excludedOfferIds?: string[];
  conflictsWith?: string[];
  redemptionCodes?: { code: string; isAffiliate?: boolean }[];
  noDepositRequired?: boolean;
  currency?: string | null;
  gameId?: string | null;
  gameName?: string | null;
  provider?: string | null;
  spinCount?: number | null;
  spinValue?: number | null;
  spinConfigurations?: { currency: string; spinValue: number }[];
  winningsDestination?: string | null;
}

async function json<T>(answer: Promise<Response>): Promise<T> {
  const r = await answer;
  const text = await r.text();
  if (!r.ok) throw explain(r.status, text);
  return JSON.parse(text) as T;
}

export const PageSize = 50;

export function readRewards(query: Record<string, string>): Promise<Page> {
  return json<Page>(rewards.getRewardGrants({ query }));
}

/** Every reward of the player, newest first (rewards answers them all for a player). */
export function readPlayerRewards(player: string): Promise<Page> {
  return json<Page>(rewards.getRewardGrants({ query: { playerId: player } }));
}

/** The wallet's open records in one currency. */
export async function readOpenRecords(player: string, currency: string): Promise<OpenRecord[]> {
  const a = await json<{ records: OpenRecord[] }>(wallet.getWalletsRestrictedFunds({ player, currency }));
  return a.records;
}

export function readOffers(): Promise<Offer[]> {
  return json<Offer[]>(rewards.getOffers());
}

/** The state an action is offered in: rewards' status, lower-cased (Revocations accepts `ACTIVE` only). */
export const stateOf = (r: Pick<Reward, "status">) => r.status.toLowerCase();

/** The record is still open at the wallet: active, or converted and awaiting its cap. */
export const isOpen = (r: Pick<Reward, "status">) => ["active", "converted_pending_cap"].includes(stateOf(r));

/** Wagering progress as a whole per cent, floored, at most 100; none when nothing is to be wagered. */
export function progressOf(wagered: number, requirement: number): number | undefined {
  if (!(requirement > 0)) return undefined;
  return Math.min(100, Math.floor((Math.max(0, wagered) * 100) / requirement));
}

/** The offers that depend on one: those that exclude it or conflict with it, in the brand's order. */
export interface Dependant {
  offer: Offer;
  how: "excludes" | "conflicts";
}

export function dependantsOf(id: string, offers: readonly Offer[]): Dependant[] {
  return offers.flatMap((o): Dependant[] => {
    if (o.id === id) return [];
    if (o.excludedOfferIds?.includes(id)) return [{ offer: o, how: "excludes" }];
    if (o.conflictsWith?.includes(id)) return [{ offer: o, how: "conflicts" }];
    return [];
  });
}
