// players' operator profile is `{player, wallet, activeSessions, pinnedNotes}` (OperatorProfileRoutes):
// what each screen takes from it is read under `player`.
import { describe, expect, test } from "vitest";
import { flagOf, profileCurrency, tierOf, type ProfileAnswer } from "../../src/web/areas/players/read.ts";

const answer = (player: ProfileAnswer["player"]): ProfileAnswer => ({
  player,
  wallet: { realBalance: "250.00", bonusBalance: "0.00", currency: "EUR" },
  activeSessions: [],
  pinnedNotes: [],
});

describe("the profile's parts", () => {
  test("the VIP form's values are the player's", () => {
    expect(tierOf(answer({ playerId: "p-1", vipTier: "gold", potVip: true }))).toEqual({ vipTier: "gold", potVip: true });
  });

  test("a fraud case's player is flagged while players flags them, and named by username, else email", () => {
    expect(flagOf(answer({ playerId: "p-1", suspectedDuplicate: true, username: "ana.s", email: "a@example.test" }))).toEqual({ flagged: true, username: "ana.s" });
    expect(flagOf(answer({ playerId: "p-1", email: "a@example.test" }))).toEqual({ flagged: false, username: "a@example.test" });
  });

  test("the currency is the player's, else the wallet's", () => {
    expect(profileCurrency(answer({ playerId: "p-1", currency: "JPY" }))).toBe("JPY");
    expect(profileCurrency(answer({ playerId: "p-1", currency: "" }))).toBe("EUR");
  });
});
