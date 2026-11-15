// The tests' hand on a running doubles process (`server.ts`): reset it, script an owner, add fixtures,
// and read the calls each owner was given, with the token each carried.
import type { Entry, Recorded, Script } from "./owner.ts";
import type { Preferences } from "../../src/server/session.ts";
import type { Seed } from "./server.ts";
import type { FraudSeed } from "./fraud.ts";

export interface Claims {
  sub?: string;
  name?: string;
  realm_access?: { roles?: string[] };
}

/** The bearer token a call carried, or undefined. */
export function bearerOf(call: Recorded): string | undefined {
  return /^Bearer (\S+)$/.exec(call.headers["authorization"] ?? "")?.[1];
}

/** The claims of the token a call carried (the double realm's tokens are unsigned). */
export function claimsOf(call: Recorded): Claims | undefined {
  const token = bearerOf(call);
  if (!token) return undefined;
  try {
    return JSON.parse(Buffer.from(token.split(".")[1] ?? "", "base64url").toString("utf8")) as Claims;
  } catch {
    return undefined;
  }
}

export class DoublesClient {
  readonly url: string;

  constructor(url: string) {
    this.url = url.replace(/\/$/, "");
  }

  private async ask<T>(method: string, path: string, body?: unknown): Promise<T> {
    const r = await fetch(`${this.url}/__double${path}`, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`the doubles answered ${r.status} to ${method} ${path}: ${await r.text()}`);
    return (await r.json()) as T;
  }

  /** Every owner back to its files; scripts, calls, sessions and preferences forgotten. */
  reset(): Promise<unknown> {
    return this.ask("POST", "/reset");
  }

  seed(): Promise<Seed> {
    return this.ask("GET", "/seed");
  }

  /** Tell an owner (`players`, `merchant.payments`, `backoffice-state`) what to do with the calls that fit. */
  script(owner: string, script: Script): Promise<unknown> {
    return this.ask("POST", "/script", { owner, ...script });
  }

  fail(owner: string, status: number, fits: Pick<Script, "method" | "path" | "times" | "body"> = {}): Promise<unknown> {
    return this.script(owner, { ...fits, status });
  }

  slow(owner: string, delayMs: number, fits: Pick<Script, "method" | "path" | "times"> = {}): Promise<unknown> {
    return this.script(owner, { ...fits, delayMs });
  }

  /** Answer `fixtures/scripted/<name>.json` to the calls that fit. */
  answer(owner: string, name: string, fits: Pick<Script, "method" | "path" | "times"> = {}): Promise<unknown> {
    return this.script(owner, { ...fits, fixture: name });
  }

  /** Fixtures added over an owner's files for this scenario: `{"GET /v1/…/{x}": entry}`. */
  fixtures(owner: string, entries: Record<string, Entry>): Promise<unknown> {
    return this.ask("PUT", `/fixtures/${encodeURIComponent(owner)}`, entries);
  }

  async calls(owner?: string, fits: { method?: string; path?: string | RegExp } = {}): Promise<Recorded[]> {
    const all = await this.ask<Recorded[]>("GET", `/calls${owner ? `?owner=${encodeURIComponent(owner)}` : ""}`);
    return all.filter(
      (c) => (!fits.method || c.method === fits.method) && (!fits.path || (typeof fits.path === "string" ? c.path === fits.path : fits.path.test(c.path))),
    );
  }

  /** Throws unless every call that fits carried a token whose subject is `sub`; answers how many fit. */
  async expectAuthorizedAs(owner: string, sub: string, fits: { method?: string; path?: string | RegExp } = {}): Promise<number> {
    const calls = await this.calls(owner, fits);
    if (calls.length === 0) throw new Error(`${owner} was not called${fits.path ? ` at ${fits.path}` : ""}`);
    const wrong = calls.filter((c) => claimsOf(c)?.sub !== sub);
    if (wrong.length > 0)
      throw new Error(`${wrong.length} of ${calls.length} calls to ${owner} did not carry ${sub}'s token: ${wrong.map((c) => `${c.method} ${c.path} as ${claimsOf(c)?.sub ?? "no one"}`).join(", ")}`);
    return calls.length;
  }

  preferences(subject: string, p: Partial<Preferences>): Promise<unknown> {
    return this.ask("PUT", `/preferences/${encodeURIComponent(subject)}`, p);
  }

  /** The subject's roles from its sessions' next token on (a role given or taken at the realm). */
  roles(subject: string, roles: string[]): Promise<unknown> {
    return this.ask("PUT", `/subjects/${encodeURIComponent(subject)}/roles`, { roles });
  }

  /** players' fraud cases kept by the double from here on: listed, reviewed, dismissed, their flags cleared. */
  fraudCases(seed: FraudSeed): Promise<unknown> {
    return this.ask("PUT", "/fraud-cases", seed);
  }

  tokenLife(ms: number): Promise<unknown> {
    return this.ask("PUT", "/settings", { tokenLifeMs: ms });
  }
}
