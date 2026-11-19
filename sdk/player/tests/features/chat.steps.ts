import { afterAll, expect } from "vitest";
import { playerSite, type PlayerSite } from "../../src/site.ts";
import type { ChatBehaviour, ChatStep, Turn } from "../../src/support/chat.ts";
import { channel, type Channel } from "../../src/testing/doubles.ts";
import { bind, world, type World } from "./setup.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

class MemoryStorage {
  readonly items = new Map<string, string>();
  getItem = (k: string) => this.items.get(k) ?? null;
  setItem = (k: string, v: string) => void this.items.set(k, v);
  removeItem = (k: string) => void this.items.delete(k);
  clear = () => this.items.clear();
  get length() {
    return this.items.size;
  }
}
const g = globalThis as Record<string, unknown>;
// each step is a test of its own, so the tab's storage is made once per scenario, in the background
afterAll(() => {
  delete g.sessionStorage;
});

let w: World;
let site: PlayerSite;
let reply: Channel;
let seen: ChatStep[] = [];
let answer: unknown;
let asked = 0;
let firstSession = "";
const chat = (): ChatBehaviour => site.chat!;
const step = () => chat().step();
/** The turns of a step that holds them. */
const turns = (): readonly Turn[] => ("turns" in step() ? (step() as { turns: readonly Turn[] }).turns : []);
const sessions = () => w.doubles.askedOf("turn").map((a) => (a.body as { session_id: string }).session_id);
const send = async (text = "Bonjour") => {
  answer = chat().actions.send(text);
  await w.clock.settle();
};
const stream = (...events: [string, unknown][]) => ({ status: 200, stream: events.map(([event, data]) => ({ event, data: JSON.stringify(data) })) });
const confirming = stream(["delta", { token: "Je peux fixer votre limite." }], ["confirm_action", { action_name: "set_deposit_limit", summary: "Votre limite de dépôt passera à 50 € par jour.", session_id: "x" }], ["done", {}]);
const atApproval = async () => {
  w.doubles.script("turn", confirming, 1);
  await send("Fixez ma limite à 50 €");
  expect(step().kind).toBe("awaiting_approval");
};
const loadAgain = async () => {
  site.stop();
  site = playerSite({ transport: w.transport, clock: w.clock, idleMinutes: 24 * 60 });
  await site.ready;
  expect(site.player?.playerId).toBe("p-ana");
};
const refusals: Record<string, object> = {
  agent_unavailable: { status: 503, body: { error: "agent_unavailable", message: "Le chat est fermé pour le moment." } },
  too_many_turns: { status: 429, body: { error: "too_many_turns", retryAfter: 20 } },
  conversation_ended: { status: 409, body: { error: "conversation_ended", message: "Merci, à bientôt." } },
  session_not_yours: { status: 403, body: { error: "session_not_yours" } },
};
const expected: Record<string, Record<string, unknown>> = {
  agent_unavailable: { kind: "unavailable", words: "Le chat est fermé pour le moment." },
  too_many_turns: { kind: "refused", refusal: { code: "too_many_turns", fields: { retryAfterSeconds: 20 } } },
  conversation_ended: { kind: "ended", words: "Merci, à bientôt." },
  session_not_yours: { kind: "refused", refusal: { code: "session_not_yours", fields: {} } },
};

await bind("chat.feature", ({ Background, Scenario, ScenarioOutline }) => {
  Background(({ Given, And }) => {
    Given('a player site of brand "guyanabet" whose brand runs the module "support"', async () => {
      g.sessionStorage = new MemoryStorage();
      w = await world({}, { idleMinutes: 24 * 60 });
      site = w.site;
      expect(site.facts?.modules).toContain("support");
    });
    And("a player signed in at the player site", async () => {
      site.session.actions.signIn(ana);
      await w.clock.settle();
    });
  });

  Scenario("a turn the player sends is answered part by part and then the chat is ready again", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at ready", () => {
      expect(step().kind).toBe("ready");
      seen = [];
      chat().subscribe((s) => seen.push(s));
    });
    When("the player sends a turn", async () => {
      reply = channel();
      w.doubles.script("turn", { status: 200, stream: reply }, 1);
      await send();
    });
    Then("the chat behaviour stands at answering", () => expect(step().kind).toBe("answering"));
    And("the page is given each part of the reply as it arrives", async () => {
      for (const part of ["Bon", "jour, ", "Ana"]) {
        reply.push("delta", { token: part });
        await w.clock.settle();
        expect(turns().at(-1)!.text.endsWith(part)).toBe(true);
      }
    });
    And("the chat behaviour stands at ready once the turn is answered", async () => {
      reply.push("done", {});
      await w.clock.settle();
      expect(step()).toMatchObject({ kind: "ready", turns: [{ from: "player", text: "Bonjour" }, { from: "agent", text: "Bonjour, Ana" }] });
    });
  });

  Scenario("a conversation's first turn is sent under a new session id and its later turns under the same one", ({ Given, When, Then }) => {
    Given("the chat behaviour stands at ready with no conversation", () => expect(step()).toEqual({ kind: "ready", allowed: ["send"], turns: [] }));
    When("the player sends a turn and then another", async () => {
      await send();
      await send("Et ensuite ?");
    });
    Then("both turns are sent under one session id the chat behaviour made", () => {
      const [a, b] = sessions();
      expect(a).toMatch(/^[0-9a-f-]{36}$/);
      expect(b).toBe(a);
    });
  });

  Scenario("a page loaded again continues the same conversation", ({ Given, When, Then }) => {
    Given("the player has a conversation at the player site", async () => {
      await send();
      firstSession = sessions()[0]!;
    });
    When("the page is loaded again and the player sends a turn", async () => {
      await loadAgain();
      await send("Toujours là ?");
    });
    Then("the turn is sent under the conversation's session id", () => expect(sessions().at(-1)).toBe(firstSession));
  });

  Scenario("a turn that ends with an approval request stands at awaiting the player's decision", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
    When("the player sends a turn the support agent answers with an approval request", atApproval);
    Then("the chat behaviour stands at awaiting approval with the action and its effect in the support agent's words", () => {
      expect(step()).toMatchObject({ kind: "awaiting_approval", request: { action: "set_deposit_limit", effect: "Votre limite de dépôt passera à 50 € par jour." } });
    });
    And("the only actions allowed are approving and cancelling", () => expect(step().allowed).toEqual(["approve", "cancel"]));
  });

  Scenario("approving an approval request sends the decision once and gives the page the action's result and the reply", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at awaiting approval", atApproval);
    When("the player approves", async () => {
      w.doubles.script("decide", stream(["action_result", { action_name: "set_deposit_limit", success: true, detail: "50 € par jour" }], ["delta", { token: "C'est " }], ["delta", { token: "fait." }], ["done", {}]), 1);
      chat().actions.approve();
      chat().actions.approve();
      await w.clock.settle();
    });
    Then("the decision is sent once for the conversation", () => {
      expect(w.doubles.askedOf("decide").map((a) => [a.path.session, a.body])).toEqual([[sessions()[0], { action: "approve" }]]);
    });
    And("the page is given the action's result and each part of the reply", () => {
      expect(turns().at(-1)).toEqual({ from: "agent", text: "C'est fait.", result: { action: "set_deposit_limit", success: true, detail: "50 € par jour" } });
    });
    And("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
  });

  Scenario("cancelling an approval request sends the decision and no action's result is given", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at awaiting approval", atApproval);
    When("the player cancels", async () => {
      w.doubles.script("decide", stream(["delta", { token: "D'accord, je ne change rien." }], ["done", {}]), 1);
      chat().actions.cancel();
      await w.clock.settle();
    });
    Then("the decision is sent once for the conversation", () => {
      expect(w.doubles.askedOf("decide").map((a) => [a.path.session, a.body])).toEqual([[sessions()[0], { action: "reject" }]]);
    });
    And("the page is given each part of the reply and no action's result", () => {
      expect(turns().at(-1)).toEqual({ from: "agent", text: "D'accord, je ne change rien." });
      expect(turns().some((t) => t.result)).toBe(false);
    });
  });

  Scenario("a turn sent while an approval request awaits sends nothing", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at awaiting approval", atApproval);
    When("the page asks the chat behaviour to send a turn", async () => {
      asked = w.doubles.asked.length;
      await send("Autre chose");
    });
    Then("nothing is sent to the brand", () => expect(w.doubles.asked.length).toBe(asked));
    And("the page is told the action is not allowed at awaiting approval", () => expect(answer).toEqual({ ok: false, notAllowedAt: "awaiting_approval" }));
  });

  Scenario("a page loaded while an approval request awaits learns of it at the next turn and stands at awaiting approval", ({ Given, And, When, Then }) => {
    Given("the player's conversation has an approval request awaiting", atApproval);
    And("the page was loaded again", async () => {
      await loadAgain();
      expect(step().kind).toBe("ready");
    });
    When("the player sends a turn and the brand refuses it naming the approval request", async () => {
      w.doubles.script("turn", { status: 409, body: { error: "Session already active", details: { approvalId: "a-1", action_name: "set_deposit_limit" } } }, 1);
      await send("Alors ?");
    });
    Then("the chat behaviour stands at awaiting approval with the action the refusal names", () => {
      expect(step()).toMatchObject({ kind: "awaiting_approval", request: { action: "set_deposit_limit" } });
    });
  });

  ScenarioOutline("a turn the brand refuses stands at a step naming the reason", ({ Given, And, When, Then }, v) => {
    Given("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
    And('the brand refuses the turn as "<code>"', () => w.doubles.script("turn", refusals[v.code as string] as never, 1));
    When("the player sends a turn", () => send());
    Then("the chat behaviour stands at <step> with <fields>", () => {
      expect(step().kind).toBe(v.step);
      expect(step()).toMatchObject(expected[v.code as string]!);
    });
  });

  Scenario("a refused turn allows only dismissing, which returns the chat to ready", ({ Given, When, Then }) => {
    Given("the chat behaviour stands at refused", async () => {
      w.doubles.script("turn", refusals.too_many_turns as never, 1);
      await send();
      expect(step()).toMatchObject({ kind: "refused", allowed: ["dismiss"] });
    });
    When("the page asks the chat behaviour to send a turn", async () => {
      asked = w.doubles.asked.length;
      await send("Encore");
    });
    Then("nothing is sent to the brand", () => {
      expect(w.doubles.asked.length).toBe(asked);
      expect(answer).toEqual({ ok: false, notAllowedAt: "refused" });
    });
    When("the player dismisses the refusal", () => void chat().actions.dismiss());
    Then("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
  });

  Scenario("a turn after a conversation ended starts a new conversation", ({ Given, When, Then }) => {
    Given("the chat behaviour stands at ended", async () => {
      w.doubles.script("turn", refusals.conversation_ended as never, 1);
      await send();
      expect(step().kind).toBe("ended");
    });
    When("the player sends a turn", () => send("Une autre question"));
    Then("the turn is sent under a new session id", () => {
      const [a, b] = sessions();
      expect(b).not.toBe(a);
    });
  });

  Scenario("a turn refused by a guardrail gives the page the support agent's refusal words and the chat is ready again", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
    When("the player sends a turn a guardrail refuses", async () => {
      w.doubles.script("turn", stream(["error", { message: "Je ne peux pas vous aider avec cela." }]), 1);
      await send("Donnez-moi un tuyau");
    });
    Then("the page is given the support agent's refusal words as the reply", () => {
      expect(turns().at(-1)).toEqual({ from: "agent", text: "Je ne peux pas vous aider avec cela." });
    });
    And("the chat behaviour stands at ready", () => expect(step().kind).toBe("ready"));
  });

  Scenario("a reply that stops arriving before the turn is answered ends the turn as interrupted", ({ Given, When, Then, And }) => {
    Given("the chat behaviour stands at answering", async () => {
      reply = channel();
      w.doubles.script("turn", { status: 200, stream: reply }, 1);
      await send();
      reply.push("delta", { token: "Un inst" });
      await w.clock.settle();
      expect(step().kind).toBe("answering");
    });
    When("the reply stops arriving before the turn is answered", async () => {
      reply.end();
      await w.clock.settle();
    });
    Then("the chat behaviour stands at ready with the turn marked interrupted", () => {
      expect(step()).toMatchObject({ kind: "ready", turns: [{ from: "player" }, { from: "agent", text: "Un inst", interrupted: true }] });
    });
    And("the turn is not sent again by itself", async () => {
      await w.clock.advance(10 * 60_000);
      expect(w.doubles.askedOf("turn")).toHaveLength(1);
    });
  });

  Scenario("a turn sent at a brand whose support agent is off stands at unavailable", ({ Given, When, Then, And }) => {
    Given('the support agent of brand "guyanabet" is off', () => w.doubles.script("turn", refusals.agent_unavailable as never));
    When("the player sends a turn", () => send());
    Then("the chat behaviour stands at unavailable with the support agent's unavailable words", () => {
      expect(step()).toEqual({ kind: "unavailable", allowed: [], words: "Le chat est fermé pour le moment." });
    });
    And("no action is allowed", async () => {
      asked = w.doubles.asked.length;
      for (const act of [() => chat().actions.send("?"), () => chat().actions.approve(), () => chat().actions.cancel(), () => chat().actions.dismiss()]) expect(act()).toEqual({ ok: false, notAllowedAt: "unavailable" });
      await w.clock.settle();
      expect(w.doubles.asked.length).toBe(asked);
    });
  });

  Scenario("a site of a brand that does not run the support module offers no chat behaviour", ({ Given, When, Then }) => {
    let solo: World;
    Given('a brand "solo" running the module "core" and not the module "support"', () => {});
    When('a player site of brand "solo" is built', async () => {
      solo = await world({ brand: "solo", modules: ["core"] });
    });
    Then("the chat behaviour is not offered", () => expect(solo.site.chat).toBeUndefined());
  });
});
