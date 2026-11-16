// An operator's roles in this brand (T055, FR-008c), given and taken away through players'
// `role-assignments`, which replaces every role the brand gives them. A role carrying a permission the
// giver does not hold is shown disabled, naming the permission: players would refuse it. players
// answers no read of an operator's current roles, so the page states that the set ticked replaces them.
import { useId, useState } from "react";
import { holds, type Me } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { bagsOf, send, useRoles, type Role } from "./roles.tsx";

/** The first permission the role carries that the giver does not hold, or none. */
export function lacking(giver: Pick<Me, "permissions">, role: Role): string | undefined {
  return role.permissions.find((p) => !holds(giver, p));
}

/** `operator`: the operator to start with, as the operators list opens this screen for one (T114). */
export function AssignPage({ operator: initial = "" }: { operator?: string } = {}) {
  const me = useOperator();
  const readOnly = useReadOnlyMode();
  const roles = useRoles();
  const operatorId = useId();
  const [operator, setOperator] = useState(initial);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [given, setGiven] = useState<{ operator: string; roles: string[] } | undefined>(undefined);
  if (!roles.data) return <p role="status">{t("roles.loading")}</p>;
  const who = operator.trim();

  return (
    <section>
      <h2>{t("assign.title")}</h2>
      <div className="bo-field">
        <label htmlFor={operatorId}>{t("assign.operator")}</label>
        <input id={operatorId} value={operator} onChange={(e) => setOperator(e.target.value)} aria-describedby={`${operatorId}-hint`} />
        <span id={`${operatorId}-hint`} className="bo-muted">
          {t("assign.operatorHint")}
        </span>
      </div>
      <fieldset className="bo-permissions">
        <legend>{t("roles.title")}</legend>
        <p className="bo-muted">{t("assign.replaces")}</p>
        {roles.data.map((r) => {
          const missing = lacking(me, r);
          const hintId = `${operatorId}-${r.name}`;
          return (
            <label key={r.name}>
              <input
                type="checkbox"
                name={r.name}
                disabled={readOnly || missing !== undefined}
                aria-describedby={missing ? hintId : undefined}
                checked={chosen.has(r.name)}
                onChange={() => {
                  const next = new Set(chosen);
                  if (next.has(r.name)) next.delete(r.name);
                  else next.add(r.name);
                  setChosen(next);
                }}
              />
              <span>{r.name}</span>
              {missing ? (
                <span id={hintId} className="bo-needs">
                  {t("assign.lacks", { permission: missing })}
                </span>
              ) : null}
            </label>
          );
        })}
      </fieldset>
      {readOnly || who === "" ? null : (
        <Action
          action={Actions["role.give"]}
          me={me}
          bags={bagsOf(roles.data)}
          subject={<p>{[...chosen].sort().join(", ")}</p>}
          run={() => send("PUT", `/role-assignments/${encodeURIComponent(who)}`, { roles: [...chosen].sort() })}
          onDone={async (answer) => setGiven((await answer.json()) as { operator: string; roles: string[] })}
        />
      )}
      {given ? (
        <p role="status">{t("assign.given", { operator: given.operator, roles: given.roles.join(", ") })}</p>
      ) : null}
    </section>
  );
}
