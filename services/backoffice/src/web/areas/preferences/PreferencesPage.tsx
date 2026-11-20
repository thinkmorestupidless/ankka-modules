// The operator's own preferences (T053): theme, the worklist's kinds, online notices, written through
// the server's `PUT /api/preferences`. They are the operator's own, not the brand's, so they stay
// editable below 1280 px.
import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { explain, type Refusal } from "../../../shared/refusals.ts";
import { kindsFor } from "../../../shared/worklist.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { useOperator } from "../../frame/context.ts";

export interface Chosen {
  theme: "dark" | "light";
  worklistKinds: string[] | null;
  onlineNotices: boolean;
}

export function chosenOf(preferences: unknown): Chosen {
  const p = (preferences ?? {}) as Partial<Chosen>;
  return {
    theme: p.theme === "light" ? "light" : "dark",
    worklistKinds: Array.isArray(p.worklistKinds) ? p.worklistKinds : null,
    onlineNotices: p.onlineNotices === true,
  };
}

export async function save(chosen: Chosen, fetchFn: typeof fetch = fetch): Promise<Refusal | undefined> {
  let r: Response;
  try {
    r = await fetchFn("/api/preferences", {
      method: "PUT",
      headers: { "Content-Type": "application/json", "X-Backoffice": "1" },
      body: JSON.stringify(chosen),
    });
  } catch {
    return explain(503, JSON.stringify({ error: "source_unavailable", source: "backoffice" }));
  }
  return r.ok ? undefined : explain(r.status, await r.text());
}

export function PreferencesPage({ write = save }: { write?: typeof save }) {
  const me = useOperator();
  const client = useQueryClient();
  const kinds = kindsFor(me);
  const [chosen, setChosen] = useState(() => chosenOf(me.preferences));
  const [state, setState] = useState<{ at: "idle" | "saving" | "saved" } | { at: "refused"; refusal: Refusal }>({ at: "idle" });
  const all = chosen.worklistKinds === null;
  const shown = new Set(chosen.worklistKinds ?? kinds.map((k) => k.id));

  const toggle = (id: string) => {
    const next = new Set(shown);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setChosen({ ...chosen, worklistKinds: kinds.map((k) => k.id).filter((k) => next.has(k)) });
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setState({ at: "saving" });
    const refusal = await write(chosen);
    if (refusal) return setState({ at: "refused", refusal });
    setState({ at: "saved" });
    await client.invalidateQueries({ queryKey: ["me"] });
  };

  return (
    <section>
      <h1>{t("area.preferences")}</h1>
      <form onSubmit={(e) => void submit(e)} className="bo-form">
        <fieldset>
          <legend>{t("preferences.theme")}</legend>
          {(["dark", "light"] as const).map((theme) => (
            <label key={theme}>
              <input type="radio" name="theme" checked={chosen.theme === theme} onChange={() => setChosen({ ...chosen, theme })} />
              {t(theme === "dark" ? "preferences.dark" : "preferences.light")}
            </label>
          ))}
        </fieldset>
        <fieldset>
          <legend>{t("preferences.kinds")}</legend>
          <p className="bo-muted">{t("preferences.kindsHint")}</p>
          <label>
            <input
              type="checkbox"
              checked={all}
              onChange={() => setChosen({ ...chosen, worklistKinds: all ? kinds.map((k) => k.id) : null })}
            />
            {t("preferences.kindsAll")}
          </label>
          {kinds.map((k) => (
            <label key={k.id}>
              <input type="checkbox" disabled={all} checked={shown.has(k.id)} onChange={() => toggle(k.id)} />
              {t(k.label)}
            </label>
          ))}
        </fieldset>
        <label>
          <input type="checkbox" checked={chosen.onlineNotices} onChange={() => setChosen({ ...chosen, onlineNotices: !chosen.onlineNotices })} />
          {t("preferences.notices")}
        </label>
        <p>
          <button type="submit" className="bo-button" disabled={state.at === "saving"}>
            {t(state.at === "saving" ? "preferences.saving" : "preferences.save")}
          </button>
          {state.at === "saved" ? (
            <span role="status" className="bo-muted">
              {t("preferences.saved")}
            </span>
          ) : null}
        </p>
        {state.at === "refused" ? <RefusalNotice refusal={state.refusal} /> : null}
      </form>
    </section>
  );
}
