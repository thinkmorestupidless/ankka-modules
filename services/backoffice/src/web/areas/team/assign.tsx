// An operator's roles in this brand (T055, FR-008c), given and taken away through players'
// `role-assignments`, which replaces every role the brand gives them. A role carrying a permission the
// giver does not hold is shown disabled, naming the permission: players would refuse it. players
// answers no read of an operator's current roles; opened from the operators list, the roles merchant
// lists for them start ticked (those that are this brand's), and the confirmation says what is given and
// what is taken away. Typed by hand, the confirmation says that every role not ticked is taken away.
import { useId, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { holds, type Me } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { Actions } from "../../actions/index.ts";
import { difference } from "../../../shared/permissions.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { bagsOf, send, useRoles, type Role } from "./roles.tsx";

/** The first permission the role carries that the giver does not hold, or none. */
export function lacking(giver: Pick<Me, "permissions">, role: Role): string | undefined {
  return role.permissions.find((p) => !holds(giver, p));
}

/** What confirming gives and takes away: against the roles known to be held, or, unknown, every role not ticked. */
function Effect({ operator, picked, held }: { operator: string; picked: readonly string[]; held: readonly string[] | undefined }) {
  if (held === undefined)
    return (
      <div data-effect>
        <p>{picked.length > 0 ? t("assign.effect.ticked", { operator, roles: picked.join(", ") }) : t("assign.effect.none", { operator })}</p>
        <p>{t("assign.effect.unknown")}</p>
      </div>
    );
  const { gained, lost } = difference(held, picked);
  if (gained.length === 0 && lost.length === 0) return <p data-effect>{t("assign.effect.same", { operator })}</p>;
  return (
    <div data-effect>
      {gained.length > 0 ? <p data-gives>{t("assign.effect.gives", { operator, roles: gained.join(", ") })}</p> : null}
      {lost.length > 0 ? <p data-takes>{t("assign.effect.takes", { operator, roles: lost.join(", ") })}</p> : null}
    </div>
  );
}

/**
 * `operator`: the operator to start with, as the operators list opens this screen for one (T114), with
 * `held`, the roles merchant lists for them.
 */
export function AssignPage({ operator: initial = "", held: listed }: { operator?: string; held?: readonly string[] } = {}) {
  const me = useOperator();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const roles = useRoles();
  const operatorId = useId();
  const [operator, setOperator] = useState(initial);
  const [chosen, setChosen] = useState<Set<string>>(() => new Set(listed ?? []));
  const [given, setGiven] = useState<{ operator: string; roles: string[] } | undefined>(undefined);
  if (roles.isError) return <RefusalNotice refusal={roles.error} />;
  if (!roles.data) return <p role="status">{t("roles.loading")}</p>;
  const who = operator.trim();
  const brandRoles = new Set(roles.data.map((r) => r.name));
  const picked = [...chosen].filter((r) => brandRoles.has(r)).sort();
  // what they hold is known only for the operator the list opened, as it listed them
  const held = listed !== undefined && who === initial.trim() ? listed.filter((r) => brandRoles.has(r)).sort() : undefined;

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
          subject={<Effect operator={who} picked={picked} held={held} />}
          run={() => send("PUT", `/role-assignments/${encodeURIComponent(who)}`, { roles: picked })}
          onDone={(answer) => {
            void answer
              .json()
              .then((b: { operator?: unknown; roles?: unknown }) =>
                setGiven({ operator: typeof b.operator === "string" ? b.operator : who, roles: Array.isArray(b.roles) ? b.roles.map(String) : picked }),
              )
              .catch(() => setGiven({ operator: who, roles: picked }));
            void client.invalidateQueries({ queryKey: ["payments", "operators"] });
          }}
        />
      )}
      {given ? (
        <p role="status">{t("assign.given", { operator: given.operator, roles: given.roles.join(", ") })}</p>
      ) : null}
    </section>
  );
}
