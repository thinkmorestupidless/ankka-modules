// What every screen inside the frame may read: the operator, and whether editing is offered here.
import { createContext, useContext } from "react";
import type { Me } from "../../shared/access.ts";

export const MeContext = createContext<Me | undefined>(undefined);
export const ReadOnlyContext = createContext(false);

export function useOperator(): Me {
  const me = useContext(MeContext);
  if (!me) throw new Error("useOperator outside the frame");
  return me;
}

/** True below 1280 px: an action is not offered (FR-041). */
export function useReadOnlyMode(): boolean {
  return useContext(ReadOnlyContext);
}
