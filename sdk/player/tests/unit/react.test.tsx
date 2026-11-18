// @vitest-environment jsdom
import { act, cleanup, render, screen } from "@testing-library/react";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { GamesBehaviour } from "../../src/casino/games.ts";
import type { DepositBehaviour } from "../../src/core/deposit.ts";
import { PlayerSiteProvider, useBehaviour, useCurrentDeposit, usePlayer, usePlayerSite } from "../../src/react/index.ts";
import { playerSite } from "../../src/site.ts";
import { handClock } from "../../src/testing/clock.ts";
import { doubles, doubleTransport } from "../../src/testing/doubles.ts";

afterEach(cleanup);

async function setup() {
  const clock = handClock();
  const d = doubles({ clock });
  const site = playerSite({ transport: doubleTransport(d), clock, idleMinutes: 24 * 60 });
  await site.ready;
  await act(async () => {
    site.session.actions.signIn({ email: "ana@example.com", password: "correct horse battery 1!" });
    await clock.settle();
  });
  return { clock, d, site };
}

const renders: string[] = [];
function DepositView({ deposit }: { deposit: DepositBehaviour }) {
  const step = useBehaviour(deposit);
  renders.push(step.kind);
  return (
    <div>
      <p data-testid="kind">{step.kind}</p>
      <p data-testid="allowed">{step.allowed.join(",")}</p>
      {(step.allowed as readonly string[]).includes("confirm") ? <button onClick={() => deposit.actions.confirm(5000n)}>confirm</button> : null}
    </div>
  );
}
function Current() {
  const site = usePlayerSite();
  const deposit = useCurrentDeposit(site);
  const player = usePlayer(site);
  return (
    <div>
      <p data-testid="player">{player?.playerId ?? "none"}</p>
      {deposit ? <DepositView deposit={deposit} /> : <p data-testid="kind">no deposit</p>}
    </div>
  );
}
function Games({ games }: { games: GamesBehaviour | undefined }) {
  const step = useBehaviour(games);
  return <p data-testid="games">{step ? step.open.map((g) => g.game).join(",") : "not run"}</p>;
}

describe("the React adapter (FR-044)", () => {
  it("renders a component again on each step and gives it the step's allowed actions", async () => {
    const { clock, site } = await setup();
    const deposit = site.deposit()!;
    renders.length = 0;
    render(<DepositView deposit={deposit} />);
    expect(screen.getByTestId("kind").textContent).toBe("amount");
    expect(screen.getByTestId("allowed").textContent).toBe("confirm,close");
    await act(async () => {
      screen.getByText("confirm").click();
      await clock.settle();
    });
    expect(screen.getByTestId("kind").textContent).toBe("choosing_terminal");
    expect(screen.getByTestId("allowed").textContent).toBe("choose,back,close");
    expect(screen.queryByText("confirm")).toBeNull();
    expect(renders).toEqual(expect.arrayContaining(["amount", "starting", "choosing_terminal"]));
  });

  it("useCurrentDeposit renders again when a deposit opens and closes; the context gives the site", async () => {
    const { site } = await setup();
    render(
      <PlayerSiteProvider site={site}>
        <Current />
      </PlayerSiteProvider>,
    );
    expect(screen.getByTestId("player").textContent).toBe("p-ana");
    expect(screen.getByTestId("kind").textContent).toBe("no deposit");
    await act(async () => void site.deposit());
    expect(screen.getByTestId("kind").textContent).toBe("amount");
    await act(async () => void site.currentDeposit!.actions.close());
    expect(screen.getByTestId("kind").textContent).toBe("no deposit");
  });

  it("renders again when a second game is opened", async () => {
    const { clock, site } = await setup();
    render(<Games games={site.games} />);
    await act(async () => {
      site.games!.actions.pick("pools");
      await clock.settle();
    });
    expect(screen.getByTestId("games").textContent).toBe("pools");
    await act(async () => {
      site.games!.actions.pick("wheel");
      await clock.settle();
    });
    expect(screen.getByTestId("games").textContent).toBe("pools,wheel");
  });

  it("a behaviour the brand does not run renders as undefined", () => {
    render(<Games games={undefined} />);
    expect(screen.getByTestId("games").textContent).toBe("not run");
  });

  it("usePlayerSite outside a provider says so", () => {
    const Lost = () => <p>{usePlayerSite().player?.playerId}</p>;
    expect(() => render(<Lost />)).toThrow(/outside a PlayerSiteProvider/);
  });
});

describe("the adapter imports nothing but the kernel", () => {
  it("each file under src/react imports react, the kernel's types, or another adapter file", () => {
    const dir = join(import.meta.dirname, "../../src/react");
    const allowed = /^(react|\.\/[A-Za-z]+\.tsx?|\.\.\/behaviour\.ts|\.\.\/site\.ts|\.\.\/core\/deposit\.ts)$/;
    for (const f of readdirSync(dir)) {
      const text = readFileSync(join(dir, f), "utf8");
      for (const m of text.matchAll(/^import\s+(type\s+)?[^;]*?from\s+"([^"]+)"/gm)) {
        expect(m[2], `${f} imports ${m[2]}`).toMatch(allowed);
        // anything from outside the adapter but react is only a type: the adapter holds no rule
        if (m[2] !== "react" && !m[2]!.startsWith("./")) expect(m[0], `${f}: ${m[0]}`).toMatch(/^import\s+type\s|import\s+\{\s*type\s/);
      }
    }
  });
});
