// The brand's roles and what each carries, as `/api/permissions` answers them, so an action the
// operator lacks can name a role that would give it (FR-005). Undefined while unread or unreadable.
import { useQuery } from "@tanstack/react-query";
import type { RoleBags } from "../../shared/access.ts";

export async function readBags(fetchFn: typeof fetch = fetch): Promise<RoleBags | null> {
  const r = await fetchFn("/api/permissions", { headers: { Accept: "application/json" } });
  if (!r.ok) return null;
  return ((await r.json()) as { roles: RoleBags }).roles;
}

export function useBags(): RoleBags | undefined {
  const q = useQuery({ queryKey: ["permissions"], queryFn: () => readBags(), staleTime: 10_000 });
  return q.data ?? undefined;
}
