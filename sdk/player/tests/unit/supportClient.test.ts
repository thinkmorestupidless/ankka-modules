import { describe, expect, it } from "vitest";
import { chatEvent, supportClient, type ChatEvent } from "../../src/support/client.ts";
import { channel, doubles, doubleTransport } from "../../src/testing/doubles.ts";

async function setup() {
  const d = doubles();
  const t = doubleTransport(d);
  await t.session.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
  return { d, support: supportClient(t, () => "guyanabet") };
}
const all = async (events: AsyncIterable<ChatEvent>) => {
  const seen: ChatEvent[] = [];
  for await (const e of events) seen.push(e);
  return seen;
};

describe("support's client", () => {
  it("a turn posts the message under the session id and reads the parts of the reply", async () => {
    const { d, support } = await setup();
    d.script("turn", { status: 200, stream: [{ event: "delta", data: '{"token":"Bon"}' }, { event: "delta", data: '{"token":"jour"}' }, { event: "heartbeat", data: "{}" }, { event: "done", data: "{}" }] });
    const r = await support.turn("s-1", "Bonjour ?");
    if (!r.ok) throw new Error("refused");
    expect(await all(r.value.events)).toEqual([{ kind: "delta", text: "Bon" }, { kind: "delta", text: "jour" }, { kind: "done" }]);
    expect(d.askedOf("turn")[0]).toMatchObject({ path: { brand: "guyanabet" }, body: { message: "Bonjour ?", session_id: "s-1" } });
  });

  it("an approval request carries the action and the agent's statement of its effect, unchanged", () => {
    expect(chatEvent({ event: "confirm_action", data: JSON.stringify({ action_name: "set_deposit_limit", summary: "Votre limite passera à 50 € par jour.", session_id: "s-1" }) })).toEqual({ kind: "confirm", action: "set_deposit_limit", effect: "Votre limite passera à 50 € par jour." });
    expect(chatEvent({ event: "action_result", data: JSON.stringify({ action_name: "set_deposit_limit", success: false, detail: "over_limit" }) })).toEqual({ kind: "result", action: "set_deposit_limit", success: false, detail: "over_limit" });
    expect(chatEvent({ event: "error", data: JSON.stringify({ message: "Je ne peux pas répondre à cela." }) })).toEqual({ kind: "error", words: "Je ne peux pas répondre à cela." });
    expect(chatEvent({ event: "delta", data: "not json" })).toBeNull();
  });

  it("decide sends approve, or reject for a cancel, for the session", async () => {
    const { d, support } = await setup();
    await support.decide("s-1", true);
    await support.decide("s-1", false, "non merci");
    expect(d.askedOf("decide").map((a) => [a.path, a.body])).toEqual([
      [{ brand: "guyanabet", session: "s-1" }, { action: "approve" }],
      [{ brand: "guyanabet", session: "s-1" }, { action: "reject", feedback: "non merci" }],
    ]);
  });

  it("a refusal before the stream is one refusal, keeping the agent's words", async () => {
    const { d, support } = await setup();
    const cases = [
      [{ status: 503, body: { error: "agent_unavailable", message: "Le chat est fermé pour le moment." } }, { status: 503, code: "agent_unavailable", fields: {}, words: "Le chat est fermé pour le moment." }],
      [{ status: 429, body: { error: "too_many_turns", retryAfter: 20 } }, { status: 429, code: "too_many_turns", fields: { retryAfterSeconds: 20 } }],
      [{ status: 409, body: { error: "conversation_ended", message: "Merci, à bientôt." } }, { status: 409, code: "conversation_ended", fields: {}, words: "Merci, à bientôt." }],
      [{ status: 403, body: { error: "session_not_yours" } }, { status: 403, code: "session_not_yours", fields: {} }],
      [{ status: 409, body: { error: "Session already active", details: { approvalId: "a-1", action_name: "set_deposit_limit" } } }, { status: 409, code: "Session already active", fields: { approvalId: "a-1", action: "set_deposit_limit" } }],
    ] as const;
    for (const [reply, refusal] of cases) {
      d.script("turn", reply, 1);
      expect(await support.turn("s-1", "?"), refusal.code).toEqual({ ok: false, refusal });
    }
  });

  it("a reply read to its end, or closed, ends", async () => {
    const { d, support } = await setup();
    const c = channel();
    d.script("turn", { status: 200, stream: c });
    const r = await support.turn("s-1", "?");
    if (!r.ok) throw new Error("refused");
    c.push("delta", { token: "a" });
    const seen = all(r.value.events);
    r.value.close();
    expect(await seen).toEqual([{ kind: "delta", text: "a" }]);
  });
});
