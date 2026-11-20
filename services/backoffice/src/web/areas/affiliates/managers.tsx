// The programme's managers at the brand (parity *managers*; the programme's O1 to O3 and its revoke):
// the realm's users holding affiliate_manager and the brand's role. A new manager, and a reset, are
// sent a link to set their own password by email; the backoffice never sees, sets or shows one.
// Revoking takes the brand's role, and the manager role with it when no other brand's is left. The
// programme admits brand_admin and platform_admin here (`programme.managers`).
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Read } from "./parts.tsx";
import { json, type Manager } from "./programme.ts";

export function ManagersSection() {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const ids = { email: useId(), name: useId() };
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const q = useQuery({ queryKey: ["programme", "managers"], queryFn: () => json<Manager[]>(programme.getManagers()) });
  const done = () => void client.invalidateQueries({ queryKey: ["programme", "managers"] });
  return (
    <div>
      <h2>{t("affiliates.section.managers")}</h2>
      <Read q={q}>
        {(rows) =>
          rows.length === 0 ? (
            <p className="bo-muted">{t("affiliates.manager.none")}</p>
          ) : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.section.managers")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.manager.name")}</th>
                  <th scope="col">{t("affiliates.email")}</th>
                  <th scope="col">{t("affiliates.manager.state")}</th>
                  <th scope="col">
                    <span className="bo-sr-only">{t("affiliates.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((m) => (
                  <tr key={m.keycloakUserId} data-manager={m.keycloakUserId}>
                    <th scope="row">{m.name}</th>
                    <td>{m.email}</td>
                    <td>
                      <Status value={m.pending ? "pending" : "active"} />
                    </td>
                    <td>
                      {readOnly ? null : (
                        <div className="bo-aff-actions">
                          <Action
                            action={Actions["programme.manager.reset"]}
                            me={me}
                            bags={bags}
                            subject={<p>{t("affiliates.manager.resetTo", { name: m.name, email: m.email })}</p>}
                            run={() => programme.postManagersResetPassword({ user: m.keycloakUserId })}
                            onDone={done}
                          />
                          <Action
                            action={Actions["programme.manager.revoke"]}
                            me={me}
                            bags={bags}
                            subject={<p>{m.name}</p>}
                            run={() => programme.deleteManagersByUser({ user: m.keycloakUserId })}
                            onDone={done}
                          />
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </Read>
      {readOnly ? null : (
        <>
          <h3>{t("affiliates.manager.new")}</h3>
          <div className="bo-aff-form">
            <div className="bo-field">
              <label htmlFor={ids.email}>{t("affiliates.email")}</label>
              <input id={ids.email} type="email" autoComplete="off" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="bo-field">
              <label htmlFor={ids.name}>{t("affiliates.manager.name")}</label>
              <input id={ids.name} value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="bo-aff-actions bo-field-wide">
              <Action
                action={Actions["programme.manager.add"]}
                me={me}
                bags={bags}
                ready={/^\S+@\S+$/.test(email.trim())}
                subject={<p>{t("affiliates.manager.inviting", { email: email.trim() })}</p>}
                run={() => programme.postManagers({ body: { email: email.trim(), ...(name.trim() ? { name: name.trim() } : {}) } })}
                onDone={() => {
                  setEmail("");
                  setName("");
                  done();
                }}
              />
            </div>
          </div>
        </>
      )}
    </div>
  );
}
