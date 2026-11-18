import { describe, expect, it } from "vitest";
import { waitFor } from "../../src/engagement/inbox.ts";
import { playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

async function setup(modules?: string[]) {
  const clock = handClock();
  const d = doubles({ clock, brand: modules ? { modules } : {} });
  const site = playerSite({ transport: doubleTransport(d), clock, idleMinutes: 24 * 60 });
  await site.ready;
  site.session.actions.signIn(ana);
  await clock.settle();
  return { clock, d, site, inbox: site.inbox! };
}

// The original's utils/unreadCount.test.ts is about reconciling a count the page cleared before
// marking messages read; this slice marks nothing read (the inbox's pages are not carried), so
// that rule is not kept: the count shown is the count the brand answered last.
describe("the inbox behaviour", () => {
  it("is closed for a visitor and connects on sign-in, reading the unread count on ready", async () => {
    const clock = handClock();
    const d = doubles({ clock });
    d.brand.unread = 3;
    const site = playerSite({ transport: doubleTransport(d), clock });
    await site.ready;
    expect(site.inbox!.step()).toEqual({ kind: "closed", allowed: [] });
    expect(d.askedOf("push")).toEqual([]);
    site.session.actions.signIn(ana);
    await clock.settle();
    expect(d.askedOf("push")).toHaveLength(1);
    expect(site.inbox!.step()).toEqual({ kind: "connected", allowed: ["refresh"], unread: 3 });
  });

  it("reads the count again on each signal, on refresh and when the page is shown again", async () => {
    const { clock, d, site, inbox } = await setup();
    d.brand.unread = 1;
    d.brand.pushes[0]!.push("signal", { kind: "inbox.changed" });
    await clock.settle();
    expect(inbox.step()).toMatchObject({ unread: 1 });
    d.brand.unread = 2;
    expect(inbox.actions.refresh()).toEqual({ ok: true });
    await clock.settle();
    expect(inbox.step()).toMatchObject({ unread: 2 });
    const reads = d.askedOf("unread").length;
    site.visible(false);
    site.visible(true);
    await clock.settle();
    expect(d.askedOf("unread")).toHaveLength(reads + 1);
    d.brand.pushes[0]!.push("heartbeat", {});
    await clock.settle();
    expect(d.askedOf("unread")).toHaveLength(reads + 1);
  });

  it("reopens a connection that ends or says closing after 1 s, doubling to 30 s; ready starts it again from 1 s", async () => {
    expect([0, 1, 2, 3, 4, 5, 6, 9].map(waitFor)).toEqual([1000, 2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000]);
    const { clock, d, inbox } = await setup();
    d.script("push", { status: 503, body: { error: "service_unavailable" } }, 3);
    d.brand.pushes[0]!.push("closing", {});
    await clock.settle();
    expect(inbox.step()).toMatchObject({ kind: "connecting", attempt: 1 });
    const opens = () => d.askedOf("push").length;
    for (const wait of [1000, 2000, 4000]) {
      const before = opens();
      await clock.advance(wait - 1);
      expect(opens()).toBe(before);
      await clock.advance(1);
      expect(opens(), `after ${wait}`).toBe(before + 1);
    }
    // three refused; the next, after 8 s, opens and says ready: the next end waits 1 s again
    await clock.advance(8000);
    expect(opens()).toBe(5);
    expect(inbox.step()).toMatchObject({ kind: "connected" });
    d.brand.pushes.at(-1)!.end();
    await clock.settle();
    await clock.advance(999);
    expect(opens()).toBe(5);
    await clock.advance(1);
    expect(opens()).toBe(6);
  });

  it("keeps the last count known while connecting", async () => {
    const { clock, d, inbox } = await setup();
    d.brand.unread = 4;
    await inbox.actions.refresh();
    await clock.settle();
    d.brand.pushes[0]!.end();
    await clock.settle();
    expect(inbox.step()).toEqual({ kind: "connecting", allowed: [], attempt: 1, unread: 4 });
    expect(inbox.actions.refresh()).toEqual({ ok: false, notAllowedAt: "connecting" });
  });

  it("closes at sign-out and opens nothing again", async () => {
    const { clock, d, site, inbox } = await setup();
    site.session.actions.signOut();
    await clock.settle();
    expect(inbox.step()).toEqual({ kind: "closed", allowed: [] });
    expect(d.brand.pushes[0]!.ended).toBe(true);
    await clock.advance(120_000);
    expect(d.askedOf("push")).toHaveLength(1);
  });

  it("is not offered where the brand does not run engagement", async () => {
    const { site, d } = await setup(["core", "casino"]);
    expect(site.inbox).toBeUndefined();
    expect(d.askedOf("push")).toEqual([]);
    expect(d.askedOf("unread")).toEqual([]);
  });
});
