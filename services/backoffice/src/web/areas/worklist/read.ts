// The worklist as the page's server answers it (`/api/worklist`, src/server/composite/worklist.ts), and
// what the page makes of it: the items of every kind read, merged oldest first; where an item is
// investigated; a withdrawal request as payouts answers it, and whether someone has acted on it.
import { explain } from "../../../shared/refusals.ts";
import { hrefOf } from "../../components/links.tsx";

export interface Item {
  id: string;
  kind: string;
  subject: { player?: string };
  since: string;
  amount?: { minor: number; currency: string };
  detail: Record<string, string | number | null>;
}

export type KindAnswer =
  | { kind: string; state: "ok"; total: number; page: number; items: Item[] }
  | { kind: string; state: "unavailable"; reason: string };

export interface Worklist {
  pageSize: number;
  kinds: KindAnswer[];
}

async function read<T>(r: Promise<Response>): Promise<T> {
  const answer = await r;
  if (!answer.ok) throw explain(answer.status, await answer.text());
  return (await answer.json()) as T;
}

/** The operator's kinds (all their roles admit when `kinds` is undefined). */
export const readWorklist = (kinds: readonly string[] | undefined) =>
  read<Worklist>(fetch(`/api/worklist${kinds === undefined ? "" : `?kinds=${encodeURIComponent(kinds.join(","))}`}`));

export const readKindPage = (kind: string, page: number) =>
  read<KindAnswer & { pageSize: number }>(fetch(`/api/worklist/${encodeURIComponent(kind)}?page=${page}`));

/** An item's key in the list and in the URL. */
export const keyOf = (i: Pick<Item, "kind" | "id">) => `${i.kind}:${i.id}`;

/** Every item of the kinds read, oldest first; two with the same time keep their kinds' order. */
export function merged(kinds: readonly KindAnswer[]): Item[] {
  const items = kinds.flatMap((k) => (k.state === "ok" ? k.items : []));
  return items.map((it, i) => [it, i] as const).sort(([a, i], [b, j]) => Date.parse(a.since) - Date.parse(b.since) || i - j).map(([it]) => it);
}

/** Where an item's investigation is: the record it is, or the player it is about. */
export function investigationOf(item: Item): string | undefined {
  const player = item.subject.player;
  const e = encodeURIComponent;
  switch (item.kind) {
    case "withdrawals_pending":
    case "payouts_needing_review":
      return hrefOf({ kind: "withdrawal", id: item.id });
    case "documents_awaiting":
    case "players_flagged":
      return player ? `/players/${e(player)}/identity` : undefined;
    case "protection_cases":
      return player ? hrefOf({ kind: "case", player, id: item.id }) : undefined;
    case "fraud_cases":
      return hrefOf({ kind: "fraudCase", id: item.id });
    default:
      return player ? hrefOf({ kind: "player", id: player }) : undefined;
  }
}

/** The key the operator is put at once an item is gone: the one that took its place, else the one before. */
export function placeAfter(rows: readonly Item[], gone: string): string | undefined {
  const at = rows.findIndex((r) => keyOf(r) === gone);
  if (at < 0) return undefined;
  const next = rows[at + 1] ?? rows[at - 1];
  return next ? keyOf(next) : undefined;
}

/** A withdrawal request as payouts answers it (OperatorEndpoint.detail). */
export interface Request {
  requestId: string;
  playerId: string;
  amount: number;
  currency: string;
  status: string;
  payoutStatus: string;
  createdAt: string;
  decisions?: { approved: boolean; operatorId: string; at: string }[];
  outcomes?: { source: string; answer: string; at: string; operatorId?: string | null }[];
}

/** The state an action on the item is offered in, as the actions name them (lower case). */
export const stateOf = (r: Request) => r.status.toLowerCase();

/** Whether the item still waits for what its kind is for. */
export function stillOpen(r: Request, kind: string): boolean {
  return kind === "payouts_needing_review" ? r.payoutStatus.toLowerCase() === "needs_review" : r.status.toLowerCase() === "pending_review";
}

/** Who acted on a request no longer waiting, and what they did: its last decision or outcome by an operator. */
export function actedOn(r: Request): { what: "approved" | "rejected" | "settled"; operator: string; at: string } | undefined {
  const decision = r.decisions?.at(-1);
  const outcome = [...(r.outcomes ?? [])].reverse().find((o) => o.operatorId);
  if (outcome && (!decision || Date.parse(outcome.at) > Date.parse(decision.at))) return { what: "settled", operator: outcome.operatorId!, at: outcome.at };
  if (decision) return { what: decision.approved ? "approved" : "rejected", operator: decision.operatorId, at: decision.at };
  return undefined;
}
