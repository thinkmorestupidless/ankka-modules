import type { PlayerSite } from "@eitheror/player-sdk";
import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { Frame } from "./pages/Frame.tsx";
import { Lobby } from "./pages/Lobby.tsx";
import { Register } from "./pages/Register.tsx";
import { SignIn } from "./pages/SignIn.tsx";
import { VerifyEmail } from "./pages/VerifyEmail.tsx";

/** The parts of the site written apart from the router: the overlays, a game's page and the cashier's return. */
export interface Pages {
  readonly overlays?: ReactNode;
  readonly game?: (code: string) => ReactNode;
  readonly cashierReturn?: () => ReactNode;
}

/** The site's addresses (research R12): the lobby, registration, verification, sign-in, a game and the cashier's return. */
export function siteRouter(site: PlayerSite, pages: Pages = {}) {
  const root = createRootRoute({ component: () => <Frame site={site} overlays={pages.overlays} /> });
  const lobby = createRoute({ getParentRoute: () => root, path: "/", component: () => <Lobby site={site} /> });
  const register = createRoute({ getParentRoute: () => root, path: "/register", component: () => <Register site={site} /> });
  const signIn = createRoute({ getParentRoute: () => root, path: "/sign-in", component: () => <SignIn site={site} /> });
  const verify = createRoute({
    getParentRoute: () => root,
    path: "/verify-email",
    validateSearch: (s: Record<string, unknown>) => ({ token: typeof s.token === "string" ? s.token : "" }),
    component: function Verify() {
      const { token } = verify.useSearch();
      return <VerifyEmail site={site} token={token} />;
    },
  });
  const game = createRoute({
    getParentRoute: () => root,
    path: "/game/$code",
    component: function Game() {
      const { code } = game.useParams();
      return <>{pages.game?.(code) ?? null}</>;
    },
  });
  const cashierReturn = createRoute({ getParentRoute: () => root, path: "/cashier/return", component: () => <>{pages.cashierReturn?.() ?? null}</> });
  return createRouter({ routeTree: root.addChildren([lobby, register, signIn, verify, game, cashierReturn]) });
}
