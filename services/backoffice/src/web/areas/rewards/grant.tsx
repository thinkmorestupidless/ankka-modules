// An award to a player (T091; US6, FR-005, FR-006, parity *Grant from an offer; grant by hand (amount, or
// free rounds)*): one of the brand's offers, sized on a deposit when one is given; a bonus by hand (an
// amount, a wagering multiplier, the most it may convert); or free rounds by hand (a game, the rounds,
// the value of a round, where the winnings go). rewards decides (OperatorAwards.scala): an award by hand
// is held to the brand's duplicate and open-reward rules only, and a refusal is shown with its reason and
// what would allow it, as rewards names them (`allowedBy`, refusals.ts).
//
// The brand's offers are read only by an operator who may manage them (rewards' `GET /offers` admits
// `offer.manage`); another operator names the offer by its id.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./rewards.css";
import { holds } from "../../../shared/access.ts";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { parseMoney } from "../../../shared/money.ts";
import { rewards } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { Money } from "../../components/money.tsx";
import { useOperator } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readOffers } from "./read.ts";

type How = "offer" | "bonus" | "freeRounds";
const Hows: [How, MessageKey][] = [
  ["offer", "rewards.grant.fromOffer"],
  ["bonus", "rewards.grant.bonus"],
  ["freeRounds", "rewards.grant.freeRounds"],
];
const Destinations = ["real_balance", "bonus_balance"] as const;

/** Typed text as minor units of the currency, above zero (or zero when allowed); undefined otherwise. */
function minorOf(text: string, currency: string, zero = false): number | undefined {
  try {
    const m = parseMoney(text, currency);
    if (m === undefined || m < 0n || (!zero && m === 0n) || m > BigInt(Number.MAX_SAFE_INTEGER)) return undefined;
    return Number(m);
  } catch {
    return undefined;
  }
}

/** A whole number from the text, at least `min`; undefined otherwise. */
function wholeOf(text: string, min = 0): number | undefined {
  if (!/^\d+$/.test(text.trim())) return undefined;
  const n = Number(text.trim());
  return Number.isSafeInteger(n) && n >= min ? n : undefined;
}

interface Form {
  how: How;
  offerId: string;
  deposit: string;
  currency: string;
  amount: string;
  wager: string;
  /** The most a bonus may convert, an amount in the bonus's currency. */
  maxConvertible: string;
  /** The most free rounds' winnings may convert: a field of its own, so text typed for a bonus never changes meaning. */
  roundsMaxConvertible: string;
  gameId: string;
  provider: string;
  spinCount: string;
  spinValue: string;
  destination: (typeof Destinations)[number];
  expiryDays: string;
}

const empty = (currency: string): Form => ({
  how: "offer",
  offerId: "",
  deposit: "",
  currency,
  amount: "",
  wager: "",
  maxConvertible: "",
  roundsMaxConvertible: "",
  gameId: "",
  provider: "",
  spinCount: "",
  spinValue: "",
  destination: "real_balance",
  expiryDays: "",
});

/**
 * The award's body as rewards reads it (OperatorAwards.award), or undefined while the form is incomplete.
 * Free rounds carry no currency: rewards reads their amounts as minor units of the player's currency, so
 * they are typed as amounts in it when the player's currency is known, else as whole minor units.
 */
export function bodyOf(f: Form, offerCurrency: string | null | undefined, playerCurrency?: string): Record<string, unknown> | undefined {
  const cur = f.currency.trim().toUpperCase();
  switch (f.how) {
    case "offer": {
      if (f.offerId.trim() === "") return undefined;
      if (f.deposit.trim() === "") return { offerId: f.offerId.trim() };
      const deposit = offerCurrency ? minorOf(f.deposit, offerCurrency) : undefined;
      return deposit === undefined ? undefined : { offerId: f.offerId.trim(), depositAmount: deposit };
    }
    case "bonus": {
      if (!/^[A-Z]{3}$/.test(cur)) return undefined;
      const bonusAmount = minorOf(f.amount, cur);
      const wager = wholeOf(f.wager);
      if (bonusAmount === undefined || wager === undefined) return undefined;
      const body: Record<string, unknown> = { bonusAmount, wager, currency: cur };
      if (f.maxConvertible.trim() !== "") {
        const max = minorOf(f.maxConvertible, cur);
        if (max === undefined) return undefined;
        body.maxConvertibleAmount = max;
      }
      return body;
    }
    case "freeRounds": {
      const roundsMinor = (text: string) => (playerCurrency ? minorOf(text, playerCurrency) : wholeOf(text, 1));
      const spinCount = wholeOf(f.spinCount, 1);
      const spinValue = roundsMinor(f.spinValue);
      if (f.gameId.trim() === "" || spinCount === undefined || spinValue === undefined) return undefined;
      const body: Record<string, unknown> = { type: "FREE_SPINS", gameId: f.gameId.trim(), spinCount, spinValue, winningsDestination: f.destination };
      if (f.provider.trim() !== "") body.provider = f.provider.trim();
      if (f.destination === "bonus_balance") {
        const max = roundsMinor(f.roundsMaxConvertible);
        const wager = f.wager.trim() === "" ? 0 : wholeOf(f.wager);
        if (max === undefined || wager === undefined) return undefined;
        body.maxConvertibleAmount = max;
        body.wageringMultiplier = wager;
      }
      if (f.expiryDays.trim() !== "") {
        const days = wholeOf(f.expiryDays, 1);
        if (days === undefined) return undefined;
        body.freeSpinsExpiryDays = days;
      }
      return body;
    }
  }
}

function Field({ named, value, onChange, inputMode, hint }: { named: MessageKey; value: string; onChange(v: string): void; inputMode?: "decimal" | "numeric"; hint?: MessageKey }) {
  const id = useId();
  const hintId = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(named)}</label>
      <input id={id} className="bo-input" value={value} inputMode={inputMode} aria-describedby={hint ? hintId : undefined} onChange={(e) => onChange(e.target.value)} />
      {hint ? (
        <span id={hintId} className="bo-field-hint">
          {t(hint)}
        </span>
      ) : null}
    </div>
  );
}

/** The amounts the award would give, as money, so the operator reads them back before confirming (FR-013, FR-025). */
function AmountsOf({ body, currency }: { body: Record<string, unknown> | undefined; currency: string | undefined }) {
  if (!body || !currency) return null;
  const shown = (["depositAmount", "bonusAmount", "spinValue", "maxConvertibleAmount"] as const).filter((k) => typeof body[k] === "number");
  if (shown.length === 0) return null;
  return (
    <dl className="bo-grant-amounts" data-preview>
      {shown.map((k) => (
        <div key={k}>
          <dt>{t(`rewards.grant.amounts.${k}`)}</dt>
          <dd>
            <Money value={{ minor: body[k] as number, currency }} />
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** The award's form: what to award and its values. */
function GrantForm({ form, set, canList, playerCurrency }: { form: Form; set(f: Partial<Form>): void; canList: boolean; playerCurrency?: string }) {
  const roundsAmount = playerCurrency ? ({ inputMode: "decimal", hint: undefined } as const) : ({ inputMode: "numeric", hint: "rewards.grant.minorUnits" } as const);
  const ids = { how: useId(), offer: useId(), destination: useId() };
  const offers = useQuery({ queryKey: ["rewards", "offers"], queryFn: readOffers, enabled: canList });
  const chosen = offers.data?.find((o) => o.id === form.offerId);
  return (
    <div className="bo-grant-form">
      <fieldset>
        <legend id={ids.how}>{t("rewards.grant.how")}</legend>
        {Hows.map(([h, label]) => (
          <label key={h} className="bo-choice">
            <input type="radio" name={`${ids.how}-h`} value={h} checked={form.how === h} onChange={() => set({ how: h })} />
            {t(label)}
          </label>
        ))}
      </fieldset>
      {form.how === "offer" ? (
        <>
          {canList ? (
            <div className="bo-field">
              <label htmlFor={ids.offer}>{t("rewards.grant.offer")}</label>
              <select id={ids.offer} value={form.offerId} onChange={(e) => set({ offerId: e.target.value })}>
                <option value="">{t("rewards.grant.chooseOffer")}</option>
                {(offers.data ?? []).map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.active ? o.name : `${o.name} (${t("offers.inactive")})`}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <Field named="rewards.grant.offerId" value={form.offerId} onChange={(v) => set({ offerId: v })} hint="rewards.grant.offersNeed" />
          )}
          {chosen?.currency ? <Field named="rewards.grant.deposit" value={form.deposit} inputMode="decimal" onChange={(v) => set({ deposit: v })} /> : null}
        </>
      ) : null}
      {form.how === "bonus" ? (
        <>
          <Field named="rewards.grant.currency" value={form.currency} onChange={(v) => set({ currency: v })} />
          <Field named="rewards.grant.amount" value={form.amount} inputMode="decimal" onChange={(v) => set({ amount: v })} />
          <Field named="rewards.grant.wager" value={form.wager} inputMode="numeric" onChange={(v) => set({ wager: v })} />
          <Field named="rewards.grant.maxConvertible" value={form.maxConvertible} inputMode="decimal" onChange={(v) => set({ maxConvertible: v })} />
        </>
      ) : null}
      {form.how === "freeRounds" ? (
        <>
          <Field named="rewards.grant.game" value={form.gameId} onChange={(v) => set({ gameId: v })} />
          <Field named="rewards.grant.provider" value={form.provider} onChange={(v) => set({ provider: v })} />
          <Field named="rewards.grant.spinCount" value={form.spinCount} inputMode="numeric" onChange={(v) => set({ spinCount: v })} />
          <Field named="rewards.grant.spinValue" value={form.spinValue} inputMode={roundsAmount.inputMode} onChange={(v) => set({ spinValue: v })} hint={roundsAmount.hint} />
          <fieldset>
            <legend id={ids.destination}>{t("rewards.grant.destination")}</legend>
            {Destinations.map((d) => (
              <label key={d} className="bo-choice">
                <input type="radio" name={`${ids.destination}-d`} value={d} checked={form.destination === d} onChange={() => set({ destination: d })} />
                {t(`rewards.destination.${d}`)}
              </label>
            ))}
          </fieldset>
          {form.destination === "bonus_balance" ? (
            <>
              <Field named="rewards.grant.maxConvertible" value={form.roundsMaxConvertible} inputMode={roundsAmount.inputMode} onChange={(v) => set({ roundsMaxConvertible: v })} hint={roundsAmount.hint} />
              <Field named="rewards.grant.wager" value={form.wager} inputMode="numeric" onChange={(v) => set({ wager: v })} />
            </>
          ) : null}
          <Field named="rewards.grant.expiryDays" value={form.expiryDays} inputMode="numeric" onChange={(v) => set({ expiryDays: v })} />
        </>
      ) : null}
    </div>
  );
}

/** "Award a reward" for the player: the form in the confirmation, below the award's effect. */
export function GrantReward({ player, currency = "" }: { player: string; currency?: string }) {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const canList = holds(me, "offer.manage");
  const [form, setForm] = useState<Form>(() => empty(currency));
  const [granted, setGranted] = useState(false);
  const offers = useQuery({ queryKey: ["rewards", "offers"], queryFn: readOffers, enabled: canList });
  const offerCurrency = offers.data?.find((o) => o.id === form.offerId)?.currency;
  const playerCurrency = currency.trim().toUpperCase() || undefined;
  const body = bodyOf(form, offerCurrency, playerCurrency);
  const bodyCurrency = form.how === "offer" ? (offerCurrency ?? undefined) : form.how === "bonus" ? form.currency.trim().toUpperCase() : playerCurrency;
  const set = (f: Partial<Form>) => {
    setGranted(false);
    setForm((prev) => ({ ...prev, ...f }));
  };
  const run = () => rewards.postRewardGrantsByPlayerId({ playerId: player }, { body: body ?? {} });
  const done = () => {
    setGranted(true);
    setForm(empty(currency));
    void client.invalidateQueries({ queryKey: ["rewards", "player", player] });
  };
  return (
    <div className="bo-grant">
      <Action action={Actions["reward.grant"]} me={me} bags={bags} subject={
          <>
            <GrantForm form={form} set={set} canList={canList} playerCurrency={playerCurrency} />
            <AmountsOf body={body} currency={bodyCurrency} />
          </>
        } ready={body !== undefined} run={run} onDone={done} />
      {granted ? (
        <p role="status" data-granted>
          {t("rewards.grant.granted")}
        </p>
      ) : null}
    </div>
  );
}
