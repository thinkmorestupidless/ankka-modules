import type { Refusal } from "@eitheror/player-sdk";
import { useLanguage } from "../app/language.tsx";

/** A refusal in the page's words; a payment provider's or the agent's own words, when given, beside them. */
export function Refused({ refusal }: { refusal: Refusal }) {
  const { refusal: say } = useLanguage();
  return (
    <div role="alert" className="rounded-md border-l-4 border-cayenne bg-white px-4 py-3">
      <p>{say(refusal.code, refusal.fields as Record<string, unknown>)}</p>
      {refusal.words ? <p className="mt-1 text-sm text-ink/70">{refusal.words}</p> : null}
    </div>
  );
}
