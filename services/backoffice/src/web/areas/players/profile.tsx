// A player's profile (T077; FR-025, parity *Edit profile*, *Correct date of birth*, *Consents*): the
// fields players keeps, edited through players' own edit, which honours the player's locks; the date of
// birth corrected with a reason (refused once identity is verified: players' `kyc_locked`); and the
// marketing consents and message suppression. Each change states its effect before it is confirmed. An
// erased player has no personal data to show or edit.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { BirthDateInput } from "../../components/date-input.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";

/** What an operator may edit (OperatorProfileRoutes.OperatorFields). */
export const Editable = ["firstName", "lastName", "country", "addressLine1", "city", "stateProv", "postalCode", "phone"] as const;
type Field = (typeof Editable)[number];

interface Player {
  playerId: string;
  status?: string;
  dateOfBirth?: string;
  kycStatus?: string;
  [k: string]: unknown;
}

interface Crm {
  emailMarketingConsent: boolean;
  smsMarketingConsent: boolean;
  messageSuppressed: boolean;
}

const NewDateOfBirth: MessageKey = "profile.newDateOfBirth";
const Erased = new Set(["erased", "erasure_pending"]);

function Fields({ player, p, done }: { player: string; p: Player; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const base = useId();
  const initial = Object.fromEntries(Editable.map((f) => [f, typeof p[f] === "string" ? (p[f] as string) : ""])) as Record<Field, string>;
  const [values, setValues] = useState(initial);
  const changed = Editable.filter((f) => values[f] !== initial[f]);
  return (
    <form className="bo-profile-form" onSubmit={(e) => e.preventDefault()} aria-label={t("profile.fields")}>
      {Editable.map((f) => (
        <div key={f} className="bo-field">
          <label htmlFor={`${base}-${f}`}>{t(`profile.field.${f}` as MessageKey)}</label>
          <input id={`${base}-${f}`} className="bo-input" value={values[f]} disabled={readOnly} onChange={(e) => setValues({ ...values, [f]: e.target.value })} />
        </div>
      ))}
      {readOnly ? null : (
        <Action
          action={Actions["player.editProfile"]}
          me={me}
          bags={bags}
          ready={changed.length > 0}
          subject={changed.length > 0 ? <p>{t("profile.changing", { fields: changed.map((f) => t(`profile.field.${f}` as MessageKey)).join(", ") })}</p> : null}
          run={() => players.patchPlayersProfile({ playerId: player }, { body: Object.fromEntries(changed.map((f) => [f, values[f] === "" ? null : values[f]])) })}
          onDone={done}
        />
      )}
    </form>
  );
}

function DateOfBirth({ player, p, done }: { player: string; p: Player; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const [day, setDay] = useState<string | undefined>(undefined);
  return (
    <div className="bo-profile-dob">
      <p>
        {t("profile.dateOfBirth")}: <span className="figures">{p.dateOfBirth || "—"}</span>
      </p>
      <Action
        action={Actions["player.correctDateOfBirth"]}
        me={me}
        bags={bags}
        ready={day !== undefined}
        subject={<BirthDateInput label={NewDateOfBirth} today={new Date().toISOString().slice(0, 10)} initial={p.dateOfBirth || undefined} onChange={setDay} required />}
        run={(reason) => players.patchPlayersDateOfBirth({ playerId: player }, { body: { dateOfBirth: day, reason } })}
        onDone={done}
      />
    </div>
  );
}

function Consents({ player }: { player: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["players", "crm", player], queryFn: () => readJson<Crm>(players.getPlayersCrm({ playerId: player })) });
  const [asked, setAsked] = useState<Partial<Crm>>({});
  if (q.isError) return <RefusalNotice refusal={refusalOf(q.error)} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const now = { ...q.data, ...asked };
  const changed = (["emailMarketingConsent", "smsMarketingConsent", "messageSuppressed"] as const).filter((k) => now[k] !== q.data[k]);
  return (
    <fieldset className="bo-profile-consents" disabled={readOnly}>
      <legend>{t("profile.consents")}</legend>
      {(["emailMarketingConsent", "smsMarketingConsent", "messageSuppressed"] as const).map((k) => (
        <label key={k} className="bo-corrections-choice">
          <input type="checkbox" checked={now[k]} onChange={(e) => setAsked({ ...asked, [k]: e.target.checked })} />
          {t(`profile.consent.${k}` as MessageKey)}
        </label>
      ))}
      {readOnly ? null : (
        <Action
          action={Actions["player.consents"]}
          me={me}
          bags={bags}
          ready={changed.length > 0}
          run={() => players.patchPlayersCrm({ playerId: player }, { body: Object.fromEntries(changed.map((k) => [k, now[k]])) })}
          onDone={() => {
            setAsked({});
            void client.invalidateQueries({ queryKey: ["players", "crm", player] });
          }}
        />
      )}
    </fieldset>
  );
}

export function PlayerProfile({ player }: { player: string }) {
  const client = useQueryClient();
  const q = useQuery({
    queryKey: ["players", "profile", player],
    queryFn: async () => {
      const b = await readJson<{ player?: Player } & Player>(players.getPlayersProfile({ playerId: player }));
      return b.player ?? b;
    },
  });
  const done = () => {
    void client.invalidateQueries({ queryKey: ["players", "profile", player] });
    void client.invalidateQueries({ queryKey: ["players", "standing", player] });
  };
  return (
    <section aria-labelledby="profile-title">
      <h2 id="profile-title">{t("players.tab.profile")}</h2>
      {q.isError ? (
        <RefusalNotice refusal={refusalOf(q.error)} />
      ) : !q.data ? (
        <p role="status">{t("list.loading")}</p>
      ) : Erased.has(q.data.status ?? "") ? (
        <p data-erased>{t("players.erased", { pseudonym: player })}</p>
      ) : (
        <>
          <Fields key={JSON.stringify(q.data)} player={player} p={q.data} done={done} />
          <DateOfBirth player={player} p={q.data} done={done} />
          <Consents player={player} />
        </>
      )}
    </section>
  );
}
