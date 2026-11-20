// Every page route in `src/web/frame/router.tsx`'s `Pages`, with the seeded records (`seed.ts`) for
// its parameters, and every section or tab a parameter names (T134, SC-008). `routes.test` in the a11y
// suite holds this list to the router: a page added there and not here fails it.
export const player = "p-ana";
export const withdrawal = "7d1e4c2a-5b3f-4e6d-8a9c-1f2e3d4c5b6a";
export const affiliate = "aff-rio";

export const playerTabs = ["timeline", "profile", "notes", "wallets", "games", "sessions", "withdrawals", "rewards", "messages", "played", "devices", "tier", "identity", "protection"];
export const reports = ["revenue", "players", "transactions", "rewards", "providers", "day-end", "compliance", "disputes"];
export const configuration = [
  "modules", "brand", "countries", "currencies", "withdrawals", "rewards", "reporting", "games", "callbacks", "crm", "sends", "agent", "attribution", "identity-policy", "protection-settings",
];
export const payments = ["deposits", "hierarchy", "merchant", "providers", "terminals", "deposit-bounds", "routing", "outcomes", "lost-answers"];
export const affiliates = ["dashboard", "accounts", "plans", "links", "creatives", "payouts", "fraud", "messages", "settings", "managers"];

/** Each router path to the concrete routes the suite opens for it. */
export const byPath: Record<string, string[]> = {
  "/": ["/"],
  "/search": ["/search", "/search?q=ana"],
  "/players": ["/players"],
  "/players/online": ["/players/online"],
  "/players/sessions": ["/players/sessions"],
  "/players/$player": [`/players/${player}`],
  "/players/$player/$tab": playerTabs.map((t) => `/players/${player}/${t}`),
  "/withdrawals": ["/withdrawals"],
  "/withdrawals/$request": [`/withdrawals/${withdrawal}`],
  "/adjustments/batch": ["/adjustments/batch"],
  "/adjustments/accounts": ["/adjustments/accounts"],
  "/rewards": ["/rewards"],
  "/offers": ["/offers"],
  "/identity": ["/identity"],
  "/protection": ["/protection"],
  "/fraud": ["/fraud"],
  "/dashboard": ["/dashboard"],
  "/reports": ["/reports"],
  "/reports/$report": reports.map((r) => `/reports/${r}`),
  "/configuration": ["/configuration"],
  ...Object.fromEntries(configuration.map((c) => [`/configuration/${c}`, [`/configuration/${c}`]])),
  "/configuration/$area": [],
  "/payments": ["/payments"],
  "/payments/$area": payments.map((a) => `/payments/${a}`),
  "/team": ["/team"],
  "/team/roles": ["/team/roles"],
  "/affiliates": ["/affiliates"],
  "/affiliates/accounts/$affiliate": [`/affiliates/accounts/${affiliate}`],
  "/affiliates/$area": affiliates.map((a) => `/affiliates/${a}`),
  "/jobs": ["/jobs"],
  "/preferences": ["/preferences"],
};

export const routes: string[] = Object.values(byPath).flat();
