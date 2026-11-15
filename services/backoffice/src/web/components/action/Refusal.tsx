// A refusal as the operator reads it (FR-006): the owner's reason in the domain's words and, where one
// exists, the next step; a code the backoffice does not know, as the owner gave it (`explain`).
import "../components.css";
import { render, type Message } from "../../../shared/messages/index.ts";
import { Glyph } from "../glyph.tsx";

export function RefusalNotice({ refusal }: { refusal: { message: Message; next?: Message } }) {
  return (
    <div className="bo-refusal" role="alert" data-refusal>
      <Glyph name="cross" />
      <span>{render(refusal.message)}</span>
      {refusal.next && <span className="bo-refusal-next">{render(refusal.next)}</span>}
    </div>
  );
}
