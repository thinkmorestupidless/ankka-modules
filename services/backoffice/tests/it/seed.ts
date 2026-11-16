// The seed for quickstart §6 (T123; SC-005, SC-007): 10,000 players, 1,000 rewards and 5,000 withdrawal
// requests in a brand on a local installation, with amounts in JPY, EUR and KWD (zero, two and three
// minor units), made only through the services' own routes: a visitor's registration and sign-in at
// `players`, a player's withdrawal request at `payouts`, and every operator's step through the
// backoffice's relay as a signed-in `brand_admin` (the verification link, identity, the credit, the
// reward). Nothing is written to a store.
//
//   node tests/it/seed.ts <slug> [--players N] [--rewards N] [--withdrawals N] [--width N]
//
// What it made is written to `test-results/it/seed-<slug>.json` (BACKOFFICE_IT_SEED) as it goes, so a
// run stopped part way is taken up again by the next: a player is registered once, each step is done
// once and under its own key. Players 0 to withdrawals-1 each make one withdrawal request (a player holds
// one pending request at a time); the next `rewards` players are each given a free bonus; the rest are
// registered only. Currencies go JPY, EUR, KWD in turn.
import { randomBytes } from "node:crypto";
import { existsSync } from "node:fs";
import { installation, operators, Operator, pool, readSeed, SeedCurrencies, sleep, writeSeed, type Installation, type Seed, type SeededPlayer, type SeedCurrency } from "./support.ts";

interface Options {
  players: number;
  rewards: number;
  withdrawals: number;
  width: number;
}

function options(argv: string[]): { brand?: string; options: Options } {
  const o: Options = { players: 10_000, rewards: 1_000, withdrawals: 5_000, width: 12 };
  let brand: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    const n = (name: keyof Options) => {
      const v = Number(argv[++i]);
      if (!Number.isInteger(v) || v < 0) throw new Error(`--${name} takes a whole number`);
      o[name] = v;
    };
    if (a === "--players") n("players");
    else if (a === "--rewards") n("rewards");
    else if (a === "--withdrawals") n("withdrawals");
    else if (a === "--width") n("width");
    else if (!a.startsWith("--")) brand = a;
    else throw new Error(`unknown option ${a}`);
  }
  if (o.withdrawals + o.rewards > o.players) throw new Error("withdrawals and rewards are made by different players: players must be at least their sum");
  return { brand, options: o };
}

/** Amounts by currency, in minor units: the credit, and a request well under it and under the brand's limits. */
const Amounts: Record<SeedCurrency, { credit: number; request: (i: number) => number; bonus: number }> = {
  JPY: { credit: 50_000, request: (i) => 1_000 + (i % 997), bonus: 1_000 },
  EUR: { credit: 50_000, request: (i) => 1_000 + (i % 997), bonus: 1_000 },
  KWD: { credit: 500_000, request: (i) => 10_000 + (i % 997), bonus: 10_000 },
};

interface Reply {
  status: number;
  body: Record<string, unknown> & { error?: string; code?: string; reason?: string };
}

async function call(method: string, url: string, body?: unknown, headers: Record<string, string> = {}): Promise<Reply> {
  const r = await fetch(url, {
    method,
    headers: { Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(60_000),
  });
  const text = await r.text();
  let parsed: Reply["body"] = {};
  try {
    parsed = text === "" ? {} : (JSON.parse(text) as Reply["body"]);
  } catch {
    parsed = { error: `not json: ${text.slice(0, 80)}` };
  }
  return { status: r.status, body: parsed };
}

const codeOf = (r: Reply) => String(r.body.error ?? r.body.code ?? r.body.reason ?? r.status);

/** `f` again while it answers what may pass with time (a fact not yet read, a 503, a 429), up to `tries`. */
async function settled(f: () => Promise<Reply>, passing: (r: Reply) => boolean, tries = 30): Promise<Reply> {
  let last: Reply = { status: 0, body: {} };
  for (let i = 0; i < tries; i++) {
    try {
      last = await f();
    } catch (e) {
      last = { status: 599, body: { error: e instanceof Error ? e.name : "failed" } };
    }
    if (!passing(last)) return last;
    await sleep(Math.min(8_000, 250 * 2 ** Math.min(i, 5)));
  }
  return last;
}

const transient = (r: Reply) => r.status === 429 || r.status >= 500;

class Seeder {
  private readonly inst: Installation;
  private readonly seed: Seed;
  private admin: Operator;
  private signingIn: Promise<Operator> | undefined;
  private readonly signIn: () => Promise<Operator>;
  private readonly options: Options;
  private dirty = 0;

  constructor(inst: Installation, seed: Seed, admin: Operator, signIn: () => Promise<Operator>, o: Options) {
    this.inst = inst;
    this.seed = seed;
    this.admin = admin;
    this.signIn = signIn;
    this.options = o;
  }

  private refused(step: string, r: Reply): void {
    const key = `${step}: ${r.status} ${codeOf(r)}`;
    this.seed.refused[key] = (this.seed.refused[key] ?? 0) + 1;
  }

  private save(force = false): void {
    if (force || ++this.dirty >= 100) {
      writeSeed(this.inst, this.seed);
      this.dirty = 0;
    }
  }

  private get players() {
    return `${this.inst.service("players")}/v1/casino/players/${this.inst.brand}`;
  }

  private get payouts() {
    return `${this.inst.service("payouts")}/v1/casino/brands/${this.inst.brand}/players`;
  }

  /** A call as the operator through the backoffice; signed in again once when the session has ended. */
  private async relay(method: string, path: string, body?: unknown): Promise<Reply> {
    let a = await this.admin.call<Reply["body"]>(method, `/api/s/${path}`, body);
    if (a.status === 401) {
      this.signingIn ??= this.signIn().finally(() => (this.signingIn = undefined));
      this.admin = await this.signingIn;
      a = await this.admin.call<Reply["body"]>(method, `/api/s/${path}`, body);
    }
    return { status: a.status, body: (a.body ?? {}) as Reply["body"] };
  }

  /** The brand offers the three currencies (players' currency configuration). */
  async currencies(): Promise<void> {
    const now = await this.relay("GET", "players/currency-config");
    if (now.status !== 200) throw new Error(`the currency configuration answered ${now.status} ${codeOf(now)}`);
    const offered = Array.isArray(now.body.offered) ? (now.body.offered as string[]) : [];
    const wanted = [...new Set([...offered, ...SeedCurrencies])].sort();
    if (wanted.length === offered.length) return;
    const set = await this.relay("PUT", "players/currency-config", { offered: wanted });
    if (set.status !== 200) throw new Error(`offering ${SeedCurrencies.join(", ")} was refused: ${set.status} ${codeOf(set)}`);
  }

  async register(p: SeededPlayer): Promise<boolean> {
    if (p.playerId) return true;
    const r = await settled(
      () =>
        call("POST", `${this.players}/register`, {
          email: p.email,
          password: this.seed.password,
          currency: p.currency,
          firstName: "Seed",
          lastName: `Player${p.index}`,
          displayName: `seed${p.index}`,
          country: "BR",
          language: "en",
          dateOfBirth: "1990-01-01",
          emailMarketingConsent: false,
          smsMarketingConsent: false,
        }),
      transient,
    );
    if (r.status === 201 && typeof r.body.playerId === "string") {
      p.playerId = r.body.playerId;
      return true;
    }
    this.refused("register", r);
    return false;
  }

  /** The operator's verification link, followed as the player would. */
  async verify(p: SeededPlayer): Promise<boolean> {
    if (p.verified) return true;
    const link = await settled(() => this.relay("GET", `players/players/${p.playerId}/verification-link`), (r) => transient(r) || r.status === 404);
    const token = typeof link.body.link === "string" ? new URL(link.body.link).searchParams.get("token") : null;
    if (link.status !== 200 || !token) {
      this.refused("verification-link", link);
      return false;
    }
    const done = await settled(() => call("POST", `${this.players}/verify-email`, { token }), transient);
    if (done.status !== 200) {
      this.refused("verify-email", done);
      return false;
    }
    p.verified = true;
    return true;
  }

  /** Identity verified by an operator's override (kyc FR-015's approve), with its reason. */
  async identity(p: SeededPlayer): Promise<boolean> {
    if (p.identityVerified) return true;
    const r = await settled(() => this.relay("POST", `kyc/${p.playerId}/override`, { status: "verified", reason: "seeded for the backoffice's checks" }), transient);
    if (r.status !== 200) {
      this.refused("identity", r);
      return false;
    }
    p.identityVerified = true;
    return true;
  }

  /** A credit to the real balance, under its own key, so a repeat makes nothing twice. */
  async credit(p: SeededPlayer): Promise<boolean> {
    if (p.credit) return true;
    const minor = Amounts[p.currency].credit;
    const key = `seed-${this.seed.run}-${p.index}-credit`;
    const r = await settled(
      () => this.relay("POST", `wallet/wallets/${p.playerId}/${p.currency}/adjustments`, { key, walletType: "real", amountMinor: minor, reason: "seeded credit", category: "compensation" }),
      (r) => transient(r) || r.status === 404,
    );
    if (r.status !== 200) {
      this.refused("credit", r);
      return false;
    }
    p.credit = { minor, key };
    return true;
  }

  async withdraw(p: SeededPlayer): Promise<boolean> {
    if (p.withdrawal) return true;
    const login = await settled(() => call("POST", `${this.players}/login`, { email: p.email, password: this.seed.password }), transient);
    if (login.status !== 200 || typeof login.body.token !== "string") {
      this.refused("login", login);
      return false;
    }
    const auth = { Authorization: `Bearer ${login.body.token}` };
    // payouts reads the player, their identity and their wallet from facts: a moment after the steps above
    const notYet = (r: Reply) => transient(r) || ["PLAYER_NOT_FOUND", "WITHDRAWAL_BLOCKED_KYC", "WALLET_NOT_FOUND", "INSUFFICIENT_FUNDS"].includes(codeOf(r));
    const terminals = await settled(() => call("GET", `${this.payouts}/${p.playerId}/withdrawal-terminals?currency=${p.currency}`, undefined, auth), transient);
    const offered = Array.isArray(terminals.body.terminals) ? (terminals.body.terminals as { id: string; requiresPayoutDestination?: boolean; coins?: string[] }[]) : [];
    const terminal = offered.find((t) => !t.requiresPayoutDestination && (t.coins ?? []).length === 0) ?? offered[0];
    if (terminals.status !== 200 || !terminal) {
      this.refused("withdrawal-terminals", terminals.status === 200 ? { status: 200, body: { error: `no terminal in ${p.currency}` } } : terminals);
      return false;
    }
    const minor = Amounts[p.currency].request(p.index);
    const key = `seed-${this.seed.run}-${p.index}-withdrawal`;
    const r = await settled(() => call("POST", `${this.payouts}/${p.playerId}/withdrawal-requests`, { key, amount: minor, currency: p.currency, terminalId: terminal.id }, auth), notYet, 40);
    if (r.status !== 201 || typeof r.body.requestId !== "string") {
      this.refused("withdrawal-request", r);
      return false;
    }
    p.withdrawal = { requestId: r.body.requestId, minor, terminalId: terminal.id };
    return true;
  }

  /** A free bonus (rewards' operator award with no offer), then its id from the player's rewards. */
  async reward(p: SeededPlayer): Promise<boolean> {
    if (p.reward) return true;
    const minor = Amounts[p.currency].bonus;
    const listed = () => this.relay("GET", `rewards/reward-grants?playerId=${p.playerId}&page=1&pageSize=5`);
    const first = await listed();
    const items = (r: Reply) => (Array.isArray(r.body.items) ? (r.body.items as { rewardId: string; faceValue?: number }[]) : []);
    if (items(first).length === 0) {
      const r = await settled(() => this.relay("POST", `rewards/reward-grants/${p.playerId}`, { bonusAmount: minor, wager: 1, currency: p.currency }), (r) => transient(r) || codeOf(r) === "player_currency_unresolved");
      if (r.status !== 200) {
        this.refused("reward", r);
        return false;
      }
    }
    const found = await settled(listed, (r) => transient(r) || (r.status === 200 && items(r).length === 0), 20);
    const reward = items(found)[0];
    if (!reward) {
      this.refused("reward-listed", found);
      return false;
    }
    p.reward = { rewardId: reward.rewardId, minor };
    return true;
  }

  async player(p: SeededPlayer): Promise<void> {
    if (!(await this.register(p))) return;
    const o = this.options;
    if (p.index < o.withdrawals) {
      if ((await this.verify(p)) && (await this.identity(p)) && (await this.credit(p))) await this.withdraw(p);
    } else if (p.index < o.withdrawals + o.rewards) {
      await this.reward(p);
    }
    this.save();
  }

  async all(): Promise<void> {
    let done = 0;
    const started = Date.now();
    await pool(this.seed.players, this.options.width, async (p) => {
      await this.player(p);
      if (++done % 250 === 0) {
        const rate = done / ((Date.now() - started) / 1000);
        console.log(`${done}/${this.seed.players.length} players (${rate.toFixed(1)}/s)`);
      }
    });
    this.seed.finishedAt = new Date().toISOString();
    this.save(true);
  }
}

function fresh(brand: string, o: Options): Seed {
  const run = randomBytes(4).toString("hex");
  return {
    brand,
    run,
    // the brand realm's policy: twelve or more characters, a digit and a special character
    password: `Seed-${run}-Pw1!`,
    startedAt: new Date().toISOString(),
    wanted: { players: o.players, rewards: o.rewards, withdrawals: o.withdrawals },
    players: Array.from({ length: o.players }, (_, index) => ({
      index,
      email: `seed-${run}-${index}@seed.eitheror.test`,
      currency: SeedCurrencies[index % SeedCurrencies.length]!,
    })),
    refused: {},
  };
}

async function main(): Promise<void> {
  const { brand, options: o } = options(process.argv.slice(2));
  const inst = installation(brand ? { ...process.env, BACKOFFICE_BRAND: brand } : process.env);
  const seed = existsSync(inst.seedFile) ? readSeed(inst) : fresh(inst.brand, o);
  if (seed.brand !== inst.brand) throw new Error(`${inst.seedFile} is the seed of ${seed.brand}, not ${inst.brand}`);
  seed.refused = {};
  console.log(`seeding ${inst.brand} at ${inst.backoffice} (run ${seed.run}; ${seed.players.length} players, ${o.rewards} rewards, ${o.withdrawals} withdrawal requests)`);
  const signIn = () => Operator.signIn(inst, operators(inst).brand_admin, "brand_admin");
  const seeder = new Seeder(inst, seed, await signIn(), signIn, o);
  await seeder.currencies();
  await seeder.all();
  const s = {
    players: seed.players.filter((p) => p.playerId).length,
    rewards: seed.players.filter((p) => p.reward).length,
    withdrawals: seed.players.filter((p) => p.withdrawal).length,
  };
  console.log(`made ${s.players} players, ${s.rewards} rewards, ${s.withdrawals} withdrawal requests; written to ${inst.seedFile}`);
  for (const [why, n] of Object.entries(seed.refused)) console.log(`  not done ×${n}: ${why}`);
  if (s.players < o.players || s.rewards < o.rewards || s.withdrawals < o.withdrawals) process.exitCode = 1;
}

await main();
