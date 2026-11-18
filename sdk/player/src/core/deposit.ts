import { amount as makeAmount, type Amount } from "../amount.ts";
import { behaviour, machine, type Answer, type Behaviour } from "../behaviour.ts";
import type { Clock } from "../clock.ts";
import type { RefusalCode } from "../codes.ts";
import { refusal as makeRefusal, type Refusal } from "../refusal.ts";
import type { CoreClient, CustomerDetails, Payment, Terminal } from "./client.ts";
import { checkDetails, need, presetAmounts, readsProfile, type DetailName } from "./schemes.ts";

/** The deposit behaviour's steps (data-model.md, *Deposit*). */
export type DepositStep =
  | { kind: "amount"; allowed: readonly ["confirm", "close"]; presets: readonly Amount[]; currency: string }
  | { kind: "starting"; allowed: readonly []; amount: Amount; requestId: string }
  | { kind: "refused"; allowed: readonly ["back", "close"] | readonly ["back", "close", "retry"]; refusal: Refusal; amount: Amount }
  | { kind: "choosing_terminal"; allowed: readonly ["choose", "back", "close"]; terminals: readonly Terminal[] }
  | { kind: "choosing_coin"; allowed: readonly ["chooseCoin", "back", "close"]; terminal: Terminal; coins: readonly string[] }
  | { kind: "details"; allowed: readonly ["giveDetails", "back", "close"]; terminal: Terminal; needs: readonly DetailName[]; known: Readonly<Partial<Record<DetailName, string>>>; invalid?: readonly DetailName[] }
  | { kind: "submitting"; allowed: readonly []; terminal: Terminal }
  | { kind: "waiting_at_provider"; allowed: readonly ["close"]; paymentId: string; address: string }
  | { kind: "waiting_on_crypto"; allowed: readonly ["close"]; paymentId: string; address: string; cryptoAmount: string; coin: string; expiresAt?: string }
  | { kind: "waiting"; allowed: readonly ["close"]; paymentId: string }
  | { kind: "succeeded"; allowed: readonly ["close"]; amount: Amount }
  | { kind: "failed"; allowed: readonly ["back", "close"]; code: RefusalCode; fields: Readonly<Record<string, unknown>>; words?: string }
  | { kind: "closed"; allowed: readonly [] };

type A<K extends string = DepositStep["kind"]> = Answer<K>;

export interface DepositActions {
  /** An amount of minor units of the player's currency. */
  confirm(minor: bigint): A;
  retry(): A;
  choose(terminalId: string): A;
  chooseCoin(coin: string): A;
  giveDetails(details: Readonly<Partial<Record<DetailName, string>>> & { readonly dialCode?: string }): A;
  back(): A;
  close(): A;
}

export type DepositBehaviour = Behaviour<DepositStep, DepositActions>;

export interface DepositOptions {
  readonly core: CoreClient;
  readonly clock: Clock;
  readonly currency: string;
  readonly country: string;
  /** The site's preset amounts for the currency, in major units; the original's when it names none. */
  readonly presets?: readonly (string | number)[];
  readonly minimum?: string | number;
  readonly maximum?: string | number;
  /** Told when a deposit succeeds, so the funds are read again (FR-022). */
  readonly succeeded?: () => void;
  /** A fresh request id per confirmation; `crypto.randomUUID` by default. */
  readonly requestId?: () => string;
}

export const DefaultPresets = [10, 25, 50, 100, 200, 500] as const;
export const FirstReadMs = 1500;
export const WaitingReadMs = 3000;
export const BoundMs = 20 * 60_000;

const waiting = new Set(["waiting_at_provider", "waiting_on_crypto", "waiting"]);

export function depositBehaviour(options: DepositOptions): DepositBehaviour {
  const { core, currency } = options;
  const mint = options.requestId ?? (() => crypto.randomUUID());
  const amountStep: DepositStep = {
    kind: "amount",
    allowed: ["confirm", "close"],
    presets: presetAmounts(options.presets ?? DefaultPresets, currency, { ...(options.minimum !== undefined ? { minimum: options.minimum } : {}), ...(options.maximum !== undefined ? { maximum: options.maximum } : {}) }),
    currency,
  };
  const m = machine<DepositStep>(amountStep, options.clock);

  // Held by the behaviour alone: never in a step, page storage or an address (FR-024).
  let ticket: string | null = null;
  let terminals: readonly Terminal[] = [];
  let started: { amount: Amount; requestId: string } | null = null;
  let polling: (() => void) | undefined;
  let bound: (() => void) | undefined;

  const stopPolling = () => {
    polling?.();
    bound?.();
    polling = bound = undefined;
  };
  const failed = (code: RefusalCode, fields: Readonly<Record<string, unknown>> = {}, words?: string): DepositStep => ({ kind: "failed", allowed: ["back", "close"], code, fields, ...(words ? { words } : {}) });
  const fail = (step: DepositStep) => {
    stopPolling();
    m.set(step);
  };
  const choosing = (): DepositStep => ({ kind: "choosing_terminal", allowed: ["choose", "back", "close"], terminals });

  function start(a: Amount, requestId: string) {
    started = { amount: a, requestId };
    const step: DepositStep = { kind: "starting", allowed: [], amount: a, requestId };
    m.set(step);
    void core.startDeposit(a, requestId).then((r) => {
      if (!m.still(step)) return;
      if (!r.ok) {
        const unanswered = r.refusal.code === "service_unavailable";
        m.set({ kind: "refused", allowed: unanswered ? ["back", "close", "retry"] : ["back", "close"], refusal: r.refusal, amount: a });
        return;
      }
      ticket = r.value.cashierTicket;
      terminals = r.value.terminals;
      m.set(terminals.length === 0 ? failed("no_terminals") : choosing());
    });
  }

  /** What a payment read means: an outcome, a waiting step, or nothing yet. */
  function outcome(paymentId: string, p: Payment): DepositStep | null {
    switch (p.status) {
      case "COMPLETED":
        return { kind: "succeeded", allowed: ["close"], amount: started!.amount };
      case "FAILED":
        return failed("payment_failed", p.failureCode ? { failureCode: p.failureCode } : {});
      case "EXPIRED":
        return failed("payment_expired", p.failureCode ? { failureCode: p.failureCode } : {});
      case "AWAITING_REDIRECT":
        return p.redirectUrl ? { kind: "waiting_at_provider", allowed: ["close"], paymentId, address: p.redirectUrl } : null;
      case "AWAITING_CRYPTO":
        return p.paymentAddress && p.cryptoAmount
          ? { kind: "waiting_on_crypto", allowed: ["close"], paymentId, address: p.paymentAddress, cryptoAmount: p.cryptoAmount, coin: p.cryptoCurrency ?? "", ...(p.validUntil ? { expiresAt: p.validUntil } : {}) }
          : null;
      case "AWAITING_PAYMENT":
        return { kind: "waiting", allowed: ["close"], paymentId };
      default:
        return null;
    }
  }

  function poll(paymentId: string, every: number) {
    polling?.();
    let reading = false;
    polling = m.every(every, () => {
      if (reading || !ticket) return;
      reading = true;
      const at = m.step();
      void core.payment(ticket, paymentId).then((r) => {
        reading = false;
        // A read that fails is ignored until the bound (FR-022).
        if (!r.ok || !m.still(at)) return;
        const next = outcome(paymentId, r.value);
        if (!next) return;
        if (next.kind === "succeeded") {
          stopPolling();
          m.set(next);
          options.succeeded?.();
          return;
        }
        if (next.kind === "failed") return fail(next);
        // Only an outcome moves a waiting step; a submitted one moves to its waiting step once.
        if (waiting.has(at.kind)) return;
        m.set(next);
        poll(paymentId, WaitingReadMs);
      });
    });
  }

  function submit(terminal: Terminal, extra: { coin?: string; details?: CustomerDetails } = {}) {
    if (!ticket) return;
    const step: DepositStep = { kind: "submitting", allowed: [], terminal };
    m.set(step);
    void core.submitCashier(ticket, terminal.id, extra).then((r) => {
      if (!m.still(step)) return;
      if (!r.ok) return fail(failed(r.refusal.code, r.refusal.fields as Record<string, unknown>, r.refusal.code === "PAYMENT_REJECTED" ? r.refusal.words : undefined));
      bound = m.after(BoundMs, () => fail(failed("timed_out")));
      poll(r.value.paymentId, FirstReadMs);
    });
  }

  const actions: DepositActions = {
    confirm: m.action("confirm", (minor: bigint) => {
      if (minor <= 0n) return m.set({ kind: "refused", allowed: ["back", "close"], refusal: makeRefusal("invalid_amount", 0), amount: makeAmount(minor, currency) });
      start(makeAmount(minor, currency), mint());
    }),
    retry: m.action("retry", () => {
      if (started) start(started.amount, started.requestId);
    }),
    choose: m.action("choose", (terminalId: string) => {
      const terminal = terminals.find((t) => t.id === terminalId);
      if (!terminal) return;
      if (terminal.scheme === "CRYPTO") return m.set({ kind: "choosing_coin", allowed: ["chooseCoin", "back", "close"], terminal, coins: terminal.currencies ?? [] });
      if (!readsProfile(terminal.scheme)) return submit(terminal);
      const step: DepositStep = { kind: "submitting", allowed: [], terminal };
      m.set(step);
      void core.profile().then((r) => {
        if (!m.still(step)) return;
        const n = need(terminal, r.ok ? r.value : null, options.country);
        if (n.kind === "details") m.set({ kind: "details", allowed: ["giveDetails", "back", "close"], terminal, needs: n.needs, known: n.known });
        else if (n.kind === "none") submit(terminal, n.details ? { details: n.details } : {});
      });
    }),
    chooseCoin: m.action("chooseCoin", (coin: string) => {
      const at = m.step();
      if (at.kind === "choosing_coin") submit(at.terminal, { coin });
    }),
    giveDetails: m.action("giveDetails", (given) => {
      const at = m.step();
      if (at.kind !== "details") return;
      const checked = checkDetails(at.terminal.scheme, { ...at.known, ...given }, options.country);
      if (!checked.ok) return m.set({ ...at, invalid: checked.invalid });
      const step: DepositStep = { kind: "submitting", allowed: [], terminal: at.terminal };
      m.set(step);
      // Saved to the profile first; its failure is ignored, as the original's (FR-019).
      void core.changeProfile({ ...checked.profile }).then(() => {
        if (m.still(step)) submit(at.terminal, { details: checked.submit });
      });
    }),
    back: m.action("back", () => {
      const at = m.step();
      if (at.kind === "choosing_coin" || at.kind === "details") return m.set(choosing());
      m.set(amountStep);
    }),
    close: m.action("close", () => {
      const at = m.step();
      // Abandoned at a waiting step, not awaited: it is sent so it outlives the page (FR-023).
      if (waiting.has(at.kind) && ticket) void core.abandonCashier(ticket);
      stopPolling();
      m.set({ kind: "closed", allowed: [] });
      m.stop();
    }),
  };
  return behaviour(m, actions);
}
