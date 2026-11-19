/**
 * One row a module's client may ask (spec 031, contracts/generation.md): which operation of which
 * service's kept description it is, and what a description cannot say. `scripts/generate.ts` reads
 * a module's selection and the descriptions and writes the module's `routes.ts` and `shapes.ts`.
 */
export interface SelectionRow {
  /** The row's id: the function of the module's client. */
  readonly id: string;
  /** The service, by its directory under `services/`. */
  readonly service: string;
  /** The operation as the kept description has it: its method and its path, `the-brand` in place of the brand. */
  readonly operation: string;
  /** A path parameter under the SDK's name (`{ playerId: "player" }`). */
  readonly rename?: Readonly<Record<string, string>>;
  /** A path parameter that always has one value (`{ route: "chat_with_player" }`). */
  readonly fixed?: Readonly<Record<string, string>>;
  /** The operation's query parameters a page may send. */
  readonly query?: readonly string[];
  /** A query the row always sends, kept in its path (`status=unread&page=1&pageSize=1`). */
  readonly fixedQuery?: string;
  /** The brand goes as `?brand=` (the catalogue). */
  readonly brandQuery?: boolean;
  /** Called by the site server's own routes only. */
  readonly serverOnly?: boolean;
  /** The service reads the visitor's network address and user agent. */
  readonly visitor?: boolean;
  /** The name `shapes.ts` gives what the operation takes. */
  readonly takes?: string;
  /** The name `shapes.ts` gives what the operation answers. */
  readonly answers?: string;
}

export type Selection = readonly SelectionRow[];
