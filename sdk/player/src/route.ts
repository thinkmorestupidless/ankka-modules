/** Who a route is for (contracts/module-clients.md). */
export type Audience = "public" | "player" | "ticket";

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/**
 * One row of a module's route table: one function of its client, and one entry of the site
 * server's allow-list (FR-004). The later generated client produces the same rows from a service's
 * OpenAPI document (FR-034).
 */
export interface Route {
  readonly module: ModuleName;
  readonly id: string;
  /** The service, by name at the calling address; `ingest` is reached at the shared payments' public address. */
  readonly service: string;
  readonly method: Method;
  /** The service's path; `{brand}` is filled by the site server, every other `{name}` from the call's path values. */
  readonly path: string;
  readonly audience: Audience;
  /** The answer is `text/event-stream`, piped as it arrives. */
  readonly stream?: boolean;
  /** Called by the site server's own routes only, never by a page (sign-in, refresh, sign-out). */
  readonly serverOnly?: boolean;
  /** The service's own query parameters a page may send. */
  readonly query?: readonly string[];
}

export type ModuleName = "core" | "casino" | "engagement" | "support";

/** A route table: what a module client may ask, by id. */
export type Routes = readonly Route[];

/** The `{name}`s of a path. */
export function pathNames(path: string): string[] {
  return [...path.matchAll(/\{([A-Za-z]+)\}/g)].map((m) => m[1]!);
}
