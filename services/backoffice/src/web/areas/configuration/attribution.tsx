// The brand's attribution (T111; spec 012): its windows, the affiliate networks it reads (added, their
// addresses changed, removed, each given its pseudonym key once), the webhooks that carry the brand's facts
// to a network, the keys the brand signs with, and the facts a network has not taken, per network (attribution's
// `undelivered` needs `network=`), each resent or discarded. attribution records the operator on every
// change and takes no reason. Its default windows are 30 days each (settings/SettingsEntity.scala), marked
// where the brand differs (FR-034); a network, a webhook and a key have no default.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { attribution } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { ConfigForm, type ConfigField, type ConfigValue } from "../../components/config-form.tsx";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { ConfigPage, keyOf, Loaded, read, useRead, useSaved } from "./page.tsx";

interface Network {
  id: string;
  keyDocument: string;
  revocationList: string;
  legacyTagDocument?: string;
  codeDocument?: string;
  roundStream: boolean;
  machine?: string;
  pseudonymKeyMade: boolean;
  removed?: string;
  documents: { kind: string; fetchedAt?: string; expiresAt?: string; fresh: boolean; lastError?: string }[];
}
interface Webhook {
  id: string;
  network: string;
  address: string;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}
interface SigningKey {
  kid: string;
  publicKey: string;
  madeAt: string;
  retiredAt?: string;
}
export interface Undelivered {
  delivery: string;
  subscription: string;
  network: string;
  factId: string;
  kind: string;
  attempts: number;
  lastStatus?: number;
  lastError?: string;
  firstAttemptAt?: string;
}

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

const WindowFields: ConfigField[] = [
  { name: "attributionWindowDays", label: "config.attribution.window", kind: "whole", default: 30, hint: "config.attribution.days.hint" },
  { name: "bonusCodeWindowDays", label: "config.attribution.codeWindow", kind: "whole", default: 30, hint: "config.attribution.days.hint" },
];

const NetworkFields: ConfigField[] = [
  { name: "keyDocument", label: "config.attribution.keyDocument", kind: "text" },
  { name: "revocationList", label: "config.attribution.revocationList", kind: "text" },
  { name: "legacyTagDocument", label: "config.attribution.legacyTagDocument", kind: "text", optional: true },
  { name: "codeDocument", label: "config.attribution.codeDocument", kind: "text", optional: true },
  { name: "roundStream", label: "config.attribution.roundStream", kind: "boolean", default: false },
  { name: "machine", label: "config.attribution.machine", kind: "text", optional: true, hint: "config.attribution.machine.hint" },
];

/** A network's body for attribution: an emptied optional document is sent as "" (attribution clears it). */
export function networkBody(change: Record<string, ConfigValue>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(change).map(([k, v]) => [k, v === null ? "" : v]));
}

function Windows() {
  const q = useRead<{ attributionWindowDays: number; bonusCodeWindowDays: number }>(["attribution", "settings"], () => attribution.getSettings());
  const saved = useSaved();
  return (
    <Loaded q={q}>
      {(s) => (
        // attribution takes both windows together
        <ConfigForm key={keyOf(s)} fields={WindowFields} values={s} action={Actions["attribution.windows"]} save={(_, next) => attribution.putSettings({ body: next })} onDone={saved} />
      )}
    </Loaded>
  );
}

function NetworkSection({ n, done }: { n: Network; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = zone();
  const state = n.removed ? "removed" : "live";
  const values: Record<string, ConfigValue> = {
    keyDocument: n.keyDocument,
    revocationList: n.revocationList,
    legacyTagDocument: n.legacyTagDocument ?? null,
    codeDocument: n.codeDocument ?? null,
    roundStream: n.roundStream,
    machine: n.machine ?? null,
  };
  return (
    <section data-network={n.id} aria-labelledby={`network-${n.id}`}>
      <h3 id={`network-${n.id}`}>
        {n.id} {n.removed ? <span className="bo-status">{t("config.attribution.removed")}</span> : null}
      </h3>
      <ul>
        {n.documents.map((d) => (
          <li key={d.kind}>
            {d.kind}: {d.fresh ? t("config.attribution.fresh") : t("config.attribution.stale")}
            {d.fetchedAt ? (
              <>
                {" "}
                <DateTime instant={d.fetchedAt} zone={tz} />
              </>
            ) : null}
            {d.lastError ? <span className="bo-field-error"> {d.lastError}</span> : null}
          </li>
        ))}
      </ul>
      <p>{n.pseudonymKeyMade ? t("config.attribution.pseudonymMade") : t("config.attribution.pseudonymNotMade")}</p>
      {n.removed ? null : <ConfigForm key={keyOf(values)} fields={NetworkFields} values={values} action={Actions["attribution.changeNetwork"]} save={(change) => attribution.putNetworksById({ id: n.id }, { body: networkBody(change) })} onDone={done} />}
      {readOnly ? null : (
        <div className="bo-actions">
          <Action action={Actions["attribution.pseudonymKey"]} me={me} bags={bags} state={n.pseudonymKeyMade || n.removed ? "made" : "none"} run={() => attribution.postNetworksPseudonymKey({ id: n.id })} onDone={done} />
          <Action action={Actions["attribution.removeNetwork"]} me={me} bags={bags} state={state} subject={<p>{n.id}</p>} run={() => attribution.deleteNetworksById({ id: n.id })} onDone={done} />
        </div>
      )}
    </section>
  );
}

function AddNetwork({ done }: { done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const base = useId();
  const [d, setD] = useState({ id: "", keyDocument: "", revocationList: "", legacyTagDocument: "", codeDocument: "", machine: "", roundStream: false });
  const text = (name: "id" | "keyDocument" | "revocationList" | "legacyTagDocument" | "codeDocument" | "machine", label: "config.attribution.networkId" | "config.attribution.keyDocument" | "config.attribution.revocationList" | "config.attribution.legacyTagDocument" | "config.attribution.codeDocument" | "config.attribution.machine") => (
    <div className="bo-field">
      <label htmlFor={`${base}-${name}`}>{t(label)}</label>
      <input id={`${base}-${name}`} className="bo-input" value={d[name]} onChange={(e) => setD({ ...d, [name]: e.target.value })} />
    </div>
  );
  const body = Object.fromEntries(Object.entries(d).filter(([, v]) => v !== "").map(([k, v]) => [k, typeof v === "string" ? v.trim() : v]));
  return (
    <Action
      action={Actions["attribution.addNetwork"]}
      me={me}
      bags={bags}
      ready={d.id.trim() !== "" && d.keyDocument.trim() !== "" && d.revocationList.trim() !== ""}
      subject={
        <>
          {text("id", "config.attribution.networkId")}
          {text("keyDocument", "config.attribution.keyDocument")}
          {text("revocationList", "config.attribution.revocationList")}
          {text("legacyTagDocument", "config.attribution.legacyTagDocument")}
          {text("codeDocument", "config.attribution.codeDocument")}
          {text("machine", "config.attribution.machine")}
          <label>
            <input type="checkbox" checked={d.roundStream} onChange={(e) => setD({ ...d, roundStream: e.target.checked })} /> <span>{t("config.attribution.roundStream")}</span>
          </label>
        </>
      }
      run={() => attribution.postNetworks({ body })}
      onDone={done}
    />
  );
}

function Webhooks({ networks, done }: { networks: Network[]; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const base = useId();
  const q = useRead<{ subscriptions: Webhook[] }>(["attribution", "webhooks"], () => attribution.getWebhooks());
  const live = networks.filter((n) => !n.removed).map((n) => n.id);
  const [d, setD] = useState({ network: "", address: "", secret: "" });
  return (
    <>
      <h2>{t("config.attribution.webhooks")}</h2>
      <Loaded q={q}>
        {(a) => (
          <table className="bo-table">
            <thead>
              <tr>
                <th scope="col">{t("config.attribution.network")}</th>
                <th scope="col">{t("config.attribution.address")}</th>
                <th scope="col">{t("config.attribution.active")}</th>
                <th scope="col">{t("config.callbacks.act")}</th>
              </tr>
            </thead>
            <tbody>
              {a.subscriptions.map((w) => (
                <tr key={w.id} data-webhook={w.id}>
                  <td>{w.network}</td>
                  <td>{w.address}</td>
                  <td>{w.active ? t("config.form.yes") : t("config.form.no")}</td>
                  <td>
                    {readOnly ? null : (
                      <span className="bo-actions">
                        <Action
                          action={w.active ? Actions["attribution.pauseWebhook"] : Actions["attribution.resumeWebhook"]}
                          me={me}
                          bags={bags}
                          subject={<p>{w.address}</p>}
                          run={() => attribution.putWebhooksById({ id: w.id }, { body: { active: !w.active } })}
                          onDone={done}
                        />
                        <Action action={Actions["attribution.removeWebhook"]} me={me} bags={bags} subject={<p>{w.address}</p>} run={() => attribution.deleteWebhooksById({ id: w.id })} onDone={done} />
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Loaded>
      {readOnly || live.length === 0 ? null : (
        <Action
          action={Actions["attribution.addWebhook"]}
          me={me}
          bags={bags}
          ready={d.network !== "" && d.address.trim() !== "" && d.secret !== ""}
          subject={
            <>
              <div className="bo-field">
                <label htmlFor={`${base}-network`}>{t("config.attribution.network")}</label>
                <select id={`${base}-network`} value={d.network} onChange={(e) => setD({ ...d, network: e.target.value })}>
                  <option value="">{t("config.attribution.chooseNetwork")}</option>
                  {live.map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </div>
              <div className="bo-field">
                <label htmlFor={`${base}-address`}>{t("config.attribution.address")}</label>
                <input id={`${base}-address`} className="bo-input" value={d.address} onChange={(e) => setD({ ...d, address: e.target.value })} />
              </div>
              <div className="bo-field">
                <label htmlFor={`${base}-secret`}>{t("config.attribution.secret")}</label>
                <input id={`${base}-secret`} className="bo-input" type="password" value={d.secret} onChange={(e) => setD({ ...d, secret: e.target.value })} />
              </div>
            </>
          }
          run={() => attribution.postWebhooks({ body: { network: d.network, address: d.address.trim(), secret: d.secret } })}
          onDone={done}
        />
      )}
    </>
  );
}

function SigningKeys({ done }: { done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = zone();
  const q = useRead<{ keys: SigningKey[] }>(["attribution", "signing-keys"], () => attribution.getSigningKeys());
  return (
    <>
      <h2>{t("config.attribution.signingKeys")}</h2>
      <Loaded q={q}>
        {(a) => (
          <ul>
            {a.keys.map((k) => (
              <li key={k.kid} data-key={k.kid}>
                <code>{k.kid}</code> {t("config.attribution.madeAt")} <DateTime instant={k.madeAt} zone={tz} />{" "}
                {k.retiredAt ? (
                  <span className="bo-status">
                    {t("config.attribution.retiredAt")} <DateTime instant={k.retiredAt} zone={tz} />
                  </span>
                ) : readOnly ? null : (
                  <Action action={Actions["attribution.retireKey"]} me={me} bags={bags} subject={<p>{k.kid}</p>} run={() => attribution.deleteSigningKeysByKid({ kid: k.kid })} onDone={done} />
                )}
              </li>
            ))}
          </ul>
        )}
      </Loaded>
      {readOnly ? null : <Action action={Actions["attribution.makeKey"]} me={me} bags={bags} run={() => attribution.postSigningKeys()} onDone={done} />}
    </>
  );
}

const PageSize = 50;

function UndeliveredFacts({ networks }: { networks: Network[] }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const tz = zone();
  const pick = useId();
  const network = list.state.filters.network ?? networks[0]?.id ?? "";
  const page = list.state.page;
  const q = useQuery({
    queryKey: ["config", "attribution", "undelivered", network, page],
    queryFn: () => read<{ undelivered: Undelivered[]; total: number }>(attribution.getWebhooksUndelivered({ query: { network, page: String(page), pageSize: String(PageSize) } })),
    enabled: network !== "",
  });
  const done = () => void client.invalidateQueries({ queryKey: ["config", "attribution", "undelivered"] });
  const columns: Column<Undelivered>[] = [
    { id: "first", header: "config.attribution.firstAttempt", cell: (u) => (u.firstAttemptAt ? <DateTime instant={u.firstAttemptAt} zone={tz} /> : null) },
    { id: "kind", header: "config.callbacks.kindHeader", cell: (u) => u.kind },
    { id: "fact", header: "config.attribution.fact", cell: (u) => <code>{u.factId}</code> },
    { id: "attempts", header: "config.attribution.attempts", numeric: true, cell: (u) => u.attempts },
    { id: "error", header: "config.callbacks.error", cell: (u) => `${u.lastStatus ?? ""} ${u.lastError ?? ""}`.trim() },
    ...(readOnly
      ? []
      : [
          {
            id: "act",
            header: "config.callbacks.act" as const,
            cell: (u: Undelivered) => (
              <span className="bo-actions">
                <Action action={Actions["fact.resend"]} me={me} bags={bags} state="undelivered" subject={<p>{u.factId}</p>} run={() => attribution.postWebhooksUndeliveredResend({ subscription: u.subscription, fact: u.factId })} onDone={done} />
                <Action action={Actions["fact.discard"]} me={me} bags={bags} state="undelivered" subject={<p>{u.factId}</p>} run={() => attribution.postWebhooksUndeliveredDiscard({ subscription: u.subscription, fact: u.factId })} onDone={done} />
              </span>
            ),
          },
        ]),
  ];
  return (
    <>
      <h2>{t("config.attribution.undelivered")}</h2>
      {networks.length === 0 ? (
        <p>{t("config.attribution.noNetworks")}</p>
      ) : (
        <>
          <div className="bo-field">
            <label htmlFor={pick}>{t("config.attribution.network")}</label>
            <select id={pick} value={network} onChange={(e) => list.setFilter("network", e.target.value)}>
              {networks.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.id}
                </option>
              ))}
            </select>
          </div>
          {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
          <List
            caption="config.attribution.undelivered"
            columns={columns}
            rows={q.data?.undelivered}
            rowKey={(u) => u.delivery}
            total={q.data?.total}
            complete={q.data ? completeOf({ page, pageSize: PageSize, total: q.data.total }) : false}
            fetching={q.isFetching}
            zone={tz}
            list={list}
          />
        </>
      )}
    </>
  );
}

export function AttributionPage() {
  const me = useOperator();
  const tz = zone();
  const saved = useSaved();
  const q = useRead<{ networks: Network[] }>(["attribution", "networks"], () => attribution.getNetworks());
  const configures = holds(me, "config.attribution");
  return (
    <ConfigPage heading="config.attribution.title">
      <ZoneNote zone={tz} />
      {configures ? (
        <>
          <h2>{t("config.attribution.windows")}</h2>
          <Windows />
        </>
      ) : null}
      <Loaded q={q}>
        {(a) => (
          <>
            {configures ? (
              <>
                <h2>{t("config.attribution.networks")}</h2>
                {a.networks.length === 0 ? <p>{t("config.attribution.noNetworks")}</p> : null}
                {a.networks.map((n) => (
                  <NetworkSection key={n.id} n={n} done={saved} />
                ))}
                <AddNetwork done={saved} />
                <Webhooks networks={a.networks} done={saved} />
                <SigningKeys done={saved} />
              </>
            ) : null}
            {holds(me, "fact.redeliver") ? <UndeliveredFacts networks={a.networks.filter((n) => !n.removed)} /> : null}
          </>
        )}
      </Loaded>
    </ConfigPage>
  );
}
