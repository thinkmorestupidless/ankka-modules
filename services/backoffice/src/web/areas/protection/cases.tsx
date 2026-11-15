// Responsible gambling cases (T096; US7, parity *Cases list; notes; close*). A player's cases come with
// responsible-gambling's read of the player: each with its notes, a note added, and closed with an
// optional note; a case opened by the operator. The brand-wide list of open cases is responsible-
// gambling's `cases?status=` (amendment G2), which it does not answer yet: the `/protection` page says
// so and opens a player's cases by id until it does.
import { useId, useState } from "react";
import { useRouter } from "@tanstack/react-router";
import { t } from "../../../shared/messages/index.ts";
import { responsibleGambling as rg } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { DateTime } from "../../components/date.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { CaseTypes, type Case } from "./rg.ts";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

function CaseRow({ player, c, done }: { player: string; c: Case; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = zone();
  return (
    <tr data-case={c.caseId}>
      <th scope="row">{word("protection.case", c.caseType)}</th>
      <td>
        <Status value={c.status} />
      </td>
      <td>{word("protection.trigger", c.trigger)}</td>
      <td>
        <DateTime instant={c.openedAt} zone={tz} />
      </td>
      <td>{c.closedAt ? <DateTime instant={c.closedAt} zone={tz} /> : c.closesAt ? <span>{t("protection.case.closes")} <DateTime instant={c.closesAt} zone={tz} /></span> : null}</td>
      <td>
        {c.notes.length === 0 && !c.closeNote ? <span className="bo-muted">{t("protection.case.noNotes")}</span> : null}
        <ul className="bo-notes">
          {c.notes.map((n, i) => (
            <li key={`${n.at}-${i}`}>
              <DateTime instant={n.at} zone={tz} /> <span>{t("protection.case.noteBy", { operator: n.operatorId })}</span> <span>{n.text}</span>
            </li>
          ))}
          {c.closeNote ? <li>{t("protection.case.closeNote", { note: c.closeNote })}</li> : null}
        </ul>
      </td>
      <td>
        {readOnly ? null : (
          <div className="bo-actions">
            <Action action={Actions["protection.noteCase"]} me={me} bags={bags} state={c.status} run={(note) => rg.postCasesNotes({ player, caseId: c.caseId }, { body: { note } })} onDone={done} />
            <Action
              action={Actions["protection.closeCase"]}
              me={me}
              bags={bags}
              state={c.status}
              subject={<p>{word("protection.case", c.caseType)}</p>}
              run={(reason) => rg.postCasesClose({ player, caseId: c.caseId }, { body: reason ? { reason } : {} })}
              onDone={done}
            />
          </div>
        )}
      </td>
    </tr>
  );
}

/** A player's cases, newest first as opened, with a case opened by the operator. */
export function PlayerCases({ player, cases, done }: { player: string; cases: readonly Case[]; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const typeId = useId();
  const [caseType, setCaseType] = useState("");
  const shown = [...cases].sort((a, b) => b.openedAt.localeCompare(a.openedAt));
  return (
    <section aria-labelledby="protection-cases">
      <h3 id="protection-cases">{t("protection.cases")}</h3>
      {shown.length === 0 ? (
        <p className="bo-muted">{t("protection.cases.none")}</p>
      ) : (
        <table className="bo-table">
          <thead>
            <tr>
              <th scope="col">{t("protection.case.type")}</th>
              <th scope="col">{t("protection.case.status")}</th>
              <th scope="col">{t("protection.case.trigger")}</th>
              <th scope="col">{t("protection.case.opened")}</th>
              <th scope="col">{t("protection.case.closed")}</th>
              <th scope="col">{t("protection.case.notes")}</th>
              <th scope="col">{t("identity.document.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((c) => (
              <CaseRow key={c.caseId} player={player} c={c} done={done} />
            ))}
          </tbody>
        </table>
      )}
      {readOnly ? null : (
        <Action
          action={Actions["protection.openCase"]}
          me={me}
          bags={bags}
          ready={caseType !== ""}
          subject={
            <div className="bo-field">
              <label htmlFor={typeId}>{t("protection.case.type")}</label>
              <select id={typeId} value={caseType} onChange={(e) => setCaseType(e.target.value)}>
                <option value="">{t("protection.case.choose")}</option>
                {CaseTypes.map((c) => (
                  <option key={c} value={c}>
                    {word("protection.case", c)}
                  </option>
                ))}
              </select>
            </div>
          }
          run={(note) => rg.postCases({ player }, { body: { caseType, ...(note ? { note } : {}) } })}
          onDone={() => {
            setCaseType("");
            done();
          }}
        />
      )}
    </section>
  );
}

/** `/protection`: the brand-wide list waits on responsible-gambling (G2); a player's cases are opened by id. */
export function ProtectionCasesPage() {
  const router = useRouter();
  const playerId = useId();
  const [player, setPlayer] = useState("");
  const go = () => {
    const p = player.trim();
    if (p !== "") void router.navigate({ to: "/players/$player/protection", params: { player: p } } as never);
  };
  return (
    <section>
      <h1>{t("area.protection")}</h1>
      <p role="note">{t("protection.cases.brandWideGap")}</p>
      <form
        className="bo-field"
        onSubmit={(e) => {
          e.preventDefault();
          go();
        }}
      >
        <label htmlFor={playerId}>{t("protection.cases.player")}</label>
        <input id={playerId} value={player} onChange={(e) => setPlayer(e.target.value)} />
        <button type="submit" className="bo-button" disabled={player.trim() === ""}>
          {t("protection.cases.open")}
        </button>
      </form>
    </section>
  );
}
