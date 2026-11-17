// The brand's roles (T054, FR-008b, FR-008d): each with what it carries; a role created from
// permissions grouped by area with their descriptions; changed, reset and deleted, each stating before
// it is confirmed who gains or loses what; the owner's refusals shown with their reasons. players
// decides every rule (contracts/roles.md); nothing here is refused that players would admit.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { RoleBags } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { DefaultBags, describePermission, difference, PermissionGroups } from "../../../shared/permissions.ts";
import { refused } from "../../../shared/refusals.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";

export interface Role {
  name: string;
  description: string;
  permissions: string[];
  default: boolean;
}

/** A write to players' roles through the page's server, as the signed-in operator. */
export function send(method: string, path: string, body?: unknown, fetchFn: typeof fetch = fetch): Promise<Response> {
  return fetchFn(`/api/s/players${path}`, {
    method,
    headers: { "Content-Type": "application/json", "X-Backoffice": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function readRoles(fetchFn: typeof fetch = fetch): Promise<Role[]> {
  const r = await fetchFn("/api/s/players/roles", { headers: { Accept: "application/json" } });
  if (!r.ok) throw refused(r.status, await r.text());
  return ((await r.json()) as { roles: Role[] }).roles;
}

export const bagsOf = (roles: readonly Role[]): RoleBags => Object.fromEntries(roles.map((r) => [r.name, r.permissions]));

export function useRoles() {
  return useQuery({ queryKey: ["roles"], queryFn: () => readRoles(), staleTime: 5_000 });
}

/** Who gains and who loses what, said before a change is confirmed. */
export function Change({ role, before, after }: { role: string; before: readonly string[]; after: readonly string[] }) {
  const { gained, lost } = difference(before, after);
  if (gained.length === 0 && lost.length === 0) return <p>{t("roles.nothingChanges")}</p>;
  return (
    <div>
      {gained.length > 0 ? <p data-gains>{t("roles.gains", { role, permissions: gained.join(", ") })}</p> : null}
      {lost.length > 0 ? <p data-loses>{t("roles.loses", { role, permissions: lost.join(", ") })}</p> : null}
    </div>
  );
}

function Permissions({ chosen, onChange, disabled }: { chosen: Set<string>; onChange(next: Set<string>): void; disabled: boolean }) {
  return (
    <>
      {PermissionGroups.map((g) => (
        <fieldset key={g.label} className="bo-permissions">
          <legend>{t(g.label)}</legend>
          {g.permissions.map((p) => (
            <label key={p}>
              <input
                type="checkbox"
                name={p}
                disabled={disabled}
                checked={chosen.has(p)}
                onChange={() => {
                  const next = new Set(chosen);
                  if (next.has(p)) next.delete(p);
                  else next.add(p);
                  onChange(next);
                }}
              />
              <span>{t(describePermission(p))}</span> <code className="bo-muted">{p}</code>
            </label>
          ))}
        </fieldset>
      ))}
    </>
  );
}

/** A new role, or one of the brand's roles changed, reset or deleted. */
export function RoleEditor({ role, bags, onDone, onClose }: { role?: Role; bags: RoleBags; onDone(): void; onClose(): void }) {
  const me = useOperator();
  const readOnly = useReadOnlyMode();
  const nameId = useId();
  const descriptionId = useId();
  const [name, setName] = useState(role?.name ?? "");
  const [description, setDescription] = useState(role?.description ?? "");
  const [chosen, setChosen] = useState(() => new Set(role?.permissions ?? []));
  const after = [...chosen].sort();
  const editable = !readOnly && me.permissions.includes("roles.change");
  const target = role?.name ?? name.trim();
  // players' PUT makes a role or replaces what an existing one carries, so a new role may not take a name in use
  const taken = role === undefined && Object.hasOwn(bags, target);
  const put = () => send("PUT", `/roles/${encodeURIComponent(target)}`, { description, permissions: after });

  return (
    <section className="bo-editor" aria-labelledby={nameId}>
      <div className="bo-field">
        <label htmlFor={nameId}>{t("roles.name")}</label>
        {role ? (
          <output id={nameId}>{role.name}</output>
        ) : (
          <>
            <input id={nameId} value={name} disabled={!editable} onChange={(e) => setName(e.target.value)} aria-describedby={`${nameId}-hint`} />
            <span id={`${nameId}-hint`} className="bo-muted">
              {t("roles.nameHint")}
            </span>
            {taken ? <span className="bo-field-error">{t("roles.nameTaken", { role: target })}</span> : null}
          </>
        )}
      </div>
      <div className="bo-field">
        <label htmlFor={descriptionId}>{t("roles.description")}</label>
        <input id={descriptionId} value={description} maxLength={200} disabled={!editable} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <Permissions chosen={chosen} onChange={setChosen} disabled={!editable} />
      {readOnly ? null : (
        <div className="bo-actions">
          {role ? (
            <>
              <Action action={Actions["role.change"]} me={me} bags={bags} subject={<Change role={role.name} before={role.permissions} after={after} />} run={put} onDone={onDone} />
              {DefaultBags[role.name] ? (
                <Action
                  action={Actions["role.reset"]}
                  me={me}
                  bags={bags}
                  state="default"
                  subject={<Change role={role.name} before={role.permissions} after={DefaultBags[role.name]!} />}
                  run={() => send("POST", `/roles/${encodeURIComponent(role.name)}/reset`)}
                  onDone={onDone}
                />
              ) : null}
              <Action
                action={Actions["role.delete"]}
                me={me}
                bags={bags}
                state={role.default ? "default" : "own"}
                subject={<Change role={role.name} before={role.permissions} after={[]} />}
                run={() => send("DELETE", `/roles/${encodeURIComponent(role.name)}`)}
                onDone={onDone}
              />
            </>
          ) : (
            <Action action={Actions["role.create"]} me={me} bags={bags} ready={target !== "" && !taken} subject={<Change role={target} before={[]} after={after} />} run={put} onDone={onDone} />
          )}
          <button type="button" className="bo-button" onClick={onClose}>
            {t("roles.close")}
          </button>
        </div>
      )}
    </section>
  );
}

export function RolesPage() {
  const roles = useRoles();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const me = useOperator();
  const [open, setOpen] = useState<string | "new" | undefined>(undefined);
  const done = () => {
    setOpen(undefined);
    void client.invalidateQueries({ queryKey: ["roles"] });
    void client.invalidateQueries({ queryKey: ["me"] });
    void client.invalidateQueries({ queryKey: ["permissions"] });
  };

  if (roles.isError) return <RefusalNotice refusal={roles.error} />;
  if (!roles.data) return <p role="status">{t("roles.loading")}</p>;
  const bags = bagsOf(roles.data);
  const editing = roles.data.find((r) => r.name === open);

  return (
    <section>
      <h2>{t("roles.title")}</h2>
      <table className="bo-table">
        <thead>
          <tr>
            <th scope="col">{t("roles.name")}</th>
            <th scope="col">{t("roles.description")}</th>
            <th scope="col">{t("roles.carries")}</th>
            <th scope="col">
              <span className="bo-sr-only">{t("roles.edit")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {roles.data.map((r) => (
            <tr key={r.name}>
              <th scope="row">
                {r.name} <span className="bo-muted">{t(r.default ? "roles.default" : "roles.own")}</span>
              </th>
              <td>{r.description}</td>
              <td title={r.permissions.join(", ")}>{t("roles.count", { count: r.permissions.length })}</td>
              <td>
                <button type="button" className="bo-button" aria-expanded={open === r.name} onClick={() => setOpen(open === r.name ? undefined : r.name)}>
                  {t("roles.edit")}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!readOnly && me.permissions.includes("roles.change") ? (
        <p>
          <button type="button" className="bo-button" aria-expanded={open === "new"} onClick={() => setOpen("new")}>
            {t("roles.new")}
          </button>
        </p>
      ) : null}
      {open === "new" ? <RoleEditor key="new" bags={bags} onDone={done} onClose={() => setOpen(undefined)} /> : null}
      {editing ? <RoleEditor key={editing.name} role={editing} bags={bags} onDone={done} onClose={() => setOpen(undefined)} /> : null}
    </section>
  );
}
