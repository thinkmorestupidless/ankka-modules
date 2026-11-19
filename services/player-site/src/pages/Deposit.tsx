import { format, fromMajor, type DepositBehaviour, type DepositStep, type PlayerSite, type RefusalCode } from "@eitheror/player-sdk";
import { useBehaviour, useCurrentDeposit } from "@eitheror/player-sdk/react";
import { useEffect, useRef, useState } from "react";
import { useLanguage } from "../app/language.tsx";
import type { TextKey } from "../words/index.ts";
import { Refused } from "./Refused.tsx";
import { Busy, Button, Field } from "./ui.tsx";

/** The deposit overlay: one view per step of the deposit behaviour, and nothing of its rules. */
export function DepositOverlay({ site }: { site: PlayerSite }) {
  const deposit = useCurrentDeposit(site);
  return deposit ? <Sheet deposit={deposit} /> : null;
}

function Sheet({ deposit }: { deposit: DepositBehaviour }) {
  const { t } = useLanguage();
  const step = useBehaviour(deposit);
  const can = (name: string) => (step.allowed as readonly string[]).includes(name);
  return (
    <div className="fixed inset-0 z-20 flex justify-end bg-ink/40" onClick={(e) => e.target === e.currentTarget && can("close") && deposit.actions.close()}>
      <section role="dialog" aria-modal="true" aria-labelledby="deposit-title" className="flex h-full w-full max-w-md flex-col overflow-y-auto bg-mist p-6">
        <header className="mb-6 flex items-center justify-between">
          <h2 id="deposit-title" className="text-2xl font-extrabold tracking-tight">
            {t("deposit.title")}
          </h2>
          {can("close") ? (
            <Button tone="quiet" onClick={() => deposit.actions.close()}>
              {t("action.close")}
            </Button>
          ) : null}
        </header>
        <Step step={step} deposit={deposit} />
        {can("back") ? (
          <Button tone="quiet" className="mt-6 self-start px-0" onClick={() => deposit.actions.back()}>
            {t("action.back")}
          </Button>
        ) : null}
      </section>
    </div>
  );
}

function Step({ step, deposit }: { step: DepositStep; deposit: DepositBehaviour }) {
  const { t, language, refusal } = useLanguage();
  switch (step.kind) {
    case "amount":
      return <AmountStep step={step} deposit={deposit} />;
    case "starting":
      return <Busy text={t("deposit.starting")} />;
    case "refused":
      return (
        <div className="space-y-4">
          <Refused refusal={step.refusal} />
          {(step.allowed as readonly string[]).includes("retry") ? <Button onClick={() => deposit.actions.retry()}>{t("action.retry")}</Button> : null}
        </div>
      );
    case "choosing_terminal":
      return (
        <fieldset>
          <legend className="mb-3 font-semibold">{t("deposit.chooseTerminal")}</legend>
          <ul className="space-y-2">
            {step.terminals.map((terminal) => (
              <li key={terminal.id}>
                <button onClick={() => deposit.actions.choose(terminal.id)} className="w-full rounded-md border border-river bg-white px-4 py-3 text-left font-medium hover:border-leaf">
                  {terminal.name}
                </button>
              </li>
            ))}
          </ul>
        </fieldset>
      );
    case "choosing_coin":
      return (
        <fieldset>
          <legend className="mb-3 font-semibold">{t("deposit.chooseCoin")}</legend>
          <div className="flex flex-wrap gap-2">
            {step.coins.map((coin) => (
              <Button key={coin} onClick={() => deposit.actions.chooseCoin(coin)}>
                {coin}
              </Button>
            ))}
          </div>
        </fieldset>
      );
    case "details":
      return <DetailsStep step={step} deposit={deposit} />;
    case "submitting":
      return <Busy text={t("deposit.submitting")} />;
    case "waiting_at_provider":
      return <AtProvider address={step.address} />;
    case "waiting_on_crypto":
      return (
        <div className="space-y-3">
          <p>{t("deposit.crypto", { cryptoAmount: step.cryptoAmount, coin: step.coin })}</p>
          <p className="rounded-md bg-white p-3 font-mono text-sm break-all select-all">{step.address}</p>
          {step.expiresAt ? <p className="text-sm text-ink/70">{t("deposit.cryptoExpires", { expiresAt: step.expiresAt })}</p> : null}
          <Busy text={t("deposit.waiting")} />
        </div>
      );
    case "waiting":
      return <Busy text={t("deposit.waiting")} />;
    case "succeeded":
      return <p role="status" className="text-xl font-bold">{t("deposit.succeeded", { amount: format(step.amount, language) })}</p>;
    case "failed":
      return (
        <div className="space-y-2">
          <p className="font-semibold">{t("deposit.failed")}</p>
          <div role="alert" className="rounded-md border-l-4 border-cayenne bg-white px-4 py-3">
            <p>{refusal(step.code as RefusalCode, step.fields)}</p>
            {step.words ? <p className="mt-1 text-sm text-ink/70">{step.words}</p> : null}
          </div>
        </div>
      );
    case "closed":
      return null;
  }
}

function AmountStep({ step, deposit }: { step: Extract<DepositStep, { kind: "amount" }>; deposit: DepositBehaviour }) {
  const { t, language } = useLanguage();
  const [other, setOther] = useState("");
  const [bad, setBad] = useState(false);
  return (
    <div className="space-y-6">
      <fieldset>
        <legend className="mb-3 font-semibold">{t("deposit.amount")}</legend>
        <div className="grid grid-cols-3 gap-2">
          {step.presets.map((a) => (
            <button key={String(a.minor)} onClick={() => deposit.actions.confirm(a.minor)} className="amount rounded-md border border-river bg-white py-3 text-lg font-bold hover:border-cayenne">
              {format(a, language)}
            </button>
          ))}
        </div>
      </fieldset>
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          const read = fromMajor(other.replace(",", "."), step.currency);
          setBad(!read.ok);
          if (read.ok) deposit.actions.confirm(read.amount.minor);
        }}
      >
        <div className="flex-1">
          <Field label={t("deposit.other")} inputMode="decimal" value={other} aria-invalid={bad} onChange={(e) => setOther(e.target.value)} />
        </div>
        <Button tone="money" type="submit" disabled={!other}>
          {t("deposit.confirm")}
        </Button>
      </form>
    </div>
  );
}

function DetailsStep({ step, deposit }: { step: Extract<DepositStep, { kind: "details" }>; deposit: DepositBehaviour }) {
  const { t } = useLanguage();
  const [given, setGiven] = useState<Record<string, string>>(() => ({ ...step.known }));
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        deposit.actions.giveDetails(given);
      }}
    >
      <p className="font-semibold">{t("deposit.details")}</p>
      {step.needs.map((name) => (
        <div key={name}>
          <Field label={t(`detail.${name}` as TextKey)} required value={given[name] ?? ""} aria-invalid={step.invalid?.includes(name) ?? false} onChange={(e) => setGiven({ ...given, [name]: e.target.value })} />
          {step.invalid?.includes(name) ? <p className="mt-1 text-sm text-cayenne">{t("deposit.invalid")}</p> : null}
        </div>
      ))}
      <Button tone="money" type="submit">
        {t("deposit.detailsSubmit")}
      </Button>
    </form>
  );
}

/** The provider's page, opened once in a new tab and offered as a link should the browser block it. */
function AtProvider({ address }: { address: string }) {
  const { t } = useLanguage();
  const opened = useRef<string | null>(null);
  useEffect(() => {
    if (opened.current === address) return;
    opened.current = address;
    window.open(address, "_blank", "noopener");
  }, [address]);
  return (
    <div className="space-y-4">
      <p>{t("deposit.atProvider")}</p>
      <a href={address} target="_blank" rel="noopener" className="inline-block rounded-md bg-cayenne px-4 py-2 font-semibold text-white">
        {t("deposit.openProvider")}
      </a>
      <Busy text={t("deposit.waiting")} />
    </div>
  );
}

/** `/cashier/return`: where the provider sends the player back; the deposit's own tab follows the payment. */
export function CashierReturn() {
  const { t } = useLanguage();
  useEffect(() => {
    window.opener?.postMessage({ kind: "eo_cashier_return" }, window.location.origin);
    window.close();
  }, []);
  return <p className="mx-auto max-w-md px-4 py-10">{t("cashierReturn.body")}</p>;
}
