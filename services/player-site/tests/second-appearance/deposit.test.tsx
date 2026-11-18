// @vitest-environment jsdom
import { playerSite, type DepositBehaviour, type DepositStep, type PlayerSite } from "@eitheror/player-sdk";
import { handClock, doubles, doubleTransport, type Doubles, type HandClock } from "@eitheror/player-sdk/testing";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { expect, vi } from "vitest";
import { bind } from "../../../../sdk/player/tests/features/setup.ts";
import { LanguageProvider } from "../../src/app/language.tsx";
import { DepositOverlay } from "../../src/pages/Deposit.tsx";
import { siteOptions } from "../../src/site.ts";
import { DepositPage } from "./DepositPage.tsx";

// composing.feature's two scenarios that compare sites (SC-008): the deposit with no component, at the
// reference site, and at a second appearance, over the same doubles and the same hand clock.

const ana = { email: "ana@example.com", password: "correct horse battery 1!" };

interface Run {
  readonly kinds: DepositStep["kind"][];
  readonly calls: string[];
  readonly outcome: unknown;
}

/** A signed-in site over fresh doubles, and the steps of every deposit it opens. */
async function signedIn(): Promise<{ site: PlayerSite; d: Doubles; clock: HandClock; kinds: DepositStep["kind"][]; from: number }> {
  const clock = handClock();
  const d = doubles({ clock });
  const site = playerSite({ transport: doubleTransport(d), clock, ...siteOptions, idleMinutes: 24 * 60 });
  await site.ready;
  site.session.actions.signIn(ana);
  await clock.settle();
  const kinds: DepositStep["kind"][] = [];
  let watched: DepositBehaviour | undefined;
  site.subscribe(() => {
    const dep = site.currentDeposit;
    if (!dep || dep === watched) return;
    watched = dep;
    kinds.push(dep.step().kind);
    dep.subscribe((s) => kinds.push(s.kind));
  });
  return { site, d, clock, kinds, from: d.asked.length };
}

/** The calls of the brand from the deposit on, each its route and its body, the request's own id left out. */
const callsOf = (d: Doubles, from: number) =>
  d.asked.slice(from).filter((a) => a.id !== "push" && a.id !== "unread").map((a) => `${a.id} ${JSON.stringify(a.body ?? null, (k, v) => (k === "requestId" ? "<id>" : v))}`);

/** The provider settles the payment and the wallet credits it, at whichever site. */
async function settle(d: Doubles, clock: HandClock) {
  for (const p of d.brand.payments.values()) p.status = "COMPLETED";
  d.brand.balance.real = "100.00";
  await act(() => clock.advance(3000));
}

async function noAppearance(): Promise<Run> {
  const { site, d, clock, kinds, from } = await signedIn();
  const dep = site.deposit()!;
  dep.actions.confirm(5000n);
  await clock.settle();
  dep.actions.choose("t-redirect");
  await clock.advance(1500);
  for (const p of d.brand.payments.values()) p.status = "COMPLETED";
  d.brand.balance.real = "100.00";
  await clock.advance(3000);
  const outcome = dep.step();
  dep.actions.close();
  site.stop();
  return { kinds, calls: callsOf(d, from), outcome };
}

async function atReference(): Promise<Run> {
  const { site, d, clock, kinds, from } = await signedIn();
  vi.stubGlobal("open", vi.fn());
  render(
    <LanguageProvider offered={["fr", "en"]} currency="EUR">
      <DepositOverlay site={site} />
    </LanguageProvider>,
  );
  await act(async () => void site.deposit());
  fireEvent.click(screen.getByRole("button", { name: /^50[.,]00\s?€$|^€50\.00$/ }));
  await act(() => clock.settle());
  fireEvent.click(screen.getByRole("button", { name: "Virement" }));
  await act(() => clock.advance(1500));
  await settle(d, clock);
  const outcome = site.currentDeposit!.step();
  fireEvent.click(screen.getByRole("button", { name: /^(fermer|close)$/i }));
  cleanup();
  site.stop();
  return { kinds, calls: callsOf(d, from), outcome };
}

async function atSecond(): Promise<Run> {
  const { site, d, clock, kinds, from } = await signedIn();
  render(<DepositPage site={site} />);
  await act(async () => void site.deposit());
  fireEvent.change(screen.getByLabelText("How much"), { target: { value: "5000" } });
  fireEvent.click(screen.getByRole("button", { name: "Pay in" }));
  await act(() => clock.settle());
  fireEvent.click(screen.getByLabelText("Virement"));
  fireEvent.click(screen.getByRole("button", { name: "Next" }));
  await act(() => clock.advance(1500));
  await settle(d, clock);
  const outcome = site.currentDeposit!.step();
  fireEvent.click(screen.getByRole("button", { name: "Done" }));
  cleanup();
  site.stop();
  return { kinds, calls: callsOf(d, from), outcome };
}

const succeeded = { kind: "succeeded", amount: { minor: 5000n, currency: "EUR" } };

const background = (Background: (fn: (s: { Given: (t: string, f: () => void) => void }) => void) => void) =>
  Background(({ Given }) => Given('a player site of brand "guyanabet"', () => {}));

const bare = "a behaviour run with no appearance takes every step it takes at a player site";
await bind(
  "composing.feature",
  ({ Background, Scenario }) => {
    background(Background as never);
    Scenario(bare, ({ Given, When, Then }) => {
      let run: Run;
      Given("the deposit behaviour bound to no component", () => {});
      When("a deposit is driven from the amount to succeeded", async () => {
        run = await noAppearance();
        expect(run.outcome).toMatchObject(succeeded);
      });
      Then("the deposit behaviour takes the same steps as at the reference site", async () => {
        const reference = await atReference();
        expect(run.kinds).toEqual(reference.kinds);
        expect(run.kinds).toEqual(["amount", "starting", "choosing_terminal", "submitting", "waiting_at_provider", "succeeded", "closed"]);
      });
    });
  },
  bare,
);

const two = "two sites of different appearance run the same deposit";
await bind(
  "composing.feature",
  ({ Background, Scenario }) => {
    background(Background as never);
    Scenario(two, ({ Given, When, Then }) => {
      let runs: [Run, Run];
      Given("two player sites of different appearance", () => {});
      When('a player deposits "5000" minor units at each', async () => {
        runs = [await atReference(), await atSecond()];
      });
      Then("the steps taken, the calls made of the brand and the outcome are the same at both", () => {
        const [reference, second] = runs;
        expect(second.kinds).toEqual(reference.kinds);
        expect(second.calls).toEqual(reference.calls);
        expect(reference.calls.length).toBeGreaterThan(0);
        expect(second.outcome).toEqual(reference.outcome);
        expect(reference.outcome).toMatchObject(succeeded);
      });
    });
  },
  two,
);
