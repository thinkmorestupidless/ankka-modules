// The page's routes (contracts/routes.md, *The page's routes*). No route names the brand: this
// backoffice is one brand's, at its own address.
import { createBrowserHistory, createRootRoute, createRoute, createRouter, type RouterHistory } from "@tanstack/react-router";
import type { ReactElement } from "react";

type Page = () => ReactElement;
import type { MessageKey } from "../../shared/messages/index.ts";
import { Shell } from "./Frame.tsx";
import { NotFound, Placeholder } from "./pages.tsx";
import { PreferencesPage } from "../areas/preferences/PreferencesPage.tsx";
import { TeamPage } from "../areas/team/TeamPage.tsx";
import { Home } from "./home.tsx";
import { JobsPage } from "../areas/jobs/JobsPage.tsx";
import { DashboardPage } from "../areas/dashboard/DashboardPage.tsx";
import { ReportPage } from "../areas/reports/ReportsPage.tsx";
import { WalletsTab } from "../areas/corrections/adjust.tsx";
import { AccountsPage } from "../areas/corrections/accounts.tsx";
import { BatchPage } from "../areas/corrections/batch.tsx";
import { IdentityQueuePage } from "../areas/identity/queue.tsx";
import { IdentityTab, ProtectionTab, RewardsTab, WithdrawalsTab } from "../areas/players/tabs.tsx";
import { RewardsPage } from "../areas/rewards/list.tsx";
import { OffersPage } from "../areas/rewards/offers.tsx";
import { WithdrawalsQueuePage } from "../areas/withdrawals/queue.tsx";
import { WithdrawalRequestPage } from "../areas/withdrawals/request.tsx";
import { ProtectionCasesPage } from "../areas/protection/cases.tsx";
import { FraudCasesPage } from "../areas/fraud/cases.tsx";
import { IdentityPolicyPage } from "../areas/configuration/identity-policy.tsx";
import { ProtectionSettingsPage } from "../areas/configuration/protection-settings.tsx";
import { BrandSitePage, ConfigurationIndexPage, CountriesPage, CurrenciesPage, ModulesRoute, ReportingSettingsPage, RewardSettingsPage, WithdrawalSettingsPage } from "../areas/configuration/brand.tsx";

const page = (label: MessageKey): Page => () => <Placeholder label={label} />;

/** Path → the page; an area's task puts its own component here. */
export const Pages: [string, Page][] = [
  ["/", () => <Home />],
  ["/search", page("area.search")],
  ["/players", page("area.players")],
  ["/players/$player", page("area.players")],
  // until the player's page (T075) puts them in its tabs
  ["/players/$player/identity", IdentityTab],
  ["/players/$player/protection", ProtectionTab],
  ["/players/$player/withdrawals", WithdrawalsTab],
  ["/players/$player/rewards", RewardsTab],
  ["/players/$player/wallets", WalletsTab],
  ["/withdrawals", () => <WithdrawalsQueuePage />],
  ["/withdrawals/$request", () => <WithdrawalRequestPage />],
  ["/adjustments/batch", () => <BatchPage />],
  ["/adjustments/accounts", () => <AccountsPage />],
  ["/rewards", () => <RewardsPage />],
  ["/offers", () => <OffersPage />],
  ["/identity", () => <IdentityQueuePage />],
  ["/protection", () => <ProtectionCasesPage />],
  ["/fraud", () => <FraudCasesPage />],
  ["/dashboard", () => <DashboardPage />],
  ["/reports", () => <ReportPage />],
  ["/reports/$report", () => <ReportPage />],
  ["/configuration", () => <ConfigurationIndexPage />],
  ["/configuration/modules", () => <ModulesRoute />],
  ["/configuration/brand", () => <BrandSitePage />],
  ["/configuration/countries", () => <CountriesPage />],
  ["/configuration/currencies", () => <CurrenciesPage />],
  ["/configuration/withdrawals", () => <WithdrawalSettingsPage />],
  ["/configuration/rewards", () => <RewardSettingsPage />],
  ["/configuration/reporting", () => <ReportingSettingsPage />],
  ["/configuration/identity-policy", () => <IdentityPolicyPage />],
  ["/configuration/protection-settings", () => <ProtectionSettingsPage />],
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
