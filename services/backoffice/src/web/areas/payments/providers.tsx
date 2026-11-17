// The providers' configurations of the sub-brand worked on (T112; spec 004 FR-006, FR-008; the original's
// PSPs page): each a provider's credential under a name and an environment, its secrets kept by merchant
// and never answered again (only which keys are set, masked). Made, its secrets replaced, activated,
// disabled, deleted, and tried with a test payment (spec 030 H4): one payment of the provider's smallest
// amount in its own test mode, which merchant records nowhere: no deposit, no payment, nothing credited.
import { useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { Choice, Field, Rows, useChanged, usePayments, type Col } from "./parts.tsx";
import { NeedsSubBrand, useScope } from "./hierarchy.tsx";

/** psp-gateway's providers, as their wire names (`Provider`). */
export const Providers = ["DREAMZPAY", "UNITYFINANCE", "T365", "COZENI", "EUPAYMENTZ", "GIGADAT", "BOUNCEMONEY", "DIGITARIUM", "MORPHOSIS"] as const;
const Environments = ["production", "sandbox"] as const;

export interface Credential {
  configId: string;
  provider: string;
  name: string;
  environment: string;
  status: string;
  scheme?: string | null;
}

/** `key=value` lines as the secrets merchant keeps; a line without `=` does not read. */
export function secretsOf(text: string): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const line of text.split("\n").map((l) => l.trim()).filter((l) => l !== "")) {
    const at = line.indexOf("=");
    if (at <= 0) return undefined;
    out[line.slice(0, at).trim()] = line.slice(at + 1).trim();
  }
  return out;
}

/** The test payment's answer: the provider's outcome to the initiation (spec 030 H4). */
export interface TestAnswer {
  outcome: "accepted" | "refused" | "unavailable" | string;
  provider?: string;
  testMode?: string;
  amount?: string;
  currency?: string;
  reference?: string;
  error?: string;
  message?: string;
  reason?: string;
}

/** The outcome in the operator's words. */
export function testWords(a: TestAnswer): string {
  const values = { gateway: a.provider ?? "", amount: a.amount ?? "", currency: a.currency ?? "", reference: a.reference ?? "", reason: a.message || a.reason || a.error || "" };
  if (a.outcome === "accepted") return t("payments.test.accepted", values);
  if (a.outcome === "refused") return t("payments.test.refused", values);
  if (a.outcome === "unavailable") return t("payments.test.unavailable", values);
  return t("payments.test.other", { ...values, outcome: a.outcome });
}

function CredentialActions({ subBrand, c, onTested }: { subBrand: string; c: Credential; onTested(a: TestAnswer): void }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged("credentials", subBrand);
  const [secrets, setSecrets] = useState("");
  const [currency, setCurrency] = useState("");
  const p = { id: subBrand, provider: c.provider };
  const named = { query: { name: c.name } };
  const parsed = secretsOf(secrets);
  const subject = <p>{t("payments.providers.subject", { gateway: c.provider, name: c.name, value: c.environment })}</p>;
  return (
    <span className="bo-actions">
      <Action
        action={Actions["payments.credential.test"]}
        me={me}
        bags={bags}
        subject={
          <>
            {subject}
            <Field labelKey="payments.test.currency" value={currency} set={setCurrency} hint={t("payments.test.currencyHint")} />
          </>
        }
        run={() => merchant.postSubBrandsPspCredentialsTestPayment(p, { ...named, body: currency.trim() === "" ? {} : { currency: currency.trim().toUpperCase() } })}
        onDone={(answer) => void answer.json().then((a: TestAnswer) => onTested(a))}
      />
      <Action
        action={Actions["payments.credential.replace"]}
        me={me}
        bags={bags}
        ready={parsed !== undefined && Object.keys(parsed).length > 0}
        subject={
          <>
            {subject}
            <Field labelKey="payments.providers.secrets" kind="longtext" value={secrets} set={setSecrets} hint={t("payments.providers.secretsHint")} />
          </>
        }
        run={() => merchant.putSubBrandsPspCredentialsByProvider(p, { ...named, body: { credentials: parsed } })}
        onDone={() => {
          setSecrets("");
          done();
        }}
      />
      <Action action={Actions["payments.credential.activate"]} me={me} bags={bags} state={c.status} subject={subject} run={() => merchant.postSubBrandsPspCredentialsActivate(p, named)} onDone={done} />
      <Action action={Actions["payments.credential.disable"]} me={me} bags={bags} state={c.status} subject={subject} run={() => merchant.postSubBrandsPspCredentialsDisable(p, named)} onDone={done} />
      <Action action={Actions["payments.credential.delete"]} me={me} bags={bags} subject={subject} run={() => merchant.deleteSubBrandsPspCredentialsByProvider(p, named)} onDone={done} />
    </span>
  );
}

function CreateCredential({ subBrand }: { subBrand: string }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged("credentials", subBrand);
  const [provider, setProvider] = useState<string>(Providers[0]);
  const [name, setName] = useState("");
  const [environment, setEnvironment] = useState<string>("production");
  const [scheme, setScheme] = useState("");
  const [secrets, setSecrets] = useState("");
  const parsed = secretsOf(secrets);
  return (
    <Action
      action={Actions["payments.credential.create"]}
      me={me}
      bags={bags}
      ready={name.trim() !== "" && parsed !== undefined}
      subject={
        <>
          <Choice labelKey="payments.providers.provider" value={provider} set={setProvider} choices={Providers} />
          <Field labelKey="payments.providers.name" value={name} set={setName} />
          <Choice labelKey="payments.providers.environment" value={environment} set={setEnvironment} choices={Environments} word={(e) => word("payments.providers.env", e)} />
          <Field labelKey="payments.providers.scheme" value={scheme} set={setScheme} hint={t("payments.providers.schemeHint")} />
          <Field labelKey="payments.providers.secrets" kind="longtext" value={secrets} set={setSecrets} hint={t("payments.providers.secretsHint")} />
        </>
      }
      run={() =>
        merchant.postSubBrandsPspCredentials(
          { id: subBrand },
          { body: { provider, name: name.trim(), environment, ...(scheme.trim() ? { scheme: scheme.trim() } : {}), credentials: parsed ?? {} } },
        )
      }
      onDone={() => {
        setName("");
        setSecrets("");
        done();
      }}
    />
  );
}

export function ProvidersSection() {
  const scope = useScope();
  if (scope.level !== "subBrand" || !scope.id) return <NeedsSubBrand />;
  return <ProviderList key={scope.id} subBrand={scope.id} />;
}

function ProviderList({ subBrand }: { subBrand: string }) {
  const readOnly = useReadOnlyMode();
  const q = usePayments<{ credentials: Credential[] }>(["credentials", subBrand], () => merchant.getSubBrandsPspCredentials({ id: subBrand }));
  const [tested, setTested] = useState<{ c: Credential; a: TestAnswer } | undefined>(undefined);
  const cols: Col<Credential>[] = [
    { header: "payments.providers.provider", cell: (c) => c.provider },
    { header: "payments.providers.name", cell: (c) => c.name },
    { header: "payments.providers.environment", cell: (c) => word("payments.providers.env", c.environment) },
    { header: "payments.providers.scheme", cell: (c) => c.scheme ?? "" },
    { header: "payments.hierarchy.status", cell: (c) => <Status value={c.status} /> },
    ...(readOnly ? [] : [{ header: "payments.act" as const, cell: (c: Credential) => <CredentialActions subBrand={subBrand} c={c} onTested={(a) => setTested({ c, a })} /> }]),
  ];
  return (
    <div>
      <p className="bo-muted">{t("payments.providers.note")}</p>
      {tested ? (
        <p role="status" data-test-outcome={tested.a.outcome}>
          {t("payments.test.of", { gateway: tested.c.provider, name: tested.c.name })} {testWords(tested.a)}
        </p>
      ) : null}
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      {q.data ? <Rows caption="payments.providers.caption" cols={cols} rows={q.data.credentials} rowKey={(c) => c.configId} empty="payments.providers.none" /> : null}
      {readOnly ? null : <CreateCredential subBrand={subBrand} />}
    </div>
  );
}
