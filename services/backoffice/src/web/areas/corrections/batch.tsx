// An adjustment batch (T087; US5, FR-028, parity *Adjustments to many players*, new): one reason and
// cause for an incident and a list of players and amounts, pasted or read from a CSV file, checked
// line by line, then run by the page's server as one wallet adjustment per row under its own key
// (`/api/adjustment-batches`, research R15). Each row is listed as it is answered: made, with the
// balance before and after; refused, with the wallet's reason; or already made, when the same batch
// ran before. The reason is written on the form, not in the confirmation, because it is part of the
// batch: the same reason, cause and rows are the same batch, so running it again makes nothing twice.
import { useId, useRef, useState } from "react";
import "./corrections.css";
import { render, t, type MessageKey } from "../../../shared/messages/index.ts";
import { exponentOf, parseMoney, UnknownCurrency } from "../../../shared/money.ts";
import { explain } from "../../../shared/refusals.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { RecordLink } from "../../components/links.tsx";
import { Money, moneyText } from "../../components/money.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { CategorySelect, CauseFields, causeOf, CorrectionsNav, Outcome, type Category, type CauseKind } from "./shared.tsx";

export const MaxRows = 500;

export interface Row {
  /** The row's number, from 1. */
  n: number;
  player: string;
  currency: string;
  walletType: "real" | "restricted";
  direction: "credit" | "debit";
  amountMinor: bigint;
}

export interface LineError {
  line: number;
  key: MessageKey;
}

const Player = /^[A-Za-z0-9._:-]{1,128}$/;

/** Whether the contract's currency table has the currency. */
function knows(currency: string): boolean {
  try {
    exponentOf(currency);
    return true;
  } catch {
    return false;
  }
}

/** The pasted text as rows, one a line (`player, currency, amount[, real|restricted]`), or what is wrong with each line. */
export function parseRows(text: string): { rows: Row[]; errors: LineError[] } {
  const rows: Row[] = [];
  const errors: LineError[] = [];
  const lines = text.split(/\r?\n/);
  for (const [i, raw] of lines.entries()) {
    const line = i + 1;
    if (raw.trim() === "") continue;
    const cells = raw.split(/[,;\t]/).map((c) => c.trim());
    if (rows.length === 0 && errors.length === 0 && cells[0]?.toLowerCase() === "player") continue;
    if (cells.length < 3 || cells.length > 4) {
      errors.push({ line, key: "corrections.batch.error.columns" });
      continue;
    }
    const [player = "", currency = "", amount = "", walletType = "real"] = cells;
    if (!Player.test(player) || player === "." || player === "..") {
      errors.push({ line, key: "corrections.batch.error.player" });
      continue;
    }
    let minor: bigint | undefined;
    try {
      minor = /^[A-Z]{3}$/.test(currency) ? parseMoney(amount, currency) : undefined;
    } catch (e) {
      if (!(e instanceof UnknownCurrency)) throw e;
    }
    if (!/^[A-Z]{3}$/.test(currency) || (minor === undefined && !knows(currency))) {
      errors.push({ line, key: "corrections.batch.error.currency" });
      continue;
    }
    if (minor === undefined || minor === 0n || (minor < 0n ? -minor : minor) > BigInt(Number.MAX_SAFE_INTEGER)) {
      errors.push({ line, key: "corrections.batch.error.amount" });
      continue;
    }
    const w = walletType.toLowerCase();
    if (w !== "real" && w !== "restricted") {
      errors.push({ line, key: "corrections.batch.error.walletType" });
      continue;
    }
    rows.push({ n: rows.length + 1, player, currency, walletType: w, direction: minor < 0n ? "debit" : "credit", amountMinor: minor < 0n ? -minor : minor });
  }
  return { rows, errors };
}

/** One answered row, as the server streams it. */
export interface Answered {
  row: number;
  player: string;
  currency: string;
  outcome: "made" | "refused" | "already_made";
  reason?: string;
  message?: string;
  status?: number;
  source?: string;
  before?: string;
  after?: string;
}

interface Done {
  done: true;
  made: number;
  refused: number;
  alreadyMade: number;
}

/** The server's lines as they arrive: each row's outcome, then the counts. */
export async function readStream(answer: Response, onRow: (a: Answered) => void): Promise<Done | undefined> {
  if (!answer.body) return undefined;
  const reader = answer.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffered = "";
  let done: Done | undefined;
  const take = (line: string) => {
    if (line.trim() === "") return;
    const v = JSON.parse(line) as Answered | Done;
    if ("done" in v) done = v;
    else onRow(v);
  };
  for (;;) {
    const { value, done: ended } = await reader.read();
    if (ended) break;
    buffered += value;
    const lines = buffered.split("\n");
    buffered = lines.pop() ?? "";
    lines.forEach(take);
  }
  take(buffered);
  return done;
}

function Detail({ row, answered }: { row: Row; answered: Answered | undefined }) {
  if (!answered) return null;
  if (answered.outcome === "refused") {
    const r = explain(answered.status ?? 409, JSON.stringify({ error: answered.reason, message: answered.message ?? answered.reason, source: answered.source }));
    return (
      <span data-refusal>
        {render(r.message)}
        {r.next ? ` ${render(r.next)}` : null}
      </span>
    );
  }
  if (answered.before === undefined || answered.after === undefined) return null;
  return <span>{t("corrections.preview", { from: moneyText({ minor: answered.before, currency: row.currency }), to: moneyText({ minor: answered.after, currency: row.currency }) })}</span>;
}

export function BatchPage() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const ids = { reason: useId(), rows: useId(), upload: useId() };
  const file = useRef<HTMLInputElement>(null);
  const [category, setCategory] = useState<Category>("compensation");
  const [reason, setReason] = useState("");
  const [cause, setCause] = useState<{ kind: CauseKind | ""; ref: string }>({ kind: "", ref: "" });
  const [text, setText] = useState("");
  const [checked, setChecked] = useState<{ rows: Row[]; errors: LineError[]; signature: string } | undefined>(undefined);
  const [answers, setAnswers] = useState<Map<number, Answered>>(new Map());
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState<Done | undefined>(undefined);
  const [cut, setCut] = useState(false);
  // One key per batch composed on this page: kept while the list is corrected and sent again, so made rows
  // are not made twice; a batch composed another time (the page opened again) is another batch.
  const [key] = useState(() => crypto.randomUUID());

  const named = causeOf(cause.kind, cause.ref);
  const signature = JSON.stringify([category, reason.trim(), named ?? null, text]);
  const current = checked && checked.signature === signature ? checked : undefined;
  const rows = checked?.rows ?? [];
  const tooMany = rows.length > MaxRows;
  const runnable = current !== undefined && current.errors.length === 0 && rows.length > 0 && !tooMany && reason.trim() !== "" && !running;
  const seen = new Map<string, number>();
  for (const r of rows) seen.set(`${r.player}/${r.currency}`, (seen.get(`${r.player}/${r.currency}`) ?? 0) + 1);

  const check = () => {
    setChecked({ ...parseRows(text), signature });
    setAnswers(new Map());
    setDone(undefined);
    setCut(false);
  };

  const run = () => {
    setAnswers(new Map());
    setDone(undefined);
    setCut(false);
    return fetch("/api/adjustment-batches", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Backoffice": "1", Accept: "application/x-ndjson" },
      body: JSON.stringify({
        key,
        reason: reason.trim(),
        category,
        ...(named ? { cause: named } : {}),
        rows: rows.map((r) => ({ player: r.player, currency: r.currency, walletType: r.walletType, direction: r.direction, amountMinor: r.amountMinor.toString() })),
      }),
    });
  };
  const follow = async (answer: Response) => {
    setRunning(true);
    try {
      const last = await readStream(answer, (a) => setAnswers((m) => new Map(m).set(a.row, a)));
      setDone(last);
      setCut(last === undefined);
    } catch {
      setCut(true);
    } finally {
      setRunning(false);
    }
  };

  return (
    <section>
      <CorrectionsNav here="batch" />
      <h1>{t("corrections.batch.title")}</h1>
      <p className="bo-muted">{t("corrections.batch.about")}</p>
      <form className="bo-corrections-form" onSubmit={(e) => e.preventDefault()}>
        <CategorySelect value={category} onChange={setCategory} disabled={readOnly} />
        <div className="bo-field">
          <label htmlFor={ids.reason}>{t("corrections.batch.reason")}</label>
          <textarea id={ids.reason} className="bo-input" rows={2} maxLength={1000} required aria-required value={reason} disabled={readOnly} onChange={(e) => setReason(e.target.value)} />
        </div>
        <CauseFields kind={cause.kind} reference={cause.ref} disabled={readOnly} onChange={(kind, ref) => setCause({ kind, ref })} />
        <div className="bo-field">
          <label htmlFor={ids.rows}>{t("corrections.batch.rows")}</label>
          <textarea id={ids.rows} className="bo-input bo-corrections-rows" rows={8} spellCheck={false} value={text} disabled={readOnly} onChange={(e) => setText(e.target.value)} />
        </div>
        <div className="bo-field">
          <label htmlFor={ids.upload}>{t("corrections.batch.upload")}</label>
          <input
            id={ids.upload}
            ref={file}
            type="file"
            accept=".csv,text/csv,text/plain"
            disabled={readOnly}
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void f.text().then(setText);
              if (file.current) file.current.value = "";
            }}
          />
        </div>
        <button type="button" className="bo-button" disabled={text.trim() === "" || running} onClick={check}>
          {t("corrections.batch.validate")}
        </button>
      </form>

      {checked ? (
        <div className="bo-corrections-checked" role="status">
          {checked.errors.length > 0 ? (
            <>
              <p className="bo-field-error">{t("corrections.batch.fix")}</p>
              <ul data-batch-errors>
                {checked.errors.map((e) => (
                  <li key={e.line}>{t(e.key, { line: e.line })}</li>
                ))}
              </ul>
            </>
          ) : tooMany ? (
            <p className="bo-field-error">{t("corrections.batch.tooMany", { count: rows.length })}</p>
          ) : (
            <p>{t("corrections.batch.checked", { count: rows.length })}</p>
          )}
          {!current ? <p className="bo-field-error">{t("corrections.batch.changed")}</p> : null}
        </div>
      ) : null}

      {readOnly || !checked ? null : <Action action={Actions["wallet.adjustBatch"]} me={me} bags={bags} ready={runnable} subject={<p>{t("corrections.batch.checked", { count: rows.length })}</p>} run={run} onDone={(a) => void follow(a)} />}

      {rows.length > 0 ? (
        <div className="bo-list" data-batch-result={answers.size > 0 || running || done ? "" : undefined}>
          {running ? <div className="bo-list-head" role="status">{t("corrections.batch.running", { count: answers.size })}</div> : null}
          <table>
            <thead>
              <tr>
                <th data-numeric>{t("corrections.batch.col.n")}</th>
                <th>{t("corrections.batch.col.player")}</th>
                <th data-numeric>{t("corrections.batch.col.amount")}</th>
                <th>{t("corrections.batch.col.target")}</th>
                <th>{t("corrections.batch.col.outcome")}</th>
                <th>{t("corrections.batch.col.detail")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, MaxRows).map((r) => {
                const a = answers.get(r.n);
                const outcome = a?.outcome ?? (running ? "waiting" : undefined);
                return (
                  <tr key={r.n} data-batch-row={r.n} data-player={r.player} data-outcome={a?.outcome}>
                    <td data-numeric>{r.n}</td>
                    <td>
                      <RecordLink to={{ kind: "player", id: r.player }}>{r.player}</RecordLink>
                      {(seen.get(`${r.player}/${r.currency}`) ?? 0) > 1 ? <span className="bo-field-hint"> {t("corrections.batch.twice")}</span> : null}
                    </td>
                    <td data-numeric>
                      <Money value={{ minor: r.direction === "debit" ? -r.amountMinor : r.amountMinor, currency: r.currency }} />
                    </td>
                    <td>{t(r.walletType === "real" ? "corrections.target.real" : "corrections.target.restricted")}</td>
                    <td>{outcome ? <Outcome value={outcome} /> : null}</td>
                    <td>
                      <Detail row={r} answered={a} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {done ? (
        <p role="status" data-batch-done>
          {t("corrections.batch.summary", { made: done.made, refused: done.refused, already: done.alreadyMade })}
        </p>
      ) : null}
      {cut ? <p className="bo-field-error">{t("corrections.batch.cut")}</p> : null}
    </section>
  );
}
