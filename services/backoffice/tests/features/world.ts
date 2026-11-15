// The world every bound feature shares: the doubles reset before each scenario (the seeded brand,
// `goldvibe`, in `tests/doubles/fixtures/`), and a way to sign in as a role. Step files under
// `steps/` take `Given`, `When`, `Then` from here, so each step gets the world as a fixture:
//
//   Given("a support operator of a brand", async ({ world }) => world.signInAs("brand_support"));
import { expect, type Page } from "@playwright/test";
import { createBdd, test as base } from "playwright-bdd";
import { DoublesClient } from "../doubles/client.ts";
import { BackofficeUrl, DoublesUrl, operator, type Who } from "../doubles/env.ts";
import type { Identity } from "../doubles/realm.ts";
import type { Seed } from "../doubles/server.ts";
import { signIn } from "../doubles/sign-in.ts";
import type { Preferences } from "../../src/server/session.ts";

export class World {
  readonly page: Page;
  readonly doubles: DoublesClient;
  readonly brand: Seed;
  readonly url = BackofficeUrl;
  /** Who the scenario signed in as, once it has. */
  operator: Identity | undefined;

  constructor(page: Page, doubles: DoublesClient, brand: Seed) {
    this.page = page;
    this.doubles = doubles;
    this.brand = brand;
  }

  /** Sign in as one of the brand's default roles, a brand-less operator, `platform_admin`, or any identity. */
  async signInAs(who: Who | Identity, opts: { preferences?: Partial<Preferences>; to?: string } = {}): Promise<Identity> {
    const identity = typeof who === "string" ? operator(who) : who;
    await signIn(this.page, this.doubles, identity, opts);
    this.operator = identity;
    return identity;
  }

  /** Go to a page route of the backoffice (`/players/p-ana`). */
  open(route: string) {
    return this.page.goto(`${this.url}${route}`);
  }
}

export const test = base.extend<{ world: World }>({
  world: async ({ page }, use) => {
    const doubles = new DoublesClient(DoublesUrl);
    await doubles.reset();
    await use(new World(page, doubles, await doubles.seed()));
  },
});

export { expect };
export const { Given, When, Then } = createBdd(test);
