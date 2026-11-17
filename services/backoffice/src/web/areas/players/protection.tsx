// A player's responsible gambling (T096; US7, parity *Limits set and removed, with pending change*,
// *Breaks started and lifted; return*): responsible-gambling's read of the player. Each limit is shown
// with the value in force now and, beside it, the change responsible-gambling has staged and when it
// takes effect; set and removed. A break started, changed and lifted; a self-exclusion recorded; a
// return requested once a time-limited exclusion has ended; the player's cases. Each action states what
// it changes for the player before it is confirmed. responsible-gambling decides every rule: whether a
// change applies now or is staged is its answer, shown as it gives it.
import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { instantOfLocal } from "../../../shared/dates.ts";
import { t } from "../../../shared/messages/index.ts";
import { parseMoney } from "../../../shared/money.ts";
import { responsibleGambling as rg } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money, moneyText } from "../../components/money.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { PlayerCases } from "../protection/cases.tsx";
import { isMoney, keyOf, LimitTypes, MinimumExclusionDays, readProtection, readSettings, type PendingChange, type Protection } from "../protection/rg.ts";


/** A limit's value as a person reads it: an amount in its currency, or minutes. */
export function LimitValue({ limitType, value, currency }: { limitType: string; value: string | number; currency: string | null }) {
  if (isMoney(limitType) && currency) return <Money value={{ minor: String(value), currency }} />;
  return <span>{t("protection.minutes", { count: Number(value) })}</span>;
}

/** Typed text as the value responsible-gambling takes: minor units of the currency, or whole minutes. */
export function valueOf(limitType: string, text: string, currency: string | null): string | undefined {
  if (isMoney(limitType)) {
    if (!currency) return undefined;
    try {
      const minor = parseMoney(text, currency);
      return minor !== undefined && minor > 0n ? minor.toString() : undefined;
    } catch {
      return undefined;
    }
  }
  return /^\s*\d+\s*$/.test(text) && Number(text) > 0 ? text.trim() : undefined;
}

/** The dialog's part that takes the new value, and says what it replaces. */
function NewValue({ limitType, currency, now, text, onText }: { limitType: string; currency: string | null; now?: number; text: string; onText(t: string): void }) {
  const id = useId();
  return (
    <div>
      <p>
        {word("protection.limit", limitType)}
        {currency ? ` (${currency})` : null}
        {now !== undefined ? (
          <>
            {" · "}
            {t("protection.limit.now")} <LimitValue limitType={limitType} value={now} currency={currency} />
          </>
        ) : null}
      </p>
      <div className="bo-field">
        <label htmlFor={id}>{t("protection.limit.value")}</label>
        <input id={id} inputMode="decimal" value={text} onChange={(e) => onText(e.target.value)} aria-describedby={`${id}-hint`} />
        <span id={`${id}-hint`} className="bo-field-hint">
          {isMoney(limitType) ? t("protection.limit.moneyHint", { currency: currency ?? "" }) : t("protection.limit.minutesHint")}
        </span>
      </div>
    </div>
  );
}

function Pending({ change, tz }: { change: PendingChange; tz: string }) {
  return (
    <span data-pending data-applies-at={change.appliesAt}>
      {change.pendingValue === null ? (
        <span>{t("protection.pending.removal")}</span>
      ) : (
        <>
          <span>{t("protection.pending.to")}</span> <LimitValue limitType={change.limitType} value={change.pendingValue} currency={change.currency} />
        </>
      )}{" "}
      <span>{t("protection.pending.from")}</span> <DateTime instant={change.appliesAt} zone={tz} />
    </span>
  );
}

function LimitRow({ player, limitType, currency, value, pending, used, done }: { player: string; limitType: string; currency: string | null; value?: number; pending?: PendingChange; used?: number; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const [text, setText] = useState("");
  const tz = useZone();
  const next = valueOf(limitType, text, currency);
  const query = currency ? { query: { currency } } : {};
  return (
    <tr data-limit={keyOf({ limitType, currency })}>
      <th scope="row">
        {word("protection.limit", limitType)}
        {currency ? <span className="bo-muted"> {currency}</span> : null}
      </th>
      <td>
        <span data-current>{value === undefined ? t("protection.limit.none") : isMoney(limitType) && currency ? moneyText({ minor: String(value), currency }) : t("protection.minutes", { count: value })}</span>
      </td>
      <td>{used !== undefined && currency ? <UsedAmount major={used} currency={currency} /> : null}</td>
      <td>{pending ? <Pending change={pending} tz={tz} /> : <span className="bo-muted">{t("protection.pending.none")}</span>}</td>
      <td>
        {readOnly ? null : (
          <div className="bo-actions">
            <Action
              action={Actions["protection.setLimit"]}
              me={me}
              bags={bags}
              ready={next !== undefined}
              subject={<NewValue limitType={limitType} currency={currency} now={value} text={text} onText={setText} />}
              run={() => rg.putLimitsByType({ player, type: limitType }, { body: currency ? { value: next, currency } : { value: next } })}
              onDone={() => {
                setText("");
                done();
              }}
            />
            {value !== undefined ? (
              <Action
                action={Actions["protection.removeLimit"]}
                me={me}
                bags={bags}
                subject={<p>{word("protection.limit", limitType)}</p>}
                run={() => rg.deleteLimitsByType({ player, type: limitType }, query)}
                onDone={done}
              />
            ) : null}
          </div>
        )}
      </td>
    </tr>
  );
}

/** responsible-gambling's used amount is in major units (Render.major); shown by the same money rule. */
function UsedAmount({ major, currency }: { major: number; currency: string }) {
  try {
    const minor = parseMoney(String(major), currency);
    return minor === undefined ? <span>{String(major)}</span> : <Money value={{ minor, currency }} />;
  } catch {
    return (
      <span>
        {String(major)} {currency}
      </span>
    );
  }
}

function Limits({ player, p, done }: { player: string; p: Protection; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const typeId = useId();
  const currencyId = useId();
  const [limitType, setLimitType] = useState<string>(LimitTypes[0]);
  const [currency, setCurrency] = useState(p.limits.find((l) => l.currency)?.currency ?? "");
  const [text, setText] = useState("");
  // every limit in force and every change staged, each once by its key, in the types' order
  const keys = new Map<string, { limitType: string; currency: string | null }>();
  for (const l of [...p.limits, ...p.pendingChanges]) keys.set(keyOf(l), { limitType: l.limitType, currency: l.currency });
  const rows = [...keys.entries()].sort(([, a], [, b]) => LimitTypes.indexOf(a.limitType as never) - LimitTypes.indexOf(b.limitType as never) || (a.currency ?? "").localeCompare(b.currency ?? ""));
  const c = isMoney(limitType) ? currency.trim().toUpperCase() || null : null;
  const next = valueOf(limitType, text, c);
  return (
    <section aria-labelledby="protection-limits">
      <h3 id="protection-limits">{t("protection.limits")}</h3>
      <table className="bo-table">
        <thead>
          <tr>
            <th scope="col">{t("protection.limit.type")}</th>
            <th scope="col">{t("protection.limit.current")}</th>
            <th scope="col">{t("protection.limit.used")}</th>
            <th scope="col">{t("protection.limit.pending")}</th>
            <th scope="col">{t("identity.document.actions")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={5}>{t("protection.limits.none")}</td>
            </tr>
          ) : null}
          {rows.map(([key, l]) => (
            <LimitRow
              key={key}
              player={player}
              limitType={l.limitType}
              currency={l.currency}
              value={p.limits.find((x) => keyOf(x) === key)?.value}
              pending={p.pendingChanges.find((x) => keyOf(x) === key)}
              used={p.usedAmounts.find((u) => u.limitType === l.limitType && u.currency === l.currency)?.usedAmount}
              done={done}
            />
          ))}
        </tbody>
      </table>
      {readOnly ? null : (
        <Action
          action={Actions["protection.newLimit"]}
          me={me}
          bags={bags}
          ready={next !== undefined}
          subject={
            <div>
              <div className="bo-field">
                <label htmlFor={typeId}>{t("protection.limit.type")}</label>
                <select id={typeId} value={limitType} onChange={(e) => setLimitType(e.target.value)}>
                  {LimitTypes.map((lt) => (
                    <option key={lt} value={lt}>
                      {word("protection.limit", lt)}
                    </option>
                  ))}
                </select>
              </div>
              {isMoney(limitType) ? (
                <div className="bo-field">
                  <label htmlFor={currencyId}>{t("protection.limit.currency")}</label>
                  <input id={currencyId} value={currency} maxLength={3} onChange={(e) => setCurrency(e.target.value)} />
                </div>
              ) : null}
              <NewValue limitType={limitType} currency={c} text={text} onText={setText} />
            </div>
          }
          run={() => rg.putLimitsByType({ player, type: limitType }, { body: c ? { value: next, currency: c } : { value: next } })}
          onDone={() => {
            setText("");
            done();
          }}
        />
      )}
    </section>
  );
}

function Breaks({ player, p, maxDays, done }: { player: string; p: Protection; maxDays?: number; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const untilId = useId();
  const [until, setUntil] = useState("");
  const tz = useZone();
  // The time typed is the brand's zone's (FR-015), as the zone note under the field says.
  const at = instantOfLocal(until, tz);
  const state = p.isCoolingOff ? "on_break" : "none";
  const input = (
    <div className="bo-field">
      <label htmlFor={untilId}>{t("protection.break.until")}</label>
      <input id={untilId} type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} aria-describedby={`${untilId}-hint`} />
      <span id={`${untilId}-hint`} className="bo-field-hint">
        {maxDays !== undefined ? t("protection.break.hint", { days: maxDays }) : t("protection.break.hintUnknown")}
      </span>
      <ZoneNote zone={tz} />
    </div>
  );
  const put = () => rg.putCoolingOff({ player }, { body: { until: at } });
  return (
    <section aria-labelledby="protection-break">
      <h3 id="protection-break">{t("protection.break")}</h3>
      <p data-break={state}>
        {p.isCoolingOff && p.coolingOffUntil ? (
          <>
            {t("protection.break.on")} <DateTime instant={p.coolingOffUntil} zone={tz} />
          </>
        ) : (
          t("protection.break.off")
        )}
      </p>
      {readOnly ? null : (
        <div className="bo-actions">
          <Action action={Actions["protection.startBreak"]} me={me} bags={bags} state={state} ready={at !== undefined} subject={input} run={put} onDone={done} />
          <Action action={Actions["protection.changeBreak"]} me={me} bags={bags} state={state} ready={at !== undefined} subject={input} run={put} onDone={done} />
          {p.isCoolingOff ? <Action action={Actions["protection.liftBreak"]} me={me} bags={bags} run={(reason) => rg.postCoolingOffLift({ player }, { body: { reason } })} onDone={done} /> : null}
        </div>
      )}
    </section>
  );
}

/** Where the player's exclusion stands, as the return is offered by it (ProfileEntity.requestReturn). */
export function exclusionState(p: Pick<Protection, "exclusion" | "returnRequest">, now = Date.now()): string {
  if (!p.exclusion) return "not_excluded";
  if (p.returnRequest) return "requested";
  if (p.exclusion.permanent) return "permanent";
  if (p.exclusion.until && Date.parse(p.exclusion.until) > now) return "running";
  return "ended";
}

function Exclusion({ player, p, done }: { player: string; p: Protection; done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const daysId = useId();
  const [permanent, setPermanent] = useState(false);
  const [days, setDays] = useState(String(MinimumExclusionDays));
  const tz = useZone();
  const state = exclusionState(p);
  const period = permanent ? "permanent" : /^\d+$/.test(days.trim()) ? `${days.trim()}d` : undefined;
  const e = p.exclusion;
  return (
    <section aria-labelledby="protection-exclusion">
      <h3 id="protection-exclusion">{t("protection.exclusion")}</h3>
      <p data-exclusion={state}>
        {e ? (
          <>
            {e.permanent ? t("protection.exclusion.permanent") : t("protection.exclusion.until")} {e.until ? <DateTime instant={e.until} zone={tz} /> : null}
            {" · "}
            {t("protection.exclusion.since")} <DateTime instant={e.since} zone={tz} /> {` · ${word("protection.trigger", e.trigger)}`}
          </>
        ) : (
          t("protection.exclusion.none")
        )}
      </p>
      {p.returnRequest ? (
        <p data-return>
          {t("protection.return.requested")} <DateTime instant={p.returnRequest.reinstatesAt} zone={tz} />
        </p>
      ) : null}
      {readOnly ? null : (
        <div className="bo-actions">
          <Action
            action={Actions["protection.selfExclude"]}
            me={me}
            bags={bags}
            state={state}
            ready={period !== undefined}
            subject={
              <fieldset>
                <legend>{t("protection.exclusion.period")}</legend>
                <label>
                  <input type="checkbox" checked={permanent} onChange={(ev) => setPermanent(ev.target.checked)} /> <span>{t("protection.exclusion.permanentChoice")}</span>
                </label>
                {permanent ? null : (
                  <div className="bo-field">
                    <label htmlFor={daysId}>{t("protection.exclusion.days")}</label>
                    <input id={daysId} inputMode="numeric" value={days} onChange={(ev) => setDays(ev.target.value)} aria-describedby={`${daysId}-hint`} />
                    <span id={`${daysId}-hint`} className="bo-field-hint">
                      {t("protection.exclusion.daysHint", { days: MinimumExclusionDays })}
                    </span>
                  </div>
                )}
              </fieldset>
            }
            run={() => rg.postSelfExclude({ player }, { body: { period } })}
            onDone={done}
          />
          <Action action={Actions["protection.requestReturn"]} me={me} bags={bags} state={state} run={() => rg.postReturn({ player })} onDone={done} />
        </div>
      )}
    </section>
  );
}

export function PlayerProtection({ player }: { player: string }) {
  const client = useQueryClient();
  const tz = useZone();
  const q = useQuery({ queryKey: ["rg", "player", player], queryFn: () => readProtection(player) });
  const settings = useQuery({ queryKey: ["rg", "settings"], queryFn: () => readSettings(), staleTime: 60_000 });
  const done = () => void client.invalidateQueries({ queryKey: ["rg", "player", player] });

  if (q.isError) return <RefusalNotice refusal={q.error as never} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const p = q.data;
  return (
    <section data-protection={player}>
      <h2>{t("protection.player.title")}</h2>
      <ZoneNote zone={tz} />
      <p data-restrictions>
        {p.activeRestrictions.length === 0 ? t("protection.restrictions.none") : t("protection.restrictions", { restrictions: p.activeRestrictions.map((r) => word("protection.restriction", r)).join(", ") })}
      </p>
      <Limits player={player} p={p} done={done} />
      <Breaks player={player} p={p} maxDays={settings.data?.maxBreakDays} done={done} />
      <Exclusion player={player} p={p} done={done} />
      <PlayerCases player={player} cases={p.cases} done={done} />
    </section>
  );
}
