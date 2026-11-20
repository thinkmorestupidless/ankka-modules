// A player's identity (T095; US7, parity *Documents*, *Verification mode, status, required documents,
// override, source of funds, dismiss a flag*): kyc's read of the player, each document viewed through
// the server's document route (its ids the only input, FR-031) and approved, or rejected with one of
// kyc's reason codes; the verification mode, the documents asked of the player, the source of funds,
// the flags, and the final decision. Each action states what it changes for the player before it is
// confirmed, and is offered only in the states kyc accepts it in. kyc decides every rule. Below them,
// kyc's decisions on the player in order, each with who made it and when (amendment K3).
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { kyc } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Categories, documentHref, readDecisions, readIdentity, ReasonCodes, type KycDecision, type KycDocument, type PlayerIdentity as Identity } from "../identity/kyc.ts";
import { word } from "../word.ts";

const body = (b: unknown) => ({ body: b });

/** A document's row: what it is, its review, and the actions its state allows. */
function DocumentRow({ player, category, required, document, done }: { player: string; category: string; required: string; document: KycDocument | null | undefined; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const codeId = useId();
  const [code, setCode] = useState("");
  const tz = useZone();
  const d = document;
  return (
    <tr data-category={category} data-document={d?.documentId}>
      <th scope="row">{word("identity.category", category)}</th>
      <td>{required}</td>
      <td>{d ? <Status value={d.status} /> : <span className="bo-muted">{t("identity.document.none")}</span>}</td>
      <td>{d ? <DateTime instant={d.uploadedAt} zone={tz} /> : null}</td>
      <td>{d ? word("identity.submittedBy", d.submittedBy) : null}</td>
      <td>
        {d?.reviewedAt ? (
          <>
            <DateTime instant={d.reviewedAt} zone={tz} />
            {d.reviewedBy ? <span> {t("identity.document.by", { operator: d.reviewedBy })}</span> : null}
            {d.reasonCode ? <span> · {word("identity.reason", d.reasonCode)}</span> : null}
          </>
        ) : null}
      </td>
      <td>
        {d ? (
          <div className="bo-actions">
            <a className="bo-link" href={documentHref(player, d.documentId)} target="_blank" rel="noopener noreferrer">
              {t("identity.document.view")}
            </a>
            {readOnly ? null : (
              <>
                <Action
                  action={Actions["identity.approveDocument"]}
                  me={me}
                  bags={bags}
                  state={d.status}
                  subject={<p>{word("identity.category", category)}</p>}
                  run={() => kyc.postReviewsByDocumentId({ playerId: player, documentId: d.documentId }, body({ outcome: "approved" }))}
                  onDone={done}
                />
                <Action
                  action={Actions["identity.rejectDocument"]}
                  me={me}
                  bags={bags}
                  state={d.status}
                  ready={code !== ""}
                  subject={
                    <div className="bo-field">
                      <label htmlFor={codeId}>{t("identity.document.rejectReason")}</label>
                      <select id={codeId} value={code} required onChange={(e) => setCode(e.target.value)}>
                        <option value="">{t("identity.document.chooseReason")}</option>
                        {ReasonCodes.map((c) => (
                          <option key={c} value={c}>
                            {word("identity.reason", c)}
                          </option>
                        ))}
                      </select>
                    </div>
                  }
                  run={() => kyc.postReviewsByDocumentId({ playerId: player, documentId: d.documentId }, body({ outcome: "rejected", reasonCode: code }))}
                  onDone={() => {
                    setCode("");
                    done();
                  }}
                />
              </>
            )}
          </div>
        ) : null}
      </td>
    </tr>
  );
}

function Documents({ identity, done }: { identity: Identity; done(): void }) {
  const rows = identity.categories.filter((c) => c.category !== "other");
  const required = (c: Identity["categories"][number]) =>
    [c.requiredByPolicy ? t("identity.required.byPolicy") : null, c.requiredOfPlayer ? t("identity.required.ofPlayer") : null].filter(Boolean).join(", ") || t("identity.required.no");
  return (
    <section aria-labelledby="identity-documents">
      <h3 id="identity-documents">{t("identity.documents")}</h3>
      <table className="bo-table">
        <thead>
          <tr>
            <th scope="col">{t("identity.document.category")}</th>
            <th scope="col">{t("identity.document.required")}</th>
            <th scope="col">{t("identity.document.status")}</th>
            <th scope="col">{t("identity.document.uploaded")}</th>
            <th scope="col">{t("identity.document.submittedBy")}</th>
            <th scope="col">{t("identity.document.reviewed")}</th>
            <th scope="col">{t("identity.document.actions")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <DocumentRow key={c.category} player={identity.playerId} category={c.category} required={required(c)} document={c.document} done={done} />
          ))}
          {(identity.other ?? []).map((d) => (
            <DocumentRow key={d.documentId} player={identity.playerId} category="other" required={t("identity.required.no")} document={d} done={done} />
          ))}
        </tbody>
      </table>
      {identity.categories.every((c) => c.document === undefined) ? <p className="bo-muted">{t("identity.documents.hidden")}</p> : null}
    </section>
  );
}

/** The documents asked of this player beyond the policy's; any puts the player in manual mode, pending. */
function Required({ identity, done }: { identity: Identity; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const [chosen, setChosen] = useState(() => new Set(identity.requiredOfPlayer));
  const after = Categories.filter((c) => chosen.has(c));
  return (
    <fieldset className="bo-permissions">
      <legend>{t("identity.required.title")}</legend>
      {Categories.map((c) => (
        <label key={c}>
          <input
            type="checkbox"
            name={c}
            checked={chosen.has(c)}
            onChange={() => {
              const next = new Set(chosen);
              if (next.has(c)) next.delete(c);
              else next.add(c);
              setChosen(next);
            }}
          />
          <span>{word("identity.category", c)}</span>
        </label>
      ))}
      <Action
        action={Actions["identity.setRequired"]}
        me={me}
        bags={bags}
        subject={<p>{after.length === 0 ? t("identity.required.none") : t("identity.required.these", { categories: after.map((c) => word("identity.category", c)).join(", ") })}</p>}
        run={() => kyc.putRequired({ playerId: identity.playerId }, body({ categories: after }))}
        onDone={done}
      />
    </fieldset>
  );
}

function Flags({ identity, done }: { identity: Identity; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  if (identity.flags.length === 0) return <p className="bo-muted">{t("identity.flags.none")}</p>;
  return (
    <table className="bo-table">
      <caption className="bo-sr-only">{t("identity.flags")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("identity.flags.reason")}</th>
          <th scope="col">{t("identity.flags.raised")}</th>
          <th scope="col">{t("identity.flags.state")}</th>
          <th scope="col">{t("identity.document.actions")}</th>
        </tr>
      </thead>
      <tbody>
        {identity.flags.map((f) => (
          <tr key={`${f.reason}-${f.at}`} data-flag={f.reason}>
            <th scope="row">{word("identity.flag", f.reason)}</th>
            <td>
              <DateTime instant={f.at} zone={tz} />
            </td>
            <td>
              {f.open ? (
                <Status value="open" />
              ) : f.dismissedAt ? (
                <span>
                  {t("identity.flags.dismissed", { operator: f.dismissedBy ?? "" })} <DateTime instant={f.dismissedAt} zone={tz} />
                </span>
              ) : (
                <span>{t("identity.flags.closed")}</span>
              )}
            </td>
            <td>
              {readOnly ? null : (
                <Action
                  action={Actions["identity.dismissFlag"]}
                  me={me}
                  bags={bags}
                  state={f.open ? "open" : "closed"}
                  subject={<p>{word("identity.flag", f.reason)}</p>}
                  run={(note) => kyc.postFlagsDismiss({ playerId: identity.playerId, reason: f.reason }, body({ note }))}
                  onDone={done}
                />
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** A decision's value in the catalogue's words: a mode, a status, or a list of categories. */
function valueOf(kind: string, v: string | string[] | undefined): string {
  if (v === undefined) return "";
  if (Array.isArray(v)) return v.length === 0 ? t("identity.missing.none") : v.map((c) => word("identity.category", c)).join(", ");
  if (kind === "mode_changed") return t(v === "manual" ? "identity.mode.manual" : "identity.mode.automatic");
  if (kind === "sof_cleared" || kind === "sof_reset") return word("identity.sof", v);
  return word("status", v);
}

/** What a decision changed, beyond its kind. */
function detailOf(d: KycDecision): string {
  switch (d.kind) {
    case "document_reviewed":
      return [d.category ? word("identity.category", d.category) : "", d.outcome ? word("status", d.outcome) : "", d.reasonCode ? word("identity.reason", d.reasonCode) : ""]
        .filter(Boolean)
        .join(", ");
    case "flag_dismissed":
      return d.reasonCode ? word("identity.flag", d.reasonCode) : "";
    case "sof_cleared":
    case "sof_reset":
      return valueOf(d.kind, d.to);
    default:
      return d.from !== undefined && d.to !== undefined ? t("identity.decisions.change", { from: valueOf(d.kind, d.from), to: valueOf(d.kind, d.to) }) : "";
  }
}

function actorOf(d: KycDecision): string {
  if (d.actor.kind === "operator") return d.actor.id ?? word("identity.decisions.actor", "operator");
  return word("identity.decisions.actor", d.actor.kind);
}

/** kyc's decisions on the player, oldest first, each with who made it and when (amendment K3). */
function Decisions({ player }: { player: string }) {
  const tz = useZone();
  const q = useQuery({ queryKey: ["kyc", "decisions", player], queryFn: () => readDecisions(player) });
  if (q.isError) return <RefusalNotice refusal={q.error} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  if (q.data.length === 0) return <p className="bo-muted">{t("identity.decisions.none")}</p>;
  return (
    <table className="bo-table">
      <caption className="bo-sr-only">{t("identity.decisions")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("identity.decisions.at")}</th>
          <th scope="col">{t("identity.decisions.what")}</th>
          <th scope="col">{t("identity.decisions.detail")}</th>
          <th scope="col">{t("identity.decisions.by")}</th>
        </tr>
      </thead>
      <tbody>
        {q.data.map((d, i) => (
          <tr key={`${d.at}-${i}`} data-decision={d.kind}>
            <td>
              <DateTime instant={d.at} zone={tz} />
            </td>
            <th scope="row">{word("identity.decisions.kind", d.kind)}</th>
            <td>{detailOf(d)}</td>
            <td>{actorOf(d)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function PlayerIdentity({ player }: { player: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const tz = useZone();
  const q = useQuery({ queryKey: ["kyc", "identity", player], queryFn: () => readIdentity(player) });
  const done = () => {
    void client.invalidateQueries({ queryKey: ["kyc"] });
  };

  if (q.isError) return <RefusalNotice refusal={q.error} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const id = q.data;
  const deposits = Object.entries(id.depositsByCurrency ?? {});
  const decide = (status: string, action: keyof typeof Actions, run: (reason: string | undefined) => Promise<Response>) => (
    <Action action={Actions[action]} me={me} bags={bags} state={id.status} subject={<p>{t("identity.decision.from", { from: word("status", id.status), to: word("status", status) })}</p>} run={run} onDone={done} />
  );

  return (
    <section data-identity={player}>
      <h2>{t("identity.player.title")}</h2>
      <ZoneNote zone={tz} />
      <dl className="bo-facts">
        <dt>{t("identity.status")}</dt>
        <dd>
          <Status value={id.status} />
        </dd>
        <dt>{t("identity.mode")}</dt>
        <dd>{t(id.mode === "manual" ? "identity.mode.manual" : "identity.mode.automatic")}</dd>
        <dt>{t("identity.ready")}</dt>
        <dd>{id.readyAt ? <DateTime instant={id.readyAt} zone={tz} /> : t("identity.ready.not")}</dd>
        <dt>{t("identity.sof")}</dt>
        <dd>{t(id.sof === "cleared" ? "identity.sof.cleared" : "identity.sof.unverified")}</dd>
        <dt>{t("identity.deadline")}</dt>
        <dd>{id.deadline ? <DateTime instant={id.deadline.at} zone={tz} /> : t("identity.deadline.none")}</dd>
        <dt>{t("identity.missing")}</dt>
        <dd>{id.missingProfileFields.length === 0 ? t("identity.missing.none") : id.missingProfileFields.join(", ")}</dd>
        <dt>{t("identity.deposits")}</dt>
        <dd>
          {deposits.length === 0
            ? t("identity.deposits.none")
            : deposits.map(([currency, minor], i) => (
                <span key={currency}>
                  {i > 0 ? ", " : null}
                  <Money value={{ minor, currency }} />
                </span>
              ))}
        </dd>
      </dl>

      {readOnly ? null : (
        <section aria-labelledby="identity-decision">
          <h3 id="identity-decision">{t("identity.decision")}</h3>
          <div className="bo-actions">
            {decide("pending", "identity.markPending", () => kyc.putStatus({ playerId: player }, body({ status: "pending" })))}
            {decide("verified", "identity.verify", () => kyc.putStatus({ playerId: player }, body({ status: "verified" })))}
            {decide("verified", "identity.overrideVerify", (reason) => kyc.postOverride({ playerId: player }, body({ status: "verified", ...(reason ? { reason } : {}) })))}
            {decide("rejected", "identity.overrideReject", (reason) => kyc.postOverride({ playerId: player }, body({ status: "rejected", ...(reason ? { reason } : {}) })))}
          </div>
          <div className="bo-actions">
            <Action action={Actions["identity.setManual"]} me={me} bags={bags} state={id.mode} run={() => kyc.putMode({ playerId: player }, body({ mode: "manual" }))} onDone={done} />
            <Action action={Actions["identity.setAutomatic"]} me={me} bags={bags} state={id.mode} run={() => kyc.putMode({ playerId: player }, body({ mode: "automatic" }))} onDone={done} />
            <Action action={Actions["identity.clearSof"]} me={me} bags={bags} state={id.sof} run={() => kyc.postSof({ playerId: player }, body({ status: "cleared" }))} onDone={done} />
            <Action action={Actions["identity.resetSof"]} me={me} bags={bags} state={id.sof} run={() => kyc.postSof({ playerId: player }, body({ status: "unverified" }))} onDone={done} />
          </div>
        </section>
      )}

      <Documents identity={id} done={done} />

      {readOnly ? null : <Required key={id.requiredOfPlayer.join(",")} identity={id} done={done} />}

      <section aria-labelledby="identity-flags">
        <h3 id="identity-flags">{t("identity.flags")}</h3>
        <Flags identity={id} done={done} />
      </section>

      <section aria-labelledby="identity-decisions">
        <h3 id="identity-decisions">{t("identity.decisions")}</h3>
        <Decisions player={player} />
      </section>
    </section>
  );
}
