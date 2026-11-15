// The brand's identity policy (T097; parity *KYC policy and licence profile*): kyc's policy, changed
// field by field (a field left as it was is not sent, so kyc keeps it), or replaced by a licence
// profile. kyc answers the profile the policy follows and whether it is the brand's default; a change
// of any field makes it `CUSTOM` (kyc's rule). kyc does not answer each profile's own values, so the
// page marks the policy as a whole as differing from its default, not each field (FR-034).
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { decimalOf, parseMoney } from "../../../shared/money.ts";
import { kyc } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Categories, Profiles, readPolicy, type Policy } from "../identity/kyc.ts";
import { word } from "../word.ts";

const Gates = ["NONE", "KYC_APPROVED"] as const;

interface Draft {
  depositGate: string;
  playGate: string;
  kycCurrency: string;
  depositThreshold: string;
  sofThreshold: string;
  kycWindowDays: string;
  requiredDocTypes: string[];
  autoSuspendOnWindowExpiry: boolean;
}

const money = (minor: number | null, currency: string | null) => {
  if (minor === null) return "";
  try {
    return currency ? decimalOf({ minor, currency }) : String(minor);
  } catch {
    return String(minor);
  }
};

const draftOf = (p: Policy): Draft => ({
  depositGate: p.depositGate,
  playGate: p.playGate,
  kycCurrency: p.kycCurrency ?? "",
  depositThreshold: money(p.depositThresholdMinorUnits, p.kycCurrency),
  sofThreshold: money(p.sofThresholdMinorUnits, p.kycCurrency),
  kycWindowDays: p.kycWindowDays === null ? "" : String(p.kycWindowDays),
  requiredDocTypes: [...p.requiredDocTypes],
  autoSuspendOnWindowExpiry: p.autoSuspendOnWindowExpiry,
});

/** The fields changed from what kyc answered, as kyc's `PUT /policy` takes them; `undefined` when one does not read. */
export function changeOf(before: Draft, after: Draft): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  const currency = after.kycCurrency.trim().toUpperCase();
  const minor = (text: string): number | null | undefined => {
    if (text.trim() === "") return null;
    if (currency === "") return undefined;
    try {
      const m = parseMoney(text, currency);
      return m === undefined || m < 0n ? undefined : Number(m);
    } catch {
      return undefined;
    }
  };
  if (after.depositGate !== before.depositGate) out.depositGate = after.depositGate;
  if (after.playGate !== before.playGate) out.playGate = after.playGate;
  if (currency !== before.kycCurrency) out.kycCurrency = currency === "" ? null : currency;
  for (const [field, b, a] of [["depositThresholdMinorUnits", before.depositThreshold, after.depositThreshold], ["sofThresholdMinorUnits", before.sofThreshold, after.sofThreshold]] as const) {
    if (a === b && currency === before.kycCurrency) continue;
    const v = minor(a);
    if (v === undefined) return undefined;
    out[field] = v;
  }
  if (after.kycWindowDays !== before.kycWindowDays) {
    if (after.kycWindowDays.trim() === "") out.kycWindowDays = null;
    else if (/^\d+$/.test(after.kycWindowDays.trim())) out.kycWindowDays = Number(after.kycWindowDays.trim());
    else return undefined;
  }
  if ([...after.requiredDocTypes].sort().join() !== [...before.requiredDocTypes].sort().join()) out.requiredDocTypes = after.requiredDocTypes;
  if (after.autoSuspendOnWindowExpiry !== before.autoSuspendOnWindowExpiry) out.autoSuspendOnWindowExpiry = after.autoSuspendOnWindowExpiry;
  return out;
}

function Editor({ policy, done }: { policy: Policy; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const editable = !readOnly && me.permissions.includes("config.brand");
  const before = draftOf(policy);
  const [d, setD] = useState(before);
  const [profile, setProfile] = useState("");
  const ids = { deposit: useId(), play: useId(), currency: useId(), dt: useId(), sof: useId(), window: useId(), suspend: useId(), profile: useId() };
  const set = (patch: Partial<Draft>) => setD({ ...d, ...patch });
  const change = changeOf(before, d);
  const changed = change ? Object.keys(change) : [];
  const mark = (field: string) => (changed.includes(field) ? <span className="bo-status" data-changed>{t("config.unsaved")}</span> : null);

  const gate = (id: string, label: "config.identity.depositGate" | "config.identity.playGate", value: string, field: "depositGate" | "playGate") => (
    <div className="bo-field">
      <label htmlFor={id}>{t(label)}</label>
      <select id={id} value={value} disabled={!editable} onChange={(e) => set({ [field]: e.target.value })}>
        {Gates.map((g) => (
          <option key={g} value={g}>
            {word("config.gate", g)}
          </option>
        ))}
      </select>
      {mark(field)}
    </div>
  );

  return (
    <div>
      <p data-profile={policy.licenceProfile}>
        {t("config.identity.profile", { profile: word("config.profile", policy.licenceProfile) })}{" "}
        <span className="bo-status" data-default={policy.isDefault}>{policy.isDefault ? t("config.isDefault") : t("config.differsFromDefault")}</span>
      </p>
      {gate(ids.deposit, "config.identity.depositGate", d.depositGate, "depositGate")}
      {gate(ids.play, "config.identity.playGate", d.playGate, "playGate")}
      <div className="bo-field">
        <label htmlFor={ids.currency}>{t("config.identity.currency")}</label>
        <input id={ids.currency} value={d.kycCurrency} maxLength={3} disabled={!editable} onChange={(e) => set({ kycCurrency: e.target.value })} />
        {mark("kycCurrency")}
      </div>
      <div className="bo-field">
        <label htmlFor={ids.dt}>{t("config.identity.depositThreshold")}</label>
        <input id={ids.dt} inputMode="decimal" value={d.depositThreshold} disabled={!editable} onChange={(e) => set({ depositThreshold: e.target.value })} />
        {mark("depositThresholdMinorUnits")}
      </div>
      <div className="bo-field">
        <label htmlFor={ids.sof}>{t("config.identity.sofThreshold")}</label>
        <input id={ids.sof} inputMode="decimal" value={d.sofThreshold} disabled={!editable} onChange={(e) => set({ sofThreshold: e.target.value })} />
        {mark("sofThresholdMinorUnits")}
      </div>
      <div className="bo-field">
        <label htmlFor={ids.window}>{t("config.identity.windowDays")}</label>
        <input id={ids.window} inputMode="numeric" value={d.kycWindowDays} disabled={!editable} onChange={(e) => set({ kycWindowDays: e.target.value })} />
        {mark("kycWindowDays")}
      </div>
      <fieldset className="bo-permissions">
        <legend>{t("config.identity.required")}</legend>
        {Categories.filter((c) => c !== "other").map((c) => (
          <label key={c}>
            <input
              type="checkbox"
              name={c}
              disabled={!editable}
              checked={d.requiredDocTypes.includes(c)}
              onChange={() => set({ requiredDocTypes: d.requiredDocTypes.includes(c) ? d.requiredDocTypes.filter((x) => x !== c) : [...d.requiredDocTypes, c] })}
            />
            <span>{word("identity.category", c)}</span>
          </label>
        ))}
        {mark("requiredDocTypes")}
      </fieldset>
      <label>
        <input id={ids.suspend} type="checkbox" disabled={!editable} checked={d.autoSuspendOnWindowExpiry} onChange={(e) => set({ autoSuspendOnWindowExpiry: e.target.checked })} />{" "}
        <span>{t("config.identity.suspendOnExpiry")}</span> {mark("autoSuspendOnWindowExpiry")}
      </label>
      {change === undefined ? <p className="bo-field-error">{t("config.identity.unreadable")}</p> : null}
      {readOnly ? null : (
        <div className="bo-actions">
          <Action
            action={Actions["config.identityPolicy"]}
            me={me}
            bags={bags}
            ready={change !== undefined && changed.length > 0}
            subject={<p>{changed.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: changed.join(", ") })}</p>}
            run={() => kyc.putPolicy({ body: change })}
            onDone={done}
          />
          <Action
            action={Actions["config.identityProfile"]}
            me={me}
            bags={bags}
            ready={profile !== ""}
            subject={
              <div className="bo-field">
                <label htmlFor={ids.profile}>{t("config.identity.chooseProfile")}</label>
                <select id={ids.profile} value={profile} onChange={(e) => setProfile(e.target.value)}>
                  <option value="">{t("config.identity.chooseProfile")}</option>
                  {Profiles.map((p) => (
                    <option key={p} value={p}>
                      {word("config.profile", p)}
                    </option>
                  ))}
                </select>
              </div>
            }
            run={() => kyc.postPolicyProfile({ body: { profile } })}
            onDone={done}
          />
        </div>
      )}
    </div>
  );
}

export function IdentityPolicyPage() {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["kyc", "policy"], queryFn: () => readPolicy() });
  const done = () => void client.invalidateQueries({ queryKey: ["kyc", "policy"] });
  return (
    <section>
      <h1>{t("config.identity.title")}</h1>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {q.data ? <Editor key={JSON.stringify(q.data)} policy={q.data} done={done} /> : q.isError ? null : <p role="status">{t("list.loading")}</p>}
    </section>
  );
}
