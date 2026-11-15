// The page's routes (contracts/routes.md, *The page's routes*). No route names the brand: this
// backoffice is one brand's, at its own address.
import { createBrowserHistory, createRootRoute, createRoute, createRouter, type RouterHistory } from "@tanstack/react-router";
import type { ReactElement } from "react";

type Page = () => ReactElement;
import type { MessageKey } from "../../shared/messages/index.ts";
import { Shell } from "./Frame.tsx";
import { ModulesPage, NotFound, Placeholder } from "./pages.tsx";

const page = (label: MessageKey): Page => () => <Placeholder label={label} />;

/** Path → the page; an area's task puts its own component here. */
export const Pages: [string, Page][] = [
  ["/", page("area.worklist")],
  ["/search", page("area.search")],
  ["/players", page("area.players")],
  ["/players/$player", page("area.players")],
  ["/withdrawals", page("area.withdrawals")],
  ["/withdrawals/$request", page("area.withdrawals")],
  ["/adjustments/batch", page("area.adjustments")],
  ["/rewards", page("area.rewards")],
  ["/offers", page("area.offers")],
  ["/identity", page("area.identity")],
  ["/protection", page("area.protection")],
  ["/fraud", page("area.fraud")],
  ["/dashboard", page("area.dashboard")],
  ["/reports", page("area.reports")],
  ["/reports/$report", page("area.reports")],
  ["/configuration", page("area.configuration")],
  ["/configuration/modules", ModulesPage],
  ["/configuration/$area", page("area.configuration")],
  ["/payments", page("area.payments")],
  ["/payments/$area", page("area.payments")],
  ["/team", page("area.team")],
  ["/team/roles", page("area.team")],
  ["/affiliates", page("area.affiliates")],
  ["/affiliates/$area", page("area.affiliates")],
  ["/jobs", page("area.jobs")],
  ["/preferences", page("area.preferences")],
];

export function createAppRouter(history: RouterHistory = createBrowserHistory()) {
  const root = createRootRoute({ component: Shell, notFoundComponent: NotFound });
  const children = Pages.map(([path, component]) => createRoute({ getParentRoute: () => root, path, component }));
  return createRouter({ routeTree: root.addChildren(children), history, defaultPreload: false });
}
