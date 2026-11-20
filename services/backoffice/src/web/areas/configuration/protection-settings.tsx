// The brand's protection settings (T097): responsible-gambling's minimum session limit a player may set
// and the longest break, changed together. responsible-gambling holds the ranges and refuses a value
// outside them naming the field; it does not answer its defaults, so the page cannot mark a value that
// differs from one (FR-034).
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { responsibleGambling as rg } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readSettings, type Settings } from "../protection/rg.ts";

const whole = (s: string) => (/^\s*\d+\s*$/.test(s) ? Number(s) : undefined);

function Editor({ settings, done }: { settings: Settings; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const editable = !readOnly && me.permissions.includes("config.brand");
  const minId = useId();
  const maxId = useId();
  const [min, setMin] = useState(String(settings.minSessionMinutes));
  const [max, setMax] = useState(String(settings.maxBreakDays));
  const m = whole(min);
  const d = whole(max);
  const change: Record<string, number> = {};
  if (m !== undefined && m !== settings.minSessionMinutes) change.minSessionMinutes = m;
  if (d !== undefined && d !== settings.maxBreakDays) change.maxBreakDays = d;
  const fields = Object.keys(change);
  return (
    <div>
      <div className="bo-field">
        <label htmlFor={minId}>{t("config.protection.minSession")}</label>
        <input id={minId} inputMode="numeric" value={min} disabled={!editable} onChange={(e) => setMin(e.target.value)} />
        {"minSessionMinutes" in change ? <span className="bo-status" data-changed>{t("config.unsaved")}</span> : null}
      </div>
      <div className="bo-field">
        <label htmlFor={maxId}>{t("config.protection.maxBreak")}</label>
        <input id={maxId} inputMode="numeric" value={max} disabled={!editable} onChange={(e) => setMax(e.target.value)} />
        {"maxBreakDays" in change ? <span className="bo-status" data-changed>{t("config.unsaved")}</span> : null}
      </div>
      <p className="bo-muted">{t("config.protection.noDefaults")}</p>
      {readOnly ? null : (
        <Action
          action={Actions["config.protectionSettings"]}
          me={me}
          bags={bags}
          ready={m !== undefined && d !== undefined && fields.length > 0}
          subject={<p>{fields.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: fields.join(", ") })}</p>}
          run={() => rg.putSettings({ body: change })}
          onDone={done}
        />
      )}
    </div>
  );
}

export function ProtectionSettingsPage() {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["rg", "settings"], queryFn: () => readSettings() });
  const done = () => void client.invalidateQueries({ queryKey: ["rg", "settings"] });
  return (
    <section>
      <h1>{t("config.protection.title")}</h1>
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      {q.data ? <Editor key={JSON.stringify(q.data)} settings={q.data} done={done} /> : q.isError ? null : <p role="status">{t("list.loading")}</p>}
    </section>
  );
}
