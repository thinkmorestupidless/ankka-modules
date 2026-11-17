// An adjustment of one player's balance (T086; US5, FR-027, parity *Adjustment: credit or debit, real or
// restricted, reason*): a credit or a debit to the real balance or the restricted funds record, an
// amount in the wallet's currency, a reason from the list with free text, and optionally what it
// corrects. The balance before and the balance it would leave are stated before it is confirmed, and
// the wallet's own before and after once it is made. The wallet decides: a debit larger than the
// balance is its refusal, shown as it gives it, never reduced here (FR-007).
//
// Each adjustment is sent under a key of its own. The key is kept while the wallet's answer is not
// known (the server not reached, or the wallet not answering in time), so trying again cannot make it
// twice; once the wallet has answered, made or refused, the next adjustment takes a new key.
import { useId, useState } from "react";
import { useParams } from "@tanstack/react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./corrections.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { parseMoney } from "../../../shared/money.ts";
import { wallet } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money, moneyText } from "../../components/money.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { profileCurrency, profileQuery } from "../players/read.ts";
import { CategorySelect, CauseFields, causeOf, readJson, refusalOf, walletMinor, type Category, type Cause, type CauseKind } from "./shared.tsx";

interface Balance {
  realBalance: string;
  bonusBalance: string;
  currency: string;
}

interface Adjusted {
  before: number;
  after: number;
  walletType: string;
}

interface TransactionRow {
  transactionId: string;
  type: string;
  walletType: string;
  amount: string;
  createdAt: string;
  reason?: string | null;
  category?: string | null;
  cause?: Cause | null;
  actorId?: string | null;
}

const newKey = () => `adjust/${crypto.randomUUID()}`;

/** Typed text as minor units of the currency, above zero; undefined otherwise. */
export function amountOf(text: string, currency: string): bigint | undefined {
  try {
    const minor = parseMoney(text, currency);
    return minor !== undefined && minor > 0n && minor <= BigInt(Number.MAX_SAFE_INTEGER) ? minor : undefined;
  } catch {
    return undefined;
  }
}

function Recent({ player, currency }: { player: string; currency: string }) {
  const tz = useZone();
  const q = useQuery({
    queryKey: ["wallet", "adjustments", player, currency],
    queryFn: () => readJson<{ transactions: TransactionRow[] }>(wallet.getWalletsTransactions({ player, currency }, { query: { type: "admin_adjust", pageSize: "20" } })),
  });
  if (q.isError) return <RefusalNotice refusal={refusalOf(q.error)} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const rows = q.data.transactions.filter((r) => r.type === "admin_adjust");
  return (
    <section aria-labelledby={`recent-${player}`}>
      <h2 id={`recent-${player}`}>{t("corrections.recent")}</h2>
      {rows.length === 0 ? (
        <p className="bo-muted">{t("corrections.recent.none")}</p>
      ) : (
        <div className="bo-list">
          <div className="bo-list-head">
            <ZoneNote zone={tz} />
          </div>
          <table>
            <thead>
              <tr>
                <th>{t("corrections.recent.at")}</th>
                <th data-numeric>{t("corrections.recent.amount")}</th>
                <th>{t("corrections.recent.balance")}</th>
                <th>{t("corrections.recent.reason")}</th>
                <th>{t("corrections.recent.cause")}</th>
                <th>{t("corrections.recent.by")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const minor = walletMinor(r.amount);
                return (
                  <tr key={r.transactionId} data-adjustment={r.transactionId}>
                    <td>
                      <DateTime instant={r.createdAt} zone={tz} />
                    </td>
                    <td data-numeric>{minor === undefined ? r.amount : <Money value={{ minor, currency }} />}</td>
                    <td>{t(r.walletType === "real" ? "corrections.target.real" : "corrections.target.restricted")}</td>
                    <td>
                      {r.category ? word("corrections.category", r.category) : null}
                      {r.reason ? ` · ${r.reason}` : null}
                    </td>
                    <td>{r.cause ? t("corrections.cause.named", { kind: word("corrections.cause", r.cause.kind), reference: r.cause.ref }) : null}</td>
                    <td>{r.actorId ?? null}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Adjuster({ player, currency }: { player: string; currency: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const ids = { direction: useId(), target: useId(), amount: useId() };
  const [direction, setDirection] = useState<"credit" | "debit">("credit");
  const [target, setTarget] = useState<"real" | "restricted">("real");
  const [amountText, setAmountText] = useState("");
  const [category, setCategory] = useState<Category>("compensation");
  const [cause, setCause] = useState<{ kind: CauseKind | ""; ref: string }>({ kind: "", ref: "" });
  const [key, setKey] = useState(newKey);
  const [made, setMade] = useState<Adjusted | undefined>(undefined);

  const balance = useQuery({
    queryKey: ["wallet", "balance", player, currency],
    queryFn: () => readJson<Balance>(wallet.getWalletsBalance({ player, currency })),
  });
  const real = walletMinor(balance.data?.realBalance);
  const restricted = walletMinor(balance.data?.bonusBalance);
  const current = target === "real" ? real : restricted;
  const minor = amountOf(amountText, currency);
  const signed = minor === undefined ? undefined : direction === "debit" ? -minor : minor;
  const named = causeOf(cause.kind, cause.ref);

  const run = async (reason: string | undefined) => {
    const answer = await wallet.postWalletsAdjustments(
      { player, currency },
      { body: { key, walletType: target, amountMinor: Number(signed), reason, category, ...(named ? { cause: named } : {}) } },
    );
    // the wallet answered (made or refused): the next adjustment is another; not answered: the same key again
    if (answer.status !== 503) setKey(newKey());
    return answer;
  };
  const done = async (answer: Response) => {
    const a = (await answer.json()) as Adjusted;
    setMade(a);
    setAmountText("");
    void client.invalidateQueries({ queryKey: ["wallet", "balance", player, currency] });
    void client.invalidateQueries({ queryKey: ["wallet", "adjustments", player, currency] });
  };

  return (
    <div className="bo-corrections">
      {balance.isError ? <RefusalNotice refusal={refusalOf(balance.error)} /> : null}
      <dl className="bo-corrections-balances">
        <div data-balance="real">
          <dt>{t("corrections.balance.real")}</dt>
          <dd>{real === undefined ? "—" : <Money value={{ minor: real, currency }} />}</dd>
        </div>
        <div data-balance="restricted">
          <dt>{t("corrections.balance.restricted")}</dt>
          <dd>{restricted === undefined ? "—" : <Money value={{ minor: restricted, currency }} />}</dd>
        </div>
      </dl>

      {made ? (
        <p role="status" className="bo-corrections-made" data-adjusted>
          {t("corrections.adjust.made", { from: moneyText({ minor: String(made.before), currency }), to: moneyText({ minor: String(made.after), currency }) })}
        </p>
      ) : null}

      <form className="bo-corrections-form" onSubmit={(e) => e.preventDefault()}>
        <fieldset disabled={readOnly}>
          <legend id={ids.direction}>{t("corrections.direction")}</legend>
          {(["credit", "debit"] as const).map((d) => (
            <label key={d} className="bo-corrections-choice">
              <input type="radio" name={`${ids.direction}-d`} value={d} checked={direction === d} onChange={() => setDirection(d)} />
              {t(`corrections.direction.${d}` as MessageKey)}
            </label>
          ))}
        </fieldset>
        <fieldset disabled={readOnly}>
          <legend id={ids.target}>{t("corrections.target")}</legend>
          {(["real", "restricted"] as const).map((w) => (
            <label key={w} className="bo-corrections-choice">
              <input type="radio" name={`${ids.target}-t`} value={w} checked={target === w} onChange={() => setTarget(w)} />
              {t(`corrections.target.${w}` as MessageKey)}
            </label>
          ))}
        </fieldset>
        <div className="bo-field">
          <label htmlFor={ids.amount}>{t("corrections.amount")}</label>
          <span className="bo-corrections-amount">
            <input id={ids.amount} className="bo-input" inputMode="decimal" value={amountText} disabled={readOnly} aria-invalid={amountText !== "" && minor === undefined} onChange={(e) => setAmountText(e.target.value)} />
            <span>{currency}</span>
          </span>
          {amountText !== "" && minor === undefined ? <span className="bo-field-error">{t("corrections.amount.invalid", { currency })}</span> : null}
        </div>
        <CategorySelect value={category} onChange={setCategory} disabled={readOnly} />
        <CauseFields kind={cause.kind} reference={cause.ref} player={player} disabled={readOnly} onChange={(kind, ref) => setCause({ kind, ref })} />
      </form>

      {readOnly ? null : (
        <Action
          action={Actions["wallet.adjust"]}
          me={me}
          bags={bags}
          ready={signed !== undefined && current !== undefined}
          subject={
            signed !== undefined && current !== undefined ? (
              <div data-preview>
                <p>
                  {t(`corrections.direction.${direction}` as MessageKey)} <Money value={{ minor: minor!, currency }} /> · {t(`corrections.target.${target}` as MessageKey)} ·{" "}
                  {word("corrections.category", category)}
                </p>
                <p>{t("corrections.preview", { from: moneyText({ minor: current, currency }), to: moneyText({ minor: current + signed, currency }) })}</p>
                {named ? <p>{t("corrections.cause.named", { kind: word("corrections.cause", named.kind), reference: named.ref })}</p> : null}
              </div>
            ) : null
          }
          run={run}
          onDone={(a) => void done(a)}
        />
      )}

      <Recent player={player} currency={currency} />
    </div>
  );
}

/** A player's balance adjusted in the player's currency as players names it (`player.currency`). */
export function PlayerAdjust({ player }: { player: string }) {
  const profile = useQuery(profileQuery(player));
  const currency = profile.data === undefined ? undefined : profileCurrency(profile.data);
  return (
    <section>
      <h1>{t("corrections.adjust.title")}</h1>
      <p className="bo-muted">{t("corrections.adjust.about")}</p>
      {profile.isError ? (
        <RefusalNotice refusal={refusalOf(profile.error)} />
      ) : !profile.data ? (
        <p role="status">{t("list.loading")}</p>
      ) : currency ? (
        <Adjuster player={player} currency={currency} />
      ) : (
        <p>{t("corrections.noCurrency")}</p>
      )}
    </section>
  );
}

/** `/players/$player/wallets`, until the player's page (T075) puts it in its tabs. */
export function WalletsTab() {
  const player = (useParams({ strict: false }) as { player?: string }).player ?? "";
  return <PlayerAdjust key={player} player={player} />;
}
