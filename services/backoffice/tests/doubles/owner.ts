// One owner's double: it answers from its fixtures, by the owner's own path templates, unless a script
// says otherwise (fail, be slow, answer another fixture, or answer as the platform's proxy). Every call
// is kept with its headers, so a test can say which token it carried.

/** An answer as the owner would give it. */
export interface Answer {
  status?: number;
  body?: unknown;
  headers?: Record<string, string>;
}

/** A fixture: one answer, or one per value of a path parameter. */
export type Entry = (Answer | { by: string; answers: Record<string, Answer>; otherwise?: Answer }) & {
  /** Answered with no `Authorization`: the server's own reads (the brand's roles and modules). */
  anonymous?: boolean;
};

/** `"GET /v1/casino/backoffice/{brand}/players/{playerId}/profile"` to its entry. */
export type Fixtures = Record<string, Entry>;

/** What a test tells an owner to do with the calls that fit; the newest script that fits wins. */
export interface Script {
  method?: string;
  /** A path template (`/v1/…/{playerId}/profile`) or a path; absent fits every path. */
  path?: string;
  /** Answer with this status (and `body`, else `{"error":"scripted_failure"}`). */
  status?: number;
  body?: unknown;
  /** Answer the scripted fixture `fixtures/scripted/<fixture>.json` (an `Answer`). */
  fixture?: string;
  /** Wait this long before answering. */
  delayMs?: number;
  /** Answer as the platform's proxy does: `X-Ankka-Answered-By: proxy`. */
  proxy?: boolean;
  /** Close the connection with no answer. */
  drop?: boolean;
  /** Fit this many calls, then stop fitting; absent fits every call. */
  times?: number;
}

export interface Recorded {
  owner: string;
  method: string;
  /** The path after the owner's segment. */
  path: string;
  query: string;
  headers: Record<string, string>;
  body: string;
  at: number;
}

export interface Asked {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Record<string, string>;
  body: string;
}

/** What the server writes: an answer, or a closed connection. */
export type Outcome = { answer: Answer; delayMs: number } | { drop: true; delayMs: number };

/** `{x}` segments as parameters; the rest literal. */
export function fit(template: string, path: string): Record<string, string> | undefined {
  const t = template.split("/");
  const p = path.split("/");
  if (t.length !== p.length) return undefined;
  const params: Record<string, string> = {};
  for (let i = 0; i < t.length; i++) {
    const name = /^\{(\w+)\}$/.exec(t[i]!)?.[1];
    if (name) {
      if (p[i] === "") return undefined;
      params[name] = decodeURIComponent(p[i]!);
    } else if (t[i] !== p[i]) return undefined;
  }
  return params;
}

/** A live route: answered by code rather than a fixture (the state service, the brand's roles). */
export type Live = (asked: Asked) => Answer | undefined;

export class Owner {
  readonly name: string;
  private fixtures: Fixtures = {};
  private scripts: (Script & { used: number })[] = [];
  readonly calls: Recorded[] = [];
  private readonly scripted: (name: string) => Answer;
  private readonly live: Live | undefined;

  constructor(name: string, scripted: (name: string) => Answer, live?: Live) {
    this.name = name;
    this.scripted = scripted;
    this.live = live;
  }

  load(fixtures: Fixtures): void {
    this.fixtures = { ...fixtures };
    this.scripts = [];
    this.calls.length = 0;
  }

  /** Entries added over the loaded ones, for one scenario. */
  merge(fixtures: Fixtures): void {
    Object.assign(this.fixtures, fixtures);
  }

  script(s: Script): void {
    this.scripts.push({ ...s, used: 0 });
  }

  handle(asked: Asked): Outcome {
    this.calls.push({ owner: this.name, method: asked.method, path: asked.path, query: asked.query.toString(), headers: asked.headers, body: asked.body, at: Date.now() });
    const s = this.fitting(asked);
    if (s) {
      s.used++;
      const delayMs = s.delayMs ?? 0;
      if (s.drop) return { drop: true, delayMs };
      if (s.proxy) return { answer: { status: s.status ?? 503, body: { error: s.body ?? "scripted proxy answer" }, headers: { "X-Ankka-Answered-By": "proxy" } }, delayMs };
      if (s.fixture) return { answer: this.scripted(s.fixture), delayMs };
      if (s.status !== undefined) return { answer: { status: s.status, body: s.body ?? { error: "scripted_failure" } }, delayMs };
      return { answer: this.answer(asked), delayMs };
    }
    return { answer: this.answer(asked), delayMs: 0 };
  }

  private fitting(asked: Asked): (Script & { used: number }) | undefined {
    for (let i = this.scripts.length - 1; i >= 0; i--) {
      const s = this.scripts[i]!;
      if (s.times !== undefined && s.used >= s.times) continue;
      if (s.method && s.method !== asked.method) continue;
      if (s.path && s.path !== asked.path && fit(s.path, asked.path) === undefined) continue;
      return s;
    }
    return undefined;
  }

  private answer(asked: Asked): Answer {
    const live = this.live?.(asked);
    if (live) return live;
    for (const [key, entry] of Object.entries(this.fixtures)) {
      const [method, template] = key.split(" ");
      if (method !== asked.method || !template) continue;
      const params = fit(template, asked.path);
      if (!params) continue;
      if (!entry.anonymous && !/^Bearer \S+$/.test(asked.headers["authorization"] ?? ""))
        return { status: 401, body: { error: "unauthenticated", message: "an operator route needs a bearer token" } };
      if ("by" in entry) return entry.answers[params[entry.by] ?? ""] ?? entry.otherwise ?? { status: 404, body: { error: "not_found", [entry.by]: params[entry.by] } };
      return entry;
    }
    return { status: 501, body: { error: "no_fixture", owner: this.name, route: `${asked.method} ${asked.path}` } };
  }
}
