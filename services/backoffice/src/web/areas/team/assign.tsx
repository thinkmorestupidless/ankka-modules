// An operator's roles in this brand (T055, FR-008c), given and taken away through players'
// `role-assignments`, which replaces every role the brand gives them. A role carrying a permission the
// giver does not hold is shown disabled, naming the permission: players would refuse it. players
// answers no read of an operator's current roles; opened from the operators list, the roles merchant
// lists for them start ticked (those that are this brand's), and the confirmation says what is given and
// what is taken away. Typed by hand, the confirmation says that every role not ticked is taken away.
// Spec 032: a giving is put in force by merchant after players answers, so the screen reads players'
// account of the brand's last giving (asked, in force, or failed and why) and any role held in the brand
// that no giving asked for; `api_keys` and `markets` are given here too.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { holds, type Me } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { Actions } from "../../actions/index.ts";
import { difference, GivenRoles } from "../../../shared/permissions.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { bagsOf, send, useRoles, type Role } from "./roles.tsx";

/** The roles routes check by name that a brand gives as it gives its others (spec 032 FR-012); in no bag. */
export const NamedRoles = GivenRoles;

/** The brand's last giving to an operator, as players answers it (P13r). */
export interface Giving {
  roles: string[];
  state?: "pending" | "in_force" | "failed";
  reason?: string;
  held?: string[];
}

export async function readGiving(operator: string, fetchFn: typeof fetch = fetch): Promise<Giving> {
  const r = await fetchFn(`/api/s/players/role-assignments/${encodeURIComponent(operator)}`, { headers: { Accept: "application/json" } });
  if (!r.ok) throw new Error(`${r.status}`);
  const b = (await r.json()) as Record<string, unknown>;
  const strings = (v: unknown) => (Array.isArray(v) ? v.map(String).sort() : undefined);
  const state = b.state === "pending" || b.state === "in_force" || b.state === "failed" ? b.state : undefined;
  return { roles: strings(b.roles) ?? [], state, reason: typeof b.reason === "string" ? b.reason : undefined, held: strings(b.held) };
}

/** How far the brand's last giving got, and what the brand holds that no giving asked for. */
export function GivingState({ operator, giving }: { operator: string; giving: Giving }) {
  const unasked = (giving.held ?? []).filter((r) => !giving.roles.includes(r));
  if (giving.state === undefined && giving.roles.length === 0 && unasked.length === 0)
    return <p data-giving="none">{t("assign.giving.none", { operator })}</p>;
  return (
    <div data-giving={giving.state ?? "unread"} role="group" aria-label={t("assign.giving.title")}>
      <h3>{t("assign.giving.title")}</h3>
      <p data-asked>{giving.roles.length > 0 ? t("assign.giving.asked", { roles: giving.roles.join(", ") }) : t("assign.giving.askedNone")}</p>
      <p data-state>
        {giving.state === "failed"
          ? t("assign.giving.failed", { reason: giving.reason ?? "" })
          : giving.state
            ? t(`assign.giving.${giving.state}`)
            : t("assign.giving.unread")}
      </p>
      {unasked.length > 0 ? <p data-unasked>{t("assign.giving.heldUnasked", { roles: unasked.join(", ") })}</p> : null}
    </div>
  );
}

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
  // the ticks as the person changed them; until then, the brand's last giving, or the roles merchant listed
  const [ticked, setChosen] = useState<Set<string> | undefined>(undefined);
  const [given, setGiven] = useState<{ operator: string; roles: string[] } | undefined>(undefined);
  const who = operator.trim();
  const giving = useQuery({
    queryKey: ["roles", "giving", who],
    queryFn: () => readGiving(who),
    enabled: who !== "",
    staleTime: 2_000,
    // a giving not yet in force is read again until it is, or fails
    refetchInterval: (q) => (q.state.data?.state === "pending" ? 2_000 : false),
  });
  if (roles.isError) return <RefusalNotice refusal={roles.error} />;
  if (!roles.data) return <p role="status">{t("roles.loading")}</p>;
  const brandRoles = new Set([...roles.data.map((r) => r.name), ...NamedRoles]);
  // a giving the brand made says what it holds them to; else what is held is known only for the operator the list opened
  const asked = giving.data?.state !== undefined ? giving.data.roles : undefined;
  const listedHere = listed !== undefined && who === initial.trim() ? listed : undefined;
  const held = (asked ?? listedHere)?.filter((r) => brandRoles.has(r)).sort();
  const chosen = ticked ?? new Set(held ?? []);
  const picked = [...chosen].filter((r) => brandRoles.has(r)).sort();

  return (
    <section>
      <h2>{t("assign.title")}</h2>
      <div className="bo-field">
        <label htmlFor={operatorId}>{t("assign.operator")}</label>
        <input id={operatorId} value={operator} onChange={(e) => {
            setOperator(e.target.value);
            setChosen(undefined);
          }} aria-describedby={`${operatorId}-hint`} />
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
        <p className="bo-muted">{t("assign.named")}</p>
        {NamedRoles.map((name) => (
          <label key={name}>
            <input
              type="checkbox"
              name={name}
              disabled={readOnly}
              checked={chosen.has(name)}
              onChange={() => {
                const next = new Set(chosen);
                if (next.has(name)) next.delete(name);
                else next.add(name);
                setChosen(next);
              }}
            />
            <span>{name}</span>
          </label>
        ))}
      </fieldset>
      {who !== "" && giving.data ? <GivingState operator={who} giving={giving.data} /> : null}
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
            void client.invalidateQueries({ queryKey: ["roles", "giving", who] });
          }}
        />
      )}
      {given ? (
        <p role="status">{t("assign.given", { operator: given.operator, roles: given.roles.join(", ") })}</p>
      ) : null}
    </section>
  );
}
