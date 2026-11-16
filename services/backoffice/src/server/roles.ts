// What the server reads from the brand's `players` as itself (research R5, R17): the brand's roles and
// what each carries, read again at most every 10 seconds, and the brand's enabled modules, every
// minute (amendment P14). A failed read keeps the last copy; before the first, there is none.
import permissionsFile from "../shared/generated/operator-permissions.v1.json" with { type: "json" };
import { paymentsPermissionsOf, programmePermissionsOf } from "../shared/access.ts";

export const PlatformAdmin = "platform_admin";
export const AllPermissions: readonly string[] = permissionsFile.permissions;
const known = new Set(AllPermissions);

/** A value read through `load`, kept for `ttlMs`, one read at a time. */
export class Kept<T> {
  private value: T | undefined;
  private readAt = -Infinity;
  private inFlight: Promise<T | undefined> | undefined;
  private readonly load: () => Promise<T>;
  private readonly ttlMs: number;
  private readonly now: () => number;

  constructor(load: () => Promise<T>, ttlMs: number, now: () => number) {
    this.load = load;
    this.ttlMs = ttlMs;
    this.now = now;
  }

  /** The kept copy when fresh; else a new read, or the last copy when the read fails. */
  get(): Promise<T | undefined> {
    if (this.value !== undefined && this.now() - this.readAt < this.ttlMs) return Promise.resolve(this.value);
    this.inFlight ??= this.load()
      .then((v) => {
        this.value = v;
        this.readAt = this.now();
        return v;
      })
      .catch(() => this.value)
      .finally(() => (this.inFlight = undefined));
    return this.inFlight;
  }
}

export type Bags = Record<string, string[]>;

/** `{"roles":[{"name","permissions"}]}`, players' answer, as each role's bag; unknown permissions dropped. */
export function parseBags(body: unknown): Bags {
  const roles = (body as { roles?: unknown })?.roles;
  if (!Array.isArray(roles)) throw new Error("not a roles answer");
  const out: Bags = {};
  for (const r of roles as { name?: unknown; permissions?: unknown }[]) {
    if (typeof r?.name !== "string" || !Array.isArray(r.permissions)) continue;
    out[r.name] = r.permissions.filter((p): p is string => typeof p === "string" && known.has(p)).sort();
  }
  return out;
}

/**
 * The permissions `roles` carry with the brand's `bags`; `platform_admin` carries every one. The
 * affiliate programme's and the shared payments', which realm roles give and no bag does, follow
 * (`programmePermissionsOf`, `paymentsPermissionsOf`).
 */
export function permissionsOf(roles: readonly string[], bags: Bags): string[] {
  const programme = [...programmePermissionsOf(roles), ...paymentsPermissionsOf(roles)];
  if (roles.includes(PlatformAdmin)) return [...AllPermissions, ...programme];
  const held = new Set<string>();
  for (const r of roles) for (const p of bags[r] ?? []) held.add(p);
  return [...AllPermissions.filter((p) => held.has(p)), ...programme];
}

/** Whether `roles` make the operator one of `brand`'s (contract `Routes.operator`). */
export function inBrand(roles: readonly string[], brand: string): boolean {
  return roles.includes(`brand_${brand}`) || roles.includes(PlatformAdmin);
}

/** The modules the vendor's agent lists for the brand, except one it has removed. */
export function parseModules(body: unknown): string[] {
  const modules = (body as { modules?: unknown })?.modules;
  if (!Array.isArray(modules)) throw new Error("not a modules answer");
  return (modules as { module?: unknown; status?: unknown }[])
    .filter((m) => typeof m?.module === "string" && m.status !== "removed")
    .map((m) => m.module as string)
    .sort();
}

export interface BrandReads {
  bags: Kept<Bags>;
  modules: Kept<string[]>;
}

/** The two reads, from players at the calling address, made as the backoffice itself (no token). */
export function brandReads(servicesUrl: string, brand: string, fetchFn: typeof fetch, now: () => number): BrandReads {
  const base = `${servicesUrl}/players/v1/casino/brands/${encodeURIComponent(brand)}/players`;
  const read = async (path: string) => {
    const r = await fetchFn(`${base}${path}`, { signal: AbortSignal.timeout(5_000) });
    if (!r.ok) throw new Error(`players answered ${r.status}`);
    return r.json() as Promise<unknown>;
  };
  return {
    bags: new Kept(async () => parseBags(await read("/roles")), 10_000, now),
    modules: new Kept(async () => parseModules(await read("/modules")), 60_000, now),
  };
}
