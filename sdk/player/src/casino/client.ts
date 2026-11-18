import { z } from "zod";
import { ask, row, type Result } from "../client.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";

/** A game as the catalogue answers it; the fields a site shows pass through as they are. */
export interface Game {
  readonly gameCode: string;
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

const WireGame = z.object({ gameCode: z.string(), restrictedTerritories: z.array(z.string()).nullish(), prohibitedTerritories: z.array(z.string()).nullish() }).passthrough();
const Catalogue = z.object({ games: z.array(WireGame), total: z.number().int().optional(), nextOffset: z.number().int().nullish() });
const WireLaunch = z.object({ gameUrl: z.string().min(1), sessionId: z.string().min(1) });
const Grant = z.object({ gameId: z.string().nullish(), spinsRemaining: z.number().nullish() }).passthrough();
const Grants = z.array(Grant);

const game = (g: z.infer<typeof WireGame>): Game => {
  const { restrictedTerritories, prohibitedTerritories, ...rest } = g;
  return { ...rest, ...(restrictedTerritories ? { restrictedTerritories } : {}), ...(prohibitedTerritories ? { prohibitedTerritories } : {}) } as Game;
};

/** `casino`'s client (contracts/module-clients.md): `game-provider` and `rewards`. */
export interface CasinoClient {
  /** The launch's address and game session; its `launchToken` is dropped. */
  launch(gameId: string, options: { currency: string; funMode?: boolean }): Promise<Result<Launched>>;
  games(query?: CatalogueQuery): Promise<Result<{ games: Game[]; total?: number; nextOffset?: number | null }>>;
  game(gameCode: string): Promise<Result<Game>>;
  /** The games the player holds free rounds for (V4: each grant names its game). */
  freeRounds(currency: string): Promise<Result<string[]>>;
}

export function casinoClient(transport: Transport, player: () => string | null = () => null): CasinoClient {
  return {
    async launch(gameId, options) {
      const r = await ask(transport, row(routes, "launch"), WireLaunch, { body: { playerId: player() ?? "", gameId, currency: options.currency, demo: options.funMode === true } });
      return r.ok ? { ok: true, value: { address: r.value.gameUrl, gameSession: r.value.sessionId } } : r;
    },
    async games(query = {}) {
      const q = Object.fromEntries(Object.entries(query).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]));
      const r = await ask(transport, row(routes, "games"), Catalogue, { query: q });
      return r.ok ? { ok: true, value: { games: r.value.games.map(game), ...(r.value.total !== undefined ? { total: r.value.total } : {}), ...(r.value.nextOffset !== undefined ? { nextOffset: r.value.nextOffset } : {}) } } : r;
    },
    async game(gameCode) {
      const r = await ask(transport, row(routes, "game"), WireGame, { path: { gameCode } });
      return r.ok ? { ok: true, value: game(r.value) } : r;
    },
    async freeRounds(currency) {
      const r = await ask(transport, row(routes, "freeRounds"), Grants, { path: { player: player() ?? "" }, query: { currency } });
      if (!r.ok) return r;
      return { ok: true, value: [...new Set(r.value.filter((g) => g.gameId && (g.spinsRemaining ?? 1) > 0).map((g) => g.gameId!))] };
    },
  };
}
