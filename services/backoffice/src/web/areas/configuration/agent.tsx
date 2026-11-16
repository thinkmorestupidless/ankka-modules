// Motoko, the brand's support agent (T111; spec 017): support-agent's configuration, changed field by
// field (a field left out keeps its value), with its status and the last changes made. support-agent
// answers its own defaults as version 0 (`versions/0`), so each field is marked where it differs from them
// (FR-034). support-agent refuses switching Motoko on for a real-money brand without the safeguarding
// message and helpline (`safeguarding_not_configured`).
import { t } from "../../../shared/messages/index.ts";
import { supportAgent } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { ConfigForm, type ConfigField, type ConfigValue } from "../../components/config-form.tsx";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { ConfigPage, keyOf, Loaded, useRead, useSaved } from "./page.tsx";

type Body = Record<string, ConfigValue>;

interface AgentConfig {
  version: number;
  config: Body;
  recentChanges: { version: number; by: string; fields: string[]; values: Record<string, string>; at: string }[];
}

interface AgentStatus {
  enabled: boolean;
  realMoney: boolean;
  safeguarding: "on" | "off";
  judged: "on" | "off";
  budget: { used: number; limit: number; spent: boolean };
  conversations: { open: number; awaiting: number; lastDay: number };
}

/** support-agent's ConfigBody (config/Model.scala), in the order an administrator reads it. */
export const AgentFields: Omit<ConfigField, "default">[] = [
  { name: "enabled", label: "config.agent.enabled", kind: "boolean" },
  { name: "model", label: "config.agent.model", kind: "text" },
  { name: "prompt", label: "config.agent.prompt", kind: "longtext" },
  { name: "handOffMessage", label: "config.agent.handOffMessage", kind: "longtext", hint: "config.agent.handOffMessage.hint" },
  { name: "supportHours", label: "config.agent.supportHours", kind: "text" },
  { name: "unavailableMessage", label: "config.agent.unavailableMessage", kind: "longtext" },
  { name: "closingMessage", label: "config.agent.closingMessage", kind: "longtext" },
  { name: "safeguardingOptIn", label: "config.agent.safeguardingOptIn", kind: "boolean" },
  { name: "safeguardingMessage", label: "config.agent.safeguardingMessage", kind: "longtext", optional: true },
  { name: "helpline", label: "config.agent.helpline", kind: "text", optional: true },
  { name: "personalFields", label: "config.agent.personalFields", kind: "list", hint: "config.agent.personalFields.hint" },
  { name: "maxInputChars", label: "config.agent.maxInputChars", kind: "whole" },
  { name: "turnsPerMinute", label: "config.agent.turnsPerMinute", kind: "whole" },
  { name: "dailyTokens", label: "config.agent.dailyTokens", kind: "whole" },
  { name: "approvalMinutes", label: "config.agent.approvalMinutes", kind: "whole" },
  { name: "retentionDays", label: "config.agent.retentionDays", kind: "whole" },
];

/** The fields with their defaults from version 0; a field version 0 lacks has none. */
export function agentFields(defaults: Body | undefined): ConfigField[] {
  return AgentFields.map((f) => (defaults && f.name in defaults ? { ...f, default: defaults[f.name] ?? null } : f));
}


export function AgentPage() {
  const q = useRead<AgentConfig>(["support-agent", "config"], () => supportAgent.getConfig());
  const defaults = useRead<Body>(["support-agent", "versions", 0], () => supportAgent.getConfigVersionsByVersion({ version: "0" }));
  const s = useRead<AgentStatus>(["support-agent", "status"], () => supportAgent.getConfigStatus());
  const saved = useSaved();
  const tz = useZone();
  return (
    <ConfigPage heading="config.agent.title">
      <Loaded q={s}>
        {(st) => (
          <section aria-labelledby="agent-status">
            <h2 id="agent-status">{t("config.agent.status")}</h2>
            <ul>
              <li>{st.enabled ? t("config.agent.on") : t("config.agent.off")}</li>
              <li>{t("config.agent.safeguardingState", { value: st.safeguarding, kind: st.judged })}</li>
              <li>{t("config.agent.budget", { used: st.budget.used, count: st.budget.limit })}</li>
              <li>{t("config.agent.conversations", { count: st.conversations.open, value: st.conversations.awaiting, days: st.conversations.lastDay })}</li>
            </ul>
          </section>
        )}
      </Loaded>
      <Loaded q={q}>
        {(c) => (
          <>
            <p className="bo-muted">{t("config.agent.version", { value: c.version })}</p>
            {defaults.isError ? <p className="bo-muted">{t("config.agent.noDefaults")}</p> : null}
            <ConfigForm
              key={keyOf([c.config, defaults.data])}
              fields={agentFields(defaults.data)}
              values={c.config}
              action={Actions["agent.configure"]}
              save={(change) => supportAgent.putConfig({ body: change })}
              onDone={saved}
            />
            {c.recentChanges.length ? (
              <>
                <h2>{t("config.agent.changes")}</h2>
                <ZoneNote zone={tz} />
                <ul>
                  {[...c.recentChanges].reverse().map((ch) => (
                    <li key={ch.version}>
                      <DateTime instant={ch.at} zone={tz} /> {t("config.agent.change", { value: ch.version, operator: ch.by, fields: ch.fields.join(", ") })}
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </>
        )}
      </Loaded>
    </ConfigPage>
  );
}
