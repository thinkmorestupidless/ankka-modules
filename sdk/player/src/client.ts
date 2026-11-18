import type { z } from "zod";
import { refusal, type Refusal } from "./refusal.ts";
import type { Route } from "./route.ts";
import type { CallOptions, Transport } from "./transport.ts";

/** What a client function answers: the parsed answer, or one refusal. */
export type Result<T> = { ok: true; value: T } | { ok: false; refusal: Refusal };

/**
 * Calls a row and parses its answer by its shape at the boundary: an answer that does not fit is
 * `malformed_answer`, naming the service, and no behaviour sees it.
 */
export async function ask<T>(transport: Transport, route: Route, shape: z.ZodType<T>, options: CallOptions = {}): Promise<Result<T>> {
  const called = await transport.call(route, options);
  if (!called.ok) return called;
  const parsed = shape.safeParse(called.body ?? {});
  if (!parsed.success) return { ok: false, refusal: refusal("malformed_answer", called.status, {}, route.service) };
  return { ok: true, value: parsed.data };
}

/** The row of a table by its id; a missing one is a fault of the SDK, found by its suites. */
export function row<R extends Route>(table: readonly R[], id: R["id"]): R {
  const r = table.find((x) => x.id === id);
  if (!r) throw new Error(`no route ${id}`);
  return r;
}
