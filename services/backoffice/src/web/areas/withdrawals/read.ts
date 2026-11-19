// payouts' withdrawal requests as the withdrawals area reads them (OperatorEndpoint.detail and .row,
// spec 030 Y2 to Y4), the neighbours a request's page reads beside it, and what the page makes of them:
// the state an action is offered in, the amounts that differ, who moved each state. Every read goes
// through the page's server as the signed-in operator; a refusal is thrown as `explain` gives it.
import { refused } from "../../../shared/refusals.ts";
import { walletMinorText } from "../../../shared/money.ts";
import { kyc, payouts, wallet } from "../../../shared/owners/index.ts";

/** A queue row and a row of a player's history (OperatorEndpoint.row). */
export interface Row {
  requestId: string;
  playerId: string;
  kycStatus: string;
  amount: number;
  currency: string;
  terminalName: string;
  method: string;
  destinationLastFour?: string | null;
  createdAt: string;
  status: string;
  payoutStatus: string;
}

export interface Page {
  requests: Row[];
  total: number;
  page: number;
  pageSize: number;
  counts?: { pendingReview: number; needsReview: number };
  /** The queue's search resolved to players (src/server/composite/withdrawals.ts). */
  players?: { matched: number; more: boolean };
}

export interface Transition {
  status: string;
  at: string;
  by: "player" | "operator" | "provider" | "system" | string;
  operatorId?: string | null;
  reason?: string | null;
}

export interface Outcome {
  source: string;
  answer: string;
  reasonCode?: string | null;
  reference?: string | null;
  operatorId?: string | null;
  at: string;
}

/** A request as payouts answers an operator (OperatorEndpoint.detail). */
export interface Request {
  requestId: string;
  playerId: string;
  kycStatus: string;
  amount: number;
  paidAmount: number;
  voidedAmount?: number;
  currency: string;
  status: string;
  payoutStatus: string;
  terminal?: { id: string; name: string; gateway: string; scheme: string } | null;
  destination?: { destinationId: string; firstName?: string | null; lastName?: string | null; ibanLastFour?: string | null } | null;
  bonusForfeited?: boolean;
  forfeitedAmount?: number;
  clawbackAmount?: number;
  providerReference?: string | null;
  balancesAtRequest?: number | null;
  restrictedAtRequest?: number | null;
  decisions?: { approved: boolean; operatorId: string; at: string }[];
  outcomes?: Outcome[];
  transitions?: Transition[];
  createdAt: string;
  initiatedAt?: string | null;
  settledAt?: string | null;
}

async function read<T>(r: Promise<Response>): Promise<T> {
  const answer = await r;
  if (!answer.ok) throw refused(answer.status, await answer.text());
  return (await answer.json()) as T;
}

/** The queue, through the server, which resolves a name or an email typed in `q` to player ids. */
export const readQueue = (query: Record<string, string>) => read<Page>(fetch(`/api/withdrawals?${new URLSearchParams(query)}`));

export const readRequest = (requestId: string) => read<Request>(payouts.getWithdrawalRequestsByRequestId({ requestId }));

/** A player's requests in every state, newest first (payouts asks no status of the history). */
export const readHistory = (playerId: string, page: number, pageSize: number) =>
  read<Page>(payouts.getPlayersWithdrawalHistory({ playerId }, { query: { page: String(page), pageSize: String(pageSize) } }));

/** The wallet answers its operator reads in major units by the currency's exponent (spec 027 FR-058): back to minor units. */
export const minorOf = (major: string | undefined | null, currency: string): string | undefined => walletMinorText(major, currency);

export interface Balances {
  real?: string;
  restricted?: string;
}

export async function readBalances(player: string, currency: string): Promise<Balances> {
  const b = await read<{ realBalance?: string; bonusBalance?: string }>(wallet.getWalletsBalance({ player, currency }));
  return { real: minorOf(b.realBalance, currency), restricted: minorOf(b.bonusBalance, currency) };
}

export interface Deposit {
  transactionId: string;
  amount?: string;
  status: string;
  createdAt: string;
  paymentReference?: string | null;
}

/** The player's last deposits in the request's currency, newest first. */
export async function readDeposits(player: string, currency: string, count = 5): Promise<Deposit[]> {
  const t = await read<{ transactions: { transactionId: string; amount: string; status: string; createdAt: string; paymentReference?: string | null }[] }>(
    wallet.getWalletsTransactions({ player, currency }, { query: { type: "deposit", page: "0", pageSize: String(count) } }),
  );
  return t.transactions.map((d) => ({ ...d, amount: minorOf(d.amount, currency) }));
}

export const readIdentity = async (playerId: string) => (await read<{ status: string }>(kyc.getByPlayerId({ playerId }))).status;

/** The state an action is offered in (actions/index.ts): a payment needing review apart from one in flight. */
export function actionStateOf(r: Pick<Request, "status" | "payoutStatus">): string {
  const status = r.status.toLowerCase();
  return status === "approved" && r.payoutStatus.toLowerCase() === "needs_review" ? "needs_review" : status;
}

const Unpaid = new Set(["rejected", "cancelled", "failed"]);
const Paid = new Set(["completed", "reversed"]);

/** The amounts a request shows (FR-014): requested always; held, paid, returned and voided where they apply. */
export function amountsOf(r: Request): { which: "requested" | "held" | "paid" | "returned" | "voided"; minor: number }[] {
  const status = r.status.toLowerCase();
  const voided = r.voidedAmount ?? 0;
  const out: { which: "requested" | "held" | "paid" | "returned" | "voided"; minor: number }[] = [{ which: "requested", minor: r.amount }];
  if (Paid.has(status)) out.push({ which: "paid", minor: r.paidAmount });
  else if (Unpaid.has(status)) out.push({ which: "returned", minor: r.paidAmount + voided });
  else out.push({ which: "held", minor: r.paidAmount });
  if (voided > 0) out.push({ which: "voided", minor: voided });
  return out;
}
