// The terminals of the sub-brand worked on (T112; spec 004 FR-007, FR-009; the original's Create Terminal
// form): each a gateway's scheme under a name, its properties as merchant's catalogue asks for them, the
// secret ones kept by merchant and shown only as set (masked), and the directions it takes. Made, its
// properties and directions changed, activated, disabled and deleted; merchant refuses deleting one the
// sub-brand's live routing tree names (`TERMINAL_IN_USE`).
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

/** The gateways merchant's catalogue serves (spec 005; ConnecX dropped, Digitarium has no terminal). */
export const Gateways = ["DREAMZPAY", "UNITYFINANCE", "T365", "COZENI", "MORPHOSIS", "EUPAYMENTZ", "GIGADAT", "BOUNCEMONEY"] as const;

export interface Integration {
  integrationId: string;
  gateway: string;
  scheme: string;
  name: string;
  status: string;
  regions?: string[];
  propertyKeys?: string[];
  missingRequiredProperties?: string[];
  propertyPreviews?: Record<string, string>;
  supportsDeposit: boolean;
  supportsWithdrawal: boolean;
  requiresPayoutDestination: boolean;
}

interface Catalog {
  gateway: string;
  schemes: { pspId: string; category: string; requiredProperties: { key: string; label: string; required: boolean; multiline: boolean; secret: boolean }[] }[];
}

function Properties({ gateway, scheme, values, set }: { gateway: string; scheme: string; values: Record<string, string>; set(v: Record<string, string>): void }) {
  const catalog = usePayments<Catalog>(["catalog", gateway], () => merchant.getCatalogGatewayByGateway({ gateway }));
  const props = catalog.data?.schemes.find((s) => s.pspId === scheme)?.requiredProperties ?? [];
  if (catalog.isError) return <RefusalNotice refusal={catalog.error} />;
  return (
    <>
      {props.map((p) => (
        <Field
          key={p.key}
          labelKey="payments.terminals.property"
          values={{ name: p.label }}
          kind={p.secret ? "password" : p.multiline ? "longtext" : "text"}
          value={values[p.key] ?? ""}
          set={(v) => set({ ...values, [p.key]: v })}
          hint={[p.required ? t("payments.terminals.required") : null, p.secret ? t("payments.terminals.secret") : null].filter(Boolean).join(" ")}
        />
      ))}
    </>
  );
}

function CreateTerminal({ subBrand }: { subBrand: string }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged("terminals", subBrand);
  const [gateway, setGateway] = useState<string>(Gateways[0]);
  const catalog = usePayments<Catalog>(["catalog", gateway], () => merchant.getCatalogGatewayByGateway({ gateway }));
  const schemes = (catalog.data?.schemes ?? []).map((s) => s.pspId);
  const [scheme, setScheme] = useState("");
  const [name, setName] = useState("");
  const [regions, setRegions] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const chosen = scheme || schemes[0] || "";
  return (
    <Action
      action={Actions["payments.terminal.create"]}
      me={me}
      bags={bags}
      ready={name.trim() !== "" && chosen !== ""}
      subject={
        <>
          <Choice labelKey="payments.terminals.gateway" value={gateway} set={(g) => {
              setGateway(g);
              setScheme("");
              setValues({});
            }} choices={Gateways} />
          <Choice labelKey="payments.terminals.scheme" value={chosen} set={setScheme} choices={schemes} />
          <Field labelKey="payments.terminals.name" value={name} set={setName} />
          <Field labelKey="payments.terminals.regions" value={regions} set={setRegions} hint={t("payments.terminals.regionsHint")} />
          <Properties gateway={gateway} scheme={chosen} values={values} set={setValues} />
        </>
      }
      run={() =>
        merchant.postMerchantsGatewaysIntegrations(
          { id: subBrand, gateway },
          {
            body: {
              scheme: chosen,
              name: name.trim(),
              regions: regions.split(",").map((r) => r.trim().toUpperCase()).filter((r) => r !== ""),
              properties: Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")),
            },
          },
        )
      }
      onDone={() => {
        setName("");
        setValues({});
        done();
      }}
    />
  );
}

function TerminalActions({ subBrand, x }: { subBrand: string; x: Integration }) {
  const me = useOperator();
  const bags = useBags();
  const done = useChanged("terminals", subBrand);
  const [values, setValues] = useState<Record<string, string>>({});
  const [deposit, setDeposit] = useState(x.supportsDeposit);
  const [withdrawal, setWithdrawal] = useState(x.supportsWithdrawal);
  const [destination, setDestination] = useState(x.requiresPayoutDestination);
  const p = { id: subBrand, tid: x.integrationId };
  const subject = <p>{t("payments.terminals.subject", { name: x.name, gateway: x.gateway, method: x.scheme })}</p>;
  const box = (label: Parameters<typeof t>[0], v: boolean, set: (b: boolean) => void) => (
    <label>
      <input type="checkbox" checked={v} onChange={(e) => set(e.target.checked)} /> {t(label)}
    </label>
  );
  return (
    <span className="bo-actions">
      <Action
        action={Actions["payments.terminal.properties"]}
        me={me}
        bags={bags}
        ready={Object.values(values).some((v) => v.trim() !== "")}
        subject={
          <>
            {subject}
            <p className="bo-muted">{t("payments.terminals.propertiesNote")}</p>
            <Properties gateway={x.gateway} scheme={x.scheme} values={values} set={setValues} />
          </>
        }
        run={() => merchant.putMerchantsGatewaysIntegrationsProperties(p, { body: { properties: Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")) } })}
        onDone={() => {
          setValues({});
          done();
        }}
      />
      <Action
        action={Actions["payments.terminal.capability"]}
        me={me}
        bags={bags}
        subject={
          <fieldset>
            <legend>{subject}</legend>
            {box("payments.terminals.deposit", deposit, setDeposit)}
            {box("payments.terminals.withdrawal", withdrawal, setWithdrawal)}
            {box("payments.terminals.destination", destination, setDestination)}
          </fieldset>
        }
        run={() => merchant.putMerchantsGatewaysIntegrationsCapability(p, { body: { supportsDeposit: deposit, supportsWithdrawal: withdrawal, requiresPayoutDestination: destination } })}
        onDone={done}
      />
      <Action action={Actions["payments.terminal.activate"]} me={me} bags={bags} state={x.status} subject={subject} run={() => merchant.postMerchantsGatewaysIntegrationsActivate(p)} onDone={done} />
      <Action action={Actions["payments.terminal.disable"]} me={me} bags={bags} state={x.status} subject={subject} run={() => merchant.postMerchantsGatewaysIntegrationsDisable(p)} onDone={done} />
      <Action action={Actions["payments.terminal.delete"]} me={me} bags={bags} subject={subject} run={() => merchant.deleteMerchantsGatewaysIntegrationsByTid(p)} onDone={done} />
    </span>
  );
}

export function TerminalsSection() {
  const scope = useScope();
  if (scope.level !== "subBrand" || !scope.id) return <NeedsSubBrand />;
  return <TerminalList key={scope.id} subBrand={scope.id} />;
}

function TerminalList({ subBrand }: { subBrand: string }) {
  const readOnly = useReadOnlyMode();
  const q = usePayments<{ integrations: Integration[] }>(["terminals", subBrand], () => merchant.getMerchantsGatewaysIntegrations({ id: subBrand }));
  const yes = (b: boolean) => (b ? t("config.form.yes") : t("config.form.no"));
  const cols: Col<Integration>[] = [
    { header: "payments.terminals.name", cell: (x) => x.name },
    { header: "payments.terminals.gateway", cell: (x) => x.gateway },
    { header: "payments.terminals.scheme", cell: (x) => x.scheme },
    { header: "payments.hierarchy.status", cell: (x) => <Status value={x.status} /> },
    { header: "payments.terminals.regions", cell: (x) => (x.regions ?? []).join(", ") },
    {
      header: "payments.terminals.directions",
      cell: (x) => t("payments.terminals.directionsOf", { from: yes(x.supportsDeposit), to: yes(x.supportsWithdrawal) }),
    },
    {
      header: "payments.terminals.properties",
      cell: (x) => (
        <>
          {Object.entries(x.propertyPreviews ?? {}).map(([k, v]) => `${k}: ${v}`).join("; ")}
          {(x.missingRequiredProperties ?? []).length > 0 ? (
            <span className="bo-status" data-tone="warning">
              {t("payments.terminals.missing", { fields: (x.missingRequiredProperties ?? []).join(", ") })}
            </span>
          ) : null}
        </>
      ),
    },
    ...(readOnly ? [] : [{ header: "payments.act" as const, cell: (x: Integration) => <TerminalActions subBrand={subBrand} x={x} /> }]),
  ];
  return (
    <div>
      <p className="bo-muted">{t("payments.terminals.note")}</p>
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      {q.data ? <Rows caption="payments.terminals.caption" cols={cols} rows={q.data.integrations} rowKey={(x) => x.integrationId} empty="payments.terminals.none" /> : null}
      {readOnly ? null : <CreateTerminal subBrand={subBrand} />}
      <Routable subBrand={subBrand} />
    </div>
  );
}

interface Routable {
  terminalEntityId: string;
  gateway: string;
  name: string;
  status: string;
  integrationType: "credential" | "integration";
  supportsDeposit: boolean;
  supportsWithdrawal: boolean;
  requiresPayoutDestination: boolean;
}

/** What routing may offer here and which ways each goes: the credential-named terminals and the terminals together. */
function Routable({ subBrand }: { subBrand: string }) {
  const q = usePayments<{ terminals: Routable[] }>(["terminals", subBrand, "routable"], () => merchant.getPaymentsTerminals({ query: { merchantId: subBrand } }));
  const yes = (b: boolean) => (b ? t("config.form.yes") : t("config.form.no"));
  const cols: Col<Routable>[] = [
    { header: "payments.terminals.name", cell: (x) => x.terminalEntityId },
    { header: "payments.terminals.gateway", cell: (x) => x.gateway },
    { header: "payments.terminals.kind", cell: (x) => word("payments.terminals.kindOf", x.integrationType) },
    { header: "payments.hierarchy.status", cell: (x) => <Status value={x.status} /> },
    { header: "payments.terminals.deposit", cell: (x) => yes(x.supportsDeposit) },
    { header: "payments.terminals.withdrawal", cell: (x) => yes(x.supportsWithdrawal) },
    { header: "payments.terminals.destination", cell: (x) => yes(x.requiresPayoutDestination) },
  ];
  return (
    <section aria-labelledby="pay-routable">
      <h2 id="pay-routable">{t("payments.terminals.routable")}</h2>
      <p className="bo-muted">{t("payments.terminals.routableNote")}</p>
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      {q.data ? <Rows caption="payments.terminals.routable" cols={cols} rows={q.data.terminals} rowKey={(x) => x.terminalEntityId} empty="payments.terminals.none" /> : null}
    </section>
  );
}
