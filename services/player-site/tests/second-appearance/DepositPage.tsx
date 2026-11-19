import { format, type DepositStep, type PlayerSite } from "@eitheror/player-sdk";
import { useBehaviour, useCurrentDeposit } from "@eitheror/player-sdk/react";
import { useState } from "react";

/**
 * A second appearance of the deposit (SC-008), deliberately unlike the reference site's: one page,
 * no overlay, a list for the amount and radio buttons for the method, English only, no styling.
 * It is written against the SDK's behaviours alone, as a site builder's own would be.
 */
export function DepositPage({ site }: { site: PlayerSite }) {
  const deposit = useCurrentDeposit(site);
  const step: DepositStep | undefined = useBehaviour(deposit);
  const [amount, setAmount] = useState("");
  const [terminal, setTerminal] = useState("");
  if (!deposit || !step) return <main><p>No deposit open.</p></main>;
  return (
    <main>
      <h1>Add funds</h1>
      <p data-step={step.kind}>Step: {step.kind}</p>
      {step.kind === "amount" ? (
        <form onSubmit={(e) => { e.preventDefault(); deposit.actions.confirm(BigInt(amount)); }}>
          <label>
            How much
            <select value={amount} onChange={(e) => setAmount(e.target.value)}>
              <option value="">choose</option>
              {step.presets.map((a) => <option key={String(a.minor)} value={String(a.minor)}>{format(a, "en-GB")}</option>)}
            </select>
          </label>
          <button type="submit">Pay in</button>
        </form>
      ) : null}
      {step.kind === "choosing_terminal" ? (
        <form onSubmit={(e) => { e.preventDefault(); deposit.actions.choose(terminal); }}>
          {step.terminals.map((t) => (
            <label key={t.id}>
              <input type="radio" name="terminal" value={t.id} checked={terminal === t.id} onChange={() => setTerminal(t.id)} />
              {t.name}
            </label>
          ))}
          <button type="submit">Next</button>
        </form>
      ) : null}
      {step.kind === "waiting_at_provider" ? <p>Finish at <a href={step.address}>the provider</a>.</p> : null}
      {step.kind === "succeeded" ? <p>Added {format(step.amount, "en-GB")}.</p> : null}
      {(step.allowed as readonly string[]).includes("close") ? <button onClick={() => deposit.actions.close()}>Done</button> : null}
    </main>
  );
}
