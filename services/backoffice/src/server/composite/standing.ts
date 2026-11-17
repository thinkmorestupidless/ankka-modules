// `GET /api/players/{p}/standing` (contracts/routes.md; FR-020): the top of a player's page, each part
// with its own state, so one owner that does not answer leaves the others shown and is named. players'
// profile is read first (it names the player's currency); kyc, responsible-gambling and the wallet's
// balance, restricted funds and lifetime totals are then asked in parallel, each bounded.
//
// An erased player (spec edge case 5) is answered under the pseudonym only: the player's id, the
// account status and the dates the brand keeps; no name, email, address, date of birth or note text.
import { walletMinorText } from "../../shared/money.ts";

/** The wallet's amount text as minor units (shared/money.ts, the wallet's rule; the currency does not change it). */
const minorOf = (text: unknown, _currency: string): string | undefined => walletMinorText(text);

export { minorOf };
import { arr, ask, num, obj, str, type AskDeps, type Asked, type Obj } from "./ask.ts";

export type Part<T = unknown> = { state: "ok"; data: T } | { state: "unavailable"; reason: string };

export const Parts = ["profile", "account", "identity", "protection", "balances", "lifetime", "notes", "flags", "presence"] as const;
export type PartName = (typeof Parts)[number];

/** The fields of players' `player` that are personal: never answered for an erased player. */
const Personal = ["email", "firstName", "lastName", "displayName", "dateOfBirth", "phone", "mobile", "addressLine1", "city", "stateProv", "postalCode", "province", "bankAccount", "institutionCode", "transitNumber"];
const Erased = new Set(["erasure_pending", "erased"]);

const failed = (a: Asked): Part => ({ state: "unavailable", reason: a.ok ? "unreadable" : a.reason });

export async function standing(deps: AskDeps, player: string, accessToken: string, holds: (p: string) => boolean): Promise<{ parts: Record<PartName, Part> }> {
  const enc = encodeURIComponent(player);
  const profile = await ask(deps, "players", `/players/${enc}/profile`, {}, accessToken, holds);
  const body = profile.ok ? obj(profile.body) : {};
  // players answers `{player, wallet, activeSessions, pinnedNotes}`; an older double answered the player alone
  const p: Obj = obj(body.player ?? body);
  const status = str(p.status);
  const erased = status !== undefined && Erased.has(status);
  const currency = str(p.currency) ?? str(obj(body.wallet).currency);

  const wallet = (path: string) =>
    currency ? ask(deps, "wallet", `/wallets/${enc}/${encodeURIComponent(currency)}/${path}`, {}, accessToken, holds) : Promise.resolve<Asked>({ ok: false, reason: "no_currency" });
  const [kyc, rg, balance, restricted, lifetime] = await Promise.all([
    ask(deps, "kyc", `/${enc}`, {}, accessToken, holds),
    ask(deps, "responsible-gambling", `/${enc}`, { currency }, accessToken, holds),
    wallet("balance"),
    wallet("restricted-funds"),
    wallet("lifetime-totals"),
  ]);

  const fromProfile = <T>(f: () => T): Part<T> | Part => (profile.ok && str(p.playerId) ? { state: "ok", data: f() } : profile.ok ? { state: "unavailable", reason: "unreadable" } : failed(profile));

  const shownProfile = () => {
    const out: Obj = { ...p };
    if (erased) for (const f of Personal) delete out[f];
    return { ...out, erased, pseudonym: erased ? str(p.playerId) : undefined };
  };

  const k = obj(kyc.ok ? kyc.body : {});
  const r = obj(rg.ok ? rg.body : {});
  const b = obj(balance.ok ? balance.body : {});
  const parts: Record<PartName, Part> = {
    profile: fromProfile(shownProfile),
    account: fromProfile(() => ({ status, isTestAccount: p.isTestAccount === true, registeredAt: str(p.registeredAt) ?? str(p.registrationDate), lastLoginAt: str(p.lastLoginAt) ?? null, vipTier: str(p.vipTier) ?? null, potVip: p.potVip === true })),
    identity: kyc.ok
      ? { state: "ok", data: { status: str(k.status), mode: str(k.mode), sof: str(k.sof), openFlags: arr(k.flags).filter((f) => obj(f).open === true).map((f) => str(obj(f).reason)).filter(Boolean) } }
      : failed(kyc),
    protection: rg.ok
      ? {
          state: "ok",
          data: {
            limits: arr(r.limits).map(obj).map((l) => ({ limitType: str(l.limitType), value: num(l.value) ?? str(l.value), currency: str(l.currency) ?? null })),
            pendingChanges: arr(r.pendingChanges).length,
            isCoolingOff: r.isCoolingOff === true,
            coolingOffUntil: str(r.coolingOffUntil) ?? null,
            exclusion: r.exclusion ? { permanent: obj(r.exclusion).permanent === true, until: str(obj(r.exclusion).until) ?? null } : null,
            openCases: arr(r.cases).filter((c) => str(obj(c).status) === "open").length,
          },
        }
      : failed(rg),
    balances:
      balance.ok && currency
        ? {
            state: "ok",
            data: [
              {
                currency,
                real: minorOf(b.realBalance, currency),
                bonus: minorOf(b.bonusBalance, currency),
                reserved: minorOf(b.reserved, currency),
                available: minorOf(b.available, currency),
                restricted: restricted.ok ? (num(obj(restricted.body).restrictedBalance) ?? 0) : null,
                restrictedRecords: restricted.ok ? arr(obj(restricted.body).records).length : null,
                restrictedReason: restricted.ok ? null : restricted.reason,
              },
            ],
          }
        : failed(balance),
    lifetime: lifetime.ok && currency
      ? { state: "ok", data: [{ currency, deposited: minorOf(obj(lifetime.body).totalDeposited, currency), wagered: minorOf(obj(lifetime.body).totalWagered, currency), won: minorOf(obj(lifetime.body).totalWon, currency) }] }
      : failed(lifetime),
    // a note's text is the operators' own record, which may name the player: not shown for an erased player
    notes: fromProfile(() => (erased ? [] : arr(body.pinnedNotes).map(obj).map((n) => ({ noteId: str(n.noteId), text: str(n.text) ?? "", author: str(n.author) ?? null, createdAt: str(n.createdAt) })))),
    flags: fromProfile(() => ({
      suspectedDuplicate: p.suspectedDuplicate === true,
      linkedPlayerIds: arr(p.linkedPlayerIds).filter((x) => typeof x === "string"),
      noDepositOfferBlocked: p.noDepositOfferBlocked === true,
      profileIncomplete: p.profileComplete === false,
      emailUnverified: !erased && p.emailVerified === false,
    })),
    presence: fromProfile(() => {
      const open = arr(body.activeSessions).map(obj);
      return { online: open.length > 0, sessions: open.map((s) => ({ sessionId: str(s.sessionId), gameName: str(s.gameName) ?? null, startedAt: str(s.startedAt) })) };
    }),
  };
  return { parts };
}
