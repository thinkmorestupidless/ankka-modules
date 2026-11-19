// A player's wallet (T076; FR-024, FR-027): the wallet's movements in the player's currency, newest
// first, a page at a time with the wallet's total, each linking to what it names (a round, a payment, a
// reward, an adjustment's cause); the player's own turnover terms for withdrawals (the wallet's
// `turnover-override`), set or cleared back to the brand's; and the adjustment (corrections'
// `PlayerAdjust`, T086) below them. `?adjustment=` or `?payment=` marks the movement a link opened.
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { wallet } from "../../../shared/owners/index.ts";
import { profileCurrency, profileQuery } from "./read.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { RecordLinks, type RecordRef } from "../../components/links.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Money } from "../../components/money.tsx";
import { walletMinorText } from "../../../shared/money.ts";
import { PlayerAdjust } from "../corrections/adjust.tsx";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";

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
  const tz = useZone();
  const list = useListState(useRouterListStore());
  const freshness = useFreshness(["wallet"]);
  const marked = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).adjustment ?? (s.location.search as Record<string, unknown>).payment });
  const q = useQuery({
    queryKey: ["wallet", "transactions", player, currency, list.state.page],
    queryFn: () => readJson<{ transactions: Movement[]; total: number }>(wallet.getWalletsTransactions({ player, currency }, { query: { page: String(list.state.page - 1), pageSize: String(PageSize) } })),
  });
  const money = (text: string | null | undefined) => {
    const minor = walletMinorText(text, currency);
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

/** How the wallet counts turnover (TurnoverTerms): the brand's choices, as its configuration offers them. */
const Definitions = ["gross_wagered", "net_loss"] as const;

/**
 * The player's turnover terms in this currency (the original's *Turnover override*): a multiplier and
 * how turnover is counted, in place of the brand's, or cleared back to the brand's. The wallet answers
 * the brand's terms (`wallet-config`) but not a player's override, so the page shows the brand's and
 * what the operator last set here.
 */
function TurnoverOverride({ player, currency }: { player: string; currency: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const multiplierId = useId();
  const definitionId = useId();
  const brand = useQuery({
    queryKey: ["wallet", "wallet-config", "turnover"],
    queryFn: () => readJson<{ turnover?: { multiplier: number; definition: string } }>(wallet.getWalletConfig()),
  });
  const [multiplier, setMultiplier] = useState("");
  const [definition, setDefinition] = useState<string>("gross_wagered");
  const [set, setSet] = useState<{ multiplier: number; definition: string } | null | undefined>(undefined);
  const whole = /^\d{1,6}$/.test(multiplier.trim());
  const terms = brand.data?.turnover;
  return (
    <section aria-labelledby="turnover-title" data-turnover-override>
      <h3 id="turnover-title">{t("wallets.turnover.title")}</h3>
      {terms ? (
        <p>{t("wallets.turnover.brand", { value: terms.multiplier, basis: word("config.withdrawals.definition", terms.definition) })}</p>
      ) : brand.isError ? (
        <RefusalNotice refusal={refusalOf(brand.error)} />
      ) : null}
      {set ? <p role="status">{t("wallets.turnover.set", { value: set.multiplier, basis: word("config.withdrawals.definition", set.definition) })}</p> : null}
      {set === null ? <p role="status">{t("wallets.turnover.cleared")}</p> : null}
      {readOnly ? null : (
        <div className="bo-actions">
          <Action
            action={Actions["wallet.turnoverOverride"]}
            me={me}
            bags={bags}
            ready={whole}
            subject={
              <>
                <div className="bo-field">
                  <label htmlFor={multiplierId}>{t("wallets.turnover.multiplier")}</label>
                  <input id={multiplierId} className="bo-input" inputMode="numeric" value={multiplier} onChange={(e) => setMultiplier(e.target.value)} />
                </div>
                <div className="bo-field">
                  <label htmlFor={definitionId}>{t("config.withdrawals.definition")}</label>
                  <select id={definitionId} value={definition} onChange={(e) => setDefinition(e.target.value)}>
                    {Definitions.map((d) => (
                      <option key={d} value={d}>
                        {word("config.withdrawals.definition", d)}
                      </option>
                    ))}
                  </select>
                </div>
                <p className="bo-muted">{t("wallets.turnover.currency", { currency })}</p>
              </>
            }
            run={() => wallet.putWalletsTurnoverOverride({ player, currency }, { body: { multiplier: Number(multiplier.trim()), definition } })}
            onDone={() => {
              setSet({ multiplier: Number(multiplier.trim()), definition });
              setMultiplier("");
            }}
          />
          <Action
            action={Actions["wallet.clearTurnoverOverride"]}
            me={me}
            bags={bags}
            subject={<p className="bo-muted">{t("wallets.turnover.currency", { currency })}</p>}
            run={() => wallet.putWalletsTurnoverOverride({ player, currency }, { body: {} })}
            onDone={() => setSet(null)}
          />
        </div>
      )}
    </section>
  );
}

export function PlayerWallets({ player }: { player: string }) {
  const profile = useQuery(profileQuery(player));
  const currency = profile.data ? profileCurrency(profile.data) : undefined;
  return (
    <section aria-labelledby="wallets-title">
      <h2 id="wallets-title">{t("players.tab.wallets")}</h2>
      {profile.isError ? <RefusalNotice refusal={refusalOf(profile.error)} /> : currency ? (
        <>
          <Movements player={player} currency={currency} />
          <TurnoverOverride key={currency} player={player} currency={currency} />
        </>
      ) : null}
      <PlayerAdjust player={player} />
    </section>
  );
}
