import { expect } from "vitest";
import { waitFor } from "../../src/engagement/inbox.ts";
import { bind, world, type World } from "./setup.ts";
import { stack, type Stack } from "./stack.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
let w: World;
let st: Stack | undefined;

async function until(done: () => boolean, ms = 2000) {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("timed out waiting");
    await new Promise((r) => setTimeout(r, 5));
  }
}
const opens = () => w.doubles.askedOf("push").length;
const reads = () => w.doubles.askedOf("unread").length;
let readsBefore = 0;

await bind("inbox.feature", ({ Background, Scenario }) => {
  Background(({ Given, And }) => {
    Given('a player site of brand "guyanabet"', async () => {
      await st?.close();
      st = undefined;
      w = await world({}, { idleMinutes: 24 * 60 });
    });
    And("a player signed in at the player site", async () => {
      w.site.session.actions.signIn(ana);
      await w.clock.settle();
      expect(w.site.player?.playerId).toBe("p-ana");
    });
  });

  Scenario("a signed-in page holds a push connection through the site server", ({ When, Then, And }) => {
    // the whole way: a page transport, the site server, the doubles over HTTP
    When("the page opens", async () => {
      st = await stack();
      st.site.session.actions.signIn(ana);
      await until(() => st!.site.inbox?.step().kind === "connected");
    });
    Then("the page holds a push connection under the player's sign-in through the site server", () => {
      const push = st!.doubles.askedOf("push");
      expect(push).toHaveLength(1);
      expect(push[0]!.headers.authorization).toMatch(/^Bearer /);
      expect(st!.answers.some((a) => a.path.startsWith("/api/c/engagement/push") && a.status === 200)).toBe(true);
      expect(st!.answers.find((a) => a.path.startsWith("/api/c/engagement/push"))!.headers).toContainEqual(["content-type", "text/event-stream"]);
    });
    And("the unread count is read", async () => {
      await until(() => st!.doubles.askedOf("unread").length === 1);
      expect(st!.site.inbox!.step()).toEqual({ kind: "connected", allowed: ["refresh"], unread: 0 });
    });
  });

  Scenario("a push makes the page read the unread count again", ({ Given, When, Then }) => {
    Given("the page holds a push connection", () => {
      expect(w.site.inbox!.step().kind).toBe("connected");
      readsBefore = reads();
    });
    When("a push arrives", async () => {
      w.doubles.brand.unread = 2;
      w.doubles.brand.pushes[0]!.push("signal", { kind: "inbox.changed" });
      await w.clock.settle();
    });
    Then("the unread count is read again", () => {
      expect(reads()).toBe(readsBefore + 1);
      expect(w.site.inbox!.step()).toMatchObject({ unread: 2 });
    });
  });

  Scenario("a push connection the brand closes is reopened after a wait that doubles to thirty seconds", ({ Given, When, Then }) => {
    const waits: number[] = [];
    Given("the page holds a push connection", () => expect(w.site.inbox!.step().kind).toBe("connected"));
    When("the brand closes the push connection again and again", async () => {
      // each reopened connection is closed before it says ready
      w.doubles.script("push", { status: 200, stream: [] }, 8);
      w.doubles.brand.pushes[0]!.end();
      await w.clock.settle();
      for (let i = 0; i < 7; i++) {
        const before = opens();
        let waited = 0;
        while (opens() === before) {
          await w.clock.advance(1000);
          waited += 1000;
          if (waited > 60_000) throw new Error("not reopened");
        }
        waits.push(waited / 1000);
      }
    });
    Then('it is reopened after "1" second, then "2", then "4", doubling to "30"', () => {
      expect(waits).toEqual([1, 2, 4, 8, 16, 30, 30]);
      expect(waitFor(20)).toBe(30_000);
    });
  });

  Scenario("a push connection that opens resets the wait", ({ Given, When, Then }) => {
    Given('a push connection reopened after a wait of "8" seconds', async () => {
      w.doubles.script("push", { status: 503, body: { error: "service_unavailable" } }, 3);
      w.doubles.brand.pushes[0]!.end();
      await w.clock.settle();
      await w.clock.advance(1000 + 2000 + 4000);
      const before = opens();
      await w.clock.advance(8000);
      expect(opens()).toBe(before + 1);
      expect(w.site.inbox!.step().kind).toBe("connected");
    });
    When("the brand closes it again", async () => {
      w.doubles.brand.pushes.at(-1)!.end();
      await w.clock.settle();
    });
    Then('it is reopened after "1" second', async () => {
      const before = opens();
      await w.clock.advance(999);
      expect(opens()).toBe(before);
      await w.clock.advance(1);
      expect(opens()).toBe(before + 1);
    });
  });

  Scenario("a page that becomes visible reads the unread count without waiting for a push", ({ Given, When, Then }) => {
    Given("the page was hidden", () => {
      w.site.visible(false);
      readsBefore = reads();
    });
    When("it becomes visible", async () => {
      w.site.visible(true);
      await w.clock.settle();
    });
    Then("the unread count is read again", () => expect(reads()).toBe(readsBefore + 1));
  });

  Scenario("signing out closes the push connection and it is not reopened", ({ Given, When, Then, And }) => {
    Given("the page holds a push connection", () => expect(w.site.inbox!.step().kind).toBe("connected"));
    When("the player signs out", async () => {
      w.site.session.actions.signOut();
      await w.clock.settle();
    });
    Then("the push connection is closed", () => {
      expect(w.doubles.brand.pushes[0]!.ended).toBe(true);
      expect(w.site.inbox!.step().kind).toBe("closed");
    });
    And("it is not reopened", async () => {
      await w.clock.advance(5 * 60_000);
      expect(opens()).toBe(1);
    });
  });
});
