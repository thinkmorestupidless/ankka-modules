// A player's rewards (T090; US6, FR-026, parity *A player's rewards with wagering progress*, *Forfeit or
// reclaim*): every reward of the player, newest first, each restricted funds record with its state in the
// glossary's words, its wagering progress and its open round count (the wallet's open record, read beside
// rewards' list), free rounds as rounds used of rounds given, and exactly the actions its state allows,
// each stating its effect beside it and again before it is done. An award (grant.tsx) is offered above.
//
// The wallet keeps a record's progress and open rounds only while it is open (active or awaiting its cap):
// once forfeited or closed the page says so rather than showing a figure the wallet no longer holds.
import { useId } from "react";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import "../rewards/rewards.css";
import { t } from "../../../shared/messages/index.ts";
import { rewards } from "../../../shared/owners/index.ts";
import { Actions, offeredIn, type ActionId } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useFreshness } from "../../components/freshness.tsx";
import { FreshnessNote } from "../../components/list/index.ts";
import { Money, moneyText } from "../../components/money.tsx";
import { Status, type Tone } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { GrantReward } from "../rewards/grant.tsx";
import { isOpen, progressOf, readOpenRecords, readPlayerRewards, stateOf, type OpenRecord, type Reward } from "../rewards/read.ts";
import { word } from "../word.ts";

const Offered = ["reward.forfeit", "reward.reclaim"] as const satisfies readonly ActionId[];

/** The tone of a reward's state: its word says it, the mark only repeats it. */
const toneOf = (state: string): Tone =>
  state === "active" ? "positive" : state === "converted_pending_cap" || state === "pending" ? "waiting" : state === "forfeited" || state === "revoked" ? "negative" : "neutral";

function Wagering({ r, open }: { r: Reward; open: OpenRecord | undefined }) {
  if (r.freeSpins) {
    const f = r.freeSpins;
    return f.winnings > 0 ? <>{t("rewards.freeRounds.won", { amount: moneyText({ minor: f.winnings, currency: r.currency }) })}</> : null;
  }
  if (!isOpen(r) || (open === undefined && (r.wageredAmount === null || r.wageredAmount === undefined))) return <>{t("rewards.wagering.closed")}</>;
  const requirement = open?.wageringRequirement ?? r.wageringRequirement ?? 0;
  const wagered = open?.releaseProgress ?? r.wageredAmount ?? 0;
  const progress = progressOf(wagered, requirement);
  if (progress === undefined) return <>{t("rewards.wagering.none")}</>;
  return (
    <>
      <progress max={100} value={progress} aria-hidden />{" "}
      {t("rewards.wagering.progress", { progress, amount: moneyText({ minor: wagered, currency: r.currency }), to: moneyText({ minor: requirement, currency: r.currency }) })}
    </>
  );
}

function OpenRounds({ r, open, unread }: { r: Reward; open: OpenRecord | undefined; unread: boolean }) {
  if (!isOpen(r)) return <>{t("rewards.openRounds.closed")}</>;
  if (open) return <>{t("rewards.openRounds.count", { count: open.openRoundCount })}</>;
  return <>{unread ? t("rewards.openRounds.unread") : t("rewards.openRounds.closed")}</>;
}

function RewardCard({ r, open, unread, current }: { r: Reward; open: OpenRecord | undefined; unread: boolean; current: boolean }) {
  const tz = useZone();
  const id = useId();
  const state = stateOf(r);
  return (
    <article className="bo-reward" data-reward={r.rewardId} aria-labelledby={id} aria-current={current ? "true" : undefined}>
      <h2 id={id}>{r.offerName || word("rewards.type", r.type.toLowerCase())}</h2>
      <dl className="bo-reward-facts">
        <div data-field="state">
          <dt>{t("rewards.record.state")}</dt>
          <dd>
            <Status value={state} tone={toneOf(state)} />
            {r.isPrimary === true ? <span className="bo-muted"> {t("rewards.record.primary")}</span> : null}
            {r.isPrimary === false && state === "active" ? <span className="bo-muted"> {t("rewards.record.notPrimary")}</span> : null}
          </dd>
        </div>
        <div data-field="type">
          <dt>{t("rewards.record.type")}</dt>
          <dd>{word("rewards.type", r.type.toLowerCase())}</dd>
        </div>
        <div data-field="value">
          <dt>{t("rewards.record.value")}</dt>
          <dd>
            {r.freeSpins ? (
              t("rewards.freeRounds.worth", { count: r.freeSpins.spinCount, amount: moneyText({ minor: r.freeSpins.spinValue, currency: r.currency }) })
            ) : (
              <Money value={{ minor: r.faceValue, currency: r.currency }} />
            )}
          </dd>
        </div>
        {r.freeSpins ? (
          <div data-field="freeRounds">
            <dt>{t("rewards.record.freeRounds")}</dt>
            <dd>{t("rewards.freeRounds.used", { used: r.freeSpins.spinsUsed, given: r.freeSpins.spinCount })}</dd>
          </div>
        ) : null}
        <div data-field="wagering">
          <dt>{t("rewards.record.wagering")}</dt>
          <dd>
            <Wagering r={r} open={open} />
          </dd>
        </div>
        {r.freeSpins ? null : (
          <div data-field="openRounds">
            <dt>{t("rewards.record.openRounds")}</dt>
            <dd>
              <OpenRounds r={r} open={open} unread={unread} />
            </dd>
          </div>
        )}
        {r.grantedAt ? (
          <div data-field="granted">
            <dt>{t("rewards.record.granted")}</dt>
            <dd>
              <DateTime instant={r.grantedAt} zone={tz} />
            </dd>
          </div>
        ) : null}
        {r.expiresAt ? (
          <div data-field="expires">
            <dt>{t("rewards.record.expires")}</dt>
            <dd>
              <DateTime instant={r.expiresAt} zone={tz} />
            </dd>
          </div>
        ) : null}
        {r.source ? (
          <div data-field="source">
            <dt>{t("rewards.record.source")}</dt>
            <dd>{word("rewards.source", r.source)}</dd>
          </div>
        ) : null}
      </dl>
      <RewardActions r={r} />
    </article>
  );
}

/** Exactly the actions the reward's state allows (FR-026), each with its effect stated beside it. */
function RewardActions({ r }: { r: Reward }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const id = useId();
  const state = stateOf(r);
  const offered = Offered.filter((a) => offeredIn(Actions[a], state));
  const done = () => void client.invalidateQueries({ queryKey: ["rewards", "player", r.playerId] });
  const revoke = (mode: "forfeit" | "reclaim") => async (reason: string | undefined) => {
    const answer = await rewards.postRewardGrantsRevoke({ rewardId: r.rewardId }, { body: { mode, ...(reason ? { reason } : {}) } });
    // a reward that moved on is read again
    if (answer.status === 409 || answer.status === 404) done();
    return answer;
  };
  const calls: Record<(typeof Offered)[number], (reason: string | undefined) => Promise<Response>> = {
    "reward.forfeit": revoke("forfeit"),
    "reward.reclaim": revoke("reclaim"),
  };
  const subject = r.freeSpins ? (
    t("rewards.freeRounds.used", { used: r.freeSpins.spinsUsed, given: r.freeSpins.spinCount })
  ) : (
    <Money value={{ minor: r.faceValue, currency: r.currency }} />
  );
  return (
    <section className="bo-reward-actions" aria-labelledby={id}>
      <h3 id={id}>{t("rewards.player.actions")}</h3>
      {offered.length === 0 ? (
        <p className="bo-muted">{t("rewards.player.noActions")}</p>
      ) : readOnly ? (
        <p className="bo-muted">{t("rewards.player.readOnly")}</p>
      ) : (
        <ul>
          {offered.map((a) => (
            <li key={a}>
              <Action action={Actions[a]} me={me} bags={bags} state={state} subject={subject} run={calls[a]} onDone={done} />
              <p className="bo-muted" data-effect-of={a}>
                {t(Actions[a].effect)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function PlayerRewards({ player }: { player: string }) {
  const tz = useZone();
  const readOnly = useReadOnlyMode();
  const freshness = useFreshness(["rewards"]);
  const opened = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).reward });
  const q = useQuery({ queryKey: ["rewards", "player", player], queryFn: () => readPlayerRewards(player), enabled: player !== "" });
  const items = q.data?.items ?? [];
  // the wallet's open records, in each currency a still-open reward is in
  const currencies = [...new Set(items.filter(isOpen).map((r) => r.currency))];
  const records = useQueries({
    queries: currencies.map((currency) => ({ queryKey: ["rewards", "player", player, "records", currency], queryFn: () => readOpenRecords(player, currency) })),
  });
  const byGrant = new Map<string, OpenRecord>();
  const unread = new Set<string>();
  records.forEach((rq, i) => {
    if (rq.isError) unread.add(currencies[i]!);
    for (const rec of rq.data ?? []) byGrant.set(rec.grantRef, rec);
  });
  const unmatched = q.data?.unmatchedRounds?.length ?? 0;
  return (
    <section className="bo-rewards">
      <h1>{t("rewards.player.title", { player })}</h1>
      <div className="bo-rewards-head">
        <ZoneNote zone={tz} />
        {freshness ? <FreshnessNote freshness={freshness} zone={tz} /> : null}
      </div>
      {readOnly ? null : <GrantReward player={player} />}
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {unmatched > 0 ? (
        <p className="bo-muted" role="status">
          {t("rewards.player.unmatched", { count: unmatched })}
        </p>
      ) : null}
      {q.data && items.length === 0 ? <p className="bo-muted">{t("rewards.player.none")}</p> : null}
      {!q.data && !q.isError ? <p role="status">{t("list.loading")}</p> : null}
      <div className="bo-reward-list" aria-label={t("rewards.player.caption")} role="list">
        {items.map((r) => (
          <div role="listitem" key={r.rewardId}>
            <RewardCard r={r} open={byGrant.get(r.rewardId)} unread={unread.has(r.currency)} current={typeof opened === "string" && opened === r.rewardId} />
          </div>
        ))}
      </div>
    </section>
  );
}
