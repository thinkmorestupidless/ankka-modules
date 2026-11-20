// Where a scenario's record offers its actions, for the steps several features share ("the operator is
// offered …"): the region that holds the record's actions, the feature's words for each action, and what
// the region says when none is offered. A feature's Given sets it; withdrawals' request is the default.
import type { Locator } from "@playwright/test";
import type { MessageKey } from "../../src/shared/messages/index.ts";
import type { ActionId } from "../../src/web/actions/index.ts";
import type { World } from "./world.ts";

export interface Offering {
  region(world: World): Locator;
  named: Record<string, ActionId>;
  none: MessageKey;
}

const offerings = new WeakMap<World, Offering>();

export function offerIn(world: World, offering: Offering) {
  offerings.set(world, offering);
}

export function offeringOf(world: World, otherwise: Offering): Offering {
  return offerings.get(world) ?? otherwise;
}
