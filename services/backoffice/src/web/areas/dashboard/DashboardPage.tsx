// `/dashboard` (FR-032; spec 015's dashboard routes): today's figures in each currency the brand holds
// figures in, apart and never summed (FR-013); their trend over the chosen range; where players are on
// a map of the world; top games and players; rewards; payment health; and today's activity by hour.
// Each panel says how fresh its figures are in reporting's terms (FR-011). The range and the currency
// the panels after the figures are read in are in the URL, so a link opens the same view.
import type { ReactNode } from "react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import "./dashboard.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { holds } from "../../../shared/access.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { Glyph } from "../../components/glyph.tsx";
import { RecordLink } from "../../components/links.tsx";
import { DotGrid, Donut, LineChart, WorldMap } from "../../components/charts/index.ts";
import { useOperator } from "../../frame/context.ts";
import { Amount, useReportingStatus, ViewsFreshness, type ReportingStatus } from "../reports/figures.tsx";
import { drawn, useReporting, type Refused } from "../reports/read.ts";

// ── reporting's answers (spec 015 contracts/routes.md) ───────────────────────────────────────────

interface Count { value: number; yesterday: number; trend: string }
interface AmountKpi { value: string; yesterday: string; trend: string; currency: string }
interface Flow { amount: string; count: number; yesterdayAmount: string; trend: string; currency: string }
export interface Kpis {
  activeSessions: Count; activeGames: Count; playersOnline: Count; registrationsToday: Count;
  ggrToday: AmountKpi; ngrToday: AmountKpi; depositsToday: Flow; withdrawalsToday: Flow;
  currency: string; currencies: string[];
}
interface Trend { range: string; currency: string; dataPoints: { label: string; ggr: string; ngr: string }[] }
interface Geo { countries: { countryCode: string; playerCount: number }[] }
interface Rankings {
  games: { gameId: string; gameName: string; provider: string; sessionCount: number }[];
  players: { playerId: string; truncatedEmail: string; countryCode: string; ggrAmount: string; totalWagered: string; currency: string }[];
}
interface Bonus { activeBonusCount: number; totalLiability: string; bonusCostToday: string; avgWageringCompletionPct: number; currency: string }
interface Health { depositSuccessRatePct: number | null; avgWithdrawalHours: number | null; currency: string; pspBreakdown: { pspName: string; depositVolume: string; depositCount: number; successRatePct: number | null }[] }
interface Activity { events: { eventId: string; eventType: string; occurredAt: string }[] }

export const Ranges = ["today", "7d", "30d", "90d"] as const;
export type Range = (typeof Ranges)[number];
const RangeLabel: Record<Range, MessageKey> = { today: "dashboard.range.today", "7d": "dashboard.range.7d", "30d": "dashboard.range.30d", "90d": "dashboard.range.90d" };

/** The reporting views each panel's figures are folded from (spec 015 `Completeness.viewTopics`). */
const Views = {
  figures: ["days", "sessions", "rounds", "players"],
  trend: ["days"],
  map: ["players"],
  rankings: ["sessions", "rounds", "player-days", "players"],
  rewards: ["wallets", "days"],
  payments: ["days"],
  activity: ["activity"],
} as const;

function Panel({ id, title, views, status, children, wide }: { id: string; title: string; views: readonly string[]; status: ReportingStatus | undefined; children: ReactNode; wide?: boolean }) {
  return (
    <section className="bo-card" aria-labelledby={`dash-${id}`} data-panel={id} data-wide={wide || undefined}>
      <header className="bo-card-head">
        <h2 id={`dash-${id}`}>{title}</h2>
        <ViewsFreshness status={status} views={views} />
      </header>
      {children}
    </section>
  );
}

/** A panel's own read: its refusal shown in place, the others still shown. */
function Read<T>({ q, children }: { q: { data?: T; error: Refused | null; isPending: boolean }; children: (data: T) => ReactNode }) {
  if (q.error) return <RefusalNotice refusal={q.error.refusal} />;
  if (q.isPending || q.data === undefined) return <p className="bo-muted">{t("list.loading")}</p>;
  return <>{children(q.data)}</>;
}

const trendGlyph = (trend: string) => (trend === "up" ? "up" : trend === "down" ? "down" : "minus");
const trendWord = (trend: string): MessageKey => (trend === "up" ? "dashboard.trend.up" : trend === "down" ? "dashboard.trend.down" : "dashboard.trend.flat");

/** The change against yesterday to this hour: an arrow and a word, never colour alone. */
function Change({ trend, children }: { trend: string; children: ReactNode }) {
  return (
    <span className="bo-chip" data-trend={trend}>
      <Glyph name={trendGlyph(trend)} />
      <span>{t(trendWord(trend))}</span>
      <span className="bo-muted">{t("dashboard.yesterday")}</span>
      {children}
    </span>
  );
}

function Figure({ label, children, change }: { label: string; children: ReactNode; change: ReactNode }) {
  return (
    <div className="bo-figure">
      <dt>{label}</dt>
      <dd>
        <span className="bo-figure-value">{children}</span>
        {change}
      </dd>
    </div>
  );
}

/** One currency's figures for today, read in that currency alone. */
function CurrencyFigures({ currency, status }: { currency: string; status: ReportingStatus | undefined }) {
  const q = useReporting<Kpis>("/dashboard/kpis", { currency });
  return (
    <Panel id={`figures-${currency}`} title={t("dashboard.figuresIn", { currency })} views={Views.figures} status={status}>
      <Read q={q}>
        {(k) => (
          <dl className="bo-figures" data-currency={k.currency}>
            <Figure label={t("dashboard.ggr")} change={<Change trend={k.ggrToday.trend}><Amount text={k.ggrToday.yesterday} currency={k.currency} /></Change>}>
              <Amount text={k.ggrToday.value} currency={k.currency} />
            </Figure>
            <Figure label={t("dashboard.ngr")} change={<Change trend={k.ngrToday.trend}><Amount text={k.ngrToday.yesterday} currency={k.currency} /></Change>}>
              <Amount text={k.ngrToday.value} currency={k.currency} />
            </Figure>
            <Figure label={t("dashboard.deposits", { count: k.depositsToday.count })} change={<Change trend={k.depositsToday.trend}><Amount text={k.depositsToday.yesterdayAmount} currency={k.currency} /></Change>}>
              <Amount text={k.depositsToday.amount} currency={k.currency} />
            </Figure>
            <Figure label={t("dashboard.withdrawals", { count: k.withdrawalsToday.count })} change={<Change trend={k.withdrawalsToday.trend}><Amount text={k.withdrawalsToday.yesterdayAmount} currency={k.currency} /></Change>}>
              <Amount text={k.withdrawalsToday.amount} currency={k.currency} />
            </Figure>
          </dl>
        )}
      </Read>
    </Panel>
  );
}

/** The counts, which have no currency: shown once. */
function Counts({ k }: { k: Kpis }) {
  const counts: [MessageKey, Count][] = [
    ["dashboard.playersOnline", k.playersOnline],
    ["dashboard.activeSessions", k.activeSessions],
    ["dashboard.activeGames", k.activeGames],
    ["dashboard.registrations", k.registrationsToday],
  ];
  return (
    <dl className="bo-figures">
      {counts.map(([label, c]) => (
        <Figure key={label} label={t(label)} change={<Change trend={c.trend}><span className="figures">{c.yesterday}</span></Change>}>
          <span className="figures">{c.value}</span>
        </Figure>
      ))}
    </dl>
  );
}

function Pills<T extends string>({ label, values, chosen, word, onChoose }: { label: string; values: readonly T[]; chosen: T; word: (v: T) => string; onChoose: (v: T) => void }) {
  return (
    <div role="group" aria-label={label} className="bo-pills">
      {values.map((v) => (
        <button key={v} type="button" className="bo-pill" aria-pressed={v === chosen} onClick={() => onChoose(v)}>
          {word(v)}
        </button>
      ))}
    </div>
  );
}

const hours = Array.from({ length: 24 }, (_, h) => String(h).padStart(2, "0"));

/** Today's activity counted by kind and hour of the brand's zone. */
export function byHour(events: Activity["events"], zone: string): { rows: string[]; counts: number[][] } {
  const kinds = [...new Set(events.map((e) => e.eventType))].sort();
  const hourOf = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", hourCycle: "h23", timeZone: zone });
  const counts = kinds.map(() => hours.map(() => 0));
  for (const e of events) {
    const at = Date.parse(e.occurredAt);
    if (Number.isNaN(at)) continue;
    const h = Number(hourOf.format(at)) % 24;
    counts[kinds.indexOf(e.eventType)]![h]!++;
  }
  return { rows: kinds, counts };
}

const ActivityWord: Record<string, MessageKey> = {
  registration: "dashboard.activity.registration",
  bonus_grant: "dashboard.activity.bonus_grant",
  self_exclusion: "dashboard.activity.self_exclusion",
  suspension: "dashboard.activity.suspension",
};

const percent = (v: number | null | undefined) => (v === null || v === undefined ? t("dashboard.noAttempts") : t("chart.percent", { value: String(v) }));

export interface DashboardViewProps {
  range: Range;
  /** The currency the panels after the figures are read in; reporting's default when not chosen. */
  currency: string | undefined;
  onRange: (r: Range) => void;
  onCurrency: (c: string) => void;
  zone: string;
}

export function DashboardView({ range, currency, onRange, onCurrency, zone }: DashboardViewProps) {
  const me = useOperator();
  const status = useReportingStatus().data;
  const first = useReporting<Kpis>("/dashboard/kpis");
  const currencies = first.data ? [first.data.currency, ...first.data.currencies.filter((c) => c !== first.data!.currency).sort()] : [];
  const chosen = currency && currencies.includes(currency) ? currency : first.data?.currency;
  const ready = chosen !== undefined;
  const trend = useReporting<Trend>("/dashboard/trend", { range, currency: chosen }, ready);
  const geo = useReporting<Geo>("/dashboard/geo");
  const rankings = useReporting<Rankings>("/dashboard/rankings", { range, currency: chosen }, ready);
  const bonus = useReporting<Bonus>("/dashboard/bonus-summary", { currency: chosen }, ready && runsCasino(me));
  const health = useReporting<Health>("/dashboard/payment-health", { currency: chosen }, ready);
  const activity = useReporting<Activity>("/dashboard/activity");

  return (
    <section className="bo-dashboard">
      <header className="bo-dashboard-head">
        <h1>{t("area.dashboard")}</h1>
        <Pills label={t("dashboard.range")} values={Ranges} chosen={range} word={(r) => t(RangeLabel[r])} onChoose={onRange} />
        {currencies.length > 1 ? <Pills label={t("dashboard.currency")} values={currencies} chosen={chosen ?? currencies[0]!} word={(c) => c} onChoose={onCurrency} /> : null}
      </header>
      {!holds(me, "dashboard.read") ? <p className="bo-muted">{t("dashboard.needs")}</p> : null}

      {first.error ? <RefusalNotice refusal={first.error.refusal} /> : null}
      {first.data ? (
        <>
          <Panel id="now" title={t("dashboard.now")} views={Views.figures} status={status}>
            <Counts k={first.data} />
          </Panel>
          <div className="bo-dashboard-currencies">
            {currencies.map((c) => (
              <CurrencyFigures key={c} currency={c} status={status} />
            ))}
          </div>
        </>
      ) : null}

      <div className="bo-dashboard-grid">
        <Panel id="trend" title={t("dashboard.trendIn", { currency: chosen ?? "" })} views={Views.trend} status={status} wide>
          <Read q={trend}>
            {(d) => (
              <LineChart
                title={t("dashboard.trendTitle", { currency: d.currency, range: t(RangeLabel[range]) })}
                series={[
                  { name: t("dashboard.ggr"), points: d.dataPoints.map((p) => ({ label: p.label, value: drawn(p.ggr), text: <Amount text={p.ggr} currency={d.currency} /> })) },
                  { name: t("dashboard.ngr"), points: d.dataPoints.map((p) => ({ label: p.label, value: drawn(p.ngr), text: <Amount text={p.ngr} currency={d.currency} /> })) },
                ]}
              />
            )}
          </Read>
        </Panel>

        <Panel id="map" title={t("dashboard.map")} views={Views.map} status={status} wide>
          <p className="bo-muted">{t("dashboard.mapEvery")}</p>
          <Read q={geo}>
            {(g) => <WorldMap title={t("dashboard.mapTable")} figure={t("dashboard.players")} countries={g.countries.map((c) => ({ code: c.countryCode, count: c.playerCount }))} />}
          </Read>
        </Panel>

        <Panel id="games" title={t("dashboard.topGames", { range: t(RangeLabel[range]) })} views={Views.rankings} status={status}>
          <Read q={rankings}>
            {(r) => (
              <table className="bo-chart-table">
                <thead>
                  <tr>
                    <th scope="col">{t("dashboard.game")}</th>
                    <th scope="col">{t("dashboard.provider")}</th>
                    <th scope="col" data-numeric>{t("dashboard.sessions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {r.games.length === 0 ? <tr><td colSpan={3}>{t("chart.none")}</td></tr> : r.games.map((g) => (
                    <tr key={g.gameId}>
                      <th scope="row">{g.gameName || g.gameId}</th>
                      <td>{g.provider}</td>
                      <td data-numeric>{g.sessionCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Read>
        </Panel>

        <Panel id="players" title={t("dashboard.topPlayers", { range: t(RangeLabel[range]), currency: chosen ?? "" })} views={Views.rankings} status={status}>
          <Read q={rankings}>
            {(r) => (
              <table className="bo-chart-table">
                <thead>
                  <tr>
                    <th scope="col">{t("dashboard.player")}</th>
                    <th scope="col">{t("chart.country")}</th>
                    <th scope="col" data-numeric>{t("dashboard.ggr")}</th>
                    <th scope="col" data-numeric>{t("dashboard.wagered")}</th>
                  </tr>
                </thead>
                <tbody>
                  {r.players.length === 0 ? <tr><td colSpan={4}>{t("chart.none")}</td></tr> : r.players.map((p) => (
                    <tr key={p.playerId}>
                      <th scope="row">
                        {holds(me, "player.read") ? <RecordLink to={{ kind: "player", id: p.playerId }}>{p.truncatedEmail || p.playerId}</RecordLink> : (p.truncatedEmail || p.playerId)}
                      </th>
                      <td>{p.countryCode}</td>
                      <td data-numeric><Amount text={p.ggrAmount} currency={p.currency} /></td>
                      <td data-numeric><Amount text={p.totalWagered} currency={p.currency} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Read>
        </Panel>

        {runsCasino(me) ? (
          <Panel id="rewards" title={t("dashboard.rewards", { currency: chosen ?? "" })} views={Views.rewards} status={status}>
            <Read q={bonus}>
              {(b) => (
                <dl className="bo-figures">
                  <Figure label={t("dashboard.activeRewards")} change={null}><span className="figures">{b.activeBonusCount}</span></Figure>
                  <Figure label={t("dashboard.liability")} change={null}><Amount text={b.totalLiability} currency={b.currency} /></Figure>
                  <Figure label={t("dashboard.rewardCost")} change={null}><Amount text={b.bonusCostToday} currency={b.currency} /></Figure>
                  <Figure label={t("dashboard.wagering")} change={null}><span className="figures">{t("chart.percent", { value: b.avgWageringCompletionPct })}</span></Figure>
                </dl>
              )}
            </Read>
          </Panel>
        ) : null}

        <Panel id="payments" title={t("dashboard.payments", { currency: chosen ?? "" })} views={Views.payments} status={status}>
          <Read q={health}>
            {(h) => (
              <>
                <dl className="bo-figures">
                  <Figure label={t("dashboard.depositSuccess")} change={null}><span className="figures">{percent(h.depositSuccessRatePct)}</span></Figure>
                  <Figure label={t("dashboard.withdrawalHours")} change={null}>
                    <span className="figures">{h.avgWithdrawalHours === null ? t("dashboard.noWithdrawals") : t("dashboard.hours", { hours: String(h.avgWithdrawalHours) })}</span>
                  </Figure>
                </dl>
                <Donut
                  title={t("dashboard.depositsByProvider", { currency: h.currency })}
                  slices={h.pspBreakdown.map((p) => ({ name: p.pspName, value: drawn(p.depositVolume), text: <><Amount text={p.depositVolume} currency={h.currency} />{" · "}{t("dashboard.providerLine", { count: p.depositCount, rate: percent(p.successRatePct) })}</> }))}
                />
              </>
            )}
          </Read>
        </Panel>

        <Panel id="activity" title={t("dashboard.activity")} views={Views.activity} status={status} wide>
          <Read q={activity}>
            {(a) => {
              const g = byHour(a.events, zone);
              return g.rows.length === 0 ? (
                <p className="bo-muted">{t("dashboard.noActivity")}</p>
              ) : (
                <DotGrid title={t("dashboard.activityTitle", { zone })} rows={g.rows.map((r) => (ActivityWord[r] ? t(ActivityWord[r]!) : r))} columns={hours} counts={g.counts} />
              );
            }}
          </Read>
        </Panel>
      </div>
    </section>
  );
}

const runsCasino = (me: { brand: { modules: string[] | null } }) => me.brand.modules === null || me.brand.modules.includes("casino");

export function rangeOf(v: unknown): Range {
  return (Ranges as readonly string[]).includes(String(v)) ? (v as Range) : "7d";
}

export function DashboardPage() {
  const search = useSearch({ strict: false }) as Record<string, unknown>;
  const navigate = useNavigate();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const set = (key: "range" | "currency", value: string) =>
    void navigate({ to: "/dashboard", search: (prev: Record<string, unknown>) => ({ ...prev, [key]: value }), replace: true });
  return (
    <DashboardView
      range={rangeOf(search.range)}
      currency={typeof search.currency === "string" ? search.currency : undefined}
      onRange={(r) => set("range", r)}
      onCurrency={(c) => set("currency", c)}
      zone={zone}
    />
  );
}
