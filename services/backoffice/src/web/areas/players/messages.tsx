// A player's messages (T122; US11, FR-036): an inbox item written with the brand's merge tags, previewed
// for this player with the tags filled before it can be sent, and sent only through its action; the
// inbox items sent to the player; and what the CRM was asked to send them (engagement's sends). players
// renders and records the item (OperatorStatusRoutes: `merge-tags`, `messages/preview`, `messages`).
import { useId, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { engagement, players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { completeOf, List, memoryStore, useListState, useRouterListStore, type Column } from "../../components/list/index.ts";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { readJson, refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";
import "../outreach/outreach.css";

const PageSize = 20;

interface MergeTag {
  name: string;
  label: string;
  example: string;
}

interface Preview {
  resolved: { subject: string; body: string };
  fallback: { subject: string; body: string };
  hasAllValues: boolean;
}

interface InboxItem {
  notificationId: string;
  subject: string;
  body: string;
  state: string;
  category: string;
  createdAt: string;
  originName?: string;
}

interface CrmSend {
  sendId: string;
  trigger: string;
  class: string;
  channel: string;
  time: string;
  outcome: string;
  reason?: string | null;
}

type Category = "service" | "marketing";
type Field = "subject" | "body";

/** `text` with `{{name|there}}` put in place of the selection of `el`, the fallback selected after (the original's insert). */
export function insertTag(text: string, start: number, end: number, name: string): { text: string; select: [number, number] } {
  const fallback = t("messages.mergeTag.fallback");
  const tag = `{{${name}|${fallback}}}`;
  const from = start + 2 + name.length + 1;
  return { text: text.slice(0, start) + tag + text.slice(end), select: [from, from + fallback.length] };
}

function Compose({ player, onSent }: { player: string; onSent(): void }) {
  const me = useOperator();
  const bags = useBags();
  const ids = { subject: useId(), body: useId(), category: useId(), tags: useId(), hint: useId(), preview: useId() };
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [category, setCategory] = useState<Category | undefined>(undefined);
  const [previewed, setPreviewed] = useState<{ subject: string; body: string; preview: Preview } | undefined>(undefined);
  const [previewing, setPreviewing] = useState(false);
  const [previewFailed, setPreviewFailed] = useState<ReturnType<typeof refusalOf> | undefined>(undefined);
  const [sent, setSent] = useState(false);
  // one key per composition, so a send repeated after a lost answer is the same inbox item
  const [key, setKey] = useState(() => crypto.randomUUID());
  const fields = { subject: useRef<HTMLInputElement>(null), body: useRef<HTMLTextAreaElement>(null) };
  const last = useRef<Field>("body");
  const tags = useQuery({ queryKey: ["players", "merge-tags"], queryFn: () => readJson<{ tags: MergeTag[] }>(players.getMergeTags()), staleTime: 300_000 });

  const insert = (tag: MergeTag) => {
    const field = last.current;
    const el = fields[field].current;
    const value = field === "subject" ? subject : body;
    const out = insertTag(value, el?.selectionStart ?? value.length, el?.selectionEnd ?? value.length, tag.name);
    (field === "subject" ? setSubject : setBody)(out.text);
    setSent(false);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(...out.select);
    });
  };

  const preview = async () => {
    setPreviewing(true);
    setPreviewFailed(undefined);
    try {
      const p = await readJson<Preview>(players.postMessagesPreview({ body: { playerId: player, subjectTemplate: subject, bodyTemplate: body } }));
      setPreviewed({ subject, body, preview: p });
    } catch (e) {
      setPreviewed(undefined);
      setPreviewFailed(refusalOf(e));
    } finally {
      setPreviewing(false);
    }
  };

  const written = subject.trim() !== "" && body.trim() !== "";
  const current = previewed !== undefined && previewed.subject === subject && previewed.body === body;
  const changed = (set: (v: string) => void) => (v: string) => {
    set(v);
    setSent(false);
  };

  return (
    <section aria-labelledby={`${ids.preview}-compose`} className="bo-messages-compose">
      <h3 id={`${ids.preview}-compose`}>{t("messages.compose")}</h3>
      <div className="bo-field">
        <label htmlFor={ids.subject}>{t("messages.subject")}</label>
        <input id={ids.subject} ref={fields.subject} className="bo-input" value={subject} maxLength={200} onFocus={() => (last.current = "subject")} onChange={(e) => changed(setSubject)(e.target.value)} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.body}>{t("messages.body")}</label>
        <textarea id={ids.body} ref={fields.body} className="bo-input" rows={5} value={body} maxLength={4000} onFocus={() => (last.current = "body")} onChange={(e) => changed(setBody)(e.target.value)} />
      </div>
      <div className="bo-field" role="group" aria-labelledby={ids.tags} aria-describedby={ids.hint}>
        <span id={ids.tags}>{t("messages.tags")}</span>
        <span id={ids.hint} className="bo-field-hint">
          {t("messages.tagsHint")}
        </span>
        {tags.isError ? <RefusalNotice refusal={refusalOf(tags.error)} /> : null}
        <div className="bo-messages-tags">
          {tags.data?.tags.map((tag) => (
            <button key={tag.name} type="button" className="bo-button" title={t("messages.example", { text: tag.example })} onMouseDown={(e) => e.preventDefault()} onClick={() => insert(tag)}>
              {t("messages.insert", { name: tag.label })}
            </button>
          ))}
        </div>
      </div>
      <fieldset className="bo-field">
        <legend>{t("messages.category")}</legend>
        {(["service", "marketing"] as const).map((c) => (
          <label key={c} className="bo-messages-choice">
            <input type="radio" name={ids.category} value={c} checked={category === c} onChange={() => setCategory(c)} />
            {t(`messages.category.${c}`)}
          </label>
        ))}
      </fieldset>
      <p>
        <button type="button" className="bo-button" disabled={!written || previewing || !holds(me, "message.send")} onClick={() => void preview()}>
          {previewing ? t("messages.previewing") : t("messages.preview")}
        </button>
      </p>
      {previewFailed ? <RefusalNotice refusal={previewFailed} /> : null}
      {previewed ? (
        <section aria-labelledby={ids.preview} className="bo-messages-preview">
          <h4 id={ids.preview}>{t("messages.preview.heading")}</h4>
          <p className="bo-muted">{t("messages.preview.resolved")}</p>
          <p>
            <strong>{previewed.preview.resolved.subject}</strong>
          </p>
          <p className="bo-messages-body">{previewed.preview.resolved.body}</p>
          <p className="bo-muted">{t("messages.preview.fallback")}</p>
          <p>
            <strong>{previewed.preview.fallback.subject}</strong>
          </p>
          <p className="bo-messages-body">{previewed.preview.fallback.body}</p>
          <p role="note">{previewed.preview.hasAllValues ? t("messages.preview.allValues") : t("messages.preview.missing")}</p>
        </section>
      ) : null}
      {written && !current && !previewing ? <p className="bo-muted">{previewed ? t("messages.preview.stale") : t("messages.preview.needed")}</p> : null}
      <Action
        action={Actions["message.send"]}
        me={me}
        bags={bags}
        ready={written && current && category !== undefined}
        subject={
          current ? (
            <div>
              <p>
                <strong>{previewed.preview.resolved.subject}</strong>
              </p>
              <p className="bo-messages-body">{previewed.preview.resolved.body}</p>
            </div>
          ) : null
        }
        run={() => players.postPlayersMessages({ playerId: player }, { body: { category, subjectTemplate: subject, bodyTemplate: body, notificationId: key } })}
        onDone={() => {
          setSubject("");
          setBody("");
          setCategory(undefined);
          setPreviewed(undefined);
          setKey(crypto.randomUUID());
          setSent(true);
          onSent();
        }}
      />
      {sent ? <p role="status">{t("messages.sent")}</p> : null}
    </section>
  );
}

function Sent({ player }: { player: string }) {
  const tz = useZone();
  const list = useListState(useRouterListStore());
  const q = useQuery({
    queryKey: ["players", "messages", player, list.state.page],
    queryFn: () => readJson<{ items: InboxItem[]; total: number }>(players.getPlayersMessages({ playerId: player }, { query: { page: String(list.state.page), pageSize: String(PageSize) } })),
  });
  const columns: Column<InboxItem>[] = [
    { id: "at", header: "messages.at", cell: (m) => <DateTime instant={m.createdAt} zone={tz} /> },
    { id: "subject", header: "messages.subject", cell: (m) => m.subject },
    { id: "category", header: "messages.category", cell: (m) => word("messages.kind", m.category) },
    { id: "from", header: "messages.from", cell: (m) => (m.originName === "CRM" ? t("messages.origin.crm") : t("messages.origin.operator")) },
    { id: "state", header: "messages.state", cell: (m) => word("messages.state", m.state) },
  ];
  return (
    <>
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      {q.data && q.data.items.length === 0 ? <p className="bo-muted">{t("messages.none")}</p> : null}
      <List
        caption="messages.sends"
        columns={columns}
        rows={q.data?.items}
        rowKey={(m) => m.notificationId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: PageSize, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
      />
    </>
  );
}

function CrmSends({ player }: { player: string }) {
  const tz = useZone();
  const store = useMemo(() => memoryStore(), []);
  const list = useListState(store);
  const q = useQuery({
    queryKey: ["engagement", "sends", player, list.state.page],
    queryFn: () => readJson<{ sends: CrmSend[]; total: number }>(engagement.getPlayersSends({ player }, { query: { page: String(list.state.page) } })),
  });
  const columns: Column<CrmSend>[] = [
    { id: "time", header: "messages.crm.time", cell: (s) => <DateTime instant={s.time} zone={tz} /> },
    { id: "trigger", header: "messages.crm.trigger", cell: (s) => s.trigger },
    { id: "channel", header: "messages.crm.channel", cell: (s) => `${s.channel} (${s.class})` },
    { id: "outcome", header: "messages.crm.outcome", cell: (s) => word("config.crm.outcome", s.outcome) },
    { id: "reason", header: "messages.crm.reason", cell: (s) => s.reason ?? "" },
  ];
  return (
    <>
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : null}
      {q.data && q.data.sends.length === 0 ? <p className="bo-muted">{t("messages.crm.none")}</p> : null}
      <List
        caption="messages.crm"
        columns={columns}
        rows={q.data?.sends}
        rowKey={(s) => s.sendId}
        total={q.data?.total}
        complete={q.data ? completeOf({ page: list.state.page, pageSize: 50, total: q.data.total }) : false}
        fetching={q.isFetching}
        zone={tz}
        list={list}
      />
    </>
  );
}

export function PlayerMessages({ player }: { player: string }) {
  const zone = useZone();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  return (
    <section aria-labelledby="messages-title">
      <h2 id="messages-title">{t("players.tab.messages")}</h2>
      {readOnly ? null : <Compose player={player} onSent={() => void client.invalidateQueries({ queryKey: ["players", "messages", player] })} />}
      <ZoneNote zone={zone} />
      <Sent player={player} />
      <CrmSends player={player} />
    </section>
  );
}
