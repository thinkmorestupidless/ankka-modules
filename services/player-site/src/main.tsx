import { followDepositAddress, pageTransport, playerSite } from "@eitheror/player-sdk";
import { PlayerSiteProvider } from "@eitheror/player-sdk/react";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { usePageEvents } from "./app/hooks.ts";
import { LanguageProvider } from "./app/language.tsx";
import { ChatOverlay } from "./pages/Chat.tsx";
import { CashierReturn, DepositOverlay } from "./pages/Deposit.tsx";
import { GamePage } from "./pages/Game.tsx";
import { siteRouter } from "./router.tsx";
import { siteOptions } from "./site.ts";
import "./styles.css";

// One site per page: every call the page makes goes through the SDK's page transport to the site server.
const site = playerSite({ transport: pageTransport(), ...siteOptions });
const router = siteRouter(site, {
  overlays: (
    <>
      <DepositOverlay site={site} />
      <ChatOverlay site={site} />
    </>
  ),
  game: (code) => <GamePage site={site} code={code} />,
  cashierReturn: () => <CashierReturn />,
});

function App() {
  usePageEvents(site);
  return (
    <LanguageProvider offered={site.facts?.languages ?? []} currency={site.facts?.currency ?? "EUR"}>
      <PlayerSiteProvider site={site}>
        <RouterProvider router={router} />
      </PlayerSiteProvider>
    </LanguageProvider>
  );
}

void site.ready.then(() => {
  followDepositAddress(site, window.location.href);
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
});
