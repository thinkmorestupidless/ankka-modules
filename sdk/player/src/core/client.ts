import { z } from "zod";
import { minorOnWire, readMajor, type Amount } from "../amount.ts";
import { ask, row, type Result } from "../client.ts";
import { refusal } from "../refusal.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";

/** What a visitor gives to register (players' `POST /register`). */
export interface Registration {
  readonly email: string;
  readonly password: string;
  readonly currency: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly displayName?: string;
  readonly country?: string;
  readonly language?: string;
  readonly dateOfBirth?: string;
  readonly emailMarketingConsent?: boolean;
  readonly smsMarketingConsent?: boolean;
  readonly deviceFingerprint?: string;
}

/** A terminal a cashier session offers (data-model.md, *Deposit*). */
export interface Terminal {
  readonly id: string;
  readonly name: string;
  readonly scheme: string;
  readonly gateway?: string;
  readonly currencies?: readonly string[];
}

/** The details a cashier submit carries, under the SDK's names; sent as the cashier's `customer…`. */
export interface CustomerDetails {
  readonly firstName?: string;
  readonly lastName?: string;
  readonly phone?: string;
  readonly mobile?: string;
  readonly province?: string;
  readonly address?: string;
  readonly city?: string;
  readonly postCode?: string;
  readonly state?: string;
}

export interface Payment {
  readonly status: string;
  readonly redirectUrl?: string;
  readonly paymentAddress?: string;
  readonly cryptoAmount?: string;
  readonly cryptoCurrency?: string;
  readonly validUntil?: string;
  readonly failureCode?: string;
}

export interface Balance {
  readonly real: Amount;
  readonly restricted: Amount;
  readonly available: Amount;
}

const Registered = z.object({ playerId: z.string(), status: z.string() });
const Verified = z.object({ status: z.string() }).partial().passthrough();
const Resent = z.object({ result: z.string() }).partial().passthrough();
const Anything = z.unknown();
const Profile = z.record(z.string(), z.unknown());
const Updated = z.object({ updated: z.boolean() }).partial().passthrough();
const opt = z.string().nullish().transform((v) => v ?? undefined);
const WireTerminal = z.object({ terminalEntityId: z.string(), name: z.string().nullish(), scheme: z.string(), gateway: opt, supportedCurrencies: z.array(z.string()).nullish() });
const Started = z.object({ cashierTicket: z.string().min(1), terminals: z.array(WireTerminal), requestId: z.string() });
const Submitted = z.object({ paymentId: z.string(), status: z.string() });
const WirePayment = z.object({ status: z.string(), redirectUrl: opt, paymentAddress: opt, cryptoAmount: opt, cryptoCurrency: opt, validUntil: opt, failureCode: opt });
const WireSession = z.object({ sessionId: z.string().optional(), status: z.string(), lastBetAt: z.string().nullish() });
const Ended = z.object({ status: z.string() }).partial().passthrough();
const Country = z.object({ country: z.string().nullish(), dialCode: z.string().nullish() });
const Protection = z.object({
  realityCheckIntervalMinutes: z.number().int().positive().nullish().catch(null),
  isCoolingOff: z.boolean().catch(false).default(false),
  coolingOffUntil: z.string().nullish().catch(null),
});
const Unread = z.object({ unreadCount: z.number().int().nonnegative() });
const Acknowledged = z.object({ acknowledged: z.boolean() }).partial().passthrough();
const MajorBalance = z.object({ realBalance: z.string(), bonusBalance: z.string(), available: z.string().nullish(), currency: z.string() });

const terminal = (t: z.infer<typeof WireTerminal>): Terminal => ({
  id: t.terminalEntityId,
  name: t.name ?? "",
  scheme: t.scheme,
  ...(t.gateway ? { gateway: t.gateway } : {}),
  ...(t.supportedCurrencies ? { currencies: t.supportedCurrencies } : {}),
});

/** A payment as the page is given it: `failureReason` and the rest of the read are not passed on. */
const payment = (p: z.infer<typeof WirePayment>): Payment => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined)) as unknown as Payment;

const customer: Record<keyof CustomerDetails, string> = {
  firstName: "customerFirstName",
  lastName: "customerLastName",
  phone: "customerPhone",
  mobile: "customerMobile",
  province: "customerProvince",
  address: "customerAddress",
  city: "customerCity",
  postCode: "customerPostCode",
  state: "customerState",
};

/** `core`'s client (contracts/module-clients.md): one function per row a page may call. */
export interface CoreClient {
  register(r: Registration): Promise<Result<z.infer<typeof Registered>>>;
  verifyEmail(token: string): Promise<Result<z.infer<typeof Verified>>>;
  resendVerification(email: string): Promise<Result<z.infer<typeof Resent>>>;
  requestPasswordReset(email: string): Promise<Result<unknown>>;
  profile(): Promise<Result<Record<string, unknown>>>;
  changeProfile(fields: Record<string, string>): Promise<Result<unknown>>;
  startDeposit(amount: Amount, requestId: string): Promise<Result<{ cashierTicket: string; terminals: Terminal[]; requestId: string }>>;
  balance(currency: string): Promise<Result<Balance>>;
  submitCashier(ticket: string, terminalId: string, options?: { coin?: string; details?: CustomerDetails }): Promise<Result<z.infer<typeof Submitted>>>;
  /** Sent with `keepalive`, so it outlives the page; nobody waits on it. */
  abandonCashier(ticket: string): Promise<Result<unknown>>;
  payment(ticket: string, paymentId: string): Promise<Result<Payment>>;
  /** A game session's status and its last bet (players, FR-057). */
  gameSession(sessionId: string): Promise<Result<{ status: string; lastBetAt: string | null }>>;
  /** Ends a game session as the player's leaving: no body, sent so it outlives the page (FR-032). */
  endGameSession(sessionId: string): Promise<Result<unknown>>;
  /** The visitor's country and dial code by their address; both null when unknown. */
  country(): Promise<Result<{ country: string | null; dialCode: string | null }>>;
  /** What of the player's responsible-gambling status this slice reads. */
  protection(currency: string): Promise<Result<{ realityCheckIntervalMinutes: number | null; isCoolingOff: boolean; coolingOffUntil: string | null }>>;
  acknowledgeRealityCheck(sessionId: string): Promise<Result<unknown>>;
  /** The count of the player's unread messages. */
  unread(): Promise<Result<number>>;
}

/** `player` names the signed-in player, whose id fills `{player}`. */
export function coreClient(transport: Transport, player: () => string | null = () => null): CoreClient {
  const me = () => ({ player: player() ?? "" });
  const ticket = (t: string) => ({ cashierTicket: t });
  return {
    register: (r) => ask(transport, row(routes, "register"), Registered, { body: r }),
    verifyEmail: (token) => ask(transport, row(routes, "verifyEmail"), Verified, { body: { token } }),
    resendVerification: (email) => ask(transport, row(routes, "resendVerification"), Resent, { body: { email } }),
    requestPasswordReset: (email) => ask(transport, row(routes, "requestPasswordReset"), Anything, { body: { email } }),
    profile: () => ask(transport, row(routes, "profile"), Profile, { path: me() }),
    changeProfile: (fields) => ask(transport, row(routes, "changeProfile"), Updated, { path: me(), body: fields }),
    async startDeposit(amount, requestId) {
      const r = await ask(transport, row(routes, "startDeposit"), Started, { path: me(), body: { amount: minorOnWire(amount), currency: amount.currency, requestId } });
      return r.ok ? { ok: true, value: { cashierTicket: r.value.cashierTicket, terminals: r.value.terminals.map(terminal), requestId: r.value.requestId } } : r;
    },
    async balance(currency) {
      const r = await ask(transport, row(routes, "balance"), MajorBalance, { path: { currency } });
      if (!r.ok) return r;
      const real = readMajor(r.value.realBalance, currency);
      const restricted = readMajor(r.value.bonusBalance, currency);
      const available = readMajor(r.value.available ?? r.value.realBalance, currency);
      // An amount that does not fit its currency is refused, never rounded (FR-037).
      if (!real.ok || !restricted.ok || !available.ok) return { ok: false, refusal: refusal("malformed_amount", 200, undefined, "wallet") };
      return { ok: true, value: { real: real.amount, restricted: restricted.amount, available: available.amount } };
    },
    async submitCashier(t, terminalId, options = {}) {
      const body: Record<string, string> = { selectedTerminalEntityId: terminalId };
      if (options.coin) body.cryptoCurrency = options.coin;
      for (const [k, v] of Object.entries(options.details ?? {})) if (v) body[customer[k as keyof CustomerDetails]] = v;
      const r = await ask(transport, row(routes, "submitCashier"), Submitted, { headers: ticket(t), body, words: true });
      // Words are passed on from a provider's rejection only (FR-022); every other refusal's are dropped.
      if (!r.ok && r.refusal.code !== "PAYMENT_REJECTED" && r.refusal.words !== undefined) {
        const { words: _dropped, ...kept } = r.refusal;
        return { ok: false, refusal: kept };
      }
      return r;
    },
    abandonCashier: (t) => ask(transport, row(routes, "abandonCashier"), Anything, { headers: ticket(t), keepalive: true }),
    async gameSession(sessionId) {
      const r = await ask(transport, row(routes, "gameSession"), WireSession, { path: { sessionId } });
      return r.ok ? { ok: true, value: { status: r.value.status, lastBetAt: r.value.lastBetAt ?? null } } : r;
    },
    endGameSession: (sessionId) => ask(transport, row(routes, "endGameSession"), Ended, { path: { sessionId }, keepalive: true }),
    async country() {
      const r = await ask(transport, row(routes, "country"), Country);
      return r.ok ? { ok: true, value: { country: r.value.country ?? null, dialCode: r.value.dialCode ?? null } } : r;
    },
    async protection(currency) {
      const r = await ask(transport, row(routes, "protection"), Protection, { path: me(), query: { currency } });
      return r.ok ? { ok: true, value: { realityCheckIntervalMinutes: r.value.realityCheckIntervalMinutes ?? null, isCoolingOff: r.value.isCoolingOff, coolingOffUntil: r.value.coolingOffUntil ?? null } } : r;
    },
    acknowledgeRealityCheck: (sessionId) => ask(transport, row(routes, "acknowledgeRealityCheck"), Acknowledged, { path: me(), body: { sessionId } }),
    async unread() {
      const r = await ask(transport, row(routes, "unread"), Unread, { path: me() });
      return r.ok ? { ok: true, value: r.value.unreadCount } : r;
    },
    async payment(t, paymentId) {
      const r = await ask(transport, row(routes, "payment"), WirePayment, { headers: ticket(t), path: { paymentId } });
      return r.ok ? { ok: true, value: payment(r.value) } : r;
    },
  };
}
