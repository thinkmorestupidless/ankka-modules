// A refusal as the operator reads it (FR-006): the owner's reason in the domain's words and, where one
// exists, the next step; a code the backoffice does not know, as the owner gave it (`explain`). It takes
// whatever a read or an action failed with (`refusalOf`): a thrown TypeError or SyntaxError is said as
// such, never thrown on. A forbidden refusal explained without the brand's roles names the role that
// would permit it from the roles the page has read (`/api/permissions`, FR-005).
import { useContext } from "react";
import { QueryClientContext } from "@tanstack/react-query";
import "../components.css";
import type { RoleBags } from "../../../shared/access.ts";
import { render } from "../../../shared/messages/index.ts";
import { refusalOf, withRoles } from "../../../shared/refusals.ts";
import { Glyph } from "../glyph.tsx";

export function RefusalNotice({ refusal: given }: { refusal: unknown }) {
  const client = useContext(QueryClientContext);
  const refusal = withRoles(refusalOf(given), client?.getQueryData<RoleBags | null>(["permissions"]));
  return (
    <div className="bo-refusal" role="alert" data-refusal>
      <Glyph name="cross" />
      <span>{render(refusal.message)}</span>
      {refusal.next && <span className="bo-refusal-next">{render(refusal.next)}</span>}
    </div>
  );
}
