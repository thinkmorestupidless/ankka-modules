import { describe, expect, it, vi } from "vitest";
import { behaviour, machine, type Step } from "../../src/behaviour.ts";
import { handClock } from "../../src/testing/clock.ts";

type S = (Step & { kind: "a"; allowed: readonly ["go"] }) | (Step & { kind: "b"; allowed: readonly [] });
const a: S = { kind: "a", allowed: ["go"] };
const b: S = { kind: "b", allowed: [] };

describe("the behaviour kernel", () => {
  it("runs an action only where the step allows it, and answers where it was not", () => {
    const m = machine<S>(a, handClock());
    const body = vi.fn(() => m.set(b));
    const go = m.action("go", body);
    expect(go()).toEqual({ ok: true });
    expect(go()).toEqual({ ok: false, notAllowedAt: "b" });
    expect(body).toHaveBeenCalledTimes(1);
  });

  it("tells each listener once per change and not for the same step", () => {
    const m = machine<S>(a, handClock());
    const seen: string[] = [];
    m.subscribe((s) => seen.push(s.kind));
    m.set(a);
    m.set(b);
    m.set(a);
    expect(seen).toEqual(["b", "a"]);
  });

  it("drops an effect's answer once the step it started from has gone", () => {
    const m = machine<S>(a, handClock());
    const from = m.step();
    m.set(b);
    expect(m.still(from)).toBe(false);
    expect(m.still(m.step())).toBe(true);
  });

  it("ends every timer at stop, runs what must outlive the page, and allows nothing after", async () => {
    const clock = handClock();
    const m = machine<S>(a, clock);
    const ticks = vi.fn();
    const once = vi.fn();
    const last = vi.fn();
    m.every(1000, ticks);
    m.after(5000, once);
    m.onStop(last);
    await clock.advance(2500);
    expect(ticks).toHaveBeenCalledTimes(2);
    const public_ = behaviour(m, { go: m.action("go", () => {}) });
    public_.stop();
    expect(last).toHaveBeenCalledTimes(1);
    expect(clock.pending()).toBe(0);
    await clock.advance(10_000);
    expect(ticks).toHaveBeenCalledTimes(2);
    expect(once).not.toHaveBeenCalled();
    expect(public_.actions.go()).toEqual({ ok: false, notAllowedAt: "a" });
  });

  it("a one-off timer leaves nothing pending once run", async () => {
    const clock = handClock();
    const m = machine<S>(a, clock);
    m.after(100, () => {});
    await clock.advance(100);
    expect(clock.pending()).toBe(0);
  });
});
