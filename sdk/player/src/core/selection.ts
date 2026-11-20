import type { Selection } from "../selection.ts";

const players = "/v1/casino/players/the-brand";
const me = { playerId: "player" } as const;

/**
 * `core`'s rows (spec 031): `players`, the wallet, responsible gambling and the cashier, in the
 * order of spec 027's table. `npm run generate` writes `routes.ts` and `shapes.ts` from these and
 * the services' kept descriptions.
 */
export const selection = [
  { id: "register", service: "players", operation: `POST ${players}/register`, visitor: true, takes: "Register", answers: "Registered" },
  { id: "verifyEmail", service: "players", operation: `POST ${players}/verify-email`, takes: "VerifyEmail", answers: "Verified" },
  { id: "resendVerification", service: "players", operation: `POST ${players}/resend-verification-email`, takes: "ResendVerification", answers: "Resent" },
  { id: "requestPasswordReset", service: "players", operation: `POST ${players}/password-reset/request`, takes: "RequestPasswordReset", answers: "ResetRequested" },
  { id: "signIn", service: "players", operation: `POST ${players}/login`, serverOnly: true, visitor: true, takes: "SignIn", answers: "SignedIn" },
  { id: "refresh", service: "players", operation: `POST ${players}/refresh`, serverOnly: true, takes: "Refresh", answers: "Refreshed" },
  { id: "signOut", service: "players", operation: `POST ${players}/{playerId}/logout`, rename: me, serverOnly: true, answers: "SignedOut" },
  { id: "profile", service: "players", operation: `GET ${players}/{playerId}`, rename: me, answers: "Profile" },
  { id: "changeProfile", service: "players", operation: `PATCH ${players}/{playerId}`, rename: me, takes: "ProfileEdit", answers: "Updated" },
  { id: "startDeposit", service: "players", operation: `POST ${players}/{playerId}/deposits`, rename: me, takes: "StartDeposit", answers: "Started" },
  { id: "unread", service: "players", operation: `GET ${players}/{playerId}/messages`, rename: me, fixedQuery: "status=unread&page=1&pageSize=1", answers: "Unread" },
  { id: "gameSession", service: "players", operation: "GET /v1/casino/sessions/the-brand/{sessionId}", answers: "GameSession" },
  { id: "endGameSession", service: "players", operation: "PATCH /v1/casino/sessions/the-brand/{sessionId}/end", answers: "Ended" },
  { id: "country", service: "players", operation: "GET /v1/casino/geo/lookup", visitor: true, answers: "Country" },
  { id: "balance", service: "wallet", operation: "GET /v1/casino/players/the-brand/wallets/{currency}/balance", answers: "MajorBalance" },
  { id: "protection", service: "responsible-gambling", operation: "GET /v1/casino/rg/the-brand/{player}/limits", query: ["currency"], answers: "Protection" },
  { id: "acknowledgeRealityCheck", service: "responsible-gambling", operation: "POST /v1/casino/rg/the-brand/{player}/reality-check/acknowledge", takes: "Acknowledge", answers: "Acknowledged" },
  { id: "submitCashier", service: "ingest", operation: "POST /v1/cashier/sessions/{ticket}/submit", takes: "Submit", answers: "Submitted" },
  { id: "abandonCashier", service: "ingest", operation: "POST /v1/cashier/sessions/{ticket}/abandon", answers: "Abandoned" },
  { id: "payment", service: "ingest", operation: "GET /v1/payments/{paymentId}", answers: "WirePayment" },
] as const satisfies Selection;
