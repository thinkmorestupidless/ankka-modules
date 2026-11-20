// The double realm and the double `backoffice-state`: enough of Keycloak's code flow for the server's
// sign-in, with no login page. The world puts the operator it chose in the `double-realm` cookie (a
// cookie for `localhost` reaches every port); the realm's authorise route redirects straight back to the
// callback with a code for that operator, and `POST /sessions` exchanges it, checking the PKCE verifier,
// into a session whose token is JWT-shaped and unsigned (the server reads it and does not verify it).
import { createHash, randomBytes } from "node:crypto";
import type { Answer, Asked } from "./owner.ts";
import type { Preferences } from "../../src/server/session.ts";

export interface Identity {
  sub: string;
  name: string;
  roles: string[];
}

export const IdentityCookie = "double-realm";

/** The cookie's value for an operator. */
export function identityValue(who: Identity): string {
  return Buffer.from(JSON.stringify(who)).toString("base64url");
}

function identityOf(cookieHeader: string | undefined): Identity | undefined {
  const raw = (cookieHeader ?? "").split(/;\s*/).find((c) => c.startsWith(`${IdentityCookie}=`))?.slice(IdentityCookie.length + 1);
  if (!raw) return undefined;
  try {
    const v = JSON.parse(Buffer.from(raw, "base64url").toString("utf8")) as Identity;
    return typeof v.sub === "string" && Array.isArray(v.roles) ? v : undefined;
  } catch {
    return undefined;
  }
}

const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");

/** A Keycloak-shaped access token, unsigned. */
export function tokenFor(who: Identity, exp: number): string {
  return `${b64({ alg: "none", typ: "JWT" })}.${b64({
    iss: "double-realm",
    aud: "eitheror-casino",
    sub: who.sub,
    name: who.name,
    preferred_username: who.sub,
    realm_access: { roles: who.roles },
    exp: Math.floor(exp / 1000),
  })}.`;
}

interface Code {
  who: Identity;
  challenge: string;
  redirectUri: string;
}

interface Session {
  who: Identity;
  lastRoles?: string[];
}

const json = (status: number, body: unknown): Answer => ({ status, body });
const id = () => randomBytes(16).toString("base64url");

export class Realm {
  private codes = new Map<string, Code>();
  private sessions = new Map<string, Session>();
  private preferences = new Map<string, Preferences>();
  /** A subject's roles changed by a test, in force at its sessions' next token. */
  private roles = new Map<string, string[]>();
  /** What each brand last gave a subject, by the brand's names (spec 032: `<role>@<slug>` in the token). */
  private givings = new Map<string, Map<string, string[]>>();
  tokenLifeMs = 60_000;
  readonly ended: string[] = [];
  private readonly defaults: () => Preferences;

  constructor(defaults: () => Preferences) {
    this.defaults = defaults;
  }

  reset(): void {
    this.codes.clear();
    this.sessions.clear();
    this.preferences.clear();
    this.roles.clear();
    this.givings.clear();
    this.ended.length = 0;
    this.tokenLifeMs = 60_000;
  }

  setPreferences(subject: string, p: Partial<Preferences>): void {
    this.preferences.set(subject, { ...(this.preferences.get(subject) ?? this.defaults()), ...p });
  }

  setRoles(subject: string, roles: string[]): void {
    this.roles.set(subject, roles);
  }

  /** A brand's giving: the roles named for `brand` become `roles` and `brand_<brand>` is held; every other role stays. */
  giveIn(subject: string, brand: string, roles: string[]): void {
    const of = this.givings.get(subject) ?? new Map<string, string[]>();
    of.set(brand, [...roles]);
    this.givings.set(subject, of);
  }

  /** The roles a subject's next token carries: as signed in or set, then each brand's giving over them. */
  rolesOf(subject: string, base: readonly string[]): string[] {
    let roles = [...(this.roles.get(subject) ?? base)];
    for (const [brand, given] of this.givings.get(subject) ?? []) {
      roles = roles.filter((r) => !r.endsWith(`@${brand}`));
      roles.push(`brand_${brand}`, ...given.map((r) => `${r}@${brand}`));
    }
    return [...new Set(roles)];
  }

  /** `GET /realms/eitheror/protocol/openid-connect/auth`: a redirect to the callback, or its error. */
  authorise(url: URL, cookieHeader: string | undefined): { location: string } | Answer {
    const q = url.searchParams;
    const redirectUri = q.get("redirect_uri");
    if (!redirectUri || !q.get("client_id")?.startsWith("backoffice-")) return json(400, { error: "invalid_request" });
    const back = new URL(redirectUri);
    if (q.get("state")) back.searchParams.set("state", q.get("state")!);
    const who = identityOf(cookieHeader);
    if (!who || q.get("code_challenge_method") !== "S256" || !q.get("code_challenge")) {
      back.searchParams.set("error", who ? "invalid_request" : "login_required");
      return { location: back.toString() };
    }
    const code = id();
    this.codes.set(code, { who, challenge: q.get("code_challenge")!, redirectUri });
    back.searchParams.set("code", code);
    return { location: back.toString() };
  }

  /** The double `backoffice-state`'s routes (contracts/routes.md). */
  state(asked: Asked): Answer | undefined {
    const { method, path } = asked;
    const body = () => {
      try {
        return JSON.parse(asked.body || "{}") as Record<string, unknown>;
      } catch {
        return {};
      }
    };
    if (method === "POST" && path === "/sessions") {
      const b = body();
      const code = this.codes.get(String(b.code));
      this.codes.delete(String(b.code));
      if (!code) return json(400, { error: "invalid_grant" });
      const challenge = createHash("sha256").update(String(b.verifier)).digest("base64url");
      if (challenge !== code.challenge || b.redirectUri !== code.redirectUri) return json(400, { error: "invalid_grant" });
      const session = id();
      this.sessions.set(session, { who: code.who });
      return json(200, { session, subject: code.who.sub, expiresAt: new Date(Date.now() + 8 * 3_600_000).toISOString() });
    }
    let m = /^\/sessions\/([^/]+)\/token$/.exec(path);
    if (method === "POST" && m) {
      const s = this.sessions.get(decodeURIComponent(m[1]!));
      if (!s) return json(404, { error: "session_ended" });
      const roles = this.rolesOf(s.who.sub, s.who.roles);
      const rolesChanged = s.lastRoles !== undefined && [...s.lastRoles].sort().join() !== [...roles].sort().join();
      s.lastRoles = roles;
      const expiresAt = Date.now() + this.tokenLifeMs;
      return json(200, { accessToken: tokenFor({ ...s.who, roles }, expiresAt), expiresAt: new Date(expiresAt).toISOString(), roles, rolesChanged });
    }
    m = /^\/sessions\/([^/]+)$/.exec(path);
    if (method === "DELETE" && m) {
      const session = decodeURIComponent(m[1]!);
      this.ended.push(session);
      return this.sessions.delete(session) ? json(200, { ended: true }) : json(404, { error: "session_ended" });
    }
    m = /^\/preferences\/([^/]+)$/.exec(path);
    if (m) {
      const subject = decodeURIComponent(m[1]!);
      if (method === "GET") return json(200, this.preferences.get(subject) ?? this.defaults());
      if (method === "PUT") {
        this.setPreferences(subject, body() as Partial<Preferences>);
        return json(200, this.preferences.get(subject));
      }
    }
    m = /^\/subjects\/([^/]+)$/.exec(path);
    if (method === "DELETE" && m) {
      const subject = decodeURIComponent(m[1]!);
      this.preferences.delete(subject);
      for (const [k, s] of this.sessions) if (s.who.sub === subject) this.sessions.delete(k);
      return json(200, { erased: subject });
    }
    return undefined;
  }
}
