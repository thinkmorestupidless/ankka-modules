// The programme's settings at the brand (parity *Settings, programme settings*; the programme's O43 to
// O46): the fees taken before RevShare, the fraud thresholds, the payout day, the reporting zone, the
// GGR source and the day it changes, how emails are sent, and the default plan; and the programme page
// applicants read. Only the fields changed are sent. The Customer.io key is written, never read back
// (the programme answers only whether one is set) and offered to those the programme lets set it
// (brand_admin, platform_admin).
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState, type ReactNode } from "react";
import { holds } from "../../../shared/access.ts";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { Read } from "./parts.tsx";
import { usePlans } from "./plans.tsx";
import { json, percentOf, type Plan, type ProgrammePage, type Settings } from "./programme.ts";

const GgrSources = ["daily", "rounds"] as const;
const whole = (s: string) => (/^\s*\d+\s*$/.test(s) ? Number(s) : undefined);
const day = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s);

/** `kind=message` lines as the sender's messages, or undefined when a line is not one. */
function messagesOf(text: string): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const line of text.split("\n").map((l) => l.trim()).filter(Boolean)) {
    const m = /^([^=\s]+)\s*=\s*(\S.*)$/.exec(line);
    if (!m) return undefined;
    out[m[1]!] = m[2]!.trim();
  }
  return out;
}
const linesOf = (m: Record<string, string>) => Object.entries(m).map(([k, v]) => `${k}=${v}`).join("\n");

function Field({ id, text, children, changed }: { id: string; text: MessageKey; children: ReactNode; changed?: boolean }) {
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(text)}</label>
      {children}
      {changed ? (
        <span className="bo-status" data-changed>
          {t("config.unsaved")}
        </span>
      ) : null}
    </div>
  );
}

function PlanPicker({ id, value, onChange, plans, disabled }: { id: string; value: string; onChange(v: string): void; plans: readonly Plan[]; disabled: boolean }) {
  return (
    <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t("affiliates.settings.noDefaultPlan")}</option>
      {plans.map((p) => (
        <option key={p.planId} value={p.planId}>
          {p.planName}
        </option>
      ))}
    </select>
  );
}

function SettingsForm({ s, plans, done }: { s: Settings; plans: readonly Plan[]; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const admin = holds(me, "programme.managers");
  const id = {
    admin: useId(), payment: useId(), provider: useId(), bonus: useId(), chargeback: useId(), cycle: useId(), zone: useId(),
    ggr: useId(), ggrFrom: useId(), region: useId(), messages: useId(), plan: useId(), key: useId(),
  };
  const [f, setF] = useState({
    adminFeePercent: String(s.adminFeePercent),
    paymentFeePercent: String(s.paymentFeePercent),
    providerFeePercent: String(s.providerFeePercent),
    bonusCostThreshold: String(s.bonusCostThreshold),
    chargebackThreshold: String(s.chargebackThreshold),
    payoutCycleDay: s.payoutCycleDay === null ? "" : String(s.payoutCycleDay),
    reportingZone: s.reportingZone,
    ggrSource: s.ggrSource,
    ggrSourceFrom: s.ggrSourceFrom ?? "",
    senderRegion: s.senderRegion ?? "",
    senderMessages: linesOf(s.senderMessages),
    defaultPlanId: s.defaultPlanId ?? "",
  });
  const [key, setKey] = useState("");
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));

  const change: Record<string, unknown> = {};
  const invalid: MessageKey[] = [];
  const percent = (k: "adminFeePercent" | "paymentFeePercent" | "providerFeePercent", label: MessageKey) => {
    const v = percentOf(f[k]);
    if (v === undefined) invalid.push(label);
    else if (v !== s[k]) change[k] = v;
  };
  percent("adminFeePercent", "affiliates.settings.adminFee");
  percent("paymentFeePercent", "affiliates.settings.paymentFee");
  percent("providerFeePercent", "affiliates.settings.providerFee");
  for (const [k, label] of [["bonusCostThreshold", "affiliates.settings.bonusThreshold"], ["chargebackThreshold", "affiliates.settings.chargebackThreshold"]] as const) {
    const v = whole(f[k]);
    if (v === undefined) invalid.push(label);
    else if (v !== s[k]) change[k] = v;
  }
  if (f.payoutCycleDay.trim() !== "" && String(s.payoutCycleDay ?? "") !== f.payoutCycleDay.trim()) {
    const v = whole(f.payoutCycleDay);
    if (v === undefined) invalid.push("affiliates.settings.payoutDay");
    else change.payoutCycleDay = v;
  }
  if (f.reportingZone.trim() !== s.reportingZone) change.reportingZone = f.reportingZone.trim();
  if (f.ggrSource !== s.ggrSource) {
    change.ggrSource = f.ggrSource;
    if (!day(f.ggrSourceFrom)) invalid.push("affiliates.settings.ggrSourceFrom");
    else change.ggrSourceFrom = f.ggrSourceFrom;
  }
  if (f.senderRegion.trim() !== (s.senderRegion ?? "") && f.senderRegion.trim() !== "") change.senderRegion = f.senderRegion.trim();
  const messages = messagesOf(f.senderMessages);
  if (messages === undefined) invalid.push("affiliates.settings.senderMessages");
  else if (linesOf(messages) !== linesOf(s.senderMessages)) change.senderMessages = messages;
  if (f.defaultPlanId !== "" && f.defaultPlanId !== (s.defaultPlanId ?? "")) change.defaultPlanId = f.defaultPlanId;
  if (key.trim() !== "") change.customerIoKey = key.trim();
  const fields = Object.keys(change).map((k) => (k === "customerIoKey" ? t("affiliates.settings.customerIoKey") : k));
  const editable = !readOnly;

  return (
    <div className="bo-aff-form" data-settings>
      <Field id={id.admin} text="affiliates.settings.adminFee" changed={"adminFeePercent" in change}>
        <input id={id.admin} inputMode="decimal" value={f.adminFeePercent} disabled={!editable} onChange={set("adminFeePercent")} />
      </Field>
      <Field id={id.payment} text="affiliates.settings.paymentFee" changed={"paymentFeePercent" in change}>
        <input id={id.payment} inputMode="decimal" value={f.paymentFeePercent} disabled={!editable} onChange={set("paymentFeePercent")} />
      </Field>
      <Field id={id.provider} text="affiliates.settings.providerFee" changed={"providerFeePercent" in change}>
        <input id={id.provider} inputMode="decimal" value={f.providerFeePercent} disabled={!editable} onChange={set("providerFeePercent")} />
      </Field>
      <Field id={id.bonus} text="affiliates.settings.bonusThreshold" changed={"bonusCostThreshold" in change}>
        <input id={id.bonus} inputMode="numeric" value={f.bonusCostThreshold} disabled={!editable} onChange={set("bonusCostThreshold")} />
      </Field>
      <Field id={id.chargeback} text="affiliates.settings.chargebackThreshold" changed={"chargebackThreshold" in change}>
        <input id={id.chargeback} inputMode="numeric" value={f.chargebackThreshold} disabled={!editable} onChange={set("chargebackThreshold")} />
      </Field>
      <Field id={id.cycle} text="affiliates.settings.payoutDay" changed={"payoutCycleDay" in change}>
        <input id={id.cycle} inputMode="numeric" value={f.payoutCycleDay} disabled={!editable} onChange={set("payoutCycleDay")} />
      </Field>
      <Field id={id.zone} text="affiliates.settings.reportingZone" changed={"reportingZone" in change}>
        <input id={id.zone} value={f.reportingZone} disabled={!editable} onChange={set("reportingZone")} />
      </Field>
      <Field id={id.ggr} text="affiliates.settings.ggrSource" changed={"ggrSource" in change}>
        <select id={id.ggr} value={f.ggrSource} disabled={!editable} onChange={set("ggrSource")}>
          {GgrSources.map((g) => (
            <option key={g} value={g}>
              {word("affiliates.ggrSource", g)}
            </option>
          ))}
        </select>
      </Field>
      {f.ggrSource !== s.ggrSource ? (
        <Field id={id.ggrFrom} text="affiliates.settings.ggrSourceFrom">
          <input id={id.ggrFrom} type="date" value={f.ggrSourceFrom} disabled={!editable} onChange={set("ggrSourceFrom")} />
        </Field>
      ) : (
        <p className="bo-muted">{t("affiliates.settings.ggrSince", { value: word("affiliates.ggrSource", s.ggrSource), day: s.ggrSourceFrom ?? "" })}</p>
      )}
      <Field id={id.region} text="affiliates.settings.senderRegion" changed={"senderRegion" in change}>
        <input id={id.region} value={f.senderRegion} disabled={!editable} onChange={set("senderRegion")} />
      </Field>
      <Field id={id.plan} text="affiliates.settings.defaultPlan" changed={"defaultPlanId" in change}>
        <PlanPicker id={id.plan} value={f.defaultPlanId} onChange={(v) => setF((x) => ({ ...x, defaultPlanId: v }))} plans={plans} disabled={!editable} />
      </Field>
      <div className="bo-field bo-field-wide">
        <label htmlFor={id.messages}>{t("affiliates.settings.senderMessages")}</label>
        <textarea id={id.messages} rows={3} value={f.senderMessages} disabled={!editable} onChange={set("senderMessages")} />
        <span className="bo-field-hint">{t("affiliates.settings.senderMessagesHint")}</span>
      </div>
      <div className="bo-field bo-field-wide">
        <span>{s.customerIoKeySet ? t("affiliates.settings.keySet") : t("affiliates.settings.keyNotSet")}</span>
        {admin && editable ? (
          <>
            <label htmlFor={id.key}>{t("affiliates.settings.customerIoKey")}</label>
            <input id={id.key} type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} />
            <span className="bo-field-hint">{t("affiliates.settings.keyHint")}</span>
          </>
        ) : null}
      </div>
      {invalid.length ? (
        <p className="bo-field-error bo-field-wide" role="status">
          {t("affiliates.invalid", { name: invalid.map((k) => t(k)).join(", ") })}
        </p>
      ) : null}
      {editable ? (
        <div className="bo-aff-actions bo-field-wide">
          <Action
            action={Actions["programme.settings.save"]}
            me={me}
            bags={bags}
            ready={invalid.length === 0 && fields.length > 0}
            subject={<p>{fields.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: fields.join(", ") })}</p>}
            run={() => programme.putSettings({ body: change })}
            onDone={() => {
              setKey("");
              done();
            }}
          />
        </div>
      ) : null}
    </div>
  );
}

function PageForm({ p, plans, done }: { p: ProgrammePage; plans: readonly Plan[]; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const id = { terms: useId(), version: useId(), contact: useId(), desc: useId(), days: useId(), min: useId(), plan: useId() };
  const [f, setF] = useState({
    terms: p.terms ?? "",
    termsVersion: p.termsVersion ?? "",
    contact: p.contact ?? "",
    description: p.description ?? "",
    payoutCycleDays: p.payoutCycleDays === null ? "" : String(p.payoutCycleDays),
    minimumPayoutAmount: p.minimumPayoutAmount === null ? "" : String(p.minimumPayoutAmount),
    defaultPlanId: p.defaultPlanId ?? "",
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const change: Record<string, unknown> = {};
  const invalid: MessageKey[] = [];
  for (const k of ["terms", "termsVersion", "contact", "description"] as const) if (f[k] !== (p[k] ?? "") && f[k].trim() !== "") change[k] = f[k];
  for (const [k, label] of [["payoutCycleDays", "affiliates.page.payoutDays"], ["minimumPayoutAmount", "affiliates.page.minimumPayout"]] as const) {
    if (f[k].trim() === "" || f[k].trim() === String(p[k] ?? "")) continue;
    const v = whole(f[k]);
    if (v === undefined) invalid.push(label);
    else change[k] = v;
  }
  if (f.defaultPlanId !== "" && f.defaultPlanId !== (p.defaultPlanId ?? "")) change.defaultPlanId = f.defaultPlanId;
  const fields = Object.keys(change);
  const editable = !readOnly;
  return (
    <div className="bo-aff-form" data-programme-page>
      <Field id={id.version} text="affiliates.page.termsVersion" changed={"termsVersion" in change}>
        <input id={id.version} value={f.termsVersion} disabled={!editable} onChange={set("termsVersion")} />
      </Field>
      <Field id={id.contact} text="affiliates.page.contact" changed={"contact" in change}>
        <input id={id.contact} value={f.contact} disabled={!editable} onChange={set("contact")} />
      </Field>
      <Field id={id.days} text="affiliates.page.payoutDays" changed={"payoutCycleDays" in change}>
        <input id={id.days} inputMode="numeric" value={f.payoutCycleDays} disabled={!editable} onChange={set("payoutCycleDays")} />
      </Field>
      <Field id={id.min} text="affiliates.page.minimumPayout" changed={"minimumPayoutAmount" in change}>
        <input id={id.min} inputMode="numeric" value={f.minimumPayoutAmount} disabled={!editable} onChange={set("minimumPayoutAmount")} />
      </Field>
      <Field id={id.plan} text="affiliates.settings.defaultPlan" changed={"defaultPlanId" in change}>
        <PlanPicker id={id.plan} value={f.defaultPlanId} onChange={(v) => setF((x) => ({ ...x, defaultPlanId: v }))} plans={plans} disabled={!editable} />
      </Field>
      <div className="bo-field bo-field-wide">
        <label htmlFor={id.desc}>{t("affiliates.page.description")}</label>
        <textarea id={id.desc} rows={3} value={f.description} disabled={!editable} onChange={set("description")} />
      </div>
      <div className="bo-field bo-field-wide">
        <label htmlFor={id.terms}>{t("affiliates.page.terms")}</label>
        <textarea id={id.terms} rows={6} value={f.terms} disabled={!editable} onChange={set("terms")} />
      </div>
      {invalid.length ? (
        <p className="bo-field-error bo-field-wide" role="status">
          {t("affiliates.invalid", { name: invalid.map((k) => t(k)).join(", ") })}
        </p>
      ) : null}
      {editable ? (
        <div className="bo-aff-actions bo-field-wide">
          <Action
            action={Actions["programme.page.save"]}
            me={me}
            bags={bags}
            ready={invalid.length === 0 && fields.length > 0}
            subject={<p>{fields.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: fields.join(", ") })}</p>}
            run={() => programme.putProgramme({ body: change })}
            onDone={done}
          />
        </div>
      ) : null}
    </div>
  );
}

export function SettingsSection() {
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["programme", "settings"], queryFn: () => json<Settings>(programme.getSettings()) });
  const page = useQuery({ queryKey: ["programme", "page"], queryFn: () => json<ProgrammePage>(programme.getProgramme()) });
  const plans = usePlans();
  return (
    <div>
      <h2>{t("affiliates.settings.title")}</h2>
      <Read q={settings}>
        {(s) => <SettingsForm key={JSON.stringify(s)} s={s} plans={plans.data ?? []} done={() => void client.invalidateQueries({ queryKey: ["programme", "settings"] })} />}
      </Read>
      <h2>{t("affiliates.page.title")}</h2>
      <Read q={page}>{(p) => <PageForm key={JSON.stringify(p)} p={p} plans={plans.data ?? []} done={() => void client.invalidateQueries({ queryKey: ["programme", "page"] })} />}</Read>
    </div>
  );
}
