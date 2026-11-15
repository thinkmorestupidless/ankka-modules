// The role matrix as the contract publishes it: the brand's bags before any change.
import permissions from "../../src/shared/generated/operator-permissions.v1.json" with { type: "json" };
import type { Bags } from "../../src/server/roles.ts";

export const OperatorBags: Bags = Object.fromEntries(
  Object.entries(permissions.defaults).map(([role, ps]) => [role, [...ps].sort()]),
);
