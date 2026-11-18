/**
 * Time, given to every behaviour so that each timer in the spec (the idle warning, a payment's
 * reads, the reality check) runs in a test without waiting (research R2).
 */
export interface Clock {
  /** Milliseconds since the epoch. */
  now(): number;
  /** Runs `run` once after `ms`; the answer cancels it. */
  after(ms: number, run: () => void): () => void;
  /** Runs `run` every `ms`, first after `ms`; the answer cancels it. */
  every(ms: number, run: () => void): () => void;
}

/** The real clock. */
export const realClock: Clock = {
  now: () => Date.now(),
  after(ms, run) {
    const id = setTimeout(run, ms);
    return () => clearTimeout(id);
  },
  every(ms, run) {
    const id = setInterval(run, ms);
    return () => clearInterval(id);
  },
};
