// The route table (research R4): what the browser may ask for under `/api/s/<service>/…`, and where
// the server sends it. A route an owner lacks is an amendment to that owner, never a workaround here
// (FR-007). Each owner still decides admission; `permission` is what that owner requires, so the page
// can say which role would give it.

/** The brand's own project, or one of the shared projects (their addresses are the brand's values). */
export type Where = "brand" | "payments" | "programme" | "reasoner" | "games";
export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface Row {
  method: Method;
  /** The owning service's name in its project. */
  service: string;
  /** The browser's path under `/api/s/<service>`, without the brand. */
  path: string;
  /** The owner's path; `{brand}` is put in by the server. */
  upstream: string;
  where: Where;
  permission: string;
}

export interface Matched {
  row: Row;
  params: Record<string, string>;
}

/** A segment the browser may name: not empty, no traversal, no encoded separator. */
function safe(segment: string): boolean {
  return segment !== "" && segment !== "." && segment !== ".." && !/%2f|%5c|\\/i.test(segment);
}

export function match(rows: readonly Row[], method: string, service: string, rest: string): Matched | undefined {
  if (!rest.startsWith("/")) return undefined;
  const asked = rest.slice(1).split("/");
  if (!asked.every(safe)) return undefined;
  for (const row of rows) {
    if (row.method !== method || row.service !== service) continue;
    const template = row.path.slice(1).split("/");
    if (template.length !== asked.length) continue;
    const params: Record<string, string> = {};
    const fits = template.every((t, i) => {
      const a = asked[i]!;
      const name = /^\{(\w+)\}$/.exec(t)?.[1];
      if (!name) return t === a;
      params[name] = a;
      return true;
    });
    if (fits) return { row, params };
  }
  return undefined;
}

/** The owner's path for a matched row: the brand put in, the browser's parameters as it sent them. */
export function upstreamPath(m: Matched, brand: string): string {
  return m.row.upstream.replace(/\{(\w+)\}/g, (_, name: string) =>
    name === "brand" ? encodeURIComponent(brand) : (m.params[name] ?? ""),
  );
}

/**
 * The payments roles (contract `Routes.PaymentsRoles`): a payments route (`where` not the brand) names
 * the least of them its ACL admits, since `Routes.paymentsOperator` admits by role and no bag gives one.
 */
export const PaymentsRoles: readonly string[] = [
  "resource_viewer", "resource_developer", "resource_finance", "resource_operator", "support_agent",
  "resource_admin", "resource_owner", "fraud_analyst", "compliance_officer", "platform_admin",
];

const r = (method: Method, service: string, path: string, upstream: string, permission: string, where: Where = "brand"): Row =>
  ({ method, service, path, upstream, where, permission });

// Each owner's operator routes, as its code admits them (`Routes.operatorMay`). A literal path comes
// before a parameter in the same place, as `match` takes the first row that fits.
const P = "/v1/casino/backoffice/{brand}";
const B = "/v1/casino/brands/{brand}";
/** The affiliate programme's manager routes, for this brand. */
const A = "/v1/affiliates/{brand}";

/** The route table; `npm run owners` makes the typed clients in src/shared/owners/ from it. */
export const table: readonly Row[] = [
  // players: OperatorEndpoint at /v1/casino/backoffice/<slug>, ModulesEndpoint at /v1/casino/brands/<slug>/modules
  r("GET", "players", "/players", `${P}/players`, "player.read"),
  r("GET", "players", "/players/{playerId}/profile", `${P}/players/{playerId}/profile`, "player.read"),
  r("PATCH", "players", "/players/{playerId}/profile", `${P}/players/{playerId}/profile`, "profile.change"),
  r("PATCH", "players", "/players/{playerId}/date-of-birth", `${P}/players/{playerId}/date-of-birth`, "profile.change"),
  r("GET", "players", "/players/{playerId}/wallet", `${P}/players/{playerId}/wallet`, "player.read"),
  r("GET", "players", "/players/{playerId}/activity", `${P}/players/{playerId}/activity`, "player.read"),
  r("GET", "players", "/players/{playerId}/bets", `${P}/players/{playerId}/bets`, "player.read"),
  r("GET", "players", "/players/{playerId}/sessions/{sessionId}/bets", `${P}/players/{playerId}/sessions/{sessionId}/bets`, "player.read"),
  r("GET", "players", "/players/{playerId}/sessions", `${P}/players/{playerId}/sessions`, "player.read"),
  r("GET", "players", "/players/{playerId}/crm", `${P}/players/{playerId}/crm`, "player.read"),
  r("PATCH", "players", "/players/{playerId}/crm", `${P}/players/{playerId}/crm`, "profile.change"),
  r("PATCH", "players", "/players/{playerId}/suspend", `${P}/players/{playerId}/suspend`, "standing.change"),
  r("PATCH", "players", "/players/{playerId}/reinstate", `${P}/players/{playerId}/reinstate`, "standing.change"),
  r("POST", "players", "/players/{playerId}/close", `${P}/players/{playerId}/close`, "standing.change"),
  r("POST", "players", "/players/{playerId}/reopen", `${P}/players/{playerId}/reopen`, "standing.change"),
  r("PUT", "players", "/players/{playerId}/vip-tier", `${P}/players/{playerId}/vip-tier`, "tier.change"),
  r("PUT", "players", "/players/{playerId}/pot-vip", `${P}/players/{playerId}/pot-vip`, "tier.change"),
  r("GET", "players", "/players/{playerId}/tags", `${P}/players/{playerId}/tags`, "player.read"),
  r("PUT", "players", "/players/{playerId}/tags", `${P}/players/{playerId}/tags`, "tier.change"),
  r("GET", "players", "/players/{playerId}/devices", `${P}/players/{playerId}/devices`, "player.read"),
  r("GET", "players", "/players/{playerId}/games", `${P}/players/{playerId}/games`, "player.read"),
  r("POST", "players", "/players/{playerId}/reset-password", `${P}/players/{playerId}/reset-password`, "profile.change"),
  r("POST", "players", "/players/{playerId}/sessions/force-end-all", `${P}/players/{playerId}/sessions/force-end-all`, "session.end"),
  r("POST", "players", "/players/{playerId}/sessions/{sessionId}/force-end", `${P}/players/{playerId}/sessions/{sessionId}/force-end`, "session.end"),
  r("GET", "players", "/players/{playerId}/player-sessions", `${P}/players/{playerId}/player-sessions`, "player.read"),
  r("GET", "players", "/sessions", `${P}/sessions`, "player.read"),
  r("POST", "players", "/players/{playerId}/player-sessions/{sessionId}/force-close", `${P}/players/{playerId}/player-sessions/{sessionId}/force-close`, "session.end"),
  r("GET", "players", "/players/{playerId}/notes", `${P}/players/{playerId}/notes`, "player.read"),
  r("POST", "players", "/players/{playerId}/notes", `${P}/players/{playerId}/notes`, "note.write"),
  r("PATCH", "players", "/players/{playerId}/notes/{noteId}/pin", `${P}/players/{playerId}/notes/{noteId}/pin`, "note.write"),
  r("PATCH", "players", "/players/{playerId}/notes/{noteId}/unpin", `${P}/players/{playerId}/notes/{noteId}/unpin`, "note.write"),
  r("GET", "players", "/players/{playerId}/messages", `${P}/players/{playerId}/messages`, "player.read"),
  r("POST", "players", "/players/{playerId}/messages", `${P}/players/{playerId}/messages`, "message.send"),
  r("GET", "players", "/merge-tags", `${P}/merge-tags`, "message.send"),
  r("POST", "players", "/messages/preview", `${P}/messages/preview`, "message.send"),
  r("POST", "players", "/players/{playerId}/clear-suspect-flag", `${P}/players/{playerId}/clear-suspect-flag`, "flag.clear"),
  r("POST", "players", "/players/{playerId}/clear-no-deposit-block", `${P}/players/{playerId}/clear-no-deposit-block`, "flag.clear"),
  r("GET", "players", "/fraud-cases", `${P}/fraud-cases`, "fraud.review"),
  r("PATCH", "players", "/fraud-cases/{caseId}/review", `${P}/fraud-cases/{caseId}/review`, "fraud.review"),
  r("PATCH", "players", "/fraud-cases/{caseId}/dismiss", `${P}/fraud-cases/{caseId}/dismiss`, "fraud.review"),
  r("POST", "players", "/players/{playerId}/activity-export", `${P}/players/{playerId}/activity-export`, "export.player"),
  r("GET", "players", "/exports/{exportId}", `${P}/exports/{exportId}`, "export.player"),
  r("POST", "players", "/players/{playerId}/erasure", `${P}/players/{playerId}/erasure`, "erasure.request"),
  r("GET", "players", "/players/{playerId}/erasure", `${P}/players/{playerId}/erasure`, "erasure.request"),
  r("GET", "players", "/players/{playerId}/verification-link", `${P}/players/{playerId}/verification-link`, "profile.change"),
  r("GET", "players", "/brand-config", `${P}/brand-config`, "config.brand"),
  r("PUT", "players", "/brand-config", `${P}/brand-config`, "config.brand"),
  r("GET", "players", "/currency-config", `${P}/currency-config`, "config.brand"),
  r("PUT", "players", "/currency-config", `${P}/currency-config`, "config.brand"),
  r("GET", "players", "/allow-list", `${P}/allow-list`, "config.brand"),
  r("PUT", "players", "/allow-list", `${P}/allow-list`, "config.brand"),
  r("GET", "players", "/roles", `${P}/roles`, "roles.read"),
  r("PUT", "players", "/roles/{name}", `${P}/roles/{name}`, "roles.change"),
  r("DELETE", "players", "/roles/{name}", `${P}/roles/{name}`, "roles.change"),
  r("POST", "players", "/roles/{name}/reset", `${P}/roles/{name}/reset`, "roles.change"),
  r("PUT", "players", "/role-assignments/{operator}", `${P}/role-assignments/{operator}`, "roles.give"),
  r("GET", "players", "/modules", `${B}/modules`, "modules.read"),
  r("POST", "players", "/modules/requests", `${B}/modules/requests`, "modules.request"),

  // wallet: OperatorEndpoint at /v1/casino/backoffice/<slug>
  r("POST", "wallet", "/wallets/{player}/{currency}/adjustments", `${P}/wallets/{player}/{currency}/adjustments`, "adjustment.make"),
  r("PUT", "wallet", "/wallets/{player}/{currency}/turnover-override", `${P}/wallets/{player}/{currency}/turnover-override`, "turnover.override"),
  r("GET", "wallet", "/wallets/{player}/{currency}/balance", `${P}/wallets/{player}/{currency}/balance`, "wallet.read"),
  r("GET", "wallet", "/wallets/{player}/{currency}/restricted-funds", `${P}/wallets/{player}/{currency}/restricted-funds`, "wallet.read"),
  r("GET", "wallet", "/wallets/{player}/{currency}/transactions", `${P}/wallets/{player}/{currency}/transactions`, "wallet.read"),
  r("GET", "wallet", "/wallets/{player}/{currency}/lifetime-totals", `${P}/wallets/{player}/{currency}/lifetime-totals`, "wallet.read"),
  r("POST", "wallet", "/accounts/{kind}/{name}/adjustments", `${P}/accounts/{kind}/{name}/adjustments`, "adjustment.make"),
  r("GET", "wallet", "/accounts/{kind}/{name}/balance", `${P}/accounts/{kind}/{name}/balance`, "wallet.read"),
  r("PUT", "wallet", "/wallet-config/turnover", `${P}/wallet-config/turnover`, "config.brand"),
  // W3 (T104, not built yet): the brand's reward settings; the page is built against the amendment and a double
  r("PUT", "wallet", "/wallet-config/rewards", `${P}/wallet-config/rewards`, "config.brand"),
  r("GET", "wallet", "/wallet-config", `${P}/wallet-config`, "wallet.read"),

  // payouts: OperatorEndpoint, PlayerEndpoint's operator read and ConfigEndpoint, under /v1/casino/brands/<slug>
  r("GET", "payouts", "/withdrawal-requests", `${B}/withdrawal-requests`, "withdrawal.read"),
  r("GET", "payouts", "/withdrawal-requests/{requestId}", `${B}/withdrawal-requests/{requestId}`, "withdrawal.read"),
  r("POST", "payouts", "/withdrawal-requests/{requestId}/approve", `${B}/withdrawal-requests/{requestId}/approve`, "withdrawal.review"),
  r("POST", "payouts", "/withdrawal-requests/{requestId}/reject", `${B}/withdrawal-requests/{requestId}/reject`, "withdrawal.review"),
  r("POST", "payouts", "/withdrawal-requests/{requestId}/confirm-payout", `${B}/withdrawal-requests/{requestId}/confirm-payout`, "withdrawal.review"),
  r("POST", "payouts", "/withdrawal-requests/{requestId}/mark-payout-failed", `${B}/withdrawal-requests/{requestId}/mark-payout-failed`, "withdrawal.review"),
  r("GET", "payouts", "/players/{playerId}/withdrawal-history", `${B}/players/{playerId}/withdrawal-history`, "withdrawal.read"),
  r("GET", "payouts", "/withdrawal-config", `${B}/withdrawal-config`, "withdrawal.read"),
  r("PUT", "payouts", "/withdrawal-config", `${B}/withdrawal-config`, "config.brand"),

  // kyc: OperatorEndpoint at /v1/casino/backoffice/<slug>/kyc
  r("GET", "kyc", "/policy", `${P}/kyc/policy`, "config.brand"),
  r("PUT", "kyc", "/policy", `${P}/kyc/policy`, "config.brand"),
  r("POST", "kyc", "/policy/profile", `${P}/kyc/policy/profile`, "config.brand"),
  r("GET", "kyc", "/queue", `${P}/kyc/queue`, "identity.review"),
  r("GET", "kyc", "/{playerId}", `${P}/kyc/{playerId}`, "identity.read"),
  r("GET", "kyc", "/{playerId}/decisions", `${P}/kyc/{playerId}/decisions`, "identity.read"),
  r("POST", "kyc", "/{playerId}/uploads", `${P}/kyc/{playerId}/uploads`, "identity.review"),
  r("POST", "kyc", "/{playerId}/documents", `${P}/kyc/{playerId}/documents`, "identity.review"),
  r("GET", "kyc", "/{playerId}/views/{documentId}", `${P}/kyc/{playerId}/views/{documentId}`, "identity.review"),
  r("POST", "kyc", "/{playerId}/reviews/{documentId}", `${P}/kyc/{playerId}/reviews/{documentId}`, "identity.review"),
  r("PUT", "kyc", "/{playerId}/mode", `${P}/kyc/{playerId}/mode`, "identity.review"),
  r("PUT", "kyc", "/{playerId}/status", `${P}/kyc/{playerId}/status`, "identity.review"),
  r("PUT", "kyc", "/{playerId}/required", `${P}/kyc/{playerId}/required`, "identity.review"),
  r("POST", "kyc", "/{playerId}/override", `${P}/kyc/{playerId}/override`, "identity.review"),
  r("POST", "kyc", "/{playerId}/sof", `${P}/kyc/{playerId}/sof`, "identity.review"),
  r("POST", "kyc", "/{playerId}/flags/{reason}/dismiss", `${P}/kyc/{playerId}/flags/{reason}/dismiss`, "identity.review"),

  // responsible-gambling: OperatorEndpoint at /v1/casino/backoffice/<slug>/rg
  r("GET", "responsible-gambling", "/settings", `${P}/rg/settings`, "protection.read"),
  r("PUT", "responsible-gambling", "/settings", `${P}/rg/settings`, "config.brand"),
  // the brand's cases by status, oldest first (G2); a literal segment, so before the player's row
  r("GET", "responsible-gambling", "/cases", `${P}/rg/cases`, "protection.read"),
  r("GET", "responsible-gambling", "/{player}", `${P}/rg/{player}`, "protection.read"),
  r("GET", "responsible-gambling", "/{player}/history", `${P}/rg/{player}/history`, "protection.read"),
  r("PUT", "responsible-gambling", "/{player}/limits/{type}", `${P}/rg/{player}/limits/{type}`, "protection.change"),
  r("DELETE", "responsible-gambling", "/{player}/limits/{type}", `${P}/rg/{player}/limits/{type}`, "protection.change"),
  r("PUT", "responsible-gambling", "/{player}/cooling-off", `${P}/rg/{player}/cooling-off`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/cooling-off/lift", `${P}/rg/{player}/cooling-off/lift`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/self-exclude", `${P}/rg/{player}/self-exclude`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/return", `${P}/rg/{player}/return`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/cases", `${P}/rg/{player}/cases`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/cases/{caseId}/notes", `${P}/rg/{player}/cases/{caseId}/notes`, "protection.change"),
  r("POST", "responsible-gambling", "/{player}/cases/{caseId}/close", `${P}/rg/{player}/cases/{caseId}/close`, "protection.change"),

  // game-provider: BrandsEndpoint's catalogue at /v1/casino/brands/<slug>, BackofficeEndpoint at /v1/casino/backoffice/<slug>/game-provider
  r("GET", "game-provider", "/game-catalog-config", `${B}/game-catalog-config`, "config.games"),
  r("PUT", "game-provider", "/game-catalog-config", `${B}/game-catalog-config`, "config.games"),
  r("GET", "game-provider", "/game-catalog", `${B}/game-catalog`, "config.games"),
  r("GET", "game-provider", "/providers", `${P}/game-provider/providers`, "config.games"),
  r("GET", "game-provider", "/providers/{provider}", `${P}/game-provider/providers/{provider}`, "config.games"),
  r("PUT", "game-provider", "/providers/{provider}", `${P}/game-provider/providers/{provider}`, "config.games"),
  r("POST", "game-provider", "/providers/{provider}/catalogue/refresh", `${P}/game-provider/providers/{provider}/catalogue/refresh`, "config.games"),
  r("GET", "game-provider", "/providers/{provider}/catalogue/refresh", `${P}/game-provider/providers/{provider}/catalogue/refresh`, "config.games"),
  r("GET", "game-provider", "/unacknowledged", `${P}/game-provider/unacknowledged`, "callback.resolve"),
  r("POST", "game-provider", "/unacknowledged/{transactionId}/replay", `${P}/game-provider/unacknowledged/{transactionId}/replay`, "callback.resolve"),
  r("POST", "game-provider", "/unacknowledged/{transactionId}/resolve", `${P}/game-provider/unacknowledged/{transactionId}/resolve`, "callback.resolve"),
  r("GET", "game-provider", "/metrics", `${P}/game-provider/metrics`, "callback.resolve"),
  r("GET", "game-provider", "/rounds", `${P}/game-provider/rounds`, "player.read"),
  // a round by its id at any of the brand's providers, or by one of its transactions (GP2); literal
  // segments, so before the adapter's row, which `match()` would otherwise take first
  r("GET", "game-provider", "/rounds/by-round/{roundId}", `${P}/game-provider/rounds/by-round/{roundId}`, "player.read"),
  r("GET", "game-provider", "/rounds/by-transaction/{transactionId}", `${P}/game-provider/rounds/by-transaction/{transactionId}`, "player.read"),
  // one route per configured adapter (`rounds/<adapter>/{roundId}`); the adapter's name is the parameter here
  r("GET", "game-provider", "/rounds/{provider}/{roundId}", `${P}/game-provider/rounds/{provider}/{roundId}`, "player.read"),

  // engagement: BackofficeEndpoint at /v1/casino/backoffice/<slug>
  r("GET", "engagement", "/parked-sends", `${P}/parked-sends`, "send.release"),
  r("POST", "engagement", "/parked-sends/{sendId}/retry", `${P}/parked-sends/{sendId}/retry`, "send.release"),
  r("POST", "engagement", "/parked-sends/{sendId}/discard", `${P}/parked-sends/{sendId}/discard`, "send.release"),
  r("GET", "engagement", "/engagement/status", `${P}/engagement/status`, "config.crm"),
  r("GET", "engagement", "/players/{player}/sends", `${P}/players/{player}/sends`, "player.read"),
  r("GET", "engagement", "/presence", `${P}/presence`, "presence.read"),
  r("POST", "engagement", "/agent-stream/ticket", `${P}/agent-stream/ticket`, "presence.read"),
  // engagement's ConfigEndpoint at /v1/casino/crm-config/<slug>: the CRM's connection and the transactional sends' ids
  r("GET", "engagement", "/crm-config", "/v1/casino/crm-config/{brand}", "config.crm"),
  r("PUT", "engagement", "/crm-config", "/v1/casino/crm-config/{brand}", "config.crm"),
  r("POST", "engagement", "/crm-config/seed", "/v1/casino/crm-config/{brand}/seed", "config.crm"),

  // support-agent: ConfigEndpoint at /v1/casino/agent-config/<slug> (Motoko's configuration, its versions and status)
  r("GET", "support-agent", "/config", "/v1/casino/agent-config/{brand}", "config.agent"),
  r("PUT", "support-agent", "/config", "/v1/casino/agent-config/{brand}", "config.agent"),
  r("GET", "support-agent", "/config/status", "/v1/casino/agent-config/{brand}/status", "config.agent"),
  r("GET", "support-agent", "/config/versions/{version}", "/v1/casino/agent-config/{brand}/versions/{version}", "config.agent"),
  r("GET", "support-agent", "/conversations", "/v1/casino/agent-config/{brand}/conversations", "player.read"),

  // attribution: BackofficeEndpoint at /v1/casino/backoffice/<slug>/attribution, WebhooksEndpoint at …/attribution-webhooks
  r("GET", "attribution", "/settings", `${P}/attribution/settings`, "config.attribution"),
  r("PUT", "attribution", "/settings", `${P}/attribution/settings`, "config.attribution"),
  r("GET", "attribution", "/status", `${P}/attribution/status`, "config.attribution"),
  r("GET", "attribution", "/networks", `${P}/attribution/networks`, "config.attribution"),
  r("POST", "attribution", "/networks", `${P}/attribution/networks`, "config.attribution"),
  r("GET", "attribution", "/networks/{id}", `${P}/attribution/networks/{id}`, "config.attribution"),
  r("PUT", "attribution", "/networks/{id}", `${P}/attribution/networks/{id}`, "config.attribution"),
  r("DELETE", "attribution", "/networks/{id}", `${P}/attribution/networks/{id}`, "config.attribution"),
  r("POST", "attribution", "/networks/{id}/pseudonym-key", `${P}/attribution/networks/{id}/pseudonym-key`, "config.attribution"),
  r("GET", "attribution", "/records/{player}", `${P}/attribution/records/{player}`, "player.read"),
  r("GET", "attribution", "/signing-keys", `${P}/attribution/signing-keys`, "config.attribution"),
  r("POST", "attribution", "/signing-keys", `${P}/attribution/signing-keys`, "config.attribution"),
  r("DELETE", "attribution", "/signing-keys/{kid}", `${P}/attribution/signing-keys/{kid}`, "config.attribution"),
  // `undelivered` needs `network=`; a delivery id is `<subscription>/<factId>`, sent percent-encoded. Literal, before `{id}`
  r("GET", "attribution", "/webhooks/undelivered", `${P}/attribution-webhooks/undelivered`, "fact.redeliver"),
  r("POST", "attribution", "/webhooks/undelivered/{delivery}/resend", `${P}/attribution-webhooks/undelivered/{delivery}/resend`, "fact.redeliver"),
  r("POST", "attribution", "/webhooks/undelivered/{delivery}/discard", `${P}/attribution-webhooks/undelivered/{delivery}/discard`, "fact.redeliver"),
  r("GET", "attribution", "/webhooks", `${P}/attribution-webhooks`, "config.attribution"),
  r("POST", "attribution", "/webhooks", `${P}/attribution-webhooks`, "config.attribution"),
  r("GET", "attribution", "/webhooks/{id}", `${P}/attribution-webhooks/{id}`, "config.attribution"),
  r("PUT", "attribution", "/webhooks/{id}", `${P}/attribution-webhooks/{id}`, "config.attribution"),
  r("DELETE", "attribution", "/webhooks/{id}", `${P}/attribution-webhooks/{id}`, "config.attribution"),

  // rewards: RewardAdminEndpoint at /v1/casino/brands/<slug>/reward-grants, OffersEndpoint at …/offers
  r("GET", "rewards", "/reward-grants", `${B}/reward-grants`, "reward.read"),
  r("GET", "rewards", "/reward-grants/{rewardId}", `${B}/reward-grants/{rewardId}`, "reward.read"),
  r("POST", "rewards", "/reward-grants/{playerId}", `${B}/reward-grants/{playerId}`, "reward.grant"),
  r("POST", "rewards", "/reward-grants/{rewardId}/revoke", `${B}/reward-grants/{rewardId}/revoke`, "reward.revoke"),
  r("GET", "rewards", "/offers", `${B}/offers`, "offer.manage"),
  r("POST", "rewards", "/offers", `${B}/offers`, "offer.manage"),
  // a literal segment, so before the offer's row
  r("PUT", "rewards", "/offers/order", `${B}/offers/order`, "offer.manage"),
  r("GET", "rewards", "/offers/free-rounds/spin-values", `${B}/offers/free-rounds/spin-values`, "offer.manage"),
  r("GET", "rewards", "/offers/{offerId}", `${B}/offers/{offerId}`, "offer.manage"),
  r("PATCH", "rewards", "/offers/{offerId}", `${B}/offers/{offerId}`, "offer.manage"),
  r("DELETE", "rewards", "/offers/{offerId}", `${B}/offers/{offerId}`, "offer.manage"),

  // merchant (shared `payments`): PaymentsEndpoint at /v1/payments; admits a brand's operator once amendment H1, H2 (T071) lands
  // merchant (shared `payments`), US9 (T108): the hierarchy, provider configurations, terminals, keys, routing
  // rules, deposits and operators, each admitted by `Routes.paymentsOperator` (any payments role at a level;
  // only an invitation compares roles, `Privilege.canAssignRole`), so the column names the least role admitted
  r("POST", "merchant", "/holdings", "/v1/holdings", "resource_viewer", "payments"),
  r("GET", "merchant", "/holdings", "/v1/holdings", "resource_viewer", "payments"),
  r("GET", "merchant", "/holdings/{id}", "/v1/holdings/{id}", "resource_viewer", "payments"),
  r("PUT", "merchant", "/holdings/{id}/status", "/v1/holdings/{id}/status", "resource_viewer", "payments"),
  r("GET", "merchant", "/holdings/{id}/companies", "/v1/holdings/{id}/companies", "resource_viewer", "payments"),
  r("POST", "merchant", "/companies", "/v1/companies", "resource_viewer", "payments"),
  r("GET", "merchant", "/companies", "/v1/companies", "resource_viewer", "payments"),
  r("GET", "merchant", "/companies/{id}", "/v1/companies/{id}", "resource_viewer", "payments"),
  r("PUT", "merchant", "/companies/{id}/status", "/v1/companies/{id}/status", "resource_viewer", "payments"),
  r("POST", "merchant", "/sub-brands", "/v1/sub-brands", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands", "/v1/sub-brands", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands/{id}", "/v1/sub-brands/{id}", "resource_viewer", "payments"),
  r("PUT", "merchant", "/sub-brands/{id}/status", "/v1/sub-brands/{id}/status", "resource_viewer", "payments"),
  r("POST", "merchant", "/sub-brands/{id}/psp-credentials", "/v1/sub-brands/{id}/psp-credentials", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands/{id}/psp-credentials", "/v1/sub-brands/{id}/psp-credentials", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands/{id}/psp-credentials/{provider}", "/v1/sub-brands/{id}/psp-credentials/{provider}", "resource_viewer", "payments"),
  r("PUT", "merchant", "/sub-brands/{id}/psp-credentials/{provider}", "/v1/sub-brands/{id}/psp-credentials/{provider}", "resource_viewer", "payments"),
  r("DELETE", "merchant", "/sub-brands/{id}/psp-credentials/{provider}", "/v1/sub-brands/{id}/psp-credentials/{provider}", "resource_viewer", "payments"),
  r("POST", "merchant", "/sub-brands/{id}/psp-credentials/{provider}/activate", "/v1/sub-brands/{id}/psp-credentials/{provider}/activate", "resource_viewer", "payments"),
  r("POST", "merchant", "/sub-brands/{id}/psp-credentials/{provider}/disable", "/v1/sub-brands/{id}/psp-credentials/{provider}/disable", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands/{id}/gateway-config", "/v1/sub-brands/{id}/gateway-config", "resource_viewer", "payments"),
  r("GET", "merchant", "/sub-brands/{id}/deposit-bounds", "/v1/sub-brands/{id}/deposit-bounds", "resource_viewer", "payments"),
  r("PUT", "merchant", "/sub-brands/{id}/deposit-bounds", "/v1/sub-brands/{id}/deposit-bounds", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}", "/v1/merchants/{id}", "resource_viewer", "payments"),
  r("POST", "merchant", "/merchants/{id}/keys", "/v1/merchants/{id}/keys", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}/keys", "/v1/merchants/{id}/keys", "resource_viewer", "payments"),
  r("POST", "merchant", "/merchants/{id}/keys/{keyId}/disable", "/v1/merchants/{id}/keys/{keyId}/disable", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}/psp-configurations", "/v1/merchants/{id}/psp-configurations", "resource_viewer", "payments"),
  r("POST", "merchant", "/merchants/{id}/gateways/{gateway}/integrations", "/v1/merchants/{id}/gateways/{gateway}/integrations", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}/gateways/integrations", "/v1/merchants/{id}/gateways/integrations", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}/gateways/integrations/{tid}", "/v1/merchants/{id}/gateways/integrations/{tid}", "resource_viewer", "payments"),
  r("PUT", "merchant", "/merchants/{id}/gateways/integrations/{tid}/properties", "/v1/merchants/{id}/gateways/integrations/{tid}/properties", "resource_viewer", "payments"),
  r("PUT", "merchant", "/merchants/{id}/gateways/integrations/{tid}/capability", "/v1/merchants/{id}/gateways/integrations/{tid}/capability", "resource_viewer", "payments"),
  r("POST", "merchant", "/merchants/{id}/gateways/integrations/{tid}/activate", "/v1/merchants/{id}/gateways/integrations/{tid}/activate", "resource_viewer", "payments"),
  r("POST", "merchant", "/merchants/{id}/gateways/integrations/{tid}/disable", "/v1/merchants/{id}/gateways/integrations/{tid}/disable", "resource_viewer", "payments"),
  r("DELETE", "merchant", "/merchants/{id}/gateways/integrations/{tid}", "/v1/merchants/{id}/gateways/integrations/{tid}", "resource_viewer", "payments"),
  r("GET", "merchant", "/merchants/{id}/gateway-config", "/v1/merchants/{id}/gateway-config", "resource_viewer", "payments"),
  r("GET", "merchant", "/catalog/gateway/{gateway}", "/v1/catalog/gateway/{gateway}", "resource_viewer", "payments"),
  r("GET", "merchant", "/payments/cashier/uncredited", "/v1/payments/cashier/uncredited", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/cashier/{paymentId}/retry-credit", "/v1/payments/cashier/{paymentId}/retry-credit", "resource_viewer", "payments"),
  r("GET", "merchant", "/payments/routing-rules/state", "/v1/payments/routing-rules/state", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/routing-rules", "/v1/payments/routing-rules", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/routing-rules/deploy", "/v1/payments/routing-rules/deploy", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/routing-rules/simulate", "/v1/payments/routing-rules/simulate", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/routing-rules/rollback/{version}", "/v1/payments/routing-rules/rollback/{version}", "resource_viewer", "payments"),
  r("POST", "merchant", "/payments/routing-rules/retire", "/v1/payments/routing-rules/retire", "resource_viewer", "payments"),
  r("GET", "merchant", "/payments/terminals", "/v1/payments/terminals", "resource_viewer", "payments"),
  // an invitation is refused `403 PRIVILEGE_ESCALATION` for a role the inviter does not strictly outrank
  r("POST", "merchant", "/operators", "/v1/operators", "resource_viewer", "payments"),
  r("POST", "merchant", "/operators/{id}/resend-invitation", "/v1/operators/{id}/resend-invitation", "resource_viewer", "payments"),
  r("POST", "merchant", "/operators/{id}/reset-password", "/v1/operators/{id}/reset-password", "resource_viewer", "payments"),
  r("PUT", "merchant", "/operators/{id}/scope", "/v1/operators/{id}/scope", "resource_viewer", "payments"),
  r("DELETE", "merchant", "/operators/{id}", "/v1/operators/{id}", "resource_viewer", "payments"),
  // merchant's operators, for the timeline's actors by name (research R12): merchant lists them to payments
  // operators only, so a brand's operator is refused and the timeline shows ids; a literal, before `/{paymentId}`
  r("GET", "merchant", "/operators", "/v1/operators", "player.read", "payments"),
  r("GET", "merchant", "/cashier", "/v1/payments/cashier", "wallet.read", "payments"),
  r("GET", "merchant", "/cashier/summary", "/v1/payments/cashier/summary", "wallet.read", "payments"),
  r("GET", "merchant", "/{paymentId}", "/v1/payments/{paymentId}", "wallet.read", "payments"),

  // psp-gateway (shared `payments`): OutcomesEndpoint at /v1/psp/outcomes, `Routes.paymentsOperator`
  r("GET", "psp-gateway", "/psp/outcomes/undelivered", "/v1/psp/outcomes/undelivered", "resource_viewer", "payments"),
  r("GET", "psp-gateway", "/psp/outcomes/refused", "/v1/psp/outcomes/refused", "resource_viewer", "payments"),
  r("POST", "psp-gateway", "/psp/outcomes/{reference}/redrive", "/v1/psp/outcomes/{reference}/redrive", "resource_viewer", "payments"),

  // ingest (shared `payments`): the merchant edge's request log, rate limits and lost answers, `Routes.paymentsOperator`
  r("GET", "ingest", "/ingest/sub-brands/{subBrand}/requests", "/v1/ingest/sub-brands/{subBrand}/requests", "resource_viewer", "payments"),
  r("GET", "ingest", "/ingest/sub-brands/{subBrand}/rate", "/v1/ingest/sub-brands/{subBrand}/rate", "resource_viewer", "payments"),
  r("PUT", "ingest", "/ingest/sub-brands/{subBrand}/rate", "/v1/ingest/sub-brands/{subBrand}/rate", "resource_viewer", "payments"),
  r("GET", "ingest", "/ingest/lost-answers", "/v1/ingest/lost-answers", "resource_viewer", "payments"),
  r("POST", "ingest", "/ingest/lost-answers/{recordId}/seen", "/v1/ingest/lost-answers/{recordId}/seen", "resource_viewer", "payments"),

  // reporting: ReportingEndpoint at /v1/casino/brands/<slug>/reporting, each route's permission its `Access`'s.
  // A `format=csv` read is an export (a document, `202 {reference}` over the synchronous limit); the page
  // starts those through `/api/jobs`. The documents are admitted by their kind's permission, which reporting
  // names in its refusal; `report.read` is the most of them.
  r("GET", "reporting", "/dashboard/kpis", `${B}/reporting/dashboard/kpis`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/trend", `${B}/reporting/dashboard/trend`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/kpi-history", `${B}/reporting/dashboard/kpi-history`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/geo", `${B}/reporting/dashboard/geo`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/rankings", `${B}/reporting/dashboard/rankings`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/activity", `${B}/reporting/dashboard/activity`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/bonus-summary", `${B}/reporting/dashboard/bonus-summary`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/payment-health", `${B}/reporting/dashboard/payment-health`, "dashboard.read"),
  r("GET", "reporting", "/dashboard/predictions", `${B}/reporting/dashboard/predictions`, "dashboard.read"),
  r("GET", "reporting", "/reports/ggr", `${B}/reporting/reports/ggr`, "report.read"),
  r("GET", "reporting", "/reports/players", `${B}/reporting/reports/players`, "report.read"),
  r("GET", "reporting", "/reports/transactions", `${B}/reporting/reports/transactions`, "report.read"),
  r("GET", "reporting", "/reports/transactions/export", `${B}/reporting/reports/transactions/export`, "report.read"),
  r("GET", "reporting", "/reports/bonus", `${B}/reporting/reports/bonus`, "report.read"),
  r("GET", "reporting", "/reports/psp", `${B}/reporting/reports/psp`, "report.read"),
  r("GET", "reporting", "/compliance/rg-cases", `${B}/reporting/compliance/rg-cases`, "report.compliance"),
  r("GET", "reporting", "/compliance/kyc-decisions", `${B}/reporting/compliance/kyc-decisions`, "report.compliance"),
  r("GET", "reporting", "/day-end/{day}", `${B}/reporting/day-end/{day}`, "report.dayEnd"),
  r("GET", "reporting", "/day-end/{day}/export", `${B}/reporting/day-end/{day}/export`, "report.dayEnd"),
  r("GET", "reporting", "/players/{playerId}/dispute-report", `${B}/reporting/players/{playerId}/dispute-report`, "export.player"),
  r("GET", "reporting", "/documents", `${B}/reporting/documents`, "report.read"),
  r("GET", "reporting", "/documents/{reference}", `${B}/reporting/documents/{reference}`, "report.read"),

  // reporting's settings (Access.settings): the reports tab, test accounts, the default currency, the
  // synchronous limits and each document kind's lifetime
  r("GET", "reporting", "/settings", `${B}/reporting/settings`, "config.brand"),
  r("PUT", "reporting", "/settings", `${B}/reporting/settings`, "config.brand"),

  // affiliate-programme (shared `programme`, US10): OperatorEndpoint at /v1/affiliates/<slug>, the project
  // where the brand's values put it (`backoffice-shared-projects`, so spec 028 can move it). The programme
  // admits a manager by realm role (`Access.manager`: affiliate_manager, brand_admin, platform_admin) holding
  // this brand, the managers' routes brand_admin and platform_admin only (`Access.managerAdmin`).
  r("GET", "affiliate-programme", "/revenue", `${A}/revenue`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/revenue/by-campaign", `${A}/revenue/by-campaign`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/revenue/geo", `${A}/revenue/geo`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/commission-plans", `${A}/commission-plans`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/commission-plans", `${A}/commission-plans`, "programme.manage", "programme"),
  r("PATCH", "affiliate-programme", "/commission-plans/{plan}", `${A}/commission-plans/{plan}`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts", `${A}/accounts`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts", `${A}/accounts`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}", `${A}/accounts/{affiliate}`, "programme.manage", "programme"),
  r("PATCH", "affiliate-programme", "/accounts/{affiliate}", `${A}/accounts/{affiliate}`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/approve", `${A}/accounts/{affiliate}/approve`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/reject", `${A}/accounts/{affiliate}/reject`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/resend-invitation", `${A}/accounts/{affiliate}/resend-invitation`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/reset-password", `${A}/accounts/{affiliate}/reset-password`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/update-email", `${A}/accounts/{affiliate}/update-email`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/revenue", `${A}/accounts/{affiliate}/revenue`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/network-health", `${A}/accounts/{affiliate}/network-health`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/network", `${A}/accounts/{affiliate}/network`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/sub-affiliates", `${A}/accounts/{affiliate}/sub-affiliates`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/notes", `${A}/accounts/{affiliate}/notes`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/notes", `${A}/accounts/{affiliate}/notes`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/accounts/{affiliate}/audit", `${A}/accounts/{affiliate}/audit`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/accounts/{affiliate}/adjustments", `${A}/accounts/{affiliate}/adjustments`, "programme.manage", "programme"),
  // what the affiliate's portal shows, read by the manager (A2): each answered read audited `VIEWED_AS`; no write under it
  r("GET", "affiliate-programme", "/accounts/{affiliate}/portal/{view}", `${A}/accounts/{affiliate}/portal/{view}`, "programme.manage", "programme"),
  // a membership's status at the brand (O11–O13; O19 is not served): the sub's id is the account's
  r("POST", "affiliate-programme", "/network/{sub}/approve", `${A}/network/{sub}/approve`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/network/{sub}/suspend", `${A}/network/{sub}/suspend`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/network/{sub}/reactivate", `${A}/network/{sub}/reactivate`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/tracking-links", `${A}/tracking-links`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/tracking-links", `${A}/tracking-links`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/creatives", `${A}/creatives`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/creatives", `${A}/creatives`, "programme.manage", "programme"),
  r("DELETE", "affiliate-programme", "/creatives/{creative}", `${A}/creatives/{creative}`, "programme.manage", "programme"),
  // a period's payouts (`?period=yyyy-MM`): closing it, each payout's status, the CSV export
  r("POST", "affiliate-programme", "/payouts/close", `${A}/payouts/close`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/payouts/export", `${A}/payouts/export`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/payouts", `${A}/payouts`, "programme.manage", "programme"),
  r("PATCH", "affiliate-programme", "/payouts/{payout}/status", `${A}/payouts/{payout}/status`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/fraud-flags", `${A}/fraud-flags`, "programme.manage", "programme"),
  r("PATCH", "affiliate-programme", "/fraud-flags/{flag}/dismiss", `${A}/fraud-flags/{flag}/dismiss`, "programme.manage", "programme"),
  r("POST", "affiliate-programme", "/messages", `${A}/messages`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/settings", `${A}/settings`, "programme.manage", "programme"),
  r("PUT", "affiliate-programme", "/settings", `${A}/settings`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/programme", `${A}/programme`, "programme.manage", "programme"),
  r("PUT", "affiliate-programme", "/programme", `${A}/programme`, "programme.manage", "programme"),
  r("GET", "affiliate-programme", "/managers", `${A}/managers`, "programme.managers", "programme"),
  r("POST", "affiliate-programme", "/managers", `${A}/managers`, "programme.managers", "programme"),
  r("POST", "affiliate-programme", "/managers/{user}/reset-password", `${A}/managers/{user}/reset-password`, "programme.managers", "programme"),
  r("DELETE", "affiliate-programme", "/managers/{user}", `${A}/managers/{user}`, "programme.managers", "programme"),

  // each service's views' freshness (amendment F, T051): its service endpoint, the permission that opens its lists
  r("GET", "players", "/status", `${B}/players/status`, "player.read"),
  r("GET", "wallet", "/status", `${B}/wallet/status`, "wallet.read"),
  r("GET", "payouts", "/status", `${B}/payouts/status`, "withdrawal.read"),
  r("GET", "kyc", "/status", `${B}/kyc/status`, "identity.review"),
  r("GET", "responsible-gambling", "/status", `${B}/rg/status`, "protection.read"),
  r("GET", "rewards", "/status", `${B}/rewards/status`, "reward.read"),
  r("GET", "reporting", "/status", `${B}/reporting/status`, "dashboard.read"),
];
