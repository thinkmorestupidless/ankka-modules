import type { Clock } from "../clock.ts";

interface Timer {
  id: number;
  at: number;
  every?: number;
  run: () => void;
}

/** A clock moved by hand. `advance` runs every timer due, in order, letting promises settle between them. */
export interface HandClock extends Clock {
  advance(ms: number): Promise<void>;
  /** Lets every pending promise settle without moving time. */
  settle(): Promise<void>;
  /** How many timers are waiting. */
  pending(): number;
}

export function handClock(start = Date.UTC(2026, 10, 18, 12, 0, 0)): HandClock {
  let now = start;
  let next = 1;
  let timers: Timer[] = [];
  const settle = async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve();
    await new Promise<void>((resolve) => setImmediate(resolve));
    for (let i = 0; i < 20; i++) await Promise.resolve();
  };
  const add = (ms: number, run: () => void, every?: number) => {
    const id = next++;
    const timer: Timer = every === undefined ? { id, at: now + ms, run } : { id, at: now + ms, run, every };
    timers.push(timer);
    return () => {
      timers = timers.filter((t) => t.id !== id);
    };
  };
  return {
    now: () => now,
    after: (ms, run) => add(ms, run),
    every: (ms, run) => add(ms, run, ms),
    pending: () => timers.length,
    settle,
    async advance(ms) {
      const until = now + ms;
      await settle();
      for (;;) {
        const due = timers.filter((t) => t.at <= until).sort((a, b) => a.at - b.at || a.id - b.id)[0];
        if (!due) break;
        now = due.at;
        if (due.every === undefined) timers = timers.filter((t) => t.id !== due.id);
        else due.at += due.every;
        due.run();
        await settle();
      }
      now = until;
      await settle();
    },
  };
}
