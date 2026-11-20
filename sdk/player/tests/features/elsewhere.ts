/**
 * The scenarios of `features/player-site/` bound in another harness than the behaviours' steps,
 * and which. A scenario named here is skipped where the feature file is bound; every other
 * scenario of a bound file must have steps, or the run fails.
 */
export interface Elsewhere {
  readonly feature: string;
  readonly scenario: string;
  readonly harness: string;
}

export const elsewhere: readonly Elsewhere[] = [
  { feature: "session.feature", scenario: "a player who placed a bet in the last six minutes is not idle", harness: "play.steps.ts, beside the play behaviour (T030)" },
  { feature: "composing.feature", scenario: "a behaviour run with no appearance takes every step it takes at a player site", harness: "T047: services/player-site/tests/second-appearance" },
  { feature: "composing.feature", scenario: "two sites of different appearance run the same deposit", harness: "T047: services/player-site/tests/second-appearance" },
  { feature: "amounts-and-refusals.feature", scenario: "every refusal code a behaviour can meet has words in each language the reference site offers", harness: "T042: services/player-site/tests/words.test.ts" },
  { feature: "generated-clients.feature", scenario: "the generated module clients answer the running brand as the module clients written by hand did", harness: "spec 031 T060: tests/contract/clients.test.ts, on the local brand" },
];

/** Feature files bound wholly elsewhere: the reference site's, by playwright-bdd (T046), its render (T049), the installation (T051). */
export const wholly: Readonly<Record<string, string>> = {
  "reference-site.feature": "T046 playwright-bdd; the first scenario by T049's render case; the end-to-end one on dev-kind by T051",
};

/** Feature files whose steps are not written yet, by the task that writes them. Each task removes its line. */
export const unbound: Readonly<Record<string, string>> = {
};
