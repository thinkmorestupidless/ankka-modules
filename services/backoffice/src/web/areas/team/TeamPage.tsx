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
  const [operator, setOperator] = useState<{ id: string; roles: readonly string[] } | undefined>(undefined);
  const gives = holds(me, "roles.give");
  return (
    <>
      {/* one h1 a page (DR-15): the area's; each part below is a section under it */}
      <h1>{t("area.team")}</h1>
      {holds(me, "payments.read") ? <OperatorsPage onRoles={gives ? (id, roles) => setOperator({ id, roles }) : undefined} /> : null}
      {holds(me, "roles.read") ? <RolesPage /> : null}
      {gives ? <AssignPage key={operator?.id ?? ""} operator={operator?.id} held={operator?.roles} /> : null}
    </>
  );
}
