// The brand's offers (T092; US6, parity *Offers: create, edit, deactivate, reorder, dependants named, free
// rounds values, games picker*): rewards' offers in the brand's order, each with the offers that depend
// on it (those that exclude it or conflict with it), named before it is changed or withdrawn. An offer is
// created, edited (only the fields changed are sent, a field cleared as `null`, as rewards' patch reads
// them), moved up or down and the order saved, or withdrawn (rewards deactivates it; it is never removed).
// A free-rounds offer's game is picked from the brand's game catalogue, and the value of a round is shown
// beside the values the provider offers for that game in each currency (rewards' spin values).
//
// Amounts are minor units at rewards. An offer in a currency takes them in that currency's own figures;
// an offer in the player's currency (none named) takes them in minor units.
import { useId, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./rewards.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { formatMoney, parseMoney } from "../../../shared/money.ts";
import { gameProvider, rewards } from "../../../shared/owners/index.ts";
import { explain } from "../../../shared/refusals.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { ZoneNote } from "../../components/date.tsx";
import { Glyph } from "../../components/glyph.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { dependantsOf, readOffers, type Offer } from "./read.ts";

const Types = ["DEPOSIT_MATCH", "FREE_SPINS", "DEPOSIT_MATCH_WITH_FREE_SPINS", "NO_DEPOSIT"] as const;
const Destinations = ["real_balance", "bonus_balance"] as const;
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const withRounds = (type: string) => type === "FREE_SPINS" || type === "DEPOSIT_MATCH_WITH_FREE_SPINS";

// --- the form --------------------------------------------------------------------------------------

/** An offer's fields as typed. */
export interface Form {
  name: string;
  description: string;
  type: string;
  currency: string;
  bonusPercentage: string;
  maxBonusValue: string;
  minDepositAmount: string;
  maxConvertibleAmount: string;
  wageringMultiplier: string;
  wageringWindowDays: string;
  claimWindowDays: string;
  maxClaimsPerPlayer: string;
  maxConcurrentGrants: string;
  depositNumberTarget: string;
  noDepositRequired: boolean;
  validFrom: string;
  validUntil: string;
  excludedOfferIds: string[];
  conflictsWith: string[];
  codes: string;
  gameId: string;
  gameName: string;
  provider: string;
  spinCount: string;
  spinValue: string;
  winningsDestination: string;
  freeSpinsExpiryDays: string;
  spinConfigurations: { currency: string; spinValue: string }[];
}

const MoneyFields = ["maxBonusValue", "minDepositAmount", "maxConvertibleAmount"] as const;
const WholeFields = ["wageringMultiplier", "wageringWindowDays", "claimWindowDays", "maxClaimsPerPlayer", "maxConcurrentGrants", "freeSpinsExpiryDays", "spinCount"] as const;

/** Minor units as typed figures: in the currency's own when there is one, else the minor units. */
function figures(minor: number | null | undefined, currency: string): string {
  if (minor === null || minor === undefined) return "";
  if (!currency) return String(minor);
  try {
    return formatMoney({ minor, currency }).replace(/[^\d.,-]/g, "").replace(/,/g, "");
  } catch {
    return String(minor);
  }
}

/** An instant as the operator's own local time for `datetime-local`, and back. */
function local(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
const instant = (text: string) => (text ? new Date(text).toISOString() : null);

export function formOf(o?: Offer): Form {
  const cur = o?.currency ?? "";
  const n = (v: number | null | undefined) => (v === null || v === undefined ? "" : String(v));
  return {
    name: o?.name ?? "",
    description: o?.description ?? "",
    type: o?.type ?? "DEPOSIT_MATCH",
    currency: cur,
    bonusPercentage: o?.bonusPercentage ? String(o.bonusPercentage / 100) : "",
    maxBonusValue: figures(o?.maxBonusValue, cur),
    minDepositAmount: figures(o?.minDepositAmount, cur),
    maxConvertibleAmount: figures(o?.maxConvertibleAmount, cur),
    wageringMultiplier: n(o?.wageringMultiplier),
    wageringWindowDays: n(o?.wageringWindowDays),
    claimWindowDays: n(o?.claimWindowDays),
    maxClaimsPerPlayer: n(o?.maxClaimsPerPlayer),
    maxConcurrentGrants: n(o?.maxConcurrentGrants),
    depositNumberTarget: o?.depositNumberTarget === undefined || o.depositNumberTarget === null || o.depositNumberTarget === "ANY" ? "" : String(o.depositNumberTarget),
    noDepositRequired: o?.noDepositRequired ?? false,
    validFrom: local(o?.validFrom),
    validUntil: local(o?.validUntil),
    excludedOfferIds: o?.excludedOfferIds ?? [],
    conflictsWith: o?.conflictsWith ?? [],
    codes: (o?.redemptionCodes ?? []).map((c) => c.code).join(", "),
    gameId: o?.gameId ?? "",
    gameName: o?.gameName ?? "",
    provider: o?.provider ?? "",
    spinCount: n(o?.spinCount),
    spinValue: figures(o?.spinValue, cur),
    winningsDestination: o?.winningsDestination ?? "",
    freeSpinsExpiryDays: n(o?.freeSpinsExpiryDays),
    spinConfigurations: (o?.spinConfigurations ?? []).map((c) => ({ currency: c.currency, spinValue: figures(c.spinValue, c.currency) })),
  };
}

type Body = Record<string, unknown>;

/** The form as rewards reads an offer's body, or the first field that cannot be read. */
export function bodyOf(f: Form, codes: Offer["redemptionCodes"] = []): { body: Body } | { invalid: MessageKey } {
  const cur = f.currency.trim().toUpperCase();
  if (cur !== "" && !/^[A-Z]{3}$/.test(cur)) return { invalid: "offers.field.currency" };
  const minor = (text: string, currency: string): number | null | undefined => {
    if (text.trim() === "") return null;
    if (!currency) return /^\d+$/.test(text.trim()) ? Number(text.trim()) : undefined;
    try {
      const m = parseMoney(text, currency);
      return m === undefined || m < 0n ? undefined : Number(m);
    } catch {
      return undefined;
    }
  };
  const whole = (text: string): number | null | undefined => (text.trim() === "" ? null : /^\d+$/.test(text.trim()) ? Number(text.trim()) : undefined);
  const body: Body = { name: f.name.trim(), description: f.description.trim(), type: f.type, currency: cur === "" ? null : cur, noDepositRequired: f.noDepositRequired };
  if (f.bonusPercentage.trim() === "") body.bonusPercentage = 0;
  else if (/^\d+(\.\d{1,2})?$/.test(f.bonusPercentage.trim())) body.bonusPercentage = Math.round(Number(f.bonusPercentage.trim()) * 100);
  else return { invalid: "offers.field.bonusPercentage" };
  for (const k of MoneyFields) {
    const v = minor(f[k], cur);
    if (v === undefined) return { invalid: `offers.field.${k}` };
    body[k] = v;
  }
  for (const k of WholeFields) {
    const v = whole(f[k]);
    if (v === undefined) return { invalid: `offers.field.${k}` };
    body[k] = v;
  }
  if (body.wageringMultiplier === null) body.wageringMultiplier = 0;
  const target = whole(f.depositNumberTarget);
  if (target === undefined) return { invalid: "offers.field.depositNumberTarget" };
  body.depositNumberTarget = target ?? "ANY";
  body.validFrom = instant(f.validFrom);
  body.validUntil = instant(f.validUntil);
  body.excludedOfferIds = [...f.excludedOfferIds].sort();
  body.conflictsWith = [...f.conflictsWith].sort();
  const affiliate = new Map((codes ?? []).map((c) => [c.code, c.isAffiliate ?? false]));
  body.redemptionCodes = f.codes
    .split(",")
    .map((c) => c.trim())
    .filter((c) => c !== "")
    .map((code) => ({ code, isAffiliate: affiliate.get(code) ?? false }));
  if (withRounds(f.type)) {
    body.gameId = f.gameId.trim() === "" ? null : f.gameId.trim();
    body.gameName = f.gameName.trim() === "" ? null : f.gameName.trim();
    body.provider = f.provider.trim() === "" ? null : f.provider.trim();
    const spin = minor(f.spinValue, cur);
    if (spin === undefined) return { invalid: "offers.field.spinValue" };
    body.spinValue = spin;
    body.winningsDestination = f.winningsDestination === "" ? null : f.winningsDestination;
    const configs: { currency: string; spinValue: number }[] = [];
    for (const c of f.spinConfigurations) {
      const code = c.currency.trim().toUpperCase();
      const v = /^[A-Z]{3}$/.test(code) ? minor(c.spinValue, code) : undefined;
      if (v === undefined || v === null) return { invalid: "offers.spinConfigurations" };
      configs.push({ currency: code, spinValue: v });
    }
    body.spinConfigurations = configs;
  }
  return { body };
}

/** An edit's body: the fields the operator changed, a field cleared as `null` (rewards' patch). */
export function changesOf(before: Body, after: Body): Body {
  const changed: Body = {};
  for (const k of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const a = k in after ? after[k] : null;
    if (JSON.stringify(before[k] ?? null) !== JSON.stringify(a)) changed[k] = a;
  }
  return changed;
}

// --- the form's parts --------------------------------------------------------------------------------

function Text({ named, value, onChange, type = "text", inputMode, wide }: { named: MessageKey; value: string; onChange(v: string): void; type?: string; inputMode?: "decimal" | "numeric"; wide?: boolean }) {
  const id = useId();
  return (
    <div className={wide ? "bo-field bo-wide" : "bo-field"}>
      <label htmlFor={id}>{t(named)}</label>
      <input id={id} className="bo-input" type={type} inputMode={inputMode} value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

function OffersChoice({ legend, offers, chosen, onChange }: { legend: MessageKey; offers: readonly Offer[]; chosen: readonly string[]; onChange(ids: string[]): void }) {
  return (
    <fieldset>
      <legend>{t(legend)}</legend>
      {offers.map((o) => (
        <label key={o.id} className="bo-choice">
          <input
            type="checkbox"
            checked={chosen.includes(o.id)}
            onChange={(e) => onChange(e.target.checked ? [...chosen, o.id] : chosen.filter((x) => x !== o.id))}
          />
          {o.name}
        </label>
      ))}
    </fieldset>
  );
}

interface Game {
  code: string;
  provider: string;
  name: string;
  effectiveEnabled?: boolean;
}

/** The brand's game catalogue, searched by name or code (game-provider `game-catalog`, `config.games`). */
function GamePicker({ form, set }: { form: Form; set(f: Partial<Form>): void }) {
  const id = useId();
  const [typed, setTyped] = useState("");
  const games = useQuery({
    queryKey: ["game-provider", "catalogue"],
    queryFn: async () => {
      const r = await gameProvider.getGameCatalog();
      if (!r.ok) throw explain(r.status, await r.text());
      return ((await r.json()) as { games: Game[] }).games;
    },
    retry: false,
  });
  if (games.isError)
    return (
      <div className="bo-field">
        <Text named="offers.field.game" value={form.gameId} onChange={(v) => set({ gameId: v })} />
        <span className="bo-field-hint">{t("offers.games.unread")}</span>
      </div>
    );
  const term = typed.trim().toLowerCase();
  const found = term === "" ? [] : (games.data ?? []).filter((g) => g.effectiveEnabled !== false && (g.name.toLowerCase().includes(term) || g.code.toLowerCase().includes(term))).slice(0, 20);
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t("offers.games.search")}</label>
      <input id={id} className="bo-input" type="search" value={typed} onChange={(e) => setTyped(e.target.value)} />
      {form.gameId ? <span data-game={form.gameId}>{t("offers.games.chosen", { name: form.gameName || form.gameId, gateway: form.provider || "—" })}</span> : null}
      {term !== "" && found.length === 0 ? <span className="bo-field-hint">{t("offers.games.none")}</span> : null}
      {found.length > 0 ? (
        <ul className="bo-games">
          {found.map((g) => (
            <li key={`${g.provider}/${g.code}`}>
              <button type="button" className="bo-button" onClick={() => (set({ gameId: g.code, gameName: g.name, provider: g.provider }), setTyped(""))}>
                {t("offers.games.chosen", { name: g.name, gateway: g.provider })}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** The values the provider offers for the game in a currency (rewards' spin values), beside the value typed. */
function SpinValues({ gameId, currency }: { gameId: string; currency: string }) {
  const code = currency.trim().toUpperCase();
  const q = useQuery({
    queryKey: ["rewards", "spin-values", gameId, code],
    queryFn: async () => {
      const r = await rewards.getOffersFreeRoundsSpinValues({ query: { gameId, currency: code } });
      if (!r.ok) throw explain(r.status, await r.text());
      return (await r.json()) as { values: number[]; source: string };
    },
    enabled: gameId !== "" && /^[A-Z]{3}$/.test(code),
    retry: false,
  });
  if (!q.data) return null;
  if (q.data.values.length === 0) return <span className="bo-field-hint">{t("offers.spinValues.none", { currency: code })}</span>;
  const values = q.data.values.map((v) => {
    try {
      return formatMoney({ minor: v, currency: code });
    } catch {
      return String(v);
    }
  });
  return <span className="bo-field-hint">{t("offers.spinValues", { currency: code, values: values.join(", ") })}</span>;
}

function Dependants({ id, offers }: { id: string; offers: readonly Offer[] }) {
  const named = dependantsOf(id, offers);
  if (named.length === 0) return <p className="bo-muted">{t("offers.dependants.none")}</p>;
  return (
    <div>
      <p>{t("offers.dependants.title")}</p>
      <ul className="bo-dependants">
        {named.map(({ offer, how }) => (
          <li key={offer.id} data-dependant={offer.id}>
            {t(how === "excludes" ? "offers.dependants.excludes" : "offers.dependants.conflicts", { name: offer.name })}
          </li>
        ))}
      </ul>
    </div>
  );
}

function OfferForm({ editing, offers, onClose }: { editing: Offer | undefined; offers: readonly Offer[]; onClose(): void }) {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const [form, setForm] = useState<Form>(() => formOf(editing));
  const set = (f: Partial<Form>) => setForm((prev) => ({ ...prev, ...f }));
  const others = offers.filter((o) => o.id !== editing?.id);
  const read = bodyOf(form, editing?.redemptionCodes);
  const before = editing ? bodyOf(formOf(editing), editing.redemptionCodes) : undefined;
  const changes = "body" in read && before && "body" in before ? changesOf(before.body, read.body) : undefined;
  const ready = "body" in read && read.body.name !== "" && (editing === undefined || Object.keys(changes ?? {}).length > 0);
  const run = () =>
    editing
      ? rewards.patchOffersByOfferId({ offerId: editing.id }, { body: changes ?? {} })
      : rewards.postOffers({ body: "body" in read ? Object.fromEntries(Object.entries(read.body).filter(([, v]) => v !== null)) : {} });
  const done = () => {
    void client.invalidateQueries({ queryKey: ["rewards", "offers"] });
    onClose();
  };
  const action = editing ? Actions["offer.edit"] : Actions["offer.create"];
  const subject: ReactNode = editing ? <Dependants id={editing.id} offers={offers} /> : undefined;
  return (
    <section className="bo-withdrawal-section" aria-label={editing ? t("offers.form.edit", { name: editing.name }) : t("offers.form.new")}>
      <h2>{editing ? t("offers.form.edit", { name: editing.name }) : t("offers.form.new")}</h2>
      <form className="bo-offer-form" onSubmit={(e) => e.preventDefault()}>
        <Text named="offers.field.name" value={form.name} onChange={(v) => set({ name: v })} />
        <Text named="offers.field.description" value={form.description} onChange={(v) => set({ description: v })} wide />
        <TypeChoice value={form.type} onChange={(v) => set({ type: v })} />
        <Text named="offers.field.currency" value={form.currency} onChange={(v) => set({ currency: v })} />
        {form.currency.trim() === "" ? <p className="bo-field-hint bo-wide">{t("offers.field.minorUnits")}</p> : null}
        <Text named="offers.field.bonusPercentage" value={form.bonusPercentage} inputMode="decimal" onChange={(v) => set({ bonusPercentage: v })} />
        {MoneyFields.map((k) => (
          <Text key={k} named={`offers.field.${k}`} value={form[k]} inputMode="decimal" onChange={(v) => set({ [k]: v })} />
        ))}
        {(["wageringMultiplier", "wageringWindowDays", "claimWindowDays", "maxClaimsPerPlayer", "maxConcurrentGrants", "depositNumberTarget"] as const).map((k) => (
          <Text key={k} named={`offers.field.${k}`} value={form[k]} inputMode="numeric" onChange={(v) => set({ [k]: v })} />
        ))}
        <label className="bo-choice">
          <input type="checkbox" checked={form.noDepositRequired} onChange={(e) => set({ noDepositRequired: e.target.checked })} />
          {t("offers.field.noDepositRequired")}
        </label>
        <Text named="offers.field.validFrom" type="datetime-local" value={form.validFrom} onChange={(v) => set({ validFrom: v })} />
        <Text named="offers.field.validUntil" type="datetime-local" value={form.validUntil} onChange={(v) => set({ validUntil: v })} />
        <p className="bo-wide">
          <ZoneNote zone={zone()} />
        </p>
        <Text named="offers.field.codes" value={form.codes} onChange={(v) => set({ codes: v })} wide />
        {others.length > 0 ? (
          <>
            <OffersChoice legend="offers.field.excluded" offers={others} chosen={form.excludedOfferIds} onChange={(ids) => set({ excludedOfferIds: ids })} />
            <OffersChoice legend="offers.field.conflicts" offers={others} chosen={form.conflictsWith} onChange={(ids) => set({ conflictsWith: ids })} />
          </>
        ) : null}
        {withRounds(form.type) ? <FreeRoundsFields form={form} set={set} /> : null}
      </form>
      {"invalid" in read ? <RefusalNotice refusal={{ message: { key: "refusal.invalid_body", values: { detail: t(read.invalid) } } }} /> : null}
      <div className="bo-offers-bar">
        <Action action={action} me={me} bags={bags} subject={subject} ready={ready} run={run} onDone={done} />
        <button type="button" className="bo-button" onClick={onClose}>
          {t("offers.form.close")}
        </button>
      </div>
    </section>
  );
}

function TypeChoice({ value, onChange }: { value: string; onChange(v: string): void }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t("offers.field.type")}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {Types.map((ty) => (
          <option key={ty} value={ty}>
            {word("rewards.type", ty.toLowerCase())}
          </option>
        ))}
      </select>
    </div>
  );
}

function FreeRoundsFields({ form, set }: { form: Form; set(f: Partial<Form>): void }) {
  const destination = useId();
  const configs = form.spinConfigurations;
  const setConfig = (i: number, c: Partial<Form["spinConfigurations"][number]>) => set({ spinConfigurations: configs.map((x, j) => (j === i ? { ...x, ...c } : x)) });
  return (
    <fieldset>
      <legend>{t("offers.freeRounds")}</legend>
      <GamePicker form={form} set={set} />
      <Text named="offers.field.spinCount" value={form.spinCount} inputMode="numeric" onChange={(v) => set({ spinCount: v })} />
      <div className="bo-field">
        <Text named="offers.field.spinValue" value={form.spinValue} inputMode="decimal" onChange={(v) => set({ spinValue: v })} />
        {form.currency ? <SpinValues gameId={form.gameId} currency={form.currency} /> : null}
      </div>
      <div className="bo-field">
        <label htmlFor={destination}>{t("offers.field.destination")}</label>
        <select id={destination} value={form.winningsDestination} onChange={(e) => set({ winningsDestination: e.target.value })}>
          <option value="">{t("rewards.destination.real_balance")}</option>
          {Destinations.filter((d) => d !== "real_balance").map((d) => (
            <option key={d} value={d}>
              {t(`rewards.destination.${d}`)}
            </option>
          ))}
        </select>
      </div>
      <Text named="offers.field.freeSpinsExpiryDays" value={form.freeSpinsExpiryDays} inputMode="numeric" onChange={(v) => set({ freeSpinsExpiryDays: v })} />
      <div className="bo-field bo-wide">
        <span>{t("offers.spinConfigurations")}</span>
        {configs.map((c, i) => (
          <div key={i} className="bo-offers-bar" data-spin-configuration={i}>
            <Text named="offers.field.currency" value={c.currency} onChange={(v) => setConfig(i, { currency: v })} />
            <Text named="offers.field.spinValue" value={c.spinValue} inputMode="decimal" onChange={(v) => setConfig(i, { spinValue: v })} />
            <SpinValues gameId={form.gameId} currency={c.currency} />
            <button type="button" className="bo-button" onClick={() => set({ spinConfigurations: configs.filter((_, j) => j !== i) })}>
              {t("offers.spinConfigurations.remove", { currency: c.currency || "—" })}
            </button>
          </div>
        ))}
        <button type="button" className="bo-button" onClick={() => set({ spinConfigurations: [...configs, { currency: "", spinValue: "" }] })}>
          {t("offers.spinConfigurations.add")}
        </button>
      </div>
    </fieldset>
  );
}

// --- the list ----------------------------------------------------------------------------------------

function valueOf(o: Offer): string {
  const cur = o.currency ?? "";
  const money = (m: number | null | undefined) => (m === null || m === undefined ? "" : cur ? formatMoney({ minor: m, currency: cur }) : String(m));
  if (o.type === "FREE_SPINS") return t("rewards.freeRounds.worth", { count: o.spinCount ?? 0, amount: money(o.spinValue) || "—" });
  const match = o.bonusPercentage ? `${o.bonusPercentage / 100}%` : "";
  return [match, money(o.maxBonusValue)].filter(Boolean).join(" · ");
}

export function OffersPage() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["rewards", "offers"], queryFn: readOffers });
  const [order, setOrder] = useState<string[] | undefined>(undefined);
  const [editing, setEditing] = useState<{ id?: string } | undefined>(undefined);
  const offers = q.data ?? [];
  const byId = new Map(offers.map((o) => [o.id, o]));
  const shown = order ? order.flatMap((id) => byId.get(id) ?? []) : offers;
  const move = (i: number, by: -1 | 1) => {
    const ids = shown.map((o) => o.id);
    const j = i + by;
    if (j < 0 || j >= ids.length) return;
    [ids[i], ids[j]] = [ids[j]!, ids[i]!];
    setOrder(ids);
  };
  const changed = order !== undefined && order.join() !== offers.map((o) => o.id).join();
  const refresh = () => void client.invalidateQueries({ queryKey: ["rewards", "offers"] });
  return (
    <section className="bo-rewards bo-offers">
      <h1>{t("area.offers")}</h1>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {readOnly ? null : (
        <div className="bo-offers-bar">
          <button type="button" className="bo-button" onClick={() => setEditing({})}>
            {t("offers.new")}
          </button>
          {changed ? (
            <>
              <span role="status">{t("offers.orderChanged")}</span>
              <Action
                action={Actions["offer.reorder"]}
                me={me}
                bags={bags}
                run={() => rewards.putOffersOrder({ body: { orderedOfferIds: order } })}
                onDone={() => (setOrder(undefined), refresh())}
              />
            </>
          ) : null}
        </div>
      )}
      {editing ? (
        <OfferForm key={editing.id ?? "new"} editing={editing.id ? byId.get(editing.id) : undefined} offers={offers} onClose={() => setEditing(undefined)} />
      ) : null}
      {q.data && offers.length === 0 ? <p className="bo-muted">{t("offers.none")}</p> : null}
      {!q.data && !q.isError ? <p role="status">{t("list.loading")}</p> : null}
      {offers.length > 0 ? (
        <div className="bo-list">
          <table>
            <caption className="bo-sr-only">{t("offers.caption")}</caption>
            <thead>
              <tr>
                <th>{t("offers.col.order")}</th>
                <th>{t("offers.col.name")}</th>
                <th>{t("offers.col.type")}</th>
                <th>{t("offers.col.state")}</th>
                <th data-numeric>{t("offers.col.value")}</th>
                <th>{t("offers.col.dependants")}</th>
                <th>{t("offers.col.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((o, i) => {
                const named = dependantsOf(o.id, offers);
                return (
                  <tr key={o.id} data-offer={o.id}>
                    <td>
                      {i + 1}
                      {readOnly ? null : (
                        <>
                          {" "}
                          <button type="button" className="bo-button" disabled={i === 0} aria-label={t("offers.up", { name: o.name })} onClick={() => move(i, -1)}>
                            <Glyph name="up" />
                          </button>{" "}
                          <button type="button" className="bo-button" disabled={i === shown.length - 1} aria-label={t("offers.down", { name: o.name })} onClick={() => move(i, 1)}>
                            <Glyph name="down" />
                          </button>
                        </>
                      )}
                    </td>
                    <td>{o.name}</td>
                    <td>{word("rewards.type", o.type.toLowerCase())}</td>
                    <td>
                      <Status value={o.active ? "active" : "withdrawn"} tone={o.active ? "positive" : "neutral"} />
                    </td>
                    <td data-numeric>{valueOf(o)}</td>
                    <td>{named.map((d) => d.offer.name).join(", ")}</td>
                    <td data-actions>
                      {readOnly ? null : (
                        <>
                          <button type="button" className="bo-button" onClick={() => setEditing({ id: o.id })}>
                            {t("offers.edit")}
                          </button>
                          <Action
                            action={Actions["offer.deactivate"]}
                            me={me}
                            bags={bags}
                            state={o.active ? "active" : "withdrawn"}
                            subject={<Dependants id={o.id} offers={offers} />}
                            run={() => rewards.deleteOffersByOfferId({ offerId: o.id })}
                            onDone={refresh}
                          />
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  );
}
