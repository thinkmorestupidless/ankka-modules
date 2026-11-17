// What every configuration page shares (US9): an owner's read through the page's server as the operator,
// refused with the owner's reason; the page's heading, the refusal, or a loading line; and the reads'
// invalidation once a change is saved, so the form is drawn again from what the owner now holds.
import type { ReactNode } from "react";
import { useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { refused } from "../../../shared/refusals.ts";
import { RefusalNotice } from "../../components/action/index.ts";

/** The owner's JSON answer, or its refusal explained (thrown, so the query is in error). */
export async function read<T>(r: Promise<Response>): Promise<T> {
  const answer = await r;
  if (!answer.ok) throw refused(answer.status, await answer.text());
  return (await answer.json()) as T;
}

export function useRead<T>(key: readonly unknown[], fn: () => Promise<Response>): UseQueryResult<T> {
  return useQuery({ queryKey: ["config", ...key], queryFn: () => read<T>(fn()) });
}

/** Drop every configuration read, so each form is drawn from the owner's answer after a change. */
export function useSaved(): () => void {
  const client = useQueryClient();
  return () => void client.invalidateQueries({ queryKey: ["config"] });
}

/** A read's place on the page: its refusal, a loading line, or what `children` draws from its data. */
export function Loaded<T>({ q, children }: { q: UseQueryResult<T>; children(data: T): ReactNode }) {
  if (q.isError) return <RefusalNotice refusal={q.error} />;
  if (q.data === undefined) return <p role="status">{t("list.loading")}</p>;
  return <>{children(q.data)}</>;
}

export function ConfigPage({ heading, note, children }: { heading: MessageKey; note?: MessageKey; children: ReactNode }) {
  return (
    <section>
      <h1>{t(heading)}</h1>
      {note ? <p className="bo-muted">{t(note)}</p> : null}
      {children}
    </section>
  );
}

/** A form's key: drawn again whenever the owner's answer changes. */
export const keyOf = (data: unknown) => JSON.stringify(data);
