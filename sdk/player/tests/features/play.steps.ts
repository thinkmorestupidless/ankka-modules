import { expect } from "vitest";
import type { PlayBehaviour, PlayStep } from "../../src/casino/play.ts";
import { bind, world, type World } from "./setup.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const MIN = 60_000;
let w: World;

const games = () => w.site.games!;
const play = (game: string): PlayBehaviour => games().step().open.find((g) => g.game === game)!;
const kind = (game: string) => play(game)?.step().kind;
const sessionOf = (game: string) => (play(game).step() as { gameSession: string }).gameSession;
const signIn = async () => {
  w.site.session.actions.signIn(ana);
  await w.clock.settle();
  expect(w.site.player).not.toBeNull();
};
const pick = async (game: string, o: { funMode?: boolean } = {}) => {
  games().actions.pick(game, o);
  await w.clock.settle();
};
const majorOf = (minor: string) => (BigInt(minor) / 100n).toString() + "." + (BigInt(minor) % 100n).toString().padStart(2, "0");
const interval = (minutes: number | null) => {
  w.doubles.brand.protection = { realityCheckIntervalMinutes: minutes, isCoolingOff: false, coolingOffUntil: null };
};
const playing = async (game = "pools") => {
  await signIn();
  await pick(game);
  expect(kind(game)).toBe("playing");
};
const ends = () => w.doubles.askedOf("endGameSession").map((a) => a.path.sessionId);
let launchesBefore = 0;

const launchRefusals: Record<string, { reply: { status: number; body?: Record<string, unknown>; silent?: boolean }; fields: Record<string, unknown>; service?: string }> = {
  PLAY_BLOCKED_KYC: { reply: { status: 403, body: { error: "PLAY_BLOCKED_KYC", message: "verify", kycStatus: "PENDING" } }, fields: { kycStatus: "PENDING" } },
  cooling_off_active: { reply: { status: 403, body: { error: "cooling_off_active", coolingOffUntil: "2026-11-25T12:00:00Z" } }, fields: { until: "2026-11-25T12:00:00Z" } },
  session_limit_reached: { reply: { status: 403, body: { error: "session_limit_reached", limitMinutes: 60, usedMinutes: 61 } }, fields: { limitMinutes: 60, usedMinutes: 61 } },
  account_restricted: { reply: { status: 403, body: { error: "account_restricted", reason: "suspended" } }, fields: { reason: "suspended" } },
  RATE_LIMITED: { reply: { status: 429, body: { error: "RATE_LIMITED", action: "launch", retryAfterSeconds: 20 } }, fields: { action: "launch", retryAfterSeconds: 20 } },
  service_unavailable: { reply: { status: 200, silent: true }, fields: {}, service: "game-provider" },
};

await bind("play.feature", ({ Background, Scenario, ScenarioOutline }) => {
  Background(({ Given, And }) => {
    Given('a player site of brand "guyanabet"', async () => {
      // a long idle limit: the idle sign-out is session.feature's, and would close a long game here
      w = await world({}, { idleMinutes: 24 * 60 });
    });
    And('a game "pools" in the catalogue of brand "guyanabet"', () => {
      expect(w.doubles.brand.games.map((g) => g.gameId)).toContain("pools");
    });
  });

  Scenario("a visitor who picks a game is asked to register", ({ Given, When, Then, And }) => {
    Given("a visitor of the player site", () => expect(w.site.player).toBeNull());
    When('they pick the game "pools"', () => pick("pools"));
    Then("they are asked to register", () => expect(kind("pools")).toBe("needs_registration"));
    And("no launch is asked of the brand", () => expect(w.doubles.askedOf("launch")).toEqual([]));
  });

  Scenario("a signed-in player with no playable funds is taken to the deposit", ({ Given, When, Then, And }) => {
    Given('a player signed in at the player site with no playable funds for the game "pools"', async () => {
      w.doubles.brand.balance = { real: "0.00", bonus: "0.00" };
      await signIn();
    });
    When('they pick the game "pools"', () => pick("pools"));
    Then("the deposit behaviour stands at the amount", () => expect(w.site.currentDeposit?.step().kind).toBe("amount"));
    And("no launch is asked of the brand", () => expect(w.doubles.askedOf("launch")).toEqual([]));
  });

  ScenarioOutline("playable funds are a real balance, a restricted balance or free rounds for the game", ({ Given, And, When, Then }, v) => {
    Given("a player signed in at the player site", signIn);
    And('their real balance is "<real>" minor units, their restricted balance is "<restricted>" minor units and they hold free rounds for <free rounds>', async () => {
      w.doubles.brand.balance = { real: majorOf(v.real as string), bonus: majorOf(v.restricted as string) };
      const free = /the game "(.+)"/.exec(v["free rounds"] as string)?.[1];
      w.doubles.brand.freeRounds = free ? [{ id: "fr-1", gameId: free, spinsRemaining: 10 }] : [];
      await w.site.funds.read();
    });
    When('they pick the game "pools"', () => pick("pools"));
    Then("the game is <launched>", () => {
      expect(w.doubles.askedOf("launch")).toHaveLength(v.launched === "launched" ? 1 : 0);
      expect(kind("pools")).toBe(v.launched === "launched" ? "playing" : "needs_deposit");
    });
  });

  Scenario("balances not yet read count as playable funds", ({ Given, When, Then }) => {
    Given("a player signed in at the player site whose balance has not been read", async () => {
      w.doubles.script("balance", { status: 200, silent: true });
      w.doubles.brand.balance = { real: "0.00", bonus: "0.00" };
      await signIn();
      expect(w.site.funds.current().real).toBeNull();
    });
    When('they pick the game "pools"', () => pick("pools"));
    Then("the game is launched", () => {
      expect(w.doubles.askedOf("launch")).toHaveLength(1);
      expect(kind("pools")).toBe("playing");
    });
  });

  Scenario("a launch the brand answers stands at playing with the game's address and the game session", ({ Given, When, Then, And }) => {
    Given("a player signed in at the player site with playable funds", signIn);
    When('they pick the game "pools"', () => pick("pools"));
    Then('the brand is asked once to launch the game "pools" in the player\'s currency', () => {
      expect(w.doubles.askedOf("launch").map((a) => a.body)).toEqual([{ playerId: "p-ana", gameId: "pools", currency: "EUR", demo: false }]);
    });
    And("the play behaviour stands at playing with the game's address and the game session", () => {
      const s = w.doubles.brand.gameSessions.values().next().value!;
      expect(play("pools").step()).toMatchObject({ kind: "playing", address: expect.stringContaining("pools"), gameSession: s.sessionId });
    });
  });

  ScenarioOutline("a launch the brand refuses stands at refused naming the reason and the page is given no game address", ({ Given, And, When, Then }, v) => {
    const r = () => launchRefusals[v.code as string]!;
    Given("a player signed in at the player site with playable funds", signIn);
    And('the brand refuses the launch as "<code>"', () => w.doubles.script("launch", r().reply));
    When('they pick the game "pools"', async () => {
      games().actions.pick("pools");
      await w.clock.advance(15_000);
    });
    Then('the play behaviour stands at refused with the code "<code>" and <fields>', () => {
      const s = play("pools").step() as Extract<PlayStep, { kind: "refused" }>;
      expect(s.kind).toBe("refused");
      expect(s.refusal.code).toBe(v.code);
      expect(s.refusal.fields ?? {}).toEqual(r().fields);
      if (r().service) expect(s.refusal.service).toBe(r().service);
    });
    And("the page is given no game address", () => {
      expect(JSON.stringify(games().step().open.map((g) => g.step()))).not.toContain("games.example");
    });
  });

  Scenario("a launch in fun mode needs a sign-in and no funds", ({ Given, When, Then, And }) => {
    Given('a player signed in at the player site with no playable funds for the game "pools"', async () => {
      w.doubles.brand.balance = { real: "0.00", bonus: "0.00" };
      await signIn();
    });
    When('they launch the game "pools" in fun mode', () => pick("pools", { funMode: true }));
    Then('the brand is asked once to launch the game "pools" in fun mode', () => {
      expect(w.doubles.askedOf("launch").map((a) => a.body)).toEqual([expect.objectContaining({ gameId: "pools", demo: true })]);
    });
    And("the play behaviour stands at playing", () => expect(kind("pools")).toBe("playing"));
  });

  Scenario("a visitor who launches a game in fun mode is asked to sign in", ({ Given, When, Then }) => {
    Given("a visitor of the player site", () => expect(w.site.player).toBeNull());
    When('they launch the game "pools" in fun mode', () => pick("pools", { funMode: true }));
    Then("they are asked to sign in", () => expect(kind("pools")).toBe("needs_sign_in"));
  });

  Scenario("a game not offered in the player's country is not shown and its game session is ended", ({ Given, And, When, Then }) => {
    Given('a player signed in at the player site with playable funds whose country is "GF"', async () => {
      await signIn();
      expect(w.site.player?.country).toBe("GF");
    });
    And('the catalogue says the game "pools" is prohibited in "GF"', () => {
      w.doubles.brand.games.find((g) => g.gameId === "pools")!.prohibitedTerritories = ["GF"];
    });
    When('they pick the game "pools"', () => pick("pools"));
    Then('the play behaviour stands at refused with the code "blocked_in_territory"', () => {
      expect(play("pools").step()).toMatchObject({ kind: "refused", refusal: { code: "blocked_in_territory" } });
    });
    And("the page is given no game address", () => expect(JSON.stringify(play("pools").step())).not.toContain("games.example"));
    And("any game session opened for the launch is ended", () => {
      expect(ends()).toEqual([...w.doubles.brand.gameSessions.keys()]);
    });
  });

  Scenario("a player of unknown country is not blocked by territory", ({ Given, When, Then }) => {
    Given("a player signed in at the player site with playable funds whose country is unknown", async () => {
      (w.doubles.brand.players[0] as { country: string }).country = "";
      w.doubles.brand.games.find((g) => g.gameId === "pools")!.prohibitedTerritories = ["GF"];
      await signIn();
    });
    When('they pick the game "pools"', () => pick("pools"));
    Then("the play behaviour stands at playing", () => expect(kind("pools")).toBe("playing"));
  });

  ScenarioOutline("leaving a game ends its game session", ({ Given, When, Then }, v) => {
    let sessionId = "";
    Given('a player playing the game "pools" at the player site', async () => {
      await playing();
      sessionId = sessionOf("pools");
    });
    When("they <leave>", async () => {
      if (v.leave === "close the game") play("pools").actions.close();
      else if (v.leave === "leave the page") w.site.stop();
      else w.site.session.actions.signOut();
      await w.clock.settle();
    });
    Then("the game session is ended as the player's leaving", () => {
      const asked = w.doubles.askedOf("endGameSession");
      expect(asked.map((a) => a.path.sessionId)).toEqual([sessionId]);
      expect(asked[0]!.body).toBeUndefined();
    });
  });

  Scenario("a reality check falls due after its interval of the game being shown", ({ Given, When, Then }) => {
    Given('a player playing the game "pools" at the player site with a reality check of "30" minutes', async () => {
      interval(30);
      await playing();
    });
    When('the game has been shown for "30" minutes', () => w.clock.advance(30 * MIN));
    Then("the play behaviour stands at the reality check with the time played", () => {
      expect(play("pools").step()).toMatchObject({ kind: "reality_check", played: 30 * 60 });
    });
  });

  const atCheck = async () => {
    interval(30);
    await playing();
    await w.clock.advance(30 * MIN);
    expect(kind("pools")).toBe("reality_check");
  };

  Scenario("keeping on at a reality check records the acknowledgement and schedules the next", ({ Given, When, Then, And }) => {
    let sessionId = "";
    Given("the play behaviour stands at the reality check", async () => {
      await atCheck();
      sessionId = sessionOf("pools");
    });
    When("the player keeps on", async () => {
      play("pools").actions.keepOn();
      await w.clock.settle();
    });
    Then("the acknowledgement is recorded with the game session", () => {
      expect(w.doubles.askedOf("acknowledgeRealityCheck").map((a) => a.body)).toEqual([{ sessionId }]);
    });
    And("the play behaviour stands at playing", () => expect(kind("pools")).toBe("playing"));
    And("the next reality check falls due after its interval", async () => {
      await w.clock.advance(30 * MIN - 1000);
      expect(kind("pools")).toBe("playing");
      await w.clock.advance(1000);
      expect(kind("pools")).toBe("reality_check");
    });
  });

  Scenario("stopping at a reality check ends the game session", ({ Given, When, Then }) => {
    let sessionId = "";
    Given("the play behaviour stands at the reality check", async () => {
      await atCheck();
      sessionId = sessionOf("pools");
    });
    When("the player stops", async () => {
      play("pools").actions.stop();
      await w.clock.settle();
    });
    Then("the game session is ended as the player's leaving", () => expect(ends()).toEqual([sessionId]));
  });

  ScenarioOutline("no reality check fires when none is set or it could not be read", ({ Given, And, When, Then }, v) => {
    Given('a player playing the game "pools" at the player site', () => {});
    And("their reality check is <state>", async () => {
      if (v.state === "not set") interval(null);
      else {
        interval(30);
        w.doubles.script("protection", { status: 503, body: { error: "service_unavailable" } });
      }
      await playing();
    });
    When('the game has been shown for "60" minutes', () => w.clock.advance(60 * MIN));
    Then("the play behaviour stands at playing", () => expect(kind("pools")).toBe("playing"));
  });

  Scenario("time the game is hidden does not count toward a reality check", ({ Given, And, When, Then }) => {
    Given('a player playing the game "pools" at the player site with a reality check of "30" minutes', async () => {
      interval(30);
      await playing();
    });
    And('the game was shown for "20" minutes and then hidden for "40" minutes', async () => {
      await w.clock.advance(20 * MIN);
      games().actions.hideAll();
      await w.clock.advance(40 * MIN);
    });
    When('the game is shown again for "10" minutes', async () => {
      games().actions.show("pools");
      await w.clock.advance(10 * MIN);
    });
    Then('the play behaviour stands at the reality check with "30" minutes played', () => {
      expect(play("pools").step()).toMatchObject({ kind: "reality_check", played: 30 * 60 });
    });
  });

  Scenario("hiding and showing a game does not start a reality check's interval again", ({ Given, And, When, Then }) => {
    Given('a player playing the game "pools" at the player site with a reality check of "30" minutes', async () => {
      interval(30);
      await playing();
    });
    And('the game was shown for "29" minutes', () => w.clock.advance(29 * MIN));
    When('the game is hidden and shown again for "1" minute', async () => {
      games().actions.hideAll();
      games().actions.show("pools");
      await w.clock.advance(1 * MIN);
    });
    Then("the play behaviour stands at the reality check", () => expect(kind("pools")).toBe("reality_check"));
  });

  Scenario("a second game picked while one is open is launched under its own game session and the first is hidden", ({ Given, And, When, Then }) => {
    let first = "";
    Given('a player playing the game "pools" at the player site', async () => {
      await playing();
      first = sessionOf("pools");
    });
    And('a game "wheel" in the catalogue of brand "guyanabet"', () => expect(w.doubles.brand.games.map((g) => g.gameId)).toContain("wheel"));
    When('they pick the game "wheel"', () => pick("wheel"));
    Then('the game "wheel" is launched under a game session of its own', () => {
      expect(w.doubles.askedOf("launch").map((a) => (a.body as { gameId: string }).gameId)).toEqual(["pools", "wheel"]);
      expect(kind("wheel")).toBe("playing");
      expect(sessionOf("wheel")).not.toBe(first);
    });
    And('the game "wheel" is shown and the game "pools" is hidden with its game session still open', () => {
      expect(games().step().shown?.game).toBe("wheel");
      expect(play("pools").step()).toMatchObject({ kind: "playing", hidden: true, gameSession: first });
      expect(ends()).toEqual([]);
    });
  });

  const twoOpen = async () => {
    await playing("pools");
    await pick("wheel");
    expect(games().step().shown?.game).toBe("wheel");
    launchesBefore = w.doubles.askedOf("launch").length;
  };

  Scenario("picking a game that is already open shows it and launches nothing", ({ Given, When, Then, And }) => {
    Given('a player with the games "pools" and "wheel" open and "wheel" shown', twoOpen);
    When('they pick the game "pools"', () => pick("pools"));
    Then('the game "pools" is shown and the game "wheel" is hidden', () => {
      expect(games().step().shown?.game).toBe("pools");
      expect(play("wheel").step()).toMatchObject({ hidden: true });
    });
    And("no launch is asked of the brand", () => expect(w.doubles.askedOf("launch")).toHaveLength(launchesBefore));
  });

  Scenario("closing the shown game ends its game session and shows the next open game", ({ Given, When, Then, And }) => {
    let wheel = "";
    Given('a player with the games "pools" and "wheel" open and "wheel" shown', async () => {
      await twoOpen();
      wheel = sessionOf("wheel");
    });
    When('they close the game "wheel"', async () => {
      play("wheel").actions.close();
      await w.clock.settle();
    });
    Then('the game session of the game "wheel" is ended as the player\'s leaving', () => expect(ends()).toEqual([wheel]));
    And('the game "pools" is shown', () => expect(games().step().shown?.game).toBe("pools"));
  });

  Scenario("a reality check counts the time shown across the open games", ({ Given, And, When, Then }) => {
    Given('a player with a reality check of "30" minutes and the games "pools" and "wheel" open', async () => {
      interval(30);
      await twoOpen();
    });
    And('the game "pools" was shown for "20" minutes', async () => {
      games().actions.show("pools");
      await w.clock.advance(20 * MIN);
    });
    When('the game "wheel" is shown for "10" minutes', async () => {
      games().actions.show("wheel");
      await w.clock.advance(10 * MIN);
    });
    Then('the play behaviour of the game "wheel" stands at the reality check', () => expect(kind("wheel")).toBe("reality_check"));
    And('the play behaviour of the game "pools" stands at playing', () => expect(kind("pools")).toBe("playing"));
  });

  Scenario("a game whose game session the brand ended stands at ended at its next bet check", ({ Given, And, When, Then }) => {
    let g: PlayBehaviour;
    Given('a player playing the game "pools" at the player site', async () => {
      await playing();
      g = play("pools");
    });
    And('the brand ended the game session of the game "pools"', () => {
      w.doubles.brand.gameSessions.get(sessionOf("pools"))!.status = "completed";
    });
    When("the play behaviour next reads the game session", () => w.clock.advance(5 * MIN));
    Then("the play behaviour stands at ended", () => {
      expect(g.step().kind).toBe("ended");
      expect(games().step().open).toEqual([]);
    });
    And("no end of the game session is asked of the brand", () => expect(ends()).toEqual([]));
  });

  Scenario("signing out ends the game session of every open game", ({ Given, When, Then }) => {
    let both: string[] = [];
    Given('a player with the games "pools" and "wheel" open', async () => {
      await twoOpen();
      both = [sessionOf("pools"), sessionOf("wheel")];
    });
    When("they sign out", async () => {
      w.site.session.actions.signOut();
      await w.clock.settle();
    });
    Then("the game sessions of both games are ended as the player's leaving", () => {
      expect([...ends()].sort()).toEqual([...both].sort());
      expect(games().step().open).toEqual([]);
    });
  });
});

// session.feature's recent-bet scenario, bound here beside the play behaviour whose bet check it is.
await bind(
  "session.feature",
  ({ Background, Scenario }) => {
    let refreshes = 0;
    Background(({ Given, And }) => {
      Given('a player site of brand "guyanabet"', async () => {
        w = await world();
        const session = w.transport.session;
        const refresh = session.refresh.bind(session);
        refreshes = 0;
        (session as { refresh: typeof session.refresh }).refresh = () => {
          refreshes++;
          return refresh();
        };
      });
      And('a player of brand "guyanabet"', () => expect(w.doubles.brand.players[0]!.playerId).toBe("p-ana"));
    });
    Scenario("a player who placed a bet in the last six minutes is not idle", ({ Given, And, When, Then }) => {
      Given("the player is playing a game", async () => {
        await playing();
        await w.clock.advance(21 * MIN); // the checks at 5, 10, 15 and 20 minutes found no bet
        expect(refreshes).toBe(0);
      });
      And("their game session's last bet was four minutes ago", () => {
        w.doubles.brand.gameSessions.get(sessionOf("pools"))!.lastBetAt = new Date(w.clock.now()).toISOString();
      });
      When("the five-minute check of the game session runs", () => w.clock.advance(4 * MIN)); // at 25:00, four minutes after the bet
      Then("the sign-in is refreshed", () => expect(refreshes).toBe(1));
      And("the player is not counted idle", async () => {
        // with no bet the idle warning falls at 29:30 and the sign-out at 30:00; the bet started the count again at 25:00
        await w.clock.advance(28 * MIN);
        expect(w.site.session.step().kind).toBe("signed_in");
      });
    });
  },
  "a player who placed a bet in the last six minutes is not idle",
);
