// The brand's commission plans (parity *Plans*; the programme's O4 to O6): listed, made, and changed.
// A plan is named once (the programme refuses a second plan of the same name, `plan_already_exists`);
// its CPA in minor units of its currency, its RevShare a percentage, its minimum qualifying deposit in
// the plan's currency, whether a negative month carries over, and whether it is active. A change applies
// from the next fact of a membership naming the plan.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { decimalOf } from "../../../shared/money.ts";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { Read } from "./parts.tsx";
import { amountOf, isCurrency, json, percentOf, type Plan } from "./programme.ts";

export const PlanTypes = ["cpa", "revshare", "hybrid"] as const;
const PlanStatuses = ["active", "inactive"] as const;

/** The brand's plans, for a picker elsewhere in the area. */
export function usePlans() {
  return useQuery({ queryKey: ["programme", "plans"], queryFn: () => json<Plan[]>(programme.getCommissionPlans()) });
}

interface Draft {
  planType: string;
  cpaAmount: string;
  cpaCurrency: string;
  revSharePercent: string;
  minimumDeposit: string;
  negativeCarryover: boolean;
  status: string;
}

const draftOf = (p?: Plan): Draft => ({
  planType: p?.planType ?? "cpa",
  cpaAmount: p ? String(p.cpaAmount) : "",
  cpaCurrency: p?.cpaCurrency ?? "EUR",
  revSharePercent: p ? String(p.revSharePercent) : "",
  minimumDeposit: p ? String(p.minimumDeposit) : "",
  negativeCarryover: p?.negativeCarryover ?? false,
  status: p?.status ?? "active",
});

/** The body a draft makes, or the first field that is not valid. Amounts are typed in major units of the currency. */
function bodyOf(d: Draft, typed: { cpa: string; min: string }): { body: Record<string, unknown> } | { invalid: MessageKey } {
  if (!isCurrency(d.cpaCurrency)) return { invalid: "affiliates.plan.currency" };
  const cpa = typed.cpa.trim() === "" ? 0 : amountOf(typed.cpa, d.cpaCurrency);
  if (cpa === undefined) return { invalid: "affiliates.plan.cpa" };
  const min = typed.min.trim() === "" ? 0 : amountOf(typed.min, d.cpaCurrency);
  if (min === undefined) return { invalid: "affiliates.plan.minimumDeposit" };
  const rev = d.revSharePercent.trim() === "" ? 0 : percentOf(d.revSharePercent);
  if (rev === undefined) return { invalid: "affiliates.plan.revShare" };
  return {
    body: { planType: d.planType, cpaAmount: cpa, cpaCurrency: d.cpaCurrency, revSharePercent: rev, minimumDepositThreshold: min, negativeCarryover: d.negativeCarryover },
  };
}

/** A plan's change: every field but its type, which the programme does not change. */
function changeOf(made: ReturnType<typeof bodyOf>): Record<string, unknown> {
  if (!("body" in made)) return {};
  const rest = { ...made.body };
  delete rest.planType;
  return rest;
}

function PlanForm({ plan, done }: { plan?: Plan; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const ids = { name: useId(), type: useId(), cpa: useId(), currency: useId(), rev: useId(), min: useId(), carry: useId(), status: useId() };
  const [name, setName] = useState("");
  const [d, setD] = useState<Draft>(() => draftOf(plan));
  const [cpa, setCpa] = useState(() => (plan ? decimal(plan.cpaAmount, plan.cpaCurrency) : ""));
  const [min, setMin] = useState(() => (plan ? decimal(plan.minimumDeposit, plan.cpaCurrency) : ""));
  const made = bodyOf(d, { cpa, min });
  const set = (k: keyof Draft) => (v: string | boolean) => setD((x) => ({ ...x, [k]: v }));
  const ready = "body" in made && (plan !== undefined || name.trim() !== "");
  return (
    <div className="bo-aff-form" data-plan-form={plan?.planId ?? "new"}>
      {plan ? null : (
        <div className="bo-field">
          <label htmlFor={ids.name}>{t("affiliates.plan.name")}</label>
          <input id={ids.name} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
      )}
      {plan ? null : (
        <div className="bo-field">
          <label htmlFor={ids.type}>{t("affiliates.plan.type")}</label>
          <select id={ids.type} value={d.planType} onChange={(e) => set("planType")(e.target.value)}>
            {PlanTypes.map((p) => (
              <option key={p} value={p}>
                {word("affiliates.planType", p)}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="bo-field">
        <label htmlFor={ids.currency}>{t("affiliates.plan.currency")}</label>
        <input id={ids.currency} value={d.cpaCurrency} maxLength={3} onChange={(e) => set("cpaCurrency")(e.target.value.toUpperCase())} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.cpa}>{t("affiliates.plan.cpa")}</label>
        <input id={ids.cpa} inputMode="decimal" value={cpa} onChange={(e) => setCpa(e.target.value)} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.rev}>{t("affiliates.plan.revShare")}</label>
        <input id={ids.rev} inputMode="decimal" value={d.revSharePercent} onChange={(e) => set("revSharePercent")(e.target.value)} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.min}>{t("affiliates.plan.minimumDeposit")}</label>
        <input id={ids.min} inputMode="decimal" value={min} onChange={(e) => setMin(e.target.value)} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.carry}>
          <input id={ids.carry} type="checkbox" checked={d.negativeCarryover} onChange={(e) => set("negativeCarryover")(e.target.checked)} /> {t("affiliates.plan.carryover")}
        </label>
      </div>
      {plan ? (
        <div className="bo-field">
          <label htmlFor={ids.status}>{t("affiliates.plan.status")}</label>
          <select id={ids.status} value={d.status} onChange={(e) => set("status")(e.target.value)}>
            {PlanStatuses.map((s) => (
              <option key={s} value={s}>
                {word("status", s)}
              </option>
            ))}
          </select>
        </div>
      ) : null}
      {"invalid" in made ? (
        <p className="bo-field-error bo-field-wide" role="status">
          {t("affiliates.invalid", { name: t(made.invalid) })}
        </p>
      ) : null}
      <div className="bo-aff-actions bo-field-wide">
        {plan ? (
          <Action
            action={Actions["programme.plan.edit"]}
            me={me}
            bags={bags}
            ready={ready}
            subject={<p>{t("affiliates.plan.editing", { name: plan.planName })}</p>}
            run={() => programme.patchCommissionPlansByPlan({ plan: plan.planId }, { body: { ...changeOf(made), status: d.status } })}
            onDone={done}
          />
        ) : (
          <Action
            action={Actions["programme.plan.create"]}
            me={me}
            bags={bags}
            ready={ready}
            subject={<p>{t("affiliates.plan.creating", { name: name.trim() })}</p>}
            run={() => programme.postCommissionPlans({ body: { planName: name.trim(), ...("body" in made ? made.body : {}) } })}
            onDone={() => {
              setName("");
              done();
            }}
          />
        )}
      </div>
    </div>
  );
}

/** Minor units as the decimal text an operator edits; a currency the table lacks as its minor units. */
function decimal(minor: number, currency: string): string {
  try {
    return decimalOf({ minor, currency });
  } catch {
    return String(minor);
  }
}

export function PlansSection() {
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const q = usePlans();
  const [editing, setEditing] = useState<string | undefined>(undefined);
  const done = () => {
    setEditing(undefined);
    void client.invalidateQueries({ queryKey: ["programme", "plans"] });
  };
  return (
    <div>
      <h2>{t("affiliates.section.plans")}</h2>
      <Read q={q}>
        {(plans) =>
          plans.length === 0 ? (
            <p className="bo-muted">{t("affiliates.plan.none")}</p>
          ) : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.section.plans")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.plan.name")}</th>
                  <th scope="col">{t("affiliates.plan.type")}</th>
                  <th scope="col" data-numeric>
                    {t("affiliates.plan.cpa")}
                  </th>
                  <th scope="col" data-numeric>
                    {t("affiliates.plan.revShare")}
                  </th>
                  <th scope="col" data-numeric>
                    {t("affiliates.plan.minimumDeposit")}
                  </th>
                  <th scope="col">{t("affiliates.plan.carryover")}</th>
                  <th scope="col">{t("affiliates.plan.status")}</th>
                  <th scope="col">
                    <span className="bo-sr-only">{t("affiliates.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {plans.map((p) => (
                  <tr key={p.planId} data-plan={p.planId}>
                    <th scope="row">{p.planName}</th>
                    <td>{word("affiliates.planType", p.planType)}</td>
                    <td data-numeric>
                      <Money value={{ minor: p.cpaAmount, currency: p.cpaCurrency }} />
                    </td>
                    <td data-numeric>{t("affiliates.percent", { value: p.revSharePercent })}</td>
                    <td data-numeric>
                      <Money value={{ minor: p.minimumDeposit, currency: p.cpaCurrency }} />
                    </td>
                    <td>{p.negativeCarryover ? t("affiliates.yes") : t("affiliates.no")}</td>
                    <td>
                      <Status value={p.status} />
                    </td>
                    <td>
                      {readOnly ? null : (
                        <button type="button" className="bo-button" aria-expanded={editing === p.planId} onClick={() => setEditing(editing === p.planId ? undefined : p.planId)}>
                          {t("affiliates.plan.change")}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </Read>
      {editing && q.data?.find((p) => p.planId === editing) ? (
        <div className="bo-aff-detail">
          <h3>{t("affiliates.plan.editing", { name: q.data.find((p) => p.planId === editing)!.planName })}</h3>
          <PlanForm key={editing} plan={q.data.find((p) => p.planId === editing)} done={done} />
        </div>
      ) : null}
      {readOnly ? null : (
        <>
          <h3>{t("affiliates.plan.new")}</h3>
          <PlanForm done={done} />
        </>
      )}
    </div>
  );
}
