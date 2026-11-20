// What the payments pages share: a read of the shared payments as the operator, invalidated once a change
// is made; a labelled field for an action's subject; a plain table of rows.
import { useId, type ReactNode } from "react";
import { useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { t, type MessageKey, type Values } from "../../../shared/messages/index.ts";
import { read } from "../configuration/page.tsx";

export function usePayments<T>(key: readonly unknown[], fn: () => Promise<Response>, enabled = true): UseQueryResult<T> {
  return useQuery({ queryKey: ["payments", ...key], queryFn: () => read<T>(fn()), enabled, retry: false });
}

/** Drop the payments reads under `key`, so each list is drawn from merchant's answer after a change. */
export function useChanged(...key: unknown[]): () => void {
  const client = useQueryClient();
  return () => void client.invalidateQueries({ queryKey: ["payments", ...key] });
}

export function Field({ labelKey: label, values, value, set, hint, kind = "text" }: { labelKey: MessageKey; values?: Values; value: string; set(v: string): void; hint?: string; kind?: "text" | "password" | "longtext" }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(label, values)}</label>
      {kind === "longtext" ? (
        <textarea id={id} className="bo-input" rows={4} value={value} onChange={(e) => set(e.target.value)} aria-describedby={hint ? `${id}-hint` : undefined} />
      ) : (
        <input id={id} className="bo-input" type={kind} autoComplete="off" value={value} onChange={(e) => set(e.target.value)} aria-describedby={hint ? `${id}-hint` : undefined} />
      )}
      {hint ? (
        <span id={`${id}-hint`} className="bo-field-hint">
          {hint}
        </span>
      ) : null}
    </div>
  );
}

export function Choice({ labelKey: label, value, set, choices, word }: { labelKey: MessageKey; value: string; set(v: string): void; choices: readonly string[]; word?(c: string): string }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t(label)}</label>
      <select id={id} value={value} onChange={(e) => set(e.target.value)}>
        {choices.map((c) => (
          <option key={c} value={c}>
            {word ? word(c) : c}
          </option>
        ))}
      </select>
    </div>
  );
}

export interface Col<R> {
  header: MessageKey;
  cell(r: R): ReactNode;
  numeric?: boolean;
}

/** A table of every row merchant answered; merchant answers these lists whole, so no page follows. */
export function Rows<R>({ caption, cols, rows, rowKey, empty }: { caption: MessageKey; cols: readonly Col<R>[]; rows: readonly R[]; rowKey(r: R): string; empty: MessageKey }) {
  return (
    <table className="bo-pay-table">
      <caption>
        {t(caption)} <span className="bo-muted">{t("payments.count", { count: rows.length })}</span>
      </caption>
      <thead>
        <tr>
          {cols.map((c) => (
            <th key={c.header} scope="col" data-numeric={c.numeric ? "" : undefined}>
              {t(c.header)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr>
            <td colSpan={cols.length} className="bo-muted">
              {t(empty)}
            </td>
          </tr>
        ) : (
          rows.map((r) => (
            <tr key={rowKey(r)}>
              {cols.map((c) => (
                <td key={c.header} data-numeric={c.numeric ? "" : undefined}>
                  {c.cell(r)}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
