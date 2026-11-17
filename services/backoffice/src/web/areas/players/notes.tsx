// A player's notes (T077; P4): every note, pinned first then newest, a page at a time with players'
// total; a note written (pinned at once if asked), a note pinned or unpinned. A note records who wrote it.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { completeOf, useListState, useRouterListStore } from "../../components/list/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";

const PageSize = 25;

interface Note {
  noteId: string;
  text: string;
  author?: string | null;
  pinned: boolean;
  createdAt: string;
}

export function PlayerNotes({ player }: { player: string }) {
  const tz = useZone();
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const list = useListState(useRouterListStore());
  const ids = { text: useId(), pin: useId() };
  const [text, setText] = useState("");
  const [pin, setPin] = useState(false);
  const q = useQuery({
    queryKey: ["players", "notes", player, list.state.page],
    queryFn: () => readJson<{ notes: Note[]; total: number }>(players.getPlayersNotes({ playerId: player }, { query: { page: String(list.state.page), pageSize: String(PageSize) } })),
  });
  const done = () => {
    void client.invalidateQueries({ queryKey: ["players", "notes", player] });
    void client.invalidateQueries({ queryKey: ["players", "standing", player] });
  };
  return (
    <section aria-labelledby="notes-title">
      <h2 id="notes-title">{t("players.tab.notes")}</h2>
      {readOnly ? null : (
        <div className="bo-notes-new">
          <div className="bo-field">
            <label htmlFor={ids.text}>{t("notes.text")}</label>
            <textarea id={ids.text} rows={3} value={text} maxLength={4000} onChange={(e) => setText(e.target.value)} />
          </div>
          <label className="bo-corrections-choice" htmlFor={ids.pin}>
            <input id={ids.pin} type="checkbox" checked={pin} onChange={(e) => setPin(e.target.checked)} />
            {t("notes.pinAtOnce")}
          </label>
          <Action
            action={Actions["note.write"]}
            me={me}
            bags={bags}
            ready={text.trim() !== ""}
            subject={text.trim() !== "" ? <q>{text.trim()}</q> : null}
            run={() => players.postPlayersNotes({ playerId: player }, { body: { content: text.trim(), pinImmediately: pin } })}
            onDone={() => {
              setText("");
              setPin(false);
              done();
            }}
          />
        </div>
      )}
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : !q.data ? <p role="status">{t("list.loading")}</p> : null}
      {q.data && q.data.notes.length === 0 ? <p className="bo-muted">{t("notes.none")}</p> : null}
      <ul className="bo-notes">
        {q.data?.notes.map((n) => (
          <li key={n.noteId} data-note={n.noteId} data-pinned={n.pinned ? "true" : "false"}>
            <p>{n.text}</p>
            <p className="bo-muted">
              {n.pinned ? <span className="bo-chip">{t("notes.pinned")}</span> : null} <DateTime instant={n.createdAt} zone={tz} />
              {n.author ? ` · ${t("players.notes.by", { operator: n.author })}` : ""}
            </p>
            {readOnly ? null : (
              <>
                <Action action={Actions["note.pin"]} me={me} bags={bags} state={n.pinned ? "pinned" : "unpinned"} run={() => players.patchPlayersNotesPin({ playerId: player, noteId: n.noteId }, { body: {} })} onDone={done} />
                <Action action={Actions["note.unpin"]} me={me} bags={bags} state={n.pinned ? "pinned" : "unpinned"} run={() => players.patchPlayersNotesUnpin({ playerId: player, noteId: n.noteId }, { body: {} })} onDone={done} />
              </>
            )}
          </li>
        ))}
      </ul>
      {q.data && (list.state.page > 1 || !completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total })) ? (
        <nav className="bo-list-foot" aria-label={t("list.pages")}>
          <button type="button" className="bo-button" disabled={list.state.page <= 1} onClick={() => list.setPage(list.state.page - 1)}>
            {t("list.previous")}
          </button>
          <button type="button" className="bo-button" disabled={completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total })} onClick={() => list.setPage(list.state.page + 1)}>
            {t("list.next")}
          </button>
        </nav>
      ) : null}
    </section>
  );
}
