// A player's VIP tier and manager tags (T114; spec 030 P7; the original's VIP controls): the tier and the
// potential-VIP flag on the configuration form, each saved to players as `tier.change`; the manager tags as
// a list, added and removed, then saved as one change replacing the list (players trims them, drops a
// repeat ignoring case, and refuses more than 20 or one over 40 characters), with who set them and when.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "../payments/payments.css";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { ConfigForm } from "../../components/config-form.tsx";
import { DateTime, useZone } from "../../components/date.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { keyOf, read } from "../configuration/page.tsx";

interface Tags {
  tags: string[];
  setBy: string | null;
  setAt: string | null;
}

export const MaxTags = 20;
export const MaxTagLength = 40;

/** The list as players keeps it: trimmed, a repeat ignoring case dropped. */
export function tidyTags(tags: readonly string[]): string[] {
  const out: string[] = [];
  for (const raw of tags) {
    const tag = raw.trim();
    if (tag !== "" && !out.some((o) => o.toLowerCase() === tag.toLowerCase())) out.push(tag);
  }
  return out;
}

function TagsEditor({ player, held }: { player: string; held: Tags }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const id = useId();
  const [tags, setTags] = useState<string[]>(held.tags);
  const [adding, setAdding] = useState("");
  const editable = !readOnly && holds(me, "tier.change");
  const next = tidyTags(tags);
  const changed = next.join("\n") !== held.tags.join("\n");
  const tooLong = next.find((x) => x.length > MaxTagLength);
  const tz = useZone();
  return (
    <section aria-labelledby={`${id}-tags`}>
      <h3 id={`${id}-tags`}>{t("players.tags.title")}</h3>
      {held.setAt ? (
        <p className="bo-muted">
          {t("players.tags.setBy", { actor: held.setBy ?? "" })} <DateTime instant={held.setAt} zone={tz} />
        </p>
      ) : null}
      {tags.length === 0 ? <p className="bo-muted">{t("players.tags.none")}</p> : null}
      <ul className="bo-pay-tags">
        {tags.map((tag) => (
          <li key={tag}>
            <span className="bo-chip">{tag}</span>
            {editable ? (
              <button type="button" className="bo-button" aria-label={t("players.tags.remove", { name: tag })} onClick={() => setTags(tags.filter((x) => x !== tag))}>
                {t("players.tags.removeShort")}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {editable ? (
        <>
          <div className="bo-pay-filters">
            <div className="bo-field">
              <label htmlFor={`${id}-add`}>{t("players.tags.add")}</label>
              <input id={`${id}-add`} className="bo-input" maxLength={MaxTagLength} value={adding} onChange={(e) => setAdding(e.target.value)} />
            </div>
            <button
              type="button"
              className="bo-button"
              disabled={adding.trim() === "" || next.length >= MaxTags}
              onClick={() => {
                setTags(tidyTags([...tags, adding]));
                setAdding("");
              }}
            >
              {t("players.tags.addButton")}
            </button>
            <span className="bo-field-hint">{t("players.tags.limits", { count: MaxTags, value: MaxTagLength })}</span>
          </div>
          <Action
            action={Actions["player.tags"]}
            me={me}
            bags={bags}
            ready={changed && tooLong === undefined && next.length <= MaxTags}
            subject={<p>{next.length === 0 ? t("players.tags.clearing") : t("players.tags.becomes", { name: next.join(", ") })}</p>}
            run={() => players.putPlayersTags({ playerId: player }, { body: { tags: next } })}
            onDone={() => void client.invalidateQueries({ queryKey: ["players", "tags", player] })}
          />
        </>
      ) : null}
    </section>
  );
}

export function PlayerTier({ player }: { player: string }) {
  const client = useQueryClient();
  const profile = useQuery({ queryKey: ["players", "tier", player], queryFn: () => read<{ vipTier?: string | null; potVip?: boolean }>(players.getPlayersProfile({ playerId: player })) });
  const tags = useQuery({ queryKey: ["players", "tags", player], queryFn: () => read<Tags>(players.getPlayersTags({ playerId: player })) });
  const saved = () => {
    void client.invalidateQueries({ queryKey: ["players", "tier", player] });
    void client.invalidateQueries({ queryKey: ["players", "standing", player] });
  };
  return (
    <section aria-labelledby="player-tier">
      <h2 id="player-tier">{t("players.tier.title")}</h2>
      {profile.isError ? <RefusalNotice refusal={profile.error as never} /> : null}
      {profile.data ? (
        <ConfigForm
          key={keyOf(profile.data)}
          fields={[
            { name: "vipTier", label: "players.tier.vipTier", kind: "text", optional: true, default: null, hint: "players.tier.vipTierHint" },
            { name: "potVip", label: "players.tier.potVip", kind: "boolean", default: false },
          ]}
          values={{ vipTier: profile.data.vipTier || null, potVip: profile.data.potVip === true }}
          action={Actions["player.tier"]}
          save={async (change) => {
            if ("vipTier" in change) {
              const r = await players.putPlayersVipTier({ playerId: player }, { body: { tier: change.vipTier ?? "" } });
              if (!r.ok || !("potVip" in change)) return r;
            }
            return players.putPlayersPotVip({ playerId: player }, { body: { potVip: change.potVip === true } });
          }}
          onDone={saved}
        />
      ) : null}
      {tags.isError ? <RefusalNotice refusal={tags.error as never} /> : null}
      {tags.data ? <TagsEditor key={keyOf(tags.data)} player={player} held={tags.data} /> : null}
    </section>
  );
}
