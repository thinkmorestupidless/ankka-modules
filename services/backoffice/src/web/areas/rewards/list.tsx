// The brand's rewards (T090; US6, parity *Brand-wide reward list with search and state*): rewards'
// `reward-grants`, newest first as rewards orders them, searched across the player, the reward, the offer
// id and the offer name, and filtered by state; its total and its place kept in the URL. A row opens the
// reward on its player's rewards.
import { useId, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useRouter, useRouterState } from "@tanstack/react-router";
import "./rewards.css";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { hrefOf } from "../../components/links.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column, type ListState } from "../../components/list/index.ts";
import { Money, moneyText } from "../../components/money.tsx";
import { statusWord } from "../../components/status.tsx";
import { useOperator } from "../../frame/context.ts";
import { word } from "../word.ts";
import { PageSize, readRewards, type Reward } from "./read.ts";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/** rewards' states (RewardStatus), as the list filters them. */
export const States = ["AVAILABLE", "PENDING", "ACTIVE", "CONVERTED_PENDING_CAP", "CLOSED", "EXPIRED", "CANCELLED", "FORFEITED", "REVOKED"] as const;

/** The query rewards is asked, from the list's place. */
export function queryOf(s: ListState, pageSize = PageSize): Record<string, string> {
  const q: Record<string, string> = { page: String(s.page), pageSize: String(pageSize) };
  if (s.filters.q) q.search = s.filters.q;
  if (s.filters.state) q.status = s.filters.state;
  return q;
}

const columns = (tz: string): Column<Reward>[] => [
  { id: "granted", header: "rewards.record.granted", cell: (r) => (r.grantedAt ? <DateTime instant={r.grantedAt} zone={tz} /> : null) },
  { id: "player", header: "rewards.record.player", cell: (r) => <code>{r.playerId}</code> },
  { id: "offer", header: "rewards.record.offer", cell: (r) => r.offerName || r.offerId },
  { id: "type", header: "rewards.record.type", cell: (r) => word("rewards.type", r.type.toLowerCase()) },
  { id: "state", header: "rewards.record.state", cell: (r) => statusWord(r.status.toLowerCase()) },
  {
    id: "value",
    header: "rewards.record.value",
    numeric: true,
    cell: (r) =>
      r.freeSpins ? (
        <>
          {t("rewards.freeRounds.worth", { count: r.freeSpins.spinCount, amount: moneyText({ minor: r.freeSpins.spinValue, currency: r.currency }) })}
          {" · "}
          {t("rewards.freeRounds.used", { used: r.freeSpins.spinsUsed, given: r.freeSpins.spinCount })}
        </>
      ) : (
        <Money value={{ minor: r.faceValue, currency: r.currency }} />
      ),
  },
  {
    id: "wagering",
    header: "rewards.record.wagering",
    numeric: true,
    cell: (r) => (r.wageringRequirement ? t("rewards.wagering.required", { amount: moneyText({ minor: r.wageringRequirement, currency: r.currency }) }) : t("rewards.wagering.none")),
  },
  { id: "expires", header: "rewards.record.expires", cell: (r) => (r.expiresAt ? <DateTime instant={r.expiresAt} zone={tz} /> : null) },
];

function Search({ value, onSearch }: { value: string; onSearch(q: string | undefined): void }) {
  const id = useId();
  const [typed, setTyped] = useState(value);
  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    onSearch(typed.trim() === "" ? undefined : typed.trim());
  };
  return (
    <form className="bo-field" role="search" onSubmit={submit}>
      <label htmlFor={id}>{t("rewards.list.search")}</label>
      <input id={id} className="bo-input" type="search" value={typed} maxLength={256} onChange={(e) => setTyped(e.target.value)} />
      <button type="submit" className="bo-button">
        {t("rewards.list.searchGo")}
      </button>
    </form>
  );
}

export function RewardsPage() {
  const tz = zone();
  const me = useOperator();
  const router = useRouter();
  const store = useRouterListStore();
  const list = useListState(store);
  const stateId = useId();
  const at = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).at });
  const freshness = useFreshness(["rewards"]);
  const query = queryOf(list.state);
  const q = useQuery({ queryKey: ["rewards", "list", query], queryFn: () => readRewards(query) });
  const f = list.state.filters;
  // opening a row keeps the place (`at`) in the list's entry, then opens the reward on its player's rewards
  const open = (r: Reward) =>
    void router
      .navigate({ to: router.state.location.pathname, search: (prev: Record<string, unknown>) => ({ ...prev, at: r.rewardId }), replace: true } as never)
      .then(() => router.history.push(hrefOf({ kind: "reward", player: r.playerId, id: r.rewardId })));
  return (
    <section className="bo-rewards">
      <h1>{t("area.rewards")}</h1>
      {holds(me, "offer.manage") ? (
        <p>
          <Link to="/offers" className="bo-link">
            {t("rewards.list.offers")}
          </Link>
        </p>
      ) : null}
      <div className="bo-rewards-filters">
        <Search key={f.q ?? ""} value={f.q ?? ""} onSearch={(v) => list.setFilter("q", v)} />
        <div className="bo-field">
          <label htmlFor={stateId}>{t("rewards.list.state")}</label>
          <select id={stateId} value={f.state ?? ""} onChange={(e) => list.setFilter("state", e.target.value === "" ? undefined : e.target.value)}>
            <option value="">{t("rewards.list.any")}</option>
            {States.map((s) => (
              <option key={s} value={s}>
                {statusWord(s.toLowerCase())}
              </option>
            ))}
          </select>
        </div>
      </div>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="rewards.list.caption"
        columns={columns(tz)}
        rows={q.data?.items}
        rowKey={(r) => r.rewardId}
        total={q.data?.total}
        complete={q.data ? completeOf(q.data) : false}
        fetching={q.isFetching}
        freshness={freshness}
        zone={tz}
        list={list}
        onOpen={open}
        current={typeof at === "string" ? at : undefined}
      />
    </section>
  );
}
