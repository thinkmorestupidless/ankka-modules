// What the integration suites and the seed share (quickstart §6, research R18): the installation they
// run against, an operator signed in through the backoffice's own sign-in at the realm, and the seed's
// record of what it made. Nothing here holds a token: an operator is a session cookie, as a browser is.
//
// The installation is named by the environment, so the suites run against any brand on any local
// installation (`just backoffice-it <slug>`):
//
// | Variable | Default | What |
// |---|---|---|
// | `BACKOFFICE_BRAND` | `goldvibe` | the brand's slug |
// | `BACKOFFICE_URL` | the brand's `backoffice-host` value | the backoffice's address |
// | `BACKOFFICE_SERVICE_URL` | `https://{service}-{project}.127.0.0.1.sslip.io:8443` | an exposed service's address |
// | `BACKOFFICE_IT_OPERATORS` | `test-results/it/operators.json` | the operators to sign in as (below) |
// | `BACKOFFICE_IT_SEED` | `test-results/it/seed-<slug>.json` | what `seed.ts` made |
// | `BACKOFFICE_IT_MAY_CHANGE_SERVICES` | unset | `1` lets a suite pause, resume and restart services with `ankka` |
// | `NODE_EXTRA_CA_CERTS` | (the recipe sets `~/.ankka/local-ca.crt`) | the local installation's CA |
//
// The operators file is `{"operators": {"<label>": {"username", "password"}}}` with the labels of
// `Labels`: each is a user of the `eitheror` realm holding `brand_<slug>` and the role it is named for
// (`platform_admin` alone; `payments` holds `brand_<slug>` and `resource_operator`; `other_brand`
// holds another brand's `brand_<x>` only; `made` holds `brand_<slug>` only, and the suites give it the
// roles they make). `bash tests/it/operators.sh <slug>` makes them and the file; the person running the
// suites runs it, never a suite.
import { readFileSync, existsSync, mkdirSync, writeFileSync, renameSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

export const ServiceDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const RepoDir = path.resolve(ServiceDir, "../..");

/** The five default roles, the payments role and the operators the suites need besides. */
export const DefaultRoles = ["brand_admin", "brand_compliance", "brand_finance", "brand_support", "platform_admin"] as const;
export const Labels = [...DefaultRoles, "payments", "other_brand", "made"] as const;
export type Label = (typeof Labels)[number];

export interface Credentials {
  username: string;
  password: string;
}

export interface Installation {
  brand: string;
  backoffice: string;
  /** An exposed service's address in a project: the brand's own (`project` omitted) or a shared one. */
  service(name: string, project?: string): string;
  operatorsFile: string;
  seedFile: string;
  mayChangeServices: boolean;
}

function brandValue(brand: string, key: string): string | undefined {
  const file = path.join(RepoDir, "brands", brand, "values.json");
  if (!existsSync(file)) return undefined;
  const values = (JSON.parse(readFileSync(file, "utf8")) as { values?: Record<string, string> }).values ?? {};
  return values[key];
}

export function installation(env: NodeJS.ProcessEnv = process.env): Installation {
  const brand = env.BACKOFFICE_BRAND ?? "goldvibe";
  const backoffice = (env.BACKOFFICE_URL ?? brandValue(brand, "backoffice-host") ?? "").replace(/\/$/, "");
  if (backoffice === "") throw new Error(`no backoffice address: set BACKOFFICE_URL, or give brands/${brand}/values.json a backoffice-host`);
  const template = env.BACKOFFICE_SERVICE_URL ?? "https://{service}-{project}.127.0.0.1.sslip.io:8443";
  return {
    brand,
    backoffice,
    service: (name, project = brand) => template.replace("{service}", name).replace("{project}", project).replace(/\/$/, ""),
    operatorsFile: env.BACKOFFICE_IT_OPERATORS ?? path.join(ServiceDir, "test-results/it/operators.json"),
    seedFile: env.BACKOFFICE_IT_SEED ?? path.join(ServiceDir, `test-results/it/seed-${brand}.json`),
    mayChangeServices: env.BACKOFFICE_IT_MAY_CHANGE_SERVICES === "1",
  };
}

export function operators(inst: Installation): Record<Label, Credentials> {
  if (!existsSync(inst.operatorsFile))
    throw new Error(`no operators file at ${inst.operatorsFile}: make the realm users with tests/it/operators.sh and name the file in BACKOFFICE_IT_OPERATORS`);
  const file = JSON.parse(readFileSync(inst.operatorsFile, "utf8")) as { operators?: Record<string, Credentials> };
  const missing = Labels.filter((l) => !file.operators?.[l]?.username || !file.operators[l].password);
  if (missing.length > 0) throw new Error(`the operators file lacks ${missing.join(", ")}`);
  return file.operators as Record<Label, Credentials>;
}

// ── A cookie jar: enough for the backoffice's and the realm's cookies ──────────────────────────────

export class Jar {
  private readonly byHost = new Map<string, Map<string, string>>();

  take(url: string, response: Response): void {
    const host = new URL(url).host;
    const kept = this.byHost.get(host) ?? new Map<string, string>();
    for (const line of response.headers.getSetCookie()) {
      const [pair = ""] = line.split(";");
      const at = pair.indexOf("=");
      if (at <= 0) continue;
      const name = pair.slice(0, at).trim();
      const value = pair.slice(at + 1).trim();
      const expired = /max-age=0\b/i.test(line) || /expires=thu, 01 jan 1970/i.test(line);
      if (expired || value === "") kept.delete(name);
      else kept.set(name, value);
    }
    this.byHost.set(host, kept);
  }

  header(url: string): string {
    const kept = this.byHost.get(new URL(url).host);
    return kept ? [...kept].map(([n, v]) => `${n}=${v}`).join("; ") : "";
  }

  has(url: string, name: string): boolean {
    return this.byHost.get(new URL(url).host)?.has(name) ?? false;
  }
}

async function follow(jar: Jar, url: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const cookie = jar.header(url);
  if (cookie) headers.set("Cookie", cookie);
  const r = await fetch(url, { ...init, headers, redirect: "manual", signal: AbortSignal.timeout(30_000) });
  jar.take(url, r);
  return r;
}

const decode = (s: string) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">");

/** The realm's login form's action, as Keycloak's login page writes it. */
export function loginAction(html: string): string | undefined {
  const form = /<form[^>]*id="kc-form-login"[^>]*>/i.exec(html)?.[0] ?? /<form[^>]*action="[^"]*login-actions\/authenticate[^"]*"[^>]*>/i.exec(html)?.[0];
  const action = form && /action="([^"]+)"/i.exec(form)?.[1];
  return action ? decode(action) : undefined;
}

// ── An operator, signed in ──────────────────────────────────────────────────────────────────────────

export interface Answer<T = unknown> {
  status: number;
  body: T;
  ms: number;
  headers: Headers;
}

export class Operator {
  readonly jar: Jar;
  readonly base: string;
  readonly label: string;

  constructor(base: string, jar: Jar, label: string) {
    this.base = base;
    this.jar = jar;
    this.label = label;
  }

  /** Signs in as the browser does: the backoffice's sign-in, the realm's form, the callback. */
  static async signIn(inst: Installation, credentials: Credentials, label = credentials.username): Promise<Operator> {
    const jar = new Jar();
    const start = await follow(jar, `${inst.backoffice}/auth/sign-in?return=/`);
    const realmUrl = start.headers.get("location");
    if (start.status !== 302 || !realmUrl) throw new Error(`${label}: /auth/sign-in answered ${start.status}, not a redirect to the realm`);
    let page = await follow(jar, realmUrl);
    // A realm session already open answers the callback at once.
    let callback = page.status === 302 ? page.headers.get("location") : null;
    if (!callback) {
      const html = await page.text();
      const action = loginAction(html);
      if (!action) throw new Error(`${label}: the realm's page has no login form (status ${page.status})`);
      page = await follow(jar, new URL(action, realmUrl).toString(), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ username: credentials.username, password: credentials.password, credentialId: "" }).toString(),
      });
      callback = page.headers.get("location");
      if (page.status !== 302 || !callback) throw new Error(`${label}: the realm refused the sign-in (status ${page.status})`);
    }
    if (!callback.startsWith(inst.backoffice)) throw new Error(`${label}: the realm sent the sign-in to ${new URL(callback).host}, not the backoffice`);
    const done = await follow(jar, callback);
    if (done.status !== 302 || !jar.has(inst.backoffice, "__Host-bo"))
      throw new Error(`${label}: the callback answered ${done.status} with no session (${(await done.text()).slice(0, 200)})`);
    return new Operator(inst.backoffice, jar, label);
  }

  async call<T = unknown>(method: string, pathAndQuery: string, body?: unknown, extra: Record<string, string> = {}): Promise<Answer<T>> {
    const url = `${this.base}${pathAndQuery}`;
    const headers: Record<string, string> = { Accept: "application/json", ...extra };
    if (method !== "GET" && method !== "HEAD") {
      headers["X-Backoffice"] = "1";
      headers.Origin = new URL(this.base).origin;
      if (body !== undefined && headers["Content-Type"] === undefined) headers["Content-Type"] = "application/json";
    }
    const started = performance.now();
    const r = await follow(this.jar, url, {
      method,
      headers,
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    });
    const text = await r.text();
    const ms = performance.now() - started;
    let parsed: unknown = text;
    try {
      parsed = text === "" ? null : JSON.parse(text);
    } catch {
      // not JSON: the text as it came
    }
    return { status: r.status, body: parsed as T, ms, headers: r.headers };
  }

  get<T = unknown>(pathAndQuery: string): Promise<Answer<T>> {
    return this.call<T>("GET", pathAndQuery);
  }

  /** `GET`, and the body when it is 200; else an error naming the route and the answer. */
  async read<T = unknown>(pathAndQuery: string): Promise<T> {
    const a = await this.get<T>(pathAndQuery);
    if (a.status !== 200) throw new Error(`${this.label}: GET ${pathAndQuery} answered ${a.status}: ${JSON.stringify(a.body).slice(0, 300)}`);
    return a.body;
  }

  async me(): Promise<Me> {
    return this.read<Me>("/api/me");
  }

  async signOut(): Promise<void> {
    await this.call("POST", "/auth/sign-out");
  }
}

export interface Me {
  subject: string | null;
  name: string | null;
  roles: string[];
  brand: { slug: string; modules: string[] | null };
  permissions: string[];
}

// ── Small tools ─────────────────────────────────────────────────────────────────────────────────────

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** `f` again until `done` holds of its answer or `withinMs` passes; the last answer either way. */
export async function until<T>(f: () => Promise<T>, done: (t: T) => boolean, withinMs: number, everyMs = 500): Promise<{ value: T; held: boolean; afterMs: number }> {
  const started = Date.now();
  for (;;) {
    const value = await f();
    if (done(value)) return { value, held: true, afterMs: Date.now() - started };
    if (Date.now() - started > withinMs) return { value, held: false, afterMs: Date.now() - started };
    await sleep(everyMs);
  }
}

/** Runs `work` over `items`, `width` at a time, in order of starting. */
export async function pool<T>(items: readonly T[], width: number, work: (item: T, index: number) => Promise<void>): Promise<void> {
  let next = 0;
  const worker = async () => {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      await work(items[i]!, i);
    }
  };
  await Promise.all(Array.from({ length: Math.min(width, items.length) }, worker));
}

/** The p-th percentile of `samples` (nearest rank). */
export function percentile(samples: readonly number[], p: number): number {
  if (samples.length === 0) return NaN;
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))]!;
}

/** The digits of a formatted amount as minor units: `KWD 1,234.567` → 1234567; the sign kept. */
export function minorOfShown(text: string): number | undefined {
  const digits = text.replace(/[^\d]/g, "");
  if (digits === "") return undefined;
  const n = Number(digits);
  return /[-−]/.test(text) ? -n : n;
}

const run = promisify(execFile);

/** `ankka services <verb> <service> --project <project>`, only when the person allowed it. */
export async function ankkaServices(inst: Installation, verb: "pause" | "resume" | "restart", service: string, project = inst.brand): Promise<void> {
  if (!inst.mayChangeServices) throw new Error(`BACKOFFICE_IT_MAY_CHANGE_SERVICES is not 1: ${verb} ${service} by hand`);
  await run("ankka", ["services", verb, service, "--project", project], { timeout: 120_000 });
}

// ── What the seed made ──────────────────────────────────────────────────────────────────────────────

export const SeedCurrencies = ["JPY", "EUR", "KWD"] as const;
export type SeedCurrency = (typeof SeedCurrencies)[number];

export interface SeededPlayer {
  index: number;
  email: string;
  currency: SeedCurrency;
  playerId?: string;
  verified?: boolean;
  identityVerified?: boolean;
  /** The credit the seed made to the real balance, in minor units, under its key. */
  credit?: { minor: number; key: string };
  reward?: { rewardId: string; minor: number };
  withdrawal?: { requestId: string; minor: number; terminalId: string };
}

export interface Seed {
  brand: string;
  run: string;
  password: string;
  startedAt: string;
  finishedAt?: string;
  wanted: { players: number; rewards: number; withdrawals: number };
  players: SeededPlayer[];
  /** What a step could not do, by its reason, with a count. */
  refused: Record<string, number>;
}

export function readSeed(inst: Installation): Seed {
  if (!existsSync(inst.seedFile)) throw new Error(`no seed at ${inst.seedFile}: run \`just backoffice-seed ${inst.brand}\` first`);
  return JSON.parse(readFileSync(inst.seedFile, "utf8")) as Seed;
}

export function writeSeed(inst: Installation, seed: Seed): void {
  mkdirSync(path.dirname(inst.seedFile), { recursive: true });
  const partial = `${inst.seedFile}.partial`;
  writeFileSync(partial, JSON.stringify(seed, null, 1));
  renameSync(partial, inst.seedFile);
}

/** Seeded players holding each thing, in each currency: what the suites sample from. */
export function seeded(seed: Seed) {
  const withId = seed.players.filter((p) => p.playerId);
  return {
    players: withId,
    rewards: withId.filter((p) => p.reward),
    withdrawals: withId.filter((p) => p.withdrawal),
    credited: withId.filter((p) => p.credit && !p.withdrawal),
    inCurrency: (c: SeedCurrency, ps = withId) => ps.filter((p) => p.currency === c),
  };
}

/** A few of `items`, spread over them: the first, the last and evenly between. */
export function spread<T>(items: readonly T[], n: number): T[] {
  if (items.length <= n) return [...items];
  return Array.from({ length: n }, (_, i) => items[Math.round((i * (items.length - 1)) / Math.max(1, n - 1))]!);
}

// ── The brand's roles, through the backoffice as an operator holding `roles.change` and `roles.give` ──

/** The core module's services: always run in a brand, so never "not run here". */
export const CoreServices = new Set(["wallet", "players", "kyc", "responsible-gambling", "payouts", "attribution"]);

export async function setRole(admin: Operator, name: string, permissions: readonly string[], description = "made by the integration suites"): Promise<Answer> {
  return admin.call("PUT", `/api/s/players/roles/${encodeURIComponent(name)}`, { description, permissions: [...permissions].sort() });
}

export async function deleteRole(admin: Operator, name: string): Promise<Answer> {
  return admin.call("DELETE", `/api/s/players/roles/${encodeURIComponent(name)}`);
}

/** Gives `operator` (the realm user's id) exactly `roles` in this brand; players asks merchant to set them. */
export async function giveRoles(giver: Operator, operator: string, roles: readonly string[]): Promise<Answer> {
  return giver.call("PUT", `/api/s/players/role-assignments/${encodeURIComponent(operator)}`, { roles: [...roles].sort() });
}

/** The brand's bags as the backoffice reads them now (`/api/permissions`). */
export async function bagsNow(op: Operator): Promise<Record<string, string[]>> {
  return (await op.read<{ roles: Record<string, string[]> }>("/api/permissions")).roles;
}

/** A role name for this run: `[a-z0-9_]{1,40}`, as players requires. */
export const runRole = (what: string) => `it_${what}_${Date.now().toString(36)}`.slice(0, 40);
