// The backoffice's home: the worklist, for an operator whose roles give one. An operator with no
// worklist area, such as a programme manager (US10, FR-037), is taken to the first area they hold,
// so they never land on a page of the brand's that is not theirs.
import { Navigate } from "@tanstack/react-router";
import { WorklistPage } from "../areas/worklist/WorklistPage.tsx";
import { areasFor } from "./areas.ts";
import { useOperator } from "./context.ts";

export function Home() {
  const held = areasFor(useOperator());
  const first = held[0];
  if (first === undefined || held.some((a) => a.id === "worklist")) return <WorklistPage />;
  return <Navigate to={first.path} replace />;
}
