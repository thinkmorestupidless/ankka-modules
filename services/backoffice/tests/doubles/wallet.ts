// The wallet's operator adjustments as the wallet double keeps them, once a scenario seeds wallets: the
// balance read, and an adjustment under its key (spec 030 W2). A key used again answers its first
// outcome, a refusal included, an adjustment's with `repeated: true` (the first `repeated: false`); a
// debit past the balance is refused, not reduced; a reason is required;
// the transactions read lists each adjustment with its reason, category, cause and actor. The wallet's
// own suites hold its rules in full; this keeps what the backoffice's scenarios read back.
import type { Answer } from "./owner.ts";

export interface SeededWallet {
  player: string;
  currency: string;
  /** Minor units. */
  real: number;
  restricted?: number;
}

export interface WalletSeed {
  wallets: SeededWallet[];
}

interface Kept extends SeededWallet {
  restricted: number;
  /** Each key's first outcome, and what it was asked. */
  keys: Map<string, { asked: string; answer: Answer }>;
  transactions: Record<string, unknown>[];
}

const Balance = /^\/v1\/casino\/backoffice\/[^/]+\/wallets\/([^/]+)\/([^/]+)\/balance$/;
const Adjust = /^\/v1\/casino\/backoffice\/[^/]+\/wallets\/([^/]+)\/([^/]+)\/adjustments$/;
const Transactions = /^\/v1\/casino\/backoffice\/[^/]+\/wallets\/([^/]+)\/([^/]+)\/transactions$/;

/** The wallet's major text: minor units over 100, two decimals, whatever the currency (Wire.major). */
const major = (minor: number) => `${minor < 0 ? "-" : ""}${Math.floor(Math.abs(minor) / 100)}.${String(Math.abs(minor) % 100).padStart(2, "0")}`;

export class Wallets {
  private kept = new Map<string, Kept>();

  reset(): void {
    this.kept.clear();
  }

  seed(s: WalletSeed): void {
    for (const w of s.wallets)
      this.kept.set(`${w.player}/${w.currency}`, { ...structuredClone(w), restricted: w.restricted ?? 0, keys: new Map(), transactions: [] });
  }

  private balance(w: Kept) {
    return {
      realBalance: major(w.real), bonusBalance: major(w.restricted), currency: w.currency, primary: null,
      depositPoolBalance: major(w.real), convertedPoolBalance: "0.00", reserved: "0.00", available: major(w.real),
    };
  }

  /** An answer for a wallet route of a seeded wallet; undefined otherwise, so the fixtures answer. */
  handle(method: string, path: string, body: string, subject: string | undefined): Answer | undefined {
    if (this.kept.size === 0) return undefined;
    const at = (re: RegExp) => {
      const m = re.exec(path);
      return m ? this.kept.get(`${decodeURIComponent(m[1]!)}/${decodeURIComponent(m[2]!)}`) : undefined;
    };
    if (method === "GET") {
      const b = at(Balance);
      if (b) return { body: this.balance(b) };
      const t = at(Transactions);
      if (t) return { body: { transactions: [...t.transactions].reverse(), page: 0, pageSize: 50, total: t.transactions.length } };
      return undefined;
    }
    const w = method === "POST" ? at(Adjust) : undefined;
    if (!w) return undefined;
    let r: { key?: string; walletType?: string; amountMinor?: number; reason?: string; category?: string; cause?: { kind: string; ref: string } };
    try {
      r = JSON.parse(body || "{}") as typeof r;
    } catch {
      return { status: 400, body: { error: "INVALID_REQUEST", message: "the body is not JSON" } };
    }
    if (!r.key) return { status: 400, body: { error: "MISSING_KEY", message: "a money command names its key" } };
    const target = r.walletType === "restricted" || r.walletType === "bonus" ? "restricted" : "real";
    const asked = JSON.stringify([target, r.amountMinor, r.reason, r.category ?? "other", r.cause ?? null]);
    const first = w.keys.get(r.key);
    if (first) {
      // the first outcome, with the balance as it is now: the wallet reads the balance after its answer
      if (first.asked === asked)
        return (first.answer.status ?? 200) === 200 ? { body: { ...(first.answer.body as object), ...this.balance(w), repeated: true } } : first.answer;
      return { status: 409, body: { error: "KEY_REUSED", message: `Key ${r.key} was used by an adjust` } };
    }
    const keep = (answer: Answer) => (w.keys.set(r.key!, { asked, answer }), answer);
    if (!r.reason || r.reason.trim() === "") return keep({ status: 400, body: { error: "REASON_REQUIRED", message: "A reason must be supplied for a manual balance adjustment" } });
    const amount = Number(r.amountMinor ?? 0);
    const before = target === "real" ? w.real : w.restricted;
    if (amount < 0 && -amount > before)
      return keep({
        status: 400,
        body: { error: "DEBIT_EXCEEDS_BALANCE", message: `Requested debit of ${-amount} exceeds current ${target === "real" ? "real" : "bonus"} balance of ${before}`, details: { requestedAmount: String(-amount), currentBalance: String(before) } },
      });
    const after = before + amount;
    if (target === "real") w.real = after;
    else w.restricted = after;
    const txnId = `txn-${w.player}-${w.transactions.length + 1}`;
    w.transactions.push({
      transactionId: txnId, type: "admin_adjust", walletType: target === "real" ? "real" : "bonus", amount: major(amount), balanceBefore: major(before), balanceAfter: major(after),
      status: "completed", roundId: null, awardRef: null, paymentReference: null, createdAt: new Date().toISOString(), depositPart: "0.00", convertedPart: "0.00",
      depositPoolAfter: major(w.real), convertedPoolAfter: "0.00", reason: r.reason, category: r.category ?? "other", cause: r.cause ?? null, actorId: subject ?? null,
    });
    return keep({ body: { ...this.balance(w), txnId, walletType: target === "real" ? "real" : "bonus", amountMinor: amount, before, after, ...(r.cause ? { cause: r.cause } : {}), repeated: false } });
  }
}
