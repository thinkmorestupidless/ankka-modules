// A player's wallet (T076; FR-024, FR-027): the wallet's movements in the player's currency, newest
// first, a page at a time with the wallet's total, each linking to what it names (a round, a payment, a
// reward, an adjustment's cause); and the adjustment (corrections' `PlayerAdjust`, T086) below them.
// `?adjustment=` or `?payment=` marks the movement a link opened.
import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { players, wallet } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { RecordLinks, type RecordRef } from "../../components/links.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { minorOfText } from "../../../shared/money.ts";
import { PlayerAdjust } from "../corrections/adjust.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const PageSize = 50;

interface Movement {
  transactionId: string;
  type: string;
  walletType: string;
  amount: string;
  balanceAfter?: string | null;
  createdAt: string;
  roundId?: string | null;
  awardRef?: string | null;
  paymentReference?: string | null;
  reason?: string | null;
  category?: string | null;
  cause?: { kind: string; ref: string } | null;
  actorId?: string | null;
}

function refsOf(player: string, m: Movement): RecordRef[] {
  const refs: RecordRef[] = [];
  if (m.roundId) refs.push({ kind: "round", player, id: m.roundId });
  if (m.paymentReference) refs.push({ kind: "payment", player, id: m.paymentReference });
  if (m.awardRef) refs.push({ kind: "reward", player, id: m.awardRef });
  if (m.cause?.kind === "round") refs.push({ kind: "round", player, id: m.cause.ref });
  if (m.cause?.kind === "payment") refs.push({ kind: "payment", player, id: m.cause.ref });
  return refs;
}

function Movements({ player, currency }: { player: string; currency: string }) {
  const tz = zone();
  const list = useListState(useRouterListStore());
  const freshness = useFreshness(["wallet"]);
  const marked = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).adjustment ?? (s.location.search as Record<string, unknown>).payment });
  const q = useQuery({
    queryKey: ["wallet", "transactions", player, currency, list.state.page],
    queryFn: () => readJson<{ transactions: Movement[]; total: number }>(wallet.getWalletsTransactions({ player, currency }, { query: { page: String(list.state.page - 1), pageSize: String(PageSize) } })),
  });
  const money = (text: string | null | undefined) => {
    const minor = minorOfText(text, currency);
    return minor === undefined ? (text ?? null) : <Money value={{ minor, currency }} />;
  };
  const columns: Column<Movement>[] = [
    { id: "at", header: "wallets.at", cell: (m) => <DateTime instant={m.createdAt} zone={tz} /> },
    { id: "type", header: "wallets.type", cell: (m) => word("wallets.type", m.type) },
    { id: "wallet", header: "wallets.wallet", cell: (m) => word("corrections.target", m.walletType) },
    { id: "amount", header: "wallets.amount", numeric: true, cell: (m) => money(m.amount) },
    { id: "after", header: "wallets.after", numeric: true, cell: (m) => money(m.balanceAfter) },
    { id: "why", header: "wallets.why", cell: (m) => [m.category ? word("corrections.category", m.category) : null, m.reason].filter(Boolean).join(" · ") },
    { id: "names", header: "record.names", cell: (m) => <RecordLinks refs={refsOf(player, m)} /> },
  ];
  return (
    <>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      <List
        caption="wallets.caption"
        columns={columns}
        rows={q.data?.transactions}
        rowKey={(m) => m.transactionId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        current={typeof marked === "string" ? marked : undefined}
      />
    </>
  );
}

export function PlayerWallets({ player }: { player: string }) {
  const profile = useQuery({
    queryKey: ["players", "profile", player],
    queryFn: () => readJson<{ currency?: string | null; player?: { currency?: string | null } }>(players.getPlayersProfile({ playerId: player })),
  });
  const currency = profile.data ? (profile.data.player?.currency ?? profile.data.currency ?? undefined) : undefined;
  return (
    <section aria-labelledby="wallets-title">
      <h2 id="wallets-title">{t("players.tab.wallets")}</h2>
      {profile.isError ? <RefusalNotice refusal={refusalOf(profile.error)} /> : currency ? <Movements player={player} currency={currency} /> : null}
      <PlayerAdjust player={player} />
    </section>
  );
}
