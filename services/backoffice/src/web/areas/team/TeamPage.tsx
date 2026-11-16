// The team area: the shared payments' operators to a payments role (T114), the brand's roles, and giving
// them to an operator; the operators list opens the giving for one operator.
import { useState } from "react";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { useOperator } from "../../frame/context.ts";
import { AssignPage } from "./assign.tsx";
import { OperatorsPage } from "./operators.tsx";
import { RolesPage } from "./roles.tsx";

export function TeamPage() {
  const me = useOperator();
  const [operator, setOperator] = useState<string | undefined>(undefined);
  const gives = holds(me, "roles.give");
  return (
    <>
      {holds(me, "roles.read") ? null : <h1>{t("area.team")}</h1>}
      {holds(me, "payments.read") ? <OperatorsPage onRoles={gives ? setOperator : undefined} /> : null}
      {holds(me, "roles.read") ? <RolesPage /> : null}
      {gives ? <AssignPage key={operator ?? ""} operator={operator} /> : null}
    </>
  );
}
