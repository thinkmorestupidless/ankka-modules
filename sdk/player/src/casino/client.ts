import { ask, row, type Result } from "../client.ts";
import { refusal } from "../refusal.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";
import * as shapes from "./shapes.ts";

/** A game as the catalogue answers it; the fields a site shows pass through as they are. */
export interface Game {
  readonly gameId: string;
  readonly name?: string;
  readonly provider?: string;
  readonly restrictedTerritories?: readonly string[];
  readonly prohibitedTerritories?: readonly string[];
  readonly [field: string]: unknown;
}

export interface Launched {
  readonly address: string;
  readonly gameSession: string;
}

export type CatalogueQuery = Partial<Record<"category" | "themes" | "features" | "bonusTypes" | "tag" | "developer" | "search" | "country" | "ids", string>> & { readonly limit?: number; readonly offset?: number };

const game = (g: shapes.WireGame): Game => {
  const { restrictedTerritories, prohibitedTerritories, ...rest } = g;
  return { ...rest, ...(restrictedTerritories ? { restrictedTerritories } : {}), ...(prohibitedTerritories ? { prohibitedTerritories } : {}) } as Game;
};

/** `casino`'s client (contracts/module-clients.md): `game-provider` and `rewards`. */
export interface CasinoClient {
  /** The launch's address and game session; its `launchToken` is dropped. */
  launch(gameId: string, options: { currency: string; funMode?: boolean }): Promise<Result<Launched>>;
  games(query?: CatalogueQuery): Promise<Result<{ games: Game[]; total?: number; nextOffset?: number | null }>>;
  game(gameId: string): Promise<Result<Game>>;
  /** The games the player holds free rounds for (V4: each grant names its game). */
  freeRounds(currency: string): Promise<Result<string[]>>;
}

export function casinoClient(transport: Transport, player: () => string | null = () => null): CasinoClient {
  return {
    async launch(gameId, options) {
      const r = await ask(transport, row(routes, "launch"), shapes.WireLaunch, { body: { playerId: player() ?? "", gameId, currency: options.currency, demo: options.funMode === true } satisfies shapes.Launch });
      if (!r.ok) return r;
      // A launch with no address or no session is none: the description says strings, the page needs both.
      if (r.value.gameUrl === "" || r.value.sessionId === "") return { ok: false, refusal: refusal("malformed_answer", 200, {}, "game-provider") };
      return { ok: true, value: { address: r.value.gameUrl, gameSession: r.value.sessionId } };
    },
    async games(query = {}) {
      const q = Object.fromEntries(Object.entries(query).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]));
      const r = await ask(transport, row(routes, "games"), shapes.Catalogue, { query: q });
      return r.ok ? { ok: true, value: { games: (r.value.games ?? []).map(game), ...(r.value.total !== undefined && r.value.total !== null ? { total: r.value.total } : {}), ...(r.value.nextOffset !== undefined ? { nextOffset: r.value.nextOffset } : {}) } } : r;
    },
    async game(gameId) {
      const r = await ask(transport, row(routes, "game"), shapes.WireGame, { path: { gameId } });
      return r.ok ? { ok: true, value: game(r.value) } : r;
    },
    async freeRounds(currency) {
      const r = await ask(transport, row(routes, "freeRounds"), shapes.Grants, { path: { player: player() ?? "" }, query: { currency } });
      if (!r.ok) return r;
      return { ok: true, value: [...new Set(r.value.filter((g) => g.gameId && g.spinsRemaining > 0).map((g) => g.gameId))] };
    },
  };
}
