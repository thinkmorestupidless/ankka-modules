import type { Clock } from "./clock.ts";

/** A step of a behaviour: what it stands at, and the names of the actions it allows (FR-012). */
export interface Step {
  readonly kind: string;
  readonly allowed: readonly string[];
}

/** What every action answers: done, or not allowed at the step it was asked at. */
export type Answer<K extends string = string> = { ok: true } | { ok: false; notAllowedAt: K };

/** A behaviour (contracts/behaviours.md): a step, told to listeners on each change, and its actions. */
export interface Behaviour<S extends Step, A> {
  step(): S;
  subscribe(listener: (step: S) => void): () => void;
  readonly actions: A;
  /** Ends its timers and streams, sending what must outlive the page. */
  stop(): void;
}

/**
 * The state a behaviour is built on. A behaviour's actions are made with `action`, which runs the
 * body only when the step allows the name, so no action can run past a step that does not allow
 * it (SC-003). Timers made through it end at `stop`; an effect that settles after the step it was
 * started from has gone is told so by `still`, and its answer is dropped.
 */
export interface Machine<S extends Step> {
  step(): S;
  set(next: S): void;
  subscribe(listener: (step: S) => void): () => void;
  /** An action that runs `body` only where the step allows `name`. */
  action<Args extends unknown[]>(name: string, body: (...args: Args) => void): (...args: Args) => Answer<S["kind"]>;
  /** Whether `step` is still the current one: an effect started from it may still act. */
  still(step: S): boolean;
  after(ms: number, run: () => void): () => void;
  every(ms: number, run: () => void): () => void;
  onStop(run: () => void): void;
  stop(): void;
  readonly stopped: boolean;
  readonly clock: Clock;
}

export function machine<S extends Step>(initial: S, clock: Clock): Machine<S> {
  let current = initial;
  let stopped = false;
  const listeners = new Set<(step: S) => void>();
  const cancels = new Set<() => void>();
  const stops: Array<() => void> = [];
  const timer = (start: (ms: number, run: () => void) => () => void, once: boolean) => (ms: number, run: () => void) => {
    if (stopped) return () => {};
    // eslint-disable-next-line prefer-const
    let cancel: () => void;
    const wrapped = () => {
      if (once) cancels.delete(cancel);
      if (!stopped) run();
    };
    const inner = start(ms, wrapped);
    cancel = () => {
      cancels.delete(cancel);
      inner();
    };
    cancels.add(cancel);
    return cancel;
  };
  const m: Machine<S> = {
    clock,
    get stopped() {
      return stopped;
    },
    step: () => current,
    set(next) {
      if (stopped || next === current) return;
      current = next;
      for (const listener of [...listeners]) listener(next);
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    action(name, body) {
      return (...args) => {
        const at = current;
        if (stopped || !at.allowed.includes(name)) return { ok: false, notAllowedAt: at.kind };
        body(...args);
        return { ok: true };
      };
    },
    still: (step) => !stopped && step === current,
    after: timer((ms, run) => clock.after(ms, run), true),
    every: timer((ms, run) => clock.every(ms, run), false),
    onStop: (run) => void stops.push(run),
    stop() {
      if (stopped) return;
      for (const run of stops) run();
      stopped = true;
      for (const cancel of [...cancels]) cancel();
      listeners.clear();
    },
  };
  return m;
}

/** The public face of a machine: what a page holds. */
export function behaviour<S extends Step, A>(m: Machine<S>, actions: A): Behaviour<S, A> {
  return { step: m.step, subscribe: m.subscribe, actions, stop: m.stop };
}
