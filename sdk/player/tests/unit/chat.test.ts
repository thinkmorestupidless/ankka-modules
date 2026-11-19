import { describe, expect, it } from "vitest";
import { playerSite } from "../../src/site.ts";
import type { ChatStep, ChatStore } from "../../src/support/chat.ts";
import { handClock } from "../../src/testing/clock.ts";
import { channel, doubles, doubleTransport, type Doubles } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };
const memory = (): ChatStore & { items: Map<string, string> } => {
  const items = new Map<string, string>();
  return { items, get: (k) => items.get(k) ?? null, set: (k, v) => void items.set(k, v), remove: (k) => void items.delete(k) };
};
const stream = (...events: [string, unknown][]) => ({ status: 200, stream: events.map(([event, data]) => ({ event, data: JSON.stringify(data) })) });
const confirm = stream(["delta", { token: "Je peux fixer votre limite à 50 €." }], ["confirm_action", { action_name: "set_deposit_limit", summary: "Votre limite de dépôt passera à 50 € par jour.", session_id: "x" }], ["done", {}]);

async function setup(o: { d?: Doubles; store?: ReturnType<typeof memory>; signIn?: boolean } = {}) {
  const clock = handClock();
  const d = o.d ?? doubles({ clock });
  const store = o.store ?? memory();
  const site = playerSite({ transport: doubleTransport(d), clock, idleMinutes: 24 * 60, chatStore: store });
  await site.ready;
  if (o.signIn !== false && !site.player) {
    site.session.actions.signIn(ana);
    await clock.settle();
  }
  const chat = site.chat!;
  const send = async (text = "Bonjour") => {
    const a = chat.actions.send(text);
    await clock.settle();
    return a;
  };
  return { clock, d, site, store, chat, send };
}
const sessions = (d: Doubles) => d.askedOf("turn").map((a) => (a.body as { session_id: string }).session_id);

describe("the chat behaviour", () => {
  it("a turn is answered part by part, then ready; the parts are told as they arrive", async () => {
    const { d, chat, clock } = await setup();
    const c = channel();
    d.script("turn", { status: 200, stream: c }, 1);
    const seen: ChatStep[] = [];
    chat.subscribe((s) => seen.push(s));
    chat.actions.send("  Bonjour ?  ");
    await clock.settle();
    expect(chat.step()).toMatchObject({ kind: "answering", turns: [{ from: "player", text: "Bonjour ?" }, { from: "agent", text: "" }] });
    c.push("delta", { token: "Bon" });
    await clock.settle();
    expect(chat.step()).toMatchObject({ turns: [{}, { text: "Bon" }] });
    c.push("delta", { token: "jour" });
    c.push("done", {});
    await clock.settle();
    expect(chat.step()).toMatchObject({ kind: "ready", turns: [{}, { from: "agent", text: "Bonjour" }] });
    expect(seen.filter((s) => s.kind === "answering").length).toBeGreaterThanOrEqual(3);
  });

  it("an empty turn is not sent, as the original", async () => {
    const { d, send } = await setup();
    await send("   ");
    expect(d.askedOf("turn")).toEqual([]);
  });

  it("mints a session id at the first turn, keeps it for the tab, and a page loaded again continues under it", async () => {
    const first = await setup();
    await first.send();
    await first.send("Et ensuite ?");
    const [a, b] = sessions(first.d);
    expect(a).toMatch(/^[0-9a-f-]{36}$/);
    expect(b).toBe(a);
    expect(first.store.items.get("eo_chat_p-ana")).toBe(a);
    first.site.stop();
    const again = await setup({ d: first.d, store: first.store });
    expect(again.chat.step()).toMatchObject({ kind: "ready", sessionId: a });
    await again.send("Toujours là ?");
    expect(sessions(first.d).at(-1)).toBe(a);
  });

  it("an approval request awaits the player's decision with the action and its effect as the agent wrote them", async () => {
    const { d, chat, send, clock } = await setup();
    d.script("turn", confirm, 1);
    await send("Fixez ma limite");
    expect(chat.step()).toMatchObject({ kind: "awaiting_approval", allowed: ["approve", "cancel"], request: { action: "set_deposit_limit", effect: "Votre limite de dépôt passera à 50 € par jour." } });
    expect(await send()).toEqual({ ok: false, notAllowedAt: "awaiting_approval" });
    d.script("decide", stream(["action_result", { action_name: "set_deposit_limit", success: true, detail: "50 €/jour" }], ["delta", { token: "C'est fait." }], ["done", {}]), 1);
    chat.actions.approve();
    expect(chat.step()).toMatchObject({ kind: "deciding", decision: "approve" });
    expect(chat.actions.approve()).toEqual({ ok: false, notAllowedAt: "deciding" });
    await clock.settle();
    expect(d.askedOf("decide")).toHaveLength(1);
    expect(d.askedOf("decide")[0]).toMatchObject({ path: { session: sessions(d)[0] }, body: { action: "approve" } });
    expect(chat.step()).toMatchObject({ kind: "ready", turns: expect.arrayContaining([{ from: "agent", text: "C'est fait.", result: { action: "set_deposit_limit", success: true, detail: "50 €/jour" } }]) });
  });

  it("cancel sends reject and gives no action's result; already decided is read as decided", async () => {
    const { d, chat, send, clock } = await setup();
    d.script("turn", confirm, 2);
    await send();
    d.script("decide", stream(["delta", { token: "D'accord." }], ["done", {}]), 1);
    chat.actions.cancel();
    await clock.settle();
    expect(d.askedOf("decide")[0]!.body).toEqual({ action: "reject" });
    expect(JSON.stringify(chat.step())).not.toContain("result");
    await send();
    d.script("decide", { status: 409, body: { error: "already_decided" } }, 1);
    chat.actions.approve();
    await clock.settle();
    expect(chat.step().kind).toBe("ready");
  });

  it("learns of an approval request from a refusal after a page load", async () => {
    const { d, chat, send } = await setup();
    d.script("turn", { status: 409, body: { error: "Session already active", details: { approvalId: "a-1", action_name: "set_deposit_limit" } } }, 1);
    await send();
    expect(chat.step()).toMatchObject({ kind: "awaiting_approval", request: { action: "set_deposit_limit" } });
    expect((chat.step() as { request: object }).request).not.toHaveProperty("effect");
  });

  it("each refusal before the reply stands at its step", async () => {
    const cases: [object, Partial<ChatStep> & Record<string, unknown>][] = [
      [{ status: 503, body: { error: "agent_unavailable", message: "Le chat est fermé." } }, { kind: "unavailable", allowed: [], words: "Le chat est fermé." }],
      [{ status: 429, body: { error: "too_many_turns", retryAfter: 20 } }, { kind: "refused", allowed: ["dismiss"], refusal: { status: 429, code: "too_many_turns", fields: { retryAfterSeconds: 20 } } }],
      [{ status: 409, body: { error: "conversation_ended", message: "Merci, à bientôt." } }, { kind: "ended", allowed: ["send"], words: "Merci, à bientôt." }],
      [{ status: 403, body: { error: "session_not_yours" } }, { kind: "refused", refusal: { status: 403, code: "session_not_yours", fields: {} } }],
    ];
    for (const [reply, step] of cases) {
      const { d, chat, send } = await setup();
      d.script("turn", reply as never, 1);
      await send();
      expect(chat.step(), JSON.stringify(reply)).toMatchObject(step);
    }
  });

  it("a session that is another player's is dropped: the next turn is under a new id", async () => {
    const { d, chat, send, store } = await setup();
    await send();
    d.script("turn", { status: 403, body: { error: "session_not_yours" } }, 1);
    await send();
    expect(store.items.has("eo_chat_p-ana")).toBe(false);
    chat.actions.dismiss();
    await send();
    const [a, , c] = sessions(d);
    expect(c).not.toBe(a);
  });

  it("a turn after the conversation ended is under a new session id", async () => {
    const { d, chat, send } = await setup();
    d.script("turn", { status: 409, body: { error: "conversation_ended", message: "Fin." } }, 1);
    await send();
    expect(chat.step().kind).toBe("ended");
    await send();
    const [a, b] = sessions(d);
    expect(b).not.toBe(a);
  });

  it("a guardrail's refusal is the reply, and the chat is ready again", async () => {
    const { d, chat, send } = await setup();
    d.script("turn", stream(["error", { message: "Je ne peux pas vous aider avec cela." }]), 1);
    await send();
    expect(chat.step()).toMatchObject({ kind: "ready", turns: [{ from: "player" }, { from: "agent", text: "Je ne peux pas vous aider avec cela." }] });
  });

  it("a reply that stops before the turn is answered marks the turn interrupted and sends nothing again", async () => {
    const { d, chat, send, clock } = await setup();
    d.script("turn", stream(["delta", { token: "Un inst" }]), 1);
    await send();
    expect(chat.step()).toMatchObject({ kind: "ready", turns: [{}, { from: "agent", text: "Un inst", interrupted: true }] });
    await clock.advance(10 * 60_000);
    expect(d.askedOf("turn")).toHaveLength(1);
  });

  it("nothing of the conversation outlives a sign-out", async () => {
    const { chat, send, site, store, clock } = await setup();
    await send();
    expect(store.items.size).toBe(1);
    site.session.actions.signOut();
    await clock.settle();
    expect(store.items.size).toBe(0);
    expect(chat.step()).toEqual({ kind: "ready", allowed: ["send"], turns: [] });
  });

  it("is not offered where the brand does not run support", async () => {
    const d = doubles({ brand: { modules: ["core"] } });
    const site = playerSite({ transport: doubleTransport(d) });
    await site.ready;
    expect(site.chat).toBeUndefined();
  });
});
