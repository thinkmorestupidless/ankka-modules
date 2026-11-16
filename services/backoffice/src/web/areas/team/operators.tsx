// The shared payments' operators (T114; spec 004 FR-027, FR-028; the original's team page): merchant's
// operators in the signed-in operator's scope, newest first, paged (spec 030 H5); invited with a payments
// role and a level; the invitation resent while it is not accepted; a password reset sent once it is; moved
// to another level; and removed. merchant gives a role only below the inviter's own (`Privilege.canAssignRole`),
// so the roles at or above it are shown disabled, naming that a higher role gives them, and a refusal that
// comes anyway (`PRIVILEGE_ESCALATION`, `SCOPE_ESCALATION`) is shown with its reason. An operator's roles
// in this brand are given through the brand's own screen (T055), opened for that operator.
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { mayGiveRole, paymentsLevel, PaymentsRoleNames } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { completeOf, List, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { read } from "../configuration/page.tsx";
import { word } from "../word.ts";
import { subBrandIdOf, useHierarchy } from "../payments/hierarchy.tsx";

export interface PaymentsOperator {
  operatorId: string;
  email: string;
  enabled: boolean;
  roles: string[];
  scopeId: string;
  scopeLevel: string;
  pending: boolean;
  firstName?: string | null;
  lastName?: string | null;
}

interface Page {
  operators: PaymentsOperator[];
  total: number;
  page?: number;
  pageSize?: number;
}

/** merchant's levels, broadest first, as the token's claim spells them. */
export const ScopeLevels = ["platform", "holding", "company", "sub_brand"] as const;
const PageSize = 25;

/** The roles the inviter may give and those they may not, lowest first. */
export function rolesFor(roles: readonly string[]): { role: string; mayGive: boolean }[] {
  return PaymentsRoleNames.filter((r) => r !== "platform_admin").map((role) => ({ role, mayGive: mayGiveRole(roles, role) }));
}

/** The level and the entity at it: a select of what merchant answers, or the id typed where it does not. */
function Where({ level, setLevel, scopeId, setScopeId }: { level: string; setLevel(l: string): void; scopeId: string; setScopeId(s: string): void }) {
  const id = useId();
  const { holdings, companies, subBrands } = useHierarchy();
  const options: [string, string][] =
    level === "holding"
      ? (holdings.data?.holdings ?? []).map((h) => [h.holdingId, h.name])
      : level === "company"
        ? (companies.data?.companies ?? []).map((c) => [c.companyId, c.name])
        : level === "sub_brand"
          ? (subBrands.data?.subBrands ?? []).map((s) => [subBrandIdOf(s), s.name])
          : [];
  return (
    <>
      <div className="bo-field">
        <label htmlFor={`${id}-level`}>{t("team.invite.level")}</label>
        <select
          id={`${id}-level`}
          value={level}
          onChange={(e) => {
            setLevel(e.target.value);
            setScopeId("");
          }}
        >
          {ScopeLevels.map((l) => (
            <option key={l} value={l}>
              {word("team.level", l)}
            </option>
          ))}
        </select>
      </div>
      {level === "platform" ? null : (
        <div className="bo-field">
          <label htmlFor={`${id}-at`}>{t("team.invite.at", { kind: word("team.level", level) })}</label>
          {options.length > 0 ? (
            <select id={`${id}-at`} value={scopeId} onChange={(e) => setScopeId(e.target.value)}>
              <option value="">{t("team.invite.choose")}</option>
              {options.map(([value, name]) => (
                <option key={value} value={value}>
                  {name}
                </option>
              ))}
            </select>
          ) : (
            <input id={`${id}-at`} className="bo-input" value={scopeId} onChange={(e) => setScopeId(e.target.value)} aria-describedby={`${id}-at-hint`} />
          )}
          {options.length > 0 ? null : (
            <span id={`${id}-at-hint`} className="bo-field-hint">
              {t("team.invite.atHint")}
            </span>
          )}
        </div>
      )}
    </>
  );
}

function Invite({ done }: { done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const id = useId();
  const choices = rolesFor(me.roles);
  const [email, setEmail] = useState("");
  const [first, setFirst] = useState("");
  const [last, setLast] = useState("");
  const [role, setRole] = useState(() => [...choices].reverse().find((c) => c.mayGive)?.role ?? "");
  const [level, setLevel] = useState<string>("sub_brand");
  const [scopeId, setScopeId] = useState("");
  // merchant checks that a company or sub-brand exists, and refuses one it does not know; a holding it does not
  const ready = /^[^\s@]+@[^\s@]+$/.test(email.trim()) && role !== "" && (level !== "holding" || scopeId.trim() !== "");
  return (
    <Action
      action={Actions["operator.invite"]}
      me={me}
      bags={bags}
      ready={ready}
      subject={
        <>
          <div className="bo-field">
            <label htmlFor={`${id}-email`}>{t("team.invite.email")}</label>
            <input id={`${id}-email`} className="bo-input" type="email" autoComplete="off" value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="bo-field">
            <label htmlFor={`${id}-first`}>{t("team.invite.firstName")}</label>
            <input id={`${id}-first`} className="bo-input" autoComplete="off" value={first} onChange={(e) => setFirst(e.target.value)} />
          </div>
          <div className="bo-field">
            <label htmlFor={`${id}-last`}>{t("team.invite.lastName")}</label>
            <input id={`${id}-last`} className="bo-input" autoComplete="off" value={last} onChange={(e) => setLast(e.target.value)} />
          </div>
          <div className="bo-field">
            <label htmlFor={`${id}-role`}>{t("team.invite.role")}</label>
            <select id={`${id}-role`} value={role} onChange={(e) => setRole(e.target.value)} aria-describedby={`${id}-role-hint`}>
              {choices.map((c) => (
                <option key={c.role} value={c.role} disabled={!c.mayGive}>
                  {c.mayGive ? word("team.role", c.role) : t("team.invite.roleAbove", { role: word("team.role", c.role) })}
                </option>
              ))}
            </select>
            <span id={`${id}-role-hint`} className="bo-field-hint">
              {choices.some((c) => c.mayGive) ? t("team.invite.roleHint") : t("team.invite.noRole")}
            </span>
          </div>
          <Where level={level} setLevel={setLevel} scopeId={scopeId} setScopeId={setScopeId} />
        </>
      }
      run={() =>
        merchant.postOperators({
          body: {
            email: email.trim(),
            role,
            scopeLevel: level,
            scopeId: level === "platform" ? "*" : scopeId.trim(),
            ...(first.trim() ? { firstName: first.trim() } : {}),
            ...(last.trim() ? { lastName: last.trim() } : {}),
          },
        })
      }
      onDone={() => {
        setEmail("");
        setFirst("");
        setLast("");
        done();
      }}
    />
  );
}

function Rescope({ o, done }: { o: PaymentsOperator; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const [level, setLevel] = useState(o.scopeLevel || "sub_brand");
  const [scopeId, setScopeId] = useState(o.scopeId === "*" ? "" : o.scopeId);
  return (
    <Action
      action={Actions["operator.rescope"]}
      me={me}
      bags={bags}
      ready={level === "platform" || scopeId.trim() !== ""}
      subject={
        <>
          <p>{t("team.operators.subject", { email: o.email })}</p>
          <Where level={level} setLevel={setLevel} scopeId={scopeId} setScopeId={setScopeId} />
        </>
      }
      run={() => merchant.putOperatorsScope({ id: o.operatorId }, { body: { scopeLevel: level, scopeId: level === "platform" ? "*" : scopeId.trim() } })}
      onDone={done}
    />
  );
}

export function OperatorsPage({ onRoles }: { onRoles?(operator: string): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const list = useListState(useRouterListStore());
  const page = list.state.page;
  const q = useQuery({
    queryKey: ["payments", "operators", page],
    queryFn: () => read<Page>(merchant.getOperators({ query: { page: String(page), pageSize: String(PageSize) } })),
  });
  const done = () => void q.refetch();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const levelOf = (o: PaymentsOperator) => (o.scopeLevel === "platform" ? word("team.level", "platform") : t("team.operators.levelAt", { kind: word("team.level", o.scopeLevel), id: o.scopeId }));
  const columns: Column<PaymentsOperator>[] = [
    { id: "email", header: "team.operators.email", cell: (o) => o.email },
    { id: "name", header: "team.operators.name", cell: (o) => [o.firstName, o.lastName].filter(Boolean).join(" ") },
    { id: "role", header: "team.operators.role", cell: (o) => o.roles.map((r) => word("team.role", r)).join(", ") },
    { id: "level", header: "team.operators.level", cell: levelOf },
    { id: "state", header: "team.operators.state", cell: (o) => <Status value={o.pending ? "pending" : o.enabled ? "active" : "disabled"} /> },
    ...(readOnly
      ? []
      : [
          {
            id: "act",
            header: "payments.act" as const,
            cell: (o: PaymentsOperator) => {
              const subject = <p>{t("team.operators.subject", { email: o.email })}</p>;
              const state = o.pending ? "pending" : "accepted";
              return (
                <span className="bo-actions">
                  <Action action={Actions["operator.resend"]} me={me} bags={bags} state={state} subject={subject} run={() => merchant.postOperatorsResendInvitation({ id: o.operatorId })} onDone={done} />
                  <Action action={Actions["operator.reset"]} me={me} bags={bags} state={state} subject={subject} run={() => merchant.postOperatorsResetPassword({ id: o.operatorId })} onDone={done} />
                  <Rescope o={o} done={done} />
                  <Action action={Actions["operator.revoke"]} me={me} bags={bags} subject={subject} run={() => merchant.deleteOperatorsById({ id: o.operatorId })} onDone={done} />
                  {onRoles ? (
                    <button type="button" className="bo-button" onClick={() => onRoles(o.operatorId)}>
                      {t("team.operators.brandRoles")}
                    </button>
                  ) : null}
                </span>
              );
            },
          },
        ]),
  ];
  return (
    <section aria-labelledby="team-operators">
      <h2 id="team-operators">{t("team.operators.title")}</h2>
      <p className="bo-muted">{t("team.operators.note", { value: paymentsLevel(me.roles) })}</p>
      {readOnly ? null : (
        <div className="bo-actions">
          <Invite done={done} />
        </div>
      )}
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      <List
        caption="team.operators.caption"
        columns={columns}
        rows={q.data?.operators}
        rowKey={(o) => o.operatorId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={zone}
        list={list}
      />
    </section>
  );
}
