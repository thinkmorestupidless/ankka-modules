// A configuration form (FR-034): each value as the owner holds it, marked when it differs from the
// brand's default or from its licence profile, and marked "changed, not saved" once edited. Saving is one
// `Action` (its effect stated first), sending only the fields changed. The page names each field's
// default from the owner: what the owner answers as its default where it answers one, else the value the
// owner holds when no one has set it (its code's empty state), cited beside the field list. A field with
// neither is not marked; the page says so (as the identity policy and protection settings do).
import { useId, useState } from "react";
import { holds } from "../../shared/access.ts";
import { t, type MessageKey } from "../../shared/messages/index.ts";
import { decimalOf, formatMoney, parseMoney } from "../../shared/money.ts";
import type { ActionDescription } from "../actions/index.ts";
import { word } from "../areas/word.ts";
import { useBags } from "../areas/bags.ts";
import { useOperator, useReadOnlyMode } from "../frame/context.ts";
import { Action } from "./action/index.ts";

export type ConfigValue = string | number | boolean | null | readonly string[];

export interface ConfigField {
  /** The owner's field, as it answers and takes it. */
  name: string;
  label: MessageKey;
  /**
   * `text`; `whole` (a whole number); `boolean`; `choice` (one of `choices`, worded `<choicePrefix>.<value>`);
   * `list` (comma-separated text, sent as a list); `money` (minor units of `currency`, typed as an amount).
   */
  kind: "text" | "whole" | "boolean" | "choice" | "list" | "money";
  choices?: readonly string[];
  choicePrefix?: string;
  currency?: string;
  /** Blank is no value (`null`); otherwise blank is refused. */
  optional?: boolean;
  /** The brand's default: the owner's answered default, or its value when unset. Absent: the owner gives none. */
  default?: ConfigValue;
  /** The value the brand's licence profile sets, where the owner gives one. */
  profile?: ConfigValue;
  hint?: MessageKey;
}

export interface ConfigFormProps {
  fields: readonly ConfigField[];
  /** What the owner answered, by field name. */
  values: Readonly<Record<string, ConfigValue>>;
  action: ActionDescription;
  /** Sends the change: the changed fields by name, and every field's value after it (for owners that take a whole object). */
  save(change: Record<string, ConfigValue>, next: Record<string, ConfigValue>): Promise<Response>;
  onDone?(): void;
}

/** Two values the same: lists as sets, numbers and their text alike. */
export function same(a: ConfigValue | undefined, b: ConfigValue | undefined): boolean {
  if (Array.isArray(a) || Array.isArray(b)) {
    const x = Array.isArray(a) ? [...(a as string[])].sort().join("\n") : a;
    const y = Array.isArray(b) ? [...(b as string[])].sort().join("\n") : b;
    return x === y;
  }
  return (a ?? null) === (b ?? null);
}

type Draft = string | boolean;

function draftOf(f: ConfigField, v: ConfigValue | undefined): Draft {
  if (f.kind === "boolean") return v === true;
  if (v === null || v === undefined) return "";
  if (Array.isArray(v)) return v.join(", ");
  if (f.kind === "money" && f.currency) {
    try {
      return decimalOf({ minor: v as number, currency: f.currency });
    } catch {
      return String(v);
    }
  }
  return String(v);
}

/** The draft as the owner takes it; `undefined` when it does not read. */
export function parse(f: ConfigField, d: Draft): ConfigValue | undefined {
  if (f.kind === "boolean") return d === true;
  const text = String(d).trim();
  if (f.kind === "list") return text === "" ? [] : text.split(/[\s,]+/).filter((s) => s !== "");
  if (text === "") return f.optional ? null : f.kind === "text" || f.kind === "choice" ? "" : undefined;
  if (f.kind === "whole") return /^\d+$/.test(text) ? Number(text) : undefined;
  if (f.kind === "money") {
    if (!f.currency) return undefined;
    try {
      const m = parseMoney(text, f.currency);
      return m === undefined || m < 0n ? undefined : Number(m);
    } catch {
      return undefined;
    }
  }
  return text;
}

/** A value as a person reads it. */
export function shown(f: ConfigField, v: ConfigValue | undefined): string {
  if (v === null || v === undefined || (Array.isArray(v) && v.length === 0) || v === "") return t("config.form.none");
  if (typeof v === "boolean") return v ? t("config.form.yes") : t("config.form.no");
  if (Array.isArray(v)) return v.join(", ");
  if (f.kind === "money" && f.currency && typeof v === "number") {
    try {
      return formatMoney({ minor: v, currency: f.currency });
    } catch {
      return String(v);
    }
  }
  if (f.kind === "choice" && f.choicePrefix) return word(f.choicePrefix, String(v));
  return String(v);
}

/** The marks for a held value: differing from the default, from the licence profile. */
export function Marks({ field, value }: { field: ConfigField; value: ConfigValue | undefined }) {
  return (
    <>
      {field.default !== undefined && !same(value, field.default) ? (
        <span className="bo-status" data-tone="warning" data-differs="default">
          {t("config.form.differsFromDefault", { value: shown(field, field.default) })}
        </span>
      ) : null}
      {field.profile !== undefined && !same(value, field.profile) ? (
        <span className="bo-status" data-tone="warning" data-differs="profile">
          {t("config.form.differsFromProfile", { value: shown(field, field.profile) })}
        </span>
      ) : null}
    </>
  );
}

export function ConfigForm({ fields, values, action, save, onDone }: ConfigFormProps) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const editable = !readOnly && holds(me, action.permission);
  const base = useId();
  const [drafts, setDrafts] = useState<Record<string, Draft>>(() => Object.fromEntries(fields.map((f) => [f.name, draftOf(f, values[f.name])])));

  const parsed = Object.fromEntries(fields.map((f) => [f.name, parse(f, drafts[f.name] ?? "")]));
  const unreadable = fields.filter((f) => parsed[f.name] === undefined);
  const changed = fields.filter((f) => parsed[f.name] !== undefined && !same(parsed[f.name], values[f.name]));
  const change = Object.fromEntries(changed.map((f) => [f.name, parsed[f.name] as ConfigValue]));
  const next: Record<string, ConfigValue> = { ...values, ...change };

  return (
    <div className="bo-config-form">
      {fields.map((f, i) => {
        const id = `${base}-${i}`;
        const marks = `${id}-marks`;
        const d = drafts[f.name] ?? "";
        const set = (v: Draft) => setDrafts({ ...drafts, [f.name]: v });
        const input =
          f.kind === "boolean" ? (
            <input id={id} type="checkbox" checked={d === true} disabled={!editable} aria-describedby={marks} onChange={(e) => set(e.target.checked)} />
          ) : f.kind === "choice" ? (
            <select id={id} value={String(d)} disabled={!editable} aria-describedby={marks} onChange={(e) => set(e.target.value)}>
              {f.optional ? <option value="">{t("config.form.none")}</option> : null}
              {(f.choices ?? []).map((c) => (
                <option key={c} value={c}>
                  {f.choicePrefix ? word(f.choicePrefix, c) : c}
                </option>
              ))}
            </select>
          ) : (
            <input
              id={id}
              className="bo-input"
              value={String(d)}
              inputMode={f.kind === "whole" ? "numeric" : f.kind === "money" ? "decimal" : undefined}
              disabled={!editable}
              aria-invalid={parsed[f.name] === undefined}
              aria-describedby={marks}
              onChange={(e) => set(e.target.value)}
            />
          );
        return (
          <div key={f.name} className="bo-field" data-field={f.name}>
            <label htmlFor={id}>
              {t(f.label)}
              {f.kind === "money" && f.currency ? ` (${f.currency})` : ""}
            </label>
            {input}
            <span id={marks} className="bo-field-hint">
              <Marks field={f} value={values[f.name]} />{" "}
              {changed.includes(f) ? (
                <span className="bo-status" data-changed>
                  {t("config.unsaved")}
                </span>
              ) : null}{" "}
              {f.hint ? t(f.hint) : null}
            </span>
          </div>
        );
      })}
      {unreadable.length > 0 ? <p className="bo-field-error">{t("config.form.unreadable", { fields: unreadable.map((f) => t(f.label)).join(", ") })}</p> : null}
      {readOnly ? null : (
        <div className="bo-actions">
          <Action
            action={action}
            me={me}
            bags={bags}
            ready={unreadable.length === 0 && changed.length > 0}
            subject={<p>{changed.length === 0 ? t("config.nothingChanged") : t("config.changes", { fields: changed.map((f) => `${t(f.label)}: ${shown(f, parsed[f.name])}`).join("; ") })}</p>}
            run={() => save(change, next)}
            onDone={() => onDone?.()}
          />
        </div>
      )}
    </div>
  );
}
