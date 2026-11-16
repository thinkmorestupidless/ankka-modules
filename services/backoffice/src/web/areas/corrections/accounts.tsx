// Non-player accounts (T086; parity *Account adjustments*, kept: the original had no screen): the
// wallet's pool and clearing accounts (spec 022 FR-009, FR-012), read by kind, name and currency and
// adjusted with a reason and a category. The reason names no player; the wallet publishes only the
// category. The wallet decides: an account is not taken below its floor, and its refusal is shown as
// it gives it. Each adjustment is under a key of its own, kept until the wallet has answered.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./corrections.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { wallet } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { Money, moneyText } from "../../components/money.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { amountOf } from "./adjust.tsx";
import { CategorySelect, CorrectionsNav, readJson, refusalOf, walletMinor, walletText, type Category } from "./shared.tsx";

interface AccountBalance {
  balance: string;
  creditLine: string;
  floor: string;
  owner: string;
  currency: string;
  txnId?: string | null;
}

type Kind = "pool" | "clearing";
const newKey = () => `account-adjust/${crypto.randomUUID()}`;

function Account({ kind, name, currency }: { kind: Kind; name: string; currency: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const ids = { direction: useId(), amount: useId() };
  const [direction, setDirection] = useState<"credit" | "debit">("credit");
  const [amountText, setAmountText] = useState("");
  const [category, setCategory] = useState<Category>("error_correction");
  const [key, setKey] = useState(newKey);
  const [after, setAfter] = useState<bigint | undefined>(undefined);
  const queryKey = ["wallet", "account", kind, name, currency];
  const q = useQuery({ queryKey, queryFn: () => readJson<AccountBalance>(wallet.getAccountsBalance({ kind, name }, { query: { currency } })) });

  if (q.isError) return <RefusalNotice refusal={refusalOf(q.error)} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const b = q.data;
  const balance = walletMinor(b.balance);
  const minor = amountOf(amountText, currency);
  const signed = minor === undefined ? undefined : direction === "debit" ? -minor : minor;
  const figure = (text: string) => {
    const m = walletMinor(text);
    return m === undefined ? text : <Money value={{ minor: m, currency }} />;
  };

  const run = async (reason: string | undefined) => {
    // the account's route takes the wallet's amount text (minor units over 100), not minor units
    const answer = await wallet.postAccountsAdjustments({ kind, name }, { body: { key, currency, amount: walletText(signed!), reason, category } });
    if (answer.status !== 503) setKey(newKey());
    return answer;
  };
  const done = async (answer: Response) => {
    setAfter(walletMinor(((await answer.json()) as AccountBalance).balance));
    setAmountText("");
    void client.invalidateQueries({ queryKey });
  };

  return (
    <div className="bo-corrections" data-account={`${kind}/${name}/${currency}`}>
      <dl className="bo-corrections-balances">
        <div data-balance="account">
          <dt>{t("corrections.accounts.balance")}</dt>
          <dd>{figure(b.balance)}</dd>
        </div>
        <div>
          <dt>{t("corrections.accounts.creditLine")}</dt>
          <dd>{figure(b.creditLine)}</dd>
        </div>
        <div>
          <dt>{t("corrections.accounts.floor")}</dt>
          <dd>{figure(b.floor)}</dd>
        </div>
        <div>
          <dt>{t("corrections.accounts.owner")}</dt>
          <dd>{b.owner}</dd>
        </div>
      </dl>
      {after !== undefined ? (
        <p role="status" className="bo-corrections-made" data-adjusted>
          {t("corrections.accounts.made", { to: moneyText({ minor: after, currency }) })}
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
        <div className="bo-field">
          <label htmlFor={ids.amount}>{t("corrections.amount")}</label>
          <span className="bo-corrections-amount">
            <input id={ids.amount} className="bo-input" inputMode="decimal" value={amountText} disabled={readOnly} aria-invalid={amountText !== "" && minor === undefined} onChange={(e) => setAmountText(e.target.value)} />
            <span>{currency}</span>
          </span>
          {amountText !== "" && minor === undefined ? <span className="bo-field-error">{t("corrections.amount.invalid", { currency })}</span> : null}
        </div>
        <CategorySelect value={category} onChange={setCategory} disabled={readOnly} />
      </form>
      {readOnly ? null : (
        <Action
          action={Actions["wallet.adjustAccount"]}
          me={me}
          bags={bags}
          ready={signed !== undefined}
          subject={
            signed !== undefined ? (
              <div data-preview>
                <p>
                  {t(`corrections.direction.${direction}` as MessageKey)} <Money value={{ minor: minor!, currency }} /> · {word("corrections.category", category)}
                </p>
                {balance !== undefined ? <p>{t("corrections.preview", { from: moneyText({ minor: balance, currency }), to: moneyText({ minor: balance + signed, currency }) })}</p> : null}
              </div>
            ) : null
          }
          run={run}
          onDone={(a) => void done(a)}
        />
      )}
    </div>
  );
}

export function AccountsPage() {
  const ids = { kind: useId(), name: useId(), currency: useId() };
  const [kind, setKind] = useState<Kind>("pool");
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("");
  const [asked, setAsked] = useState<{ kind: Kind; name: string; currency: string } | undefined>(undefined);
  const valid = /^[A-Za-z0-9._:-]{1,128}$/.test(name.trim()) && /^[A-Z]{3}$/.test(currency.trim());
  return (
    <section>
      <CorrectionsNav here="accounts" />
      <h1>{t("corrections.accounts.title")}</h1>
      <p className="bo-muted">{t("corrections.accounts.about")}</p>
      <form
        className="bo-corrections-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) setAsked({ kind, name: name.trim(), currency: currency.trim() });
        }}
      >
        <div className="bo-field">
          <label htmlFor={ids.kind}>{t("corrections.accounts.kind")}</label>
          <select id={ids.kind} className="bo-input" value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
            <option value="pool">{t("corrections.accounts.kind.pool")}</option>
            <option value="clearing">{t("corrections.accounts.kind.clearing")}</option>
          </select>
        </div>
        <div className="bo-field">
          <label htmlFor={ids.name}>{t("corrections.accounts.name")}</label>
          <input id={ids.name} className="bo-input" value={name} maxLength={128} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="bo-field">
          <label htmlFor={ids.currency}>{t("corrections.currency")}</label>
          <input id={ids.currency} className="bo-input" value={currency} maxLength={3} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
        </div>
        <button type="submit" className="bo-button" disabled={!valid}>
          {t("corrections.accounts.read")}
        </button>
      </form>
      {asked ? <Account key={`${asked.kind}/${asked.name}/${asked.currency}`} {...asked} /> : null}
    </section>
  );
}
