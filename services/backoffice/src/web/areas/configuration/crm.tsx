// The brand's CRM (T111; parity *Customer.io integration*): engagement's connection to the processor (its
// site, region, sender and keys) and the transactional message each trigger sends, saved through an Action;
// the processor seeded with the brand's people; and what engagement did with its sends in the last hour.
// engagement answers each key masked (its first 3 characters and `*`); sending the mask back keeps the key,
// so only a key typed again is sent. The defaults are engagement's CrmConfig when unset (config/Model.scala):
// no site, region "us", no sender, no key, no transactional message (FR-034).
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { engagement } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { ConfigForm, type ConfigField, type ConfigValue } from "../../components/config-form.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { ConfigPage, keyOf, Loaded, useRead, useSaved } from "./page.tsx";

interface CrmConfig {
  siteId: string;
  region: string;
  fromEmail: string;
  trackApiKey: string;
  appApiKey: string;
  webhookSigningKey: string;
  triggers: Record<string, { transactionalId: string }>;
}

interface Status {
  configured: boolean;
  processor: string;
  parked: { count: number; oldestAt?: string | null };
  lastHour: Record<string, number>;
}

const Connection: ConfigField[] = [
  { name: "siteId", label: "config.crm.siteId", kind: "text", default: "", hint: "config.crm.siteId.hint" },
  { name: "region", label: "config.crm.region", kind: "choice", choices: ["us", "eu"], choicePrefix: "config.crm.region", default: "us", hint: "config.crm.region.hint" },
  { name: "fromEmail", label: "config.crm.fromEmail", kind: "text", default: "", hint: "config.crm.fromEmail.hint" },
  { name: "trackApiKey", label: "config.crm.trackApiKey", kind: "text", default: "", hint: "config.crm.key.hint" },
  { name: "appApiKey", label: "config.crm.appApiKey", kind: "text", default: "", hint: "config.crm.key.hint" },
  { name: "webhookSigningKey", label: "config.crm.webhookSigningKey", kind: "text", default: "", hint: "config.crm.key.hint" },
];

/** The fields of the connection and one per trigger, in engagement's order, each labelled with its trigger. */
export function crmFields(c: CrmConfig): ConfigField[] {
  return [
    ...Connection,
    ...Object.keys(c.triggers).map((tr): ConfigField => ({ name: `trigger.${tr}`, label: "config.crm.transactionalId", labelValues: { name: word("config.crm.trigger", tr) }, kind: "text", default: "", hint: "config.crm.transactionalId.hint" })),
  ];
}

/** engagement's body: the connection's fields changed, and the triggers changed as `{<trigger>: {transactionalId}}`. */
export function crmBody(change: Record<string, ConfigValue>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  const triggers: Record<string, { transactionalId: ConfigValue }> = {};
  for (const [k, v] of Object.entries(change)) {
    if (k.startsWith("trigger.")) triggers[k.slice("trigger.".length)] = { transactionalId: v };
    else body[k] = v;
  }
  if (Object.keys(triggers).length) body.triggers = triggers;
  return body;
}

function Triggers({ c }: { c: CrmConfig }) {
  const saved = useSaved();
  const values: Record<string, ConfigValue> = {
    siteId: c.siteId,
    region: c.region,
    fromEmail: c.fromEmail,
    trackApiKey: c.trackApiKey,
    appApiKey: c.appApiKey,
    webhookSigningKey: c.webhookSigningKey,
    ...Object.fromEntries(Object.entries(c.triggers).map(([tr, v]) => [`trigger.${tr}`, v.transactionalId])),
  };
  return (
    <>
      <p className="bo-muted">{t("config.crm.triggers")}</p>
      <ConfigForm key={keyOf(values)} fields={crmFields(c)} values={values} action={Actions["crm.configure"]} save={(change) => engagement.putCrmConfig({ body: crmBody(change) })} onDone={saved} />
    </>
  );
}

function Seed({ configured }: { configured: boolean }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  if (readOnly || !configured) return configured ? null : <p>{t("config.crm.seedNeedsSite")}</p>;
  return <Action action={Actions["crm.seed"]} me={me} bags={bags} run={() => engagement.postCrmConfigSeed()} />;
}

export function CrmPage() {
  const me = useOperator();
  const q = useRead<CrmConfig>(["engagement", "crm-config"], () => engagement.getCrmConfig());
  const s = useRead<Status>(["engagement", "status"], () => engagement.getEngagementStatus());
  return (
    <ConfigPage heading="config.crm.title">
      <Loaded q={s}>
        {(st) => (
          <section aria-labelledby="crm-status">
            <h2 id="crm-status">{t("config.crm.status")}</h2>
            <p>{st.configured ? t("config.crm.configured", { name: st.processor }) : t("config.crm.notConfigured", { name: st.processor })}</p>
            <p>{t("config.crm.parked", { count: st.parked.count })}</p>
            <ul>
              {Object.entries(st.lastHour).map(([outcome, n]) => (
                <li key={outcome}>
                  {word("config.crm.outcome", outcome)}: {n}
                </li>
              ))}
            </ul>
          </section>
        )}
      </Loaded>
      <h2>{t("config.crm.connection")}</h2>
      <Loaded q={q}>
        {(c) => (
          <>
            <Triggers c={c} />
            {holds(me, "config.crm") ? <Seed configured={c.siteId !== ""} /> : null}
          </>
        )}
      </Loaded>
    </ConfigPage>
  );
}
