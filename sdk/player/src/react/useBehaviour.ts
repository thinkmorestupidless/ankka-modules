import { useSyncExternalStore } from "react";
import type { Behaviour, Step } from "../behaviour.ts";

const none = () => () => {};
const nothing = () => undefined;

/**
 * The behaviour's step, rendered again on each change (FR-044). The step carries its fields and
 * `allowed`; call `behaviour.actions` for what it allows. The adapter adds no rule of its own.
 * A behaviour the brand does not run (`undefined`) gives `undefined`.
 */
export function useBehaviour<S extends Step>(behaviour: Behaviour<S, unknown>): S;
export function useBehaviour<S extends Step>(behaviour: Behaviour<S, unknown> | undefined): S | undefined;
export function useBehaviour<S extends Step>(behaviour: Behaviour<S, unknown> | undefined): S | undefined {
  return useSyncExternalStore<S | undefined>(behaviour ? behaviour.subscribe : none, behaviour ? behaviour.step : nothing, behaviour ? behaviour.step : nothing);
}
