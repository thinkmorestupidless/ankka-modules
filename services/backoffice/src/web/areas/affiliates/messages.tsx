// A message to affiliates (parity *messages*; the programme's O47): a notification in the portal to one
// affiliate of the brand, or to every affiliate active here, as the original's broadcast. The programme
// answers how many it reached.
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { useActiveAccounts } from "./links.tsx";
import { accountName } from "./programme.ts";

export function MessagesSection() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const accounts = useActiveAccounts();
  const ids = { to: useId(), body: useId() };
  const [to, setTo] = useState("");
  const [body, setBody] = useState("");
  const [sent, setSent] = useState<number | undefined>(undefined);
  const target = accounts.data?.items.find((r) => r.account.affiliateId === to);
  return (
    <div>
      <h2>{t("affiliates.section.messages")}</h2>
      <div className="bo-aff-form">
        <div className="bo-field">
          <label htmlFor={ids.to}>{t("affiliates.message.to")}</label>
          <select id={ids.to} value={to} disabled={readOnly} onChange={(e) => setTo(e.target.value)}>
            <option value="">{t("affiliates.message.everyone")}</option>
            {accounts.data?.items.map((r) => (
              <option key={r.account.affiliateId} value={r.account.affiliateId}>
                {accountName(r.account)}
              </option>
            ))}
          </select>
        </div>
        <div className="bo-field bo-field-wide">
          <label htmlFor={ids.body}>{t("affiliates.message.body")}</label>
          <textarea id={ids.body} rows={4} value={body} disabled={readOnly} onChange={(e) => setBody(e.target.value)} />
        </div>
        {readOnly ? null : (
          <div className="bo-aff-actions bo-field-wide">
            <Action
              action={Actions["programme.message.send"]}
              me={me}
              bags={bags}
              ready={body.trim() !== ""}
              subject={<p>{target ? t("affiliates.message.toOne", { name: accountName(target.account) }) : t("affiliates.message.toEveryone")}</p>}
              run={() => programme.postMessages({ body: { body: body.trim(), ...(to ? { targetAffiliateId: to } : {}) } })}
              onDone={(answer) =>
                void answer
                  .clone()
                  .json()
                  .then((a: { sent?: number }) => {
                    setSent(a.sent ?? 0);
                    setBody("");
                  })
                  .catch(() => setSent(undefined))
              }
            />
          </div>
        )}
      </div>
      {sent !== undefined ? <p role="status">{t("affiliates.message.sent", { count: sent })}</p> : null}
    </div>
  );
}
