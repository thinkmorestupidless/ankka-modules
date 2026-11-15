// The team area: the brand's roles, and giving them to an operator.
import { useOperator } from "../../frame/context.ts";
import { AssignPage } from "./assign.tsx";
import { RolesPage } from "./roles.tsx";

export function TeamPage() {
  const me = useOperator();
  return (
    <>
      <RolesPage />
      {me.permissions.includes("roles.give") ? <AssignPage /> : null}
    </>
  );
}
