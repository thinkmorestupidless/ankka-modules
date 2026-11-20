// What the player's page reads: the standing and the timeline from the page's server (composite routes),
// the rest from the owners through the route table. A refusal is thrown as `explain` gives it.
import { players } from "../../../shared/owners/index.ts";
import { readJson } from "../corrections/shared.tsx";

/**
 * players' operator profile as OperatorProfileRoutes answers it: the player under `player`, beside the
 * wallet, the active sessions and the pinned notes. Every screen reads it through [[profileQuery]], so one
 * cache entry holds one shape; a screen takes the part it needs with `select`.
 */
export interface ProfileAnswer {
  player: Record<string, unknown> & {
    playerId?: string;
    currency?: string | null;
    email?: string;
    username?: string;
    vipTier?: string | null;
    potVip?: boolean;
    suspectedDuplicate?: boolean;
  };
  wallet?: { realBalance?: string; bonusBalance?: string; currency?: string } | null;
  activeSessions?: unknown[];
  pinnedNotes?: unknown[];
}

/** The VIP form's values: the player's tier and the potential-VIP mark. */
export const tierOf = (b: ProfileAnswer): { vipTier?: string | null; potVip?: boolean } => ({ vipTier: b.player.vipTier, potVip: b.player.potVip });

/** A fraud case's player: whether players still flags them a suspected duplicate, and the name shown. */
export const flagOf = (b: ProfileAnswer): { flagged: boolean; username?: string } => ({
  flagged: b.player.suspectedDuplicate === true,
  username: b.player.username ?? b.player.email,
});

export const profileKey = (player: string) => ["players", "profile", player] as const;

export const profileQuery = (player: string) => ({
  queryKey: profileKey(player),
  queryFn: () => readJson<ProfileAnswer>(players.getPlayersProfile({ playerId: player })),
});

export type Part<T> = { state: "ok"; data: T } | { state: "unavailable"; reason: string };

export interface Profile {
  playerId: string;
  status?: string;
  email?: string;
  emailVerified?: boolean;
  firstName?: string;
  lastName?: string;
  displayName?: string;
  dateOfBirth?: string;
  country?: string;
  currency?: string;
  phone?: string;
  addressLine1?: string;
  city?: string;
  stateProv?: string;
  postalCode?: string;
  language?: string;
  registeredAt?: string;
  erasureHoldUntil?: string;
  kycStatus?: string;
  erased: boolean;
  pseudonym?: string;
}

export interface Standing {
  parts: {
    profile: Part<Profile>;
    account: Part<{ status?: string; isTestAccount: boolean; registeredAt?: string; lastLoginAt: string | null; vipTier: string | null; potVip: boolean }>;
    identity: Part<{ status?: string; mode?: string; sof?: string; openFlags: string[] }>;
    protection: Part<{
      limits: { limitType?: string; value?: number | string; currency: string | null }[];
      pendingChanges: number;
      isCoolingOff: boolean;
      coolingOffUntil: string | null;
      exclusion: { permanent: boolean; until: string | null } | null;
      openCases: number;
    }>;
    balances: Part<{ currency: string; real?: string; bonus?: string; reserved?: string; available?: string; restricted: number | null; restrictedRecords: number | null; restrictedReason: string | null }[]>;
    lifetime: Part<{ currency: string; deposited?: string; wagered?: string; won?: string }[]>;
    notes: Part<{ noteId?: string; text: string; author: string | null; createdAt?: string }[]>;
    flags: Part<{ suspectedDuplicate: boolean; linkedPlayerIds: string[]; noDepositOfferBlocked: boolean; profileIncomplete: boolean; emailUnverified: boolean }>;
    presence: Part<{ online: boolean; sessions: { sessionId?: string; gameName: string | null; startedAt?: string }[] }>;
  };
}

/**
 * The player's currency from players' profile answer (OperatorProfileRoutes.profile: `{player, wallet,
 * activeSessions, pinnedNotes}`): the player's own, else the wallet's; a blank one is none. An answer of the
 * player alone (the older doubles) is read the same way.
 */
export function profileCurrency(body: unknown): string | undefined {
  const o = (v: unknown): Record<string, unknown> => (v !== null && typeof v === "object" ? (v as Record<string, unknown>) : {});
  const text = (v: unknown) => (typeof v === "string" && v.trim() !== "" ? v.trim() : undefined);
  const b = o(body);
  return text(o(b.player ?? b).currency) ?? text(o(b.wallet).currency);
}

export const readStanding = (player: string) => readJson<Standing>(fetch(`/api/players/${encodeURIComponent(player)}/standing`, { headers: { Accept: "application/json" } }));

export interface Line {
  id: string;
  at: string;
  kind: string;
  source: string;
  actor: { kind: "player" | "operator" | "system" | "provider"; id?: string; name?: string };
  cause?: { kind: string; ref: string };
  amount?: { minor: number; currency: string };
  links: { kind: string; id: string }[];
  summary: { key: string; values: Record<string, string | number> };
}

export interface TimelinePage {
  lines: Line[];
  next: string | null;
  gaps: { source: string; from: string; to: string; reason: string }[];
  from: string;
  to: string;
}

export const TimelineKinds = ["payments", "withdrawals", "rounds", "rewards", "adjustments", "account", "identity", "protection", "notes", "messages"] as const;

export function readTimeline(player: string, q: { from?: string; to?: string; kinds?: readonly string[]; after?: string }): Promise<TimelinePage> {
  const p = new URLSearchParams();
  if (q.from) p.set("from", q.from);
  if (q.to) p.set("to", q.to);
  if (q.after) p.set("after", q.after);
  if (q.kinds && q.kinds.length > 0 && q.kinds.length < TimelineKinds.length) p.set("kinds", q.kinds.join(","));
  const s = p.size > 0 ? `?${p}` : "";
  return readJson<TimelinePage>(fetch(`/api/players/${encodeURIComponent(player)}/timeline${s}`, { headers: { Accept: "application/json" } }));
}

/** The operator's name, or their id where no one could name them. */
export const actorName = (a: Line["actor"]) => a.name ?? a.id;
