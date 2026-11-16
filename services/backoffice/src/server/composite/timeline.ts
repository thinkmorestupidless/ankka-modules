// `GET /api/players/{p}/timeline?from&to&kinds&after` (contracts/routes.md; contracts/sources.md,
// *Timeline*; research R10; FR-021, FR-022). Every source of a player's lines is asked for the period in
// parallel, as the operator, each bounded; their lines are merged newest first. A source that answered
// only part of the period (it has more) holds the merge back: no line older than the oldest it gave is
// emitted on this page, so a page never lacks a line a slower or longer source would have put in it, and
// `next` is where the next page starts. A source that cannot be read is a gap: named, with the period
// it is missing, and the page is not complete.
//
// Each line's words are a message key and its values (FR-040a), never the owner's prose. Actors are
// named: an operator's id is looked up in merchant's operators (research R12), and the signed-in
// operator is named from their own sign-in; an operator no one can name is shown by id.
import { arr, ask, num, obj, str, type AskDeps, type Asked, type Obj } from "./ask.ts";
import { minorOf } from "./standing.ts";

export type ActorKind = "player" | "operator" | "system" | "provider";
export interface Actor {
  kind: ActorKind;
  id?: string;
  name?: string;
}

export interface Line {
  id: string;
  at: string;
  kind: Kind;
  source: string;
  actor: Actor;
  cause?: { kind: string; ref: string };
  amount?: { minor: number; currency: string };
  links: { kind: string; id: string }[];
  summary: { key: string; values: Record<string, string | number> };
}

export interface Gap {
  source: string;
  from: string;
  to: string;
  reason: string;
}

export interface Page {
  lines: Line[];
  /** Where the next page starts (an instant: lines before it), or null when the period is done. */
  next: string | null;
  gaps: Gap[];
  from: string;
  to: string;
}

export const Kinds = ["payments", "withdrawals", "rounds", "rewards", "adjustments", "account", "identity", "protection", "notes", "messages"] as const;
export type Kind = (typeof Kinds)[number];

/** What a source answered for the period: its lines, and whether it holds more than it gave. */
interface Read {
  lines: Line[];
  more: boolean;
}

interface Period {
  from: number;
  to: number;
}

interface Source {
  name: string;
  kind: Kind;
  read(deps: AskDeps, player: string, period: Period, token: string, holds: (p: string) => boolean, context: Context): Promise<Asked & { read?: Read }>;
}

interface Context {
  /** The player's currency, for the wallet's adjustments; read once from the profile when an adjustment is asked for. */
  currency(): Promise<string | undefined>;
}

const iso = (ms: number) => new Date(ms).toISOString();
const ms = (v: unknown): number | undefined => {
  const s = str(v);
  if (s === undefined) return num(v);
  const n = Date.parse(s);
  return Number.isNaN(n) ? undefined : n;
};
const within = (p: Period) => (l: Line) => {
  const t = Date.parse(l.at);
  return t >= p.from && t < p.to;
};

/** An owner's actor as one of the four kinds: `player`, `system`, `provider`, `operator`, `operator:<id>`, or an operator's id. */
export function actorOf(v: unknown): Actor {
  const o = obj(v);
  if (Object.keys(o).length > 0) {
    const kind = str(o.kind) ?? "system";
    return { ...actorOf(kind), ...(str(o.id) ? { id: str(o.id) } : {}) };
  }
  const s = (str(v) ?? "system").trim();
  const lower = s.toLowerCase();
  if (lower === "player" || lower === "system" || lower === "provider") return { kind: lower };
  if (lower === "operator") return { kind: "operator" };
  if (lower.startsWith("operator:")) return { kind: "operator", id: s.slice("operator:".length) };
  if (lower === "game_provider" || lower === "psp") return { kind: "provider" };
  return { kind: "operator", id: s };
}

const snake = (s: string) => s.replace(/([a-z0-9])([A-Z])/g, "$1_$2").replace(/[^A-Za-z0-9]+/g, "_").toLowerCase();

const asked = (r: Asked, read: (body: unknown) => Read): Asked & { read?: Read } => (r.ok ? { ...r, read: read(r.body) } : r);

/** players' activity in one category: lines for the period, a page of 50 by its cursor. */
function activity(name: string, kind: Kind, category: string, keep: (eventType: string) => boolean = () => true): Source {
  return {
    name,
    kind,
    async read(deps, player, p, token, holds) {
      const r = await ask(deps, "players", `/players/${encodeURIComponent(player)}/activity`, { category, from: iso(p.from), to: iso(p.to) }, token, holds);
      return asked(r, (body) => {
        const b = obj(body);
        const lines = arr(b.events)
          .map(obj)
          .filter((e) => keep(str(e.eventType) ?? ""))
          .flatMap((e): Line[] => {
            const at = ms(e.timestamp);
            const id = str(e.eventId);
            if (at === undefined || !id) return [];
            const minor = num(e.amountMinorUnits);
            const currency = str(e.currency);
            const eventType = snake(str(e.eventType) ?? "event");
            return [{
              id: `${name}/${id}`,
              at: iso(at),
              kind,
              source: name,
              actor: actorOf(e.actor),
              ...(minor !== undefined && currency ? { amount: { minor, currency } } : {}),
              links: [],
              summary: { key: `timeline.${kind}.${eventType}`, values: { event: eventType } },
            }];
          });
        return { lines, more: b.hasMore === true || str(b.next) !== undefined };
      });
    },
  };
}

const notes: Source = {
  name: "notes",
  kind: "notes",
  async read(deps, player, p, token, holds) {
    const r = await ask(deps, "players", `/players/${encodeURIComponent(player)}/notes`, {}, token, holds);
    return asked(r, (body) => ({
      lines: arr(obj(body).notes).map(obj).flatMap((n): Line[] => {
        const at = ms(n.createdAt);
        const id = str(n.noteId);
        if (at === undefined || !id) return [];
        return [{ id: `notes/${id}`, at: iso(at), kind: "notes", source: "notes", actor: { kind: "operator", ...(str(n.author) ? { id: str(n.author) } : {}) }, links: [], summary: { key: n.pinned === true ? "timeline.notes.pinned" : "timeline.notes.written", values: { text: str(n.text) ?? "" } } }];
      }).filter(within(p)),
      more: false,
    }));
  },
};

const messages: Source = {
  name: "messages",
  kind: "messages",
  async read(deps, player, p, token, holds) {
    const r = await ask(deps, "players", `/players/${encodeURIComponent(player)}/messages`, { page: "1", pageSize: "100" }, token, holds);
    return asked(r, (body) => {
      const b = obj(body);
      const items = arr(b.items).map(obj);
      const lines = items.flatMap((m): Line[] => {
        const at = ms(m.createdAt);
        const id = str(m.notificationId);
        if (at === undefined || !id) return [];
        const crm = str(m.originName) === "CRM";
        return [{ id: `messages/${id}`, at: iso(at), kind: "messages", source: "messages", actor: crm ? { kind: "system" } : { kind: "operator" }, links: [], summary: { key: "timeline.messages.sent", values: { subject: str(m.subject) ?? "", state: str(m.state) ?? "" } } }];
      });
      // newest first from players; more than a page held back by the oldest given
      return { lines: lines.filter(within(p)), more: (num(b.total) ?? items.length) > items.length };
    });
  },
};

const adjustments: Source = {
  name: "adjustments",
  kind: "adjustments",
  async read(deps, player, p, token, holds, context) {
    const currency = await context.currency();
    if (!currency) return { ok: false, reason: "no_currency" };
    const r = await ask(deps, "wallet", `/wallets/${encodeURIComponent(player)}/${encodeURIComponent(currency)}/transactions`, { type: "admin_adjust", from: String(p.from), to: String(p.to), page: "0", pageSize: "200" }, token, holds);
    return asked(r, (body) => {
      const b = obj(body);
      const rows = arr(b.transactions).map(obj);
      const lines = rows.flatMap((t): Line[] => {
        const at = ms(t.createdAt);
        const id = str(t.transactionId);
        if (at === undefined || !id) return [];
        const minor = minorText(t.amount, currency);
        const cause = obj(t.cause);
        const causeKind = str(cause.kind);
        const causeRef = str(cause.ref);
        return [{
          id: `adjustments/${id}`,
          at: iso(at),
          kind: "adjustments",
          source: "adjustments",
          actor: str(t.actorId) ? { kind: "operator", id: str(t.actorId) } : { kind: "operator" },
          ...(causeKind && causeRef ? { cause: { kind: causeKind, ref: causeRef } } : {}),
          ...(minor !== undefined ? { amount: { minor, currency } } : {}),
          links: [{ kind: "adjustment", id }, ...(causeKind === "round" && causeRef ? [{ kind: "round", id: causeRef }] : []), ...(causeKind === "payment" && causeRef ? [{ kind: "payment", id: causeRef }] : [])],
          summary: { key: "timeline.adjustments.made", values: { category: str(t.category) ?? "", reason: str(t.reason) ?? "", wallet: str(t.walletType) ?? "" } },
        }];
      });
      return { lines: lines.filter(within(p)), more: (num(b.total) ?? rows.length) > rows.length };
    });
  },
};

/** The wallet's major-unit amount text as minor units of the currency. */
function minorText(v: unknown, currency: string): number | undefined {
  const m = minorOf(v, currency);
  return m === undefined ? undefined : Number(m);
}

const withdrawals: Source = {
  name: "withdrawals",
  kind: "withdrawals",
  async read(deps, player, p, token, holds) {
    const r = await ask(deps, "payouts", `/players/${encodeURIComponent(player)}/withdrawal-history`, { page: "1", pageSize: "200" }, token, holds);
    return asked(r, (body) => {
      const b = obj(body);
      const rows = arr(b.requests).map(obj);
      const lines = rows.flatMap((w): Line[] => {
        const at = ms(w.createdAt);
        const id = str(w.requestId);
        if (at === undefined || !id) return [];
        const minor = num(w.amount);
        const currency = str(w.currency);
        return [{
          id: `withdrawals/${id}`,
          at: iso(at),
          kind: "withdrawals",
          source: "withdrawals",
          actor: { kind: "player" },
          ...(minor !== undefined && currency ? { amount: { minor, currency } } : {}),
          links: [{ kind: "withdrawal", id }],
          summary: { key: "timeline.withdrawals.requested", values: { status: (str(w.status) ?? "").toLowerCase(), payoutStatus: (str(w.payoutStatus) ?? "").toLowerCase() } },
        }];
      });
      return { lines: lines.filter(within(p)), more: (num(b.total) ?? rows.length) > rows.length };
    });
  },
};

const identity: Source = {
  name: "identity",
  kind: "identity",
  async read(deps, player, p, token, holds) {
    const r = await ask(deps, "kyc", `/${encodeURIComponent(player)}/decisions`, { from: iso(p.from), to: iso(p.to) }, token, holds);
    return asked(r, (body) => ({
      lines: arr(obj(body).decisions).map(obj).flatMap((d, i): Line[] => {
        const at = ms(d.at);
        if (at === undefined) return [];
        const kind = snake(str(d.kind) ?? "decision");
        const document = str(d.documentId);
        return [{
          id: `identity/${iso(at)}/${kind}/${i}`,
          at: iso(at),
          kind: "identity",
          source: "identity",
          actor: actorOf(d.actor),
          links: document ? [{ kind: "document", id: document }] : [],
          summary: { key: `timeline.identity.${kind}`, values: { category: str(d.category) ?? "", outcome: str(d.outcome) ?? "", reason: str(d.reasonCode) ?? "", to: Array.isArray(d.to) ? d.to.join(", ") : (str(d.to) ?? "") } },
        }];
      }),
      more: false,
    }));
  },
};

const protection: Source = {
  name: "protection",
  kind: "protection",
  async read(deps, player, p, token, holds) {
    const r = await ask(deps, "responsible-gambling", `/${encodeURIComponent(player)}/history`, { from: iso(p.from), to: iso(p.to) }, token, holds);
    return asked(r, (body) => ({
      lines: arr(body).map(obj).flatMap((h, i): Line[] => {
        const at = ms(h.occurredAt);
        if (at === undefined) return [];
        const event = snake(str(h.eventType) ?? "change");
        return [{
          id: `protection/${iso(at)}/${event}/${i}`,
          at: iso(at),
          kind: "protection",
          source: "protection",
          actor: actorOf(h.actor),
          links: [],
          summary: { key: `timeline.protection.${event}`, values: { limitType: str(h.limitType) ?? "", from: str(h.previousValue) ?? "", to: str(h.newValue) ?? "" } },
        }];
      }),
      more: false,
    }));
  },
};

/** Activity's money lines that another source tells better: bets and wins are the rounds', adjustments the wallet's. */
const TransactionsElsewhere = new Set(["bet", "win", "rollback", "loss", "adjustment"]);

export const Sources: readonly Source[] = [
  activity("payments", "payments", "transactions", (e) => !TransactionsElsewhere.has(e)),
  activity("rounds", "rounds", "games"),
  activity("rewards", "rewards", "bonus"),
  activity("account", "account", "account"),
  adjustments,
  withdrawals,
  identity,
  protection,
  notes,
  messages,
];

export interface TimelineQuery {
  from?: number;
  to?: number;
  /** Lines before this instant: the `next` of the page before. */
  after?: number;
  kinds?: readonly Kind[];
}

export interface TimelineDeps extends AskDeps {
  now(): number;
  /** An operator's name by their id, where it can be read; undefined otherwise. */
  names?(ids: readonly string[]): Promise<Map<string, string>>;
}

export const DefaultDays = 30;
const Day = 86_400_000;

/** The kinds named in `kinds=a,b`, the known ones only; every kind when none is named. */
export function kindsOf(raw: string | undefined): Kind[] | undefined {
  if (raw === undefined || raw.trim() === "") return [...Kinds];
  const named = raw.split(",").map((s) => s.trim());
  if (named.some((k) => !(Kinds as readonly string[]).includes(k))) return undefined;
  return named as Kind[];
}

export async function timeline(deps: TimelineDeps, player: string, q: TimelineQuery, accessToken: string, holds: (p: string) => boolean): Promise<Page> {
  const to = Math.min(q.to ?? deps.now(), q.after ?? Number.POSITIVE_INFINITY);
  const from = q.from ?? to - DefaultDays * Day;
  const period: Period = { from, to };
  const wanted = new Set(q.kinds ?? Kinds);
  let currency: Promise<string | undefined> | undefined;
  const context: Context = {
    currency: () =>
      (currency ??= ask(deps, "players", `/players/${encodeURIComponent(player)}/profile`, {}, accessToken, holds).then((r) => {
        if (!r.ok) return undefined;
        const b: Obj = obj(r.body);
        return str(obj(b.player ?? b).currency);
      })),
  };
  const sources = Sources.filter((s) => wanted.has(s.kind));
  const reads = await Promise.all(sources.map(async (s) => ({ s, r: await s.read(deps, player, period, accessToken, holds, context).catch((): Asked => ({ ok: false, reason: "unreadable" })) })));

  const gaps: Gap[] = [];
  let all: Line[] = [];
  /** The latest of the oldest lines of the sources that hold more: nothing older is emitted here. */
  let cut: number | undefined;
  for (const { s, r } of reads) {
    if (!r.ok || !("read" in r) || r.read === undefined) {
      // a source the operator's roles do not open is not a gap in what they may read
      if (!r.ok && r.reason === "forbidden") continue;
      gaps.push({ source: s.name, from: iso(from), to: iso(to), reason: r.ok ? "unreadable" : r.reason });
      continue;
    }
    const lines = r.read.lines.filter(within(period));
    if (r.read.more && lines.length > 0) {
      const oldest = Math.min(...lines.map((l) => Date.parse(l.at)));
      cut = cut === undefined ? oldest : Math.max(cut, oldest);
    }
    all = all.concat(lines);
  }
  const emitted = all
    .filter((l) => cut === undefined || Date.parse(l.at) >= cut)
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at) || a.id.localeCompare(b.id));

  if (deps.names) {
    const ids = [...new Set(emitted.flatMap((l) => (l.actor.kind === "operator" && l.actor.id ? [l.actor.id] : [])))];
    if (ids.length > 0) {
      const names = await deps.names(ids).catch(() => new Map<string, string>());
      for (const l of emitted) if (l.actor.id && names.has(l.actor.id)) l.actor.name = names.get(l.actor.id);
    }
  }
  return { lines: emitted, next: cut === undefined ? null : iso(cut), gaps, from: iso(from), to: iso(to) };
}

/** Operators' names from merchant's operators (research R12), kept five minutes; the signed-in operator from their own sign-in. */
export function operatorNames(deps: AskDeps, now: () => number) {
  let kept: { at: number; names: Map<string, string> } | undefined;
  return async (ids: readonly string[], accessToken: string, me: { sub?: string; name?: string }, holds: (p: string) => boolean): Promise<Map<string, string>> => {
    if (kept === undefined || now() - kept.at > 5 * 60_000) {
      const r = await ask(deps, "merchant", "/operators", {}, accessToken, holds);
      const names = new Map<string, string>();
      if (r.ok)
        for (const o of arr(obj(r.body).operators).map(obj)) {
          const id = str(o.operatorId);
          const name = [str(o.firstName), str(o.lastName)].filter(Boolean).join(" ") || str(o.username) || str(o.email);
          if (id && name) names.set(id, name);
        }
      // a refusal (merchant lists operators to payments operators only) is kept too: the ids are shown
      kept = { at: now(), names };
    }
    const out = new Map<string, string>();
    for (const id of ids) {
      const n = kept.names.get(id) ?? (me.sub === id ? me.name : undefined);
      if (n) out.set(id, n);
    }
    return out;
  };
}
