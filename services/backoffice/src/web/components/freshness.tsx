// How fresh the views behind what a page shows are (FR-011), read from `/api/freshness` every 30 s and
// combined: the oldest time any of them last applied a change, and provisional when any is (a view
// rebuilding, or a service whose status could not be read).
import { useQuery } from "@tanstack/react-query";
import type { Freshness } from "./list/freshness.tsx";

export interface Answer {
  services: ({ service: string; state: "ok"; asOf: string | null; provisional: boolean } | { service: string; state: "unavailable"; reason: string; provisional: true })[];
}

export function combine(answer: Answer): Freshness | undefined {
  if (answer.services.length === 0) return undefined;
  const times = answer.services.flatMap((s) => (s.state === "ok" && s.asOf !== null ? [s.asOf] : [])).sort();
  const provisional = answer.services.some((s) => s.provisional);
  if (times.length === 0 && !provisional) return undefined;
  return { at: times[0] ?? null, provisional };
}

export async function readFreshness(services: readonly string[], fetchFn: typeof fetch = fetch): Promise<Freshness | undefined> {
  const r = await fetchFn(`/api/freshness?services=${services.map(encodeURIComponent).join(",")}`, { headers: { Accept: "application/json" } });
  if (!r.ok) return { at: null, provisional: true };
  return combine((await r.json()) as Answer);
}

/** The freshness of the services' views, or undefined while unknown or when they have none. */
export function useFreshness(services: readonly string[]): Freshness | undefined {
  const q = useQuery({
    queryKey: ["freshness", ...services],
    queryFn: () => readFreshness(services),
    enabled: services.length > 0,
    refetchInterval: 30_000,
    staleTime: 10_000,
  });
  return q.data ?? undefined;
}
