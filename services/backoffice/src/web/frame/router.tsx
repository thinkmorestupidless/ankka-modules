// The page's routes (contracts/routes.md, *The page's routes*). No route names the brand: this
// backoffice is one brand's, at its own address.
import { createBrowserHistory, createRootRoute, createRoute, createRouter, type RouterHistory } from "@tanstack/react-router";
import type { ReactElement } from "react";

type Page = () => ReactElement;
import type { MessageKey } from "../../shared/messages/index.ts";
import { Shell } from "./Frame.tsx";
import { ModulesPage, NotFound, Placeholder } from "./pages.tsx";
import { PreferencesPage } from "../areas/preferences/PreferencesPage.tsx";
import { TeamPage } from "../areas/team/TeamPage.tsx";
import { JobsPage } from "../areas/jobs/JobsPage.tsx";
import { DashboardPage } from "../areas/dashboard/DashboardPage.tsx";
import { ReportPage } from "../areas/reports/ReportsPage.tsx";
import { IdentityQueuePage } from "../areas/identity/queue.tsx";
import { IdentityTab, ProtectionTab } from "../areas/players/tabs.tsx";
import { ProtectionCasesPage } from "../areas/protection/cases.tsx";

const page = (label: MessageKey): Page => () => <Placeholder label={label} />;

/** Path → the page; an area's task puts its own component here. */
export const Pages: [string, Page][] = [
  ["/", page("area.worklist")],
  ["/search", page("area.search")],
  ["/players", page("area.players")],
  ["/players/$player", page("area.players")],
  // until the player's page (T075) puts them in its tabs
  ["/players/$player/identity", IdentityTab],
  ["/players/$player/protection", ProtectionTab],
  ["/withdrawals", page("area.withdrawals")],
  ["/withdrawals/$request", page("area.withdrawals")],
  ["/adjustments/batch", page("area.adjustments")],
  ["/rewards", page("area.rewards")],
  ["/offers", page("area.offers")],
  ["/identity", () => <IdentityQueuePage />],
  ["/protection", () => <ProtectionCasesPage />],
  ["/fraud", page("area.fraud")],
  ["/dashboard", () => <DashboardPage />],
  ["/reports", () => <ReportPage />],
  ["/reports/$report", () => <ReportPage />],
  ["/configuration", page("area.configuration")],
  ["/configuration/modules", ModulesPage],
  ["/configuration/$area", page("area.configuration")],
  ["/payments", page("area.payments")],
  ["/payments/$area", page("area.payments")],
  ["/team", () => <TeamPage />],
  ["/team/roles", () => <TeamPage />],
  ["/affiliates", page("area.affiliates")],
  ["/affiliates/$area", page("area.affiliates")],
  ["/jobs", () => <JobsPage />],
  ["/preferences", () => <PreferencesPage />],
];

export function createAppRouter(history: RouterHistory = createBrowserHistory()) {
  const root = createRootRoute({ component: Shell, notFoundComponent: NotFound });
  const children = Pages.map(([path, component]) => createRoute({ getParentRoute: () => root, path, component }));
  return createRouter({ routeTree: root.addChildren(children), history, defaultPreload: false });
}
