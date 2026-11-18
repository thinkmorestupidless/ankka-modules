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
      if (!real.ok || !restricted.ok || !available.ok) return { ok: false, refusal: refusal("malformed_answer", 200, {}, "wallet") };
      return { ok: true, value: { real: real.amount, restricted: restricted.amount, available: available.amount } };
    },
    submitCashier(t, terminalId, options = {}) {
      const body: Record<string, string> = { selectedTerminalEntityId: terminalId };
      if (options.coin) body.cryptoCurrency = options.coin;
      for (const [k, v] of Object.entries(options.details ?? {})) if (v) body[customer[k as keyof CustomerDetails]] = v;
      return ask(transport, row(routes, "submitCashier"), Submitted, { headers: ticket(t), body });
    },
    abandonCashier: (t) => ask(transport, row(routes, "abandonCashier"), Anything, { headers: ticket(t), keepalive: true }),
    async payment(t, paymentId) {
      const r = await ask(transport, row(routes, "payment"), WirePayment, { headers: ticket(t), path: { paymentId } });
      return r.ok ? { ok: true, value: payment(r.value) } : r;
    },
  };
}
