import { minorOnWire, readMajor, type Amount } from "../amount.ts";
import { ask, row, type Result } from "../client.ts";
import { refusal } from "../refusal.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";
import * as shapes from "./shapes.ts";

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

const terminal = (t: NonNullable<shapes.Started["terminals"]>[number]): Terminal => ({
  id: t.terminalEntityId,
  name: t.name ?? "",
  scheme: t.scheme,
  ...(t.gateway ? { gateway: t.gateway } : {}),
  ...(t.supportedCurrencies ? { currencies: t.supportedCurrencies } : {}),
});

/** A payment as the page is given it: `failureReason` and the rest of the read are not passed on. */
const payment = (p: shapes.WirePayment): Payment => ({
  status: p.status,
  ...(typeof p.redirectUrl === "string" ? { redirectUrl: p.redirectUrl } : {}),
  ...(typeof p.paymentAddress === "string" ? { paymentAddress: p.paymentAddress } : {}),
  ...(typeof p.cryptoAmount === "string" ? { cryptoAmount: p.cryptoAmount } : {}),
  ...(typeof p.cryptoCurrency === "string" ? { cryptoCurrency: p.cryptoCurrency } : {}),
  ...(typeof p.validUntil === "string" ? { validUntil: p.validUntil } : {}),
  ...(typeof p.failureCode === "string" ? { failureCode: p.failureCode } : {}),
});

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
  register(r: Registration): Promise<Result<{ playerId: string; status: string }>>;
  verifyEmail(token: string): Promise<Result<{ status?: string }>>;
  resendVerification(email: string): Promise<Result<{ result?: string }>>;
  requestPasswordReset(email: string): Promise<Result<unknown>>;
  profile(): Promise<Result<Record<string, unknown>>>;
  changeProfile(fields: Record<string, string>): Promise<Result<unknown>>;
  startDeposit(amount: Amount, requestId: string): Promise<Result<{ cashierTicket: string; terminals: Terminal[]; requestId: string }>>;
  balance(currency: string): Promise<Result<Balance>>;
  submitCashier(ticket: string, terminalId: string, options?: { coin?: string; details?: CustomerDetails }): Promise<Result<{ paymentId: string; status: string }>>;
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
    register: (r) => ask(transport, row(routes, "register"), shapes.Registered, { body: { ...r } satisfies shapes.Register }),
    verifyEmail: (token) => ask(transport, row(routes, "verifyEmail"), shapes.Verified, { body: { token } satisfies shapes.VerifyEmail }),
    resendVerification: (email) => ask(transport, row(routes, "resendVerification"), shapes.Resent, { body: { email } satisfies shapes.ResendVerification }),
    requestPasswordReset: (email) => ask(transport, row(routes, "requestPasswordReset"), shapes.ResetRequested, { body: { email } satisfies shapes.RequestPasswordReset }),
    profile: () => ask(transport, row(routes, "profile"), shapes.Profile, { path: me() }),
    changeProfile: (fields) => ask(transport, row(routes, "changeProfile"), shapes.Updated, { path: me(), body: fields satisfies shapes.ProfileEdit }),
    async startDeposit(amount, requestId) {
      const r = await ask(transport, row(routes, "startDeposit"), shapes.Started, { path: me(), body: { amount: minorOnWire(amount), currency: amount.currency, requestId } satisfies shapes.StartDeposit });
      if (!r.ok) return r;
      // A cashier session with no ticket is none: the description says a string, the cashier needs one.
      if (r.value.cashierTicket === "") return { ok: false, refusal: refusal("malformed_answer", 200, {}, "players") };
      return { ok: true, value: { cashierTicket: r.value.cashierTicket, terminals: (r.value.terminals ?? []).map(terminal), requestId: r.value.requestId } };
    },
    async balance(currency) {
      const r = await ask(transport, row(routes, "balance"), shapes.MajorBalance, { path: { currency } });
      if (!r.ok) return r;
      const real = readMajor(r.value.realBalance, currency);
      const restricted = readMajor(r.value.bonusBalance, currency);
      const available = readMajor(r.value.available, currency);
      // An amount that does not fit its currency is refused, never rounded (FR-037).
      if (!real.ok || !restricted.ok || !available.ok) return { ok: false, refusal: refusal("malformed_amount", 200, undefined, "wallet") };
      return { ok: true, value: { real: real.amount, restricted: restricted.amount, available: available.amount } };
    },
    async submitCashier(t, terminalId, options = {}) {
      const body: shapes.Submit & Record<string, string> = { selectedTerminalEntityId: terminalId };
      if (options.coin) body.cryptoCurrency = options.coin;
      for (const [k, v] of Object.entries(options.details ?? {})) if (v) body[customer[k as keyof CustomerDetails]] = v;
      const r = await ask(transport, row(routes, "submitCashier"), shapes.Submitted, { headers: ticket(t), body, words: true });
      // Words are passed on from a provider's rejection only (FR-022); every other refusal's are dropped.
      if (!r.ok && r.refusal.code !== "PAYMENT_REJECTED" && r.refusal.words !== undefined) {
        const { words: _dropped, ...kept } = r.refusal;
        return { ok: false, refusal: kept };
      }
      return r;
    },
    abandonCashier: (t) => ask(transport, row(routes, "abandonCashier"), shapes.Abandoned, { headers: ticket(t), keepalive: true }),
    async gameSession(sessionId) {
      const r = await ask(transport, row(routes, "gameSession"), shapes.GameSession, { path: { sessionId } });
      return r.ok ? { ok: true, value: { status: r.value.status, lastBetAt: r.value.lastBetAt ?? null } } : r;
    },
    endGameSession: (sessionId) => ask(transport, row(routes, "endGameSession"), shapes.Ended, { path: { sessionId }, keepalive: true }),
    async country() {
      const r = await ask(transport, row(routes, "country"), shapes.Country);
      return r.ok ? { ok: true, value: { country: r.value.country ?? null, dialCode: r.value.dialCode ?? null } } : r;
    },
    async protection(currency) {
      const r = await ask(transport, row(routes, "protection"), shapes.Protection, { path: me(), query: { currency } });
      return r.ok ? { ok: true, value: { realityCheckIntervalMinutes: (r.value.realityCheckIntervalMinutes ?? 0) > 0 ? r.value.realityCheckIntervalMinutes! : null, isCoolingOff: r.value.isCoolingOff, coolingOffUntil: r.value.coolingOffUntil ?? null } } : r;
    },
    acknowledgeRealityCheck: (sessionId) => ask(transport, row(routes, "acknowledgeRealityCheck"), shapes.Acknowledged, { path: me(), body: { sessionId } satisfies shapes.Acknowledge }),
    async unread() {
      const r = await ask(transport, row(routes, "unread"), shapes.Unread, { path: me() });
      return r.ok ? { ok: true, value: r.value.unreadCount } : r;
    },
    async payment(t, paymentId) {
      const r = await ask(transport, row(routes, "payment"), shapes.WirePayment, { headers: ticket(t), path: { paymentId } });
      return r.ok ? { ok: true, value: payment(r.value) } : r;
    },
  };
}
