// The brand's own configuration (T109; parity *Brand configuration*): the brand and its sites, the
// countries it admits, its currencies and zone, the withdrawal and wagering settings, the reward settings,
// the reporting settings, and the modules page with why a module is off. Each value is marked where it
// differs from the brand's default (FR-034). The owners answer no defaults of their own, so each field's
// default is the value its owner holds when no one has set it, named beside it:
//   players   BrandConfig (config/ConfigEntities.scala): reportingZone "UTC", singleSession false,
//             multiAccountDetection true, retentionDays none (5 years, OperatorStatusRoutes.DefaultRetentionDays);
//             CurrencyConfig: none offered, no default currency; AllowList: none (every country)
//   wallet    BrandConfig (config/BrandConfigEntity.scala): turnover 1 × gross wagered (TurnoverTerms.Default),
//             a game type's contribution 100 % (DefaultRate), bonus wagering counts, partial splits allowed,
//             no limit on concurrent awards
//   payouts   no weekly or monthly limit (limits/WithdrawalLimits.scala)
//   reporting ReportingSettingsState: reports tab shown, test accounts kept, no default currency, 500 rows,
//             7 days, 24 hours for each document kind (settings/ReportingSettings.scala)
// No owner of these answers a licence profile's values, so none is marked against one.
import { useId, useState } from "react";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { payouts, players, reporting, wallet } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { ConfigForm, type ConfigField, type ConfigValue } from "../../components/config-form.tsx";
import { Areas } from "../../frame/areas.ts";
import { ModulesPage } from "../../frame/pages.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { Link } from "@tanstack/react-router";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { ConfigPage, keyOf, Loaded, useRead, useSaved } from "./page.tsx";

const put = (f: (o: { body: unknown }) => Promise<Response>) => (body: unknown) => f({ body });

// --- the brand and its sites (players' brand-config) ---------------------------------------------------

interface BrandConfig {
  name: string;
  legalName: string;
  playerSiteBase: string;
  reportingZone: string;
  singleSession: boolean;
  multiAccountDetection: boolean;
  retentionDays: number | null;
  features: Record<string, boolean>;
}

const SiteFields: ConfigField[] = [
  { name: "name", label: "config.brand.name", kind: "text", default: "", hint: "config.brand.name.hint" },
  { name: "legalName", label: "config.brand.legalName", kind: "text", default: "", hint: "config.brand.legalName.hint" },
  { name: "playerSiteBase", label: "config.brand.playerSiteBase", kind: "text", default: "", hint: "config.brand.playerSiteBase.hint" },
  { name: "singleSession", label: "config.brand.singleSession", kind: "boolean", default: false, hint: "config.brand.singleSession.hint" },
  { name: "multiAccountDetection", label: "config.brand.multiAccountDetection", kind: "boolean", default: true, hint: "config.brand.multiAccountDetection.hint" },
  { name: "retentionDays", label: "config.brand.retentionDays", kind: "whole", optional: true, default: null, hint: "config.brand.retentionDays.hint" },
];

export function BrandSitePage() {
  const q = useRead<BrandConfig>(["players", "brand-config"], () => players.getBrandConfig());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.brand.title">
      <Loaded q={q}>
        {(c) => (
          <>
            <ConfigForm key={keyOf(c)} fields={SiteFields} values={c as unknown as Record<string, ConfigValue>} action={Actions["config.brandSite"]} save={put(players.putBrandConfig)} onDone={saved} />
            {Object.keys(c.features).length > 0 ? (
              <>
                <h2>{t("config.brand.features")}</h2>
                <ul>
                  {Object.entries(c.features).map(([f, on]) => (
                    <li key={f}>
                      {f}: {on ? t("config.form.yes") : t("config.form.no")}
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </>
        )}
      </Loaded>
    </ConfigPage>
  );
}

// --- the countries (players' allow-list) ---------------------------------------------------------------

const CountryFields: ConfigField[] = [{ name: "countries", label: "config.countries.list", kind: "list", default: [], hint: "config.countries.hint" }];

export function CountriesPage() {
  const q = useRead<{ countries: string[] }>(["players", "allow-list"], () => players.getAllowList());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.countries.title">
      <Loaded q={q}>
        {(c) => (
          <ConfigForm
            key={keyOf(c)}
            fields={CountryFields}
            values={c}
            action={Actions["config.countries"]}
            save={(_, next) => players.putAllowList({ body: { countries: (next.countries as string[]).map((s) => s.toUpperCase()) } })}
            onDone={saved}
          />
        )}
      </Loaded>
    </ConfigPage>
  );
}

// --- currencies and zone (players' currency-config, and brand-config's reporting zone) ------------------

interface CurrencyConfig {
  offered: string[];
  defaultCurrency: string | null;
}

const CurrencyFields: ConfigField[] = [
  { name: "offered", label: "config.currencies.offered", kind: "list", default: [], hint: "config.currencies.offered.hint" },
  { name: "defaultCurrency", label: "config.currencies.default", kind: "text", optional: true, default: null, hint: "config.currencies.default.hint" },
];
const ZoneFields: ConfigField[] = [{ name: "reportingZone", label: "config.currencies.zone", kind: "text", default: "UTC", hint: "config.currencies.zone.hint" }];

export function CurrenciesPage() {
  const currencies = useRead<CurrencyConfig>(["players", "currency-config"], () => players.getCurrencyConfig());
  const brand = useRead<BrandConfig>(["players", "brand-config"], () => players.getBrandConfig());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.currencies.title">
      <Loaded q={currencies}>
        {(c) => (
          <ConfigForm
            key={keyOf(c)}
            fields={CurrencyFields}
            values={c as unknown as Record<string, ConfigValue>}
            action={Actions["config.currencies"]}
            save={(change) =>
              players.putCurrencyConfig({
                body: Object.fromEntries(Object.entries(change).map(([k, v]) => [k, Array.isArray(v) ? v.map((s) => s.toUpperCase()) : typeof v === "string" ? v.toUpperCase() : v])),
              })
            }
            onDone={saved}
          />
        )}
      </Loaded>
      <h2>{t("config.currencies.zone")}</h2>
      <Loaded q={brand}>
        {(b) => (
          <ConfigForm primary={false} key={keyOf(b.reportingZone)} fields={ZoneFields} values={{ reportingZone: b.reportingZone }} action={Actions["config.zone"]} save={put(players.putBrandConfig)} onDone={saved} />
        )}
      </Loaded>
    </ConfigPage>
  );
}

// --- withdrawals: the turnover a deposit needs (wallet) and the limits per currency (payouts) ----------

interface WalletConfig {
  contributionRateByGameType?: Record<string, number>;
  bonusWageringCounts?: boolean;
  partialSplitsAllowed?: boolean;
  maxConcurrentAwards?: number | null;
  turnover?: { multiplier: number; definition: string };
}

const TurnoverFields: ConfigField[] = [
  { name: "multiplier", label: "config.withdrawals.multiplier", kind: "whole", default: 1, hint: "config.withdrawals.multiplier.hint" },
  { name: "definition", label: "config.withdrawals.definition", kind: "choice", choices: ["gross_wagered", "net_loss"], choicePrefix: "config.withdrawals.definition", default: "gross_wagered", hint: "config.withdrawals.definition.hint" },
];

function limitFields(currencies: readonly string[]): ConfigField[] {
  return currencies.flatMap((c): ConfigField[] => [
    { name: `${c}.weekly`, label: "config.withdrawals.weekly", kind: "money", currency: c, optional: true, default: null, hint: "config.withdrawals.weekly.hint" },
    { name: `${c}.monthly`, label: "config.withdrawals.monthly", kind: "money", currency: c, optional: true, default: null, hint: "config.withdrawals.monthly.hint" },
  ]);
}

export function WithdrawalSettingsPage() {
  const me = useOperator();
  const w = useRead<WalletConfig>(["wallet", "wallet-config"], () => wallet.getWalletConfig());
  const l = useRead<{ limits: Record<string, { weekly: number | null; monthly: number | null }> }>(["payouts", "withdrawal-config"], () => payouts.getWithdrawalConfig());
  const c = useRead<CurrencyConfig>(["players", "currency-config"], () => players.getCurrencyConfig());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.withdrawals.title">
      <h2>{t("config.withdrawals.turnover")}</h2>
      <Loaded q={w}>
        {(cfg) => {
          const turnover = cfg.turnover ?? { multiplier: 1, definition: "gross_wagered" };
          return (
            <ConfigForm
              key={keyOf(turnover)}
              fields={TurnoverFields}
              values={turnover}
              action={Actions["config.turnover"]}
              save={(_, next) => wallet.putWalletConfigTurnover({ body: { multiplier: next.multiplier, definition: next.definition } })}
              onDone={saved}
            />
          );
        }}
      </Loaded>
      <h2>{t("config.withdrawals.limits")}</h2>
      <Loaded q={l}>
        {(limits) => {
          // the brand's currencies (when the operator may read them) and every currency payouts holds a limit in
          const offered = holds(me, "config.brand") && c.data ? c.data.offered : [];
          const currencies = [...new Set([...offered, ...Object.keys(limits.limits)])].sort();
          if (currencies.length === 0) return <p>{t("config.withdrawals.noCurrencies")}</p>;
          const values = Object.fromEntries(currencies.flatMap((cur) => [[`${cur}.weekly`, limits.limits[cur]?.weekly ?? null], [`${cur}.monthly`, limits.limits[cur]?.monthly ?? null]]));
          return (
            <ConfigForm primary={false}
              key={keyOf(values)}
              fields={limitFields(currencies)}
              values={values}
              action={Actions["config.withdrawalLimits"]}
              // payouts replaces the whole set of limits, so every currency's two windows are sent
              save={(_, next) => payouts.putWithdrawalConfig({ body: { limits: Object.fromEntries(currencies.map((cur) => [cur, { weekly: next[`${cur}.weekly`], monthly: next[`${cur}.monthly`] }])) } })}
              onDone={saved}
            />
          );
        }}
      </Loaded>
    </ConfigPage>
  );
}

// --- reward settings (wallet W3, `PUT wallet-config/rewards`) ------------------------------------------

/** The game types the original sets a contribution for (BrandConfigPage); the wallet's default for any is 100 %. */
export const GameTypes = ["slots", "table", "live_dealer", "poker", "bingo", "sports"] as const;

const RewardFields: ConfigField[] = [
  ...GameTypes.map((g): ConfigField => ({ name: `rate.${g}`, label: `config.rewards.rate.${g}` as const, kind: "whole", default: 100 })),
  { name: "bonusWageringCounts", label: "config.rewards.bonusWageringCounts", kind: "boolean", default: true, hint: "config.rewards.bonusWageringCounts.hint" },
  { name: "partialSplitsAllowed", label: "config.rewards.partialSplitsAllowed", kind: "boolean", default: true, hint: "config.rewards.partialSplitsAllowed.hint" },
  { name: "maxConcurrentAwards", label: "config.rewards.maxConcurrentAwards", kind: "whole", optional: true, default: null, hint: "config.rewards.maxConcurrentAwards.hint" },
];

/** The wallet's body for W3 (contracts/amendments.md): the rates as one map, the other fields as changed. */
export function rewardBody(change: Record<string, ConfigValue>, next: Record<string, ConfigValue>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (Object.keys(change).some((k) => k.startsWith("rate."))) body.contributionRateByGameType = Object.fromEntries(GameTypes.map((g) => [g, next[`rate.${g}`]]));
  for (const k of ["bonusWageringCounts", "partialSplitsAllowed", "maxConcurrentAwards"]) if (k in change) body[k] = change[k];
  return body;
}

export function RewardSettingsPage() {
  const w = useRead<WalletConfig>(["wallet", "wallet-config"], () => wallet.getWalletConfig());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.rewards.title" note="config.rewards.note">
      <Loaded q={w}>
        {(cfg) => {
          const rates = cfg.contributionRateByGameType ?? {};
          const values: Record<string, ConfigValue> = {
            ...Object.fromEntries(GameTypes.map((g) => [`rate.${g}`, rates[g] ?? 100])),
            bonusWageringCounts: cfg.bonusWageringCounts ?? true,
            partialSplitsAllowed: cfg.partialSplitsAllowed ?? true,
            maxConcurrentAwards: cfg.maxConcurrentAwards ?? null,
          };
          return <ConfigForm key={keyOf(values)} fields={RewardFields} values={values} action={Actions["config.rewardSettings"]} save={(change, next) => wallet.putWalletConfigRewards({ body: rewardBody(change, next) })} onDone={saved} />;
        }}
      </Loaded>
    </ConfigPage>
  );
}

// --- reporting settings ----------------------------------------------------------------------------------

interface ReportingSettings {
  reportsTab: boolean;
  excludeTestAccounts: boolean;
  defaultCurrency: string | null;
  syncRows: number;
  syncDays: number;
  lifetimeHours: Record<string, number>;
}

const DocumentKinds = ["report-export", "dispute-report", "day-end-export", "compliance-export"] as const;

const ReportingFields: ConfigField[] = [
  { name: "reportsTab", label: "config.reporting.reportsTab", kind: "boolean", default: true, hint: "config.reporting.reportsTab.hint" },
  { name: "excludeTestAccounts", label: "config.reporting.excludeTestAccounts", kind: "boolean", default: false, hint: "config.reporting.excludeTestAccounts.hint" },
  { name: "defaultCurrency", label: "config.reporting.defaultCurrency", kind: "text", optional: true, default: null, hint: "config.reporting.defaultCurrency.hint" },
  { name: "syncRows", label: "config.reporting.syncRows", kind: "whole", default: 500, hint: "config.reporting.syncRows.hint" },
  { name: "syncDays", label: "config.reporting.syncDays", kind: "whole", default: 7, hint: "config.reporting.syncDays.hint" },
  ...DocumentKinds.map((k): ConfigField => ({ name: `lifetime.${k}`, label: `config.reporting.lifetime.${k}` as const, kind: "whole", default: 24 })),
];

export function ReportingSettingsPage() {
  const q = useRead<ReportingSettings>(["reporting", "settings"], () => reporting.getSettings());
  const saved = useSaved();
  return (
    <ConfigPage heading="config.reporting.title">
      <Loaded q={q}>
        {(s) => {
          const values: Record<string, ConfigValue> = {
            reportsTab: s.reportsTab,
            excludeTestAccounts: s.excludeTestAccounts,
            defaultCurrency: s.defaultCurrency,
            syncRows: s.syncRows,
            syncDays: s.syncDays,
            ...Object.fromEntries(DocumentKinds.map((k) => [`lifetime.${k}`, s.lifetimeHours[k] ?? 24])),
          };
          const save = (change: Record<string, ConfigValue>) => {
            const body: Record<string, unknown> = {};
            const lifetimes: Record<string, ConfigValue> = {};
            for (const [k, v] of Object.entries(change)) {
              if (k.startsWith("lifetime.")) lifetimes[k.slice("lifetime.".length)] = v;
              else body[k] = k === "defaultCurrency" && typeof v === "string" ? v.toUpperCase() : v;
            }
            if (Object.keys(lifetimes).length) body.lifetimeHours = lifetimes;
            return reporting.putSettings({ body });
          };
          return <ConfigForm key={keyOf(values)} fields={ReportingFields} values={values} action={Actions["config.reportingSettings"]} save={save} onDone={saved} />;
        }}
      </Loaded>
    </ConfigPage>
  );
}

// --- the modules (players' `modules`, the vendor's agent passed through) -------------------------------

interface ModuleState {
  module: string;
  status: string;
  version?: string;
  follow?: string;
  lastChange?: string;
  dataKeptUntil?: string;
}
interface ModulesAnswer {
  modules: ModuleState[];
  requests?: { module: string; note?: string | null; by?: string; at?: string }[];
  missingKeys?: string[];
}

/** The modules a brand may run (spec 021, decision 0055), beside the core. */
export const KnownModules = ["casino", "predictions", "reporting", "engagement", "support", "public-api", "backoffice"] as const;
const Running = new Set(["running", "enabled"]);

/** Why a module is off, in the catalogue's words: its state at the vendor, or that the brand is not entitled to it. */
export function whyOff(m: ModuleState | undefined, requested: boolean): string {
  if (!m) return requested ? t("config.modules.why.requested") : t("config.modules.why.notEntitled");
  return word("config.modules.why", m.status);
}

export function ModulesSettingsPage() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const q = useRead<ModulesAnswer>(["players", "modules"], () => players.getModules());
  const saved = useSaved();
  const [module, setModule] = useState("");
  const [note, setNote] = useState("");
  const ids = { module: useId(), note: useId() };
  return (
    <ConfigPage heading="config.modules.title">
      <p>{me.brand.modules === null ? t("frame.modulesUnknown") : t("frame.modulesWhy", { modules: me.brand.modules.join(", ") })}</p>
      <Loaded q={q}>
        {(a) => {
          const by = new Map(a.modules.map((m) => [m.module, m]));
          const requested = new Set((a.requests ?? []).map((r) => r.module));
          const names = [...new Set([...a.modules.map((m) => m.module), ...KnownModules])];
          const off = names.filter((n) => !Running.has(by.get(n)?.status ?? ""));
          return (
            <>
              <table className="bo-table">
                <thead>
                  <tr>
                    <th scope="col">{t("config.modules.module")}</th>
                    <th scope="col">{t("config.modules.state")}</th>
                    <th scope="col">{t("config.modules.version")}</th>
                    <th scope="col">{t("config.modules.areas")}</th>
                  </tr>
                </thead>
                <tbody>
                  {names.map((n) => {
                    const m = by.get(n);
                    const on = Running.has(m?.status ?? "");
                    return (
                      <tr key={n} data-module={n} data-on={on}>
                        <th scope="row">{n}</th>
                        <td>
                          <span className="bo-status" data-tone={on ? "positive" : "neutral"}>
                            {on ? t("config.modules.on") : t("config.modules.off", { reason: whyOff(m, requested.has(n)) })}
                          </span>
                        </td>
                        <td>{m?.version ? `${m.version}${m.follow ? ` (${m.follow})` : ""}` : t("config.form.none")}</td>
                        <td>
                          {Areas.filter((ar) => ar.module === n).map((ar) => (
                            <span key={ar.id}>{t(ar.label)} </span>
                          ))}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {a.missingKeys?.length ? <p className="bo-field-error">{t("config.modules.missingKeys", { fields: a.missingKeys.join(", ") })}</p> : null}
              {(a.requests ?? []).length ? (
                <>
                  <h2>{t("config.modules.requests")}</h2>
                  <ul>
                    {(a.requests ?? []).map((r, i) => (
                      <li key={i}>{t("config.modules.request", { module: r.module, note: r.note ?? "" })}</li>
                    ))}
                  </ul>
                </>
              ) : null}
              {readOnly || off.length === 0 ? null : (
                <Action
                  action={Actions["module.request"]}
                  me={me}
                  bags={bags}
                  ready={module !== ""}
                  subject={
                    <>
                      <div className="bo-field">
                        <label htmlFor={ids.module}>{t("config.modules.module")}</label>
                        <select id={ids.module} value={module} onChange={(e) => setModule(e.target.value)}>
                          <option value="">{t("config.modules.choose")}</option>
                          {off.map((n) => (
                            <option key={n} value={n}>
                              {n}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="bo-field">
                        <label htmlFor={ids.note}>{t("action.note")}</label>
                        <textarea id={ids.note} rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
                      </div>
                    </>
                  }
                  run={() => players.postModulesRequests({ body: { module, note: note.trim() === "" ? undefined : note.trim() } })}
                  onDone={saved}
                />
              )}
            </>
          );
        }}
      </Loaded>
    </ConfigPage>
  );
}

/** The modules page for an operator who may read the vendor's answer; the frame's own otherwise. */
export function ModulesRoute() {
  return holds(useOperator(), "modules.read") ? <ModulesSettingsPage /> : <ModulesPage />;
}

// --- the configuration's own page: what the operator may open ------------------------------------------

/** The configuration's pages, each with the permission its owner requires and the module that runs it. */
export const ConfigurationPages = [
  { path: "/configuration/brand", label: "config.brand.title", permission: "config.brand", module: "core" },
  { path: "/configuration/countries", label: "config.countries.title", permission: "config.brand", module: "core" },
  { path: "/configuration/currencies", label: "config.currencies.title", permission: "config.brand", module: "core" },
  { path: "/configuration/withdrawals", label: "config.withdrawals.title", permission: "config.brand", module: "core" },
  { path: "/configuration/rewards", label: "config.rewards.title", permission: "config.brand", module: "casino" },
  { path: "/configuration/reporting", label: "config.reporting.title", permission: "config.brand", module: "reporting" },
  { path: "/configuration/identity-policy", label: "config.identity.title", permission: "config.brand", module: "core" },
  { path: "/configuration/protection-settings", label: "config.protection.title", permission: "config.brand", module: "core" },
  { path: "/configuration/games", label: "config.games.title", permission: "config.games", module: "casino" },
  { path: "/configuration/callbacks", label: "config.callbacks.title", permission: "callback.resolve", module: "casino" },
  { path: "/configuration/crm", label: "config.crm.title", permission: "config.crm", module: "engagement" },
  { path: "/configuration/sends", label: "config.sends.title", permission: "send.release", module: "engagement" },
  { path: "/configuration/agent", label: "config.agent.title", permission: "config.agent", module: "support" },
  { path: "/configuration/attribution", label: "config.attribution.title", permission: "config.attribution", module: "core" },
  { path: "/configuration/modules", label: "config.modules.title", permission: "modules.read", module: "core" },
] as const;

export function ConfigurationIndexPage() {
  const me = useOperator();
  const shown = ConfigurationPages.filter((p) => holds(me, p.permission) && (me.brand.modules === null || p.module === "core" || me.brand.modules.includes(p.module)));
  return (
    <ConfigPage heading="area.configuration">
      <ul className="bo-config-index">
        {shown.map((p) => (
          <li key={p.path}>
            <Link to={p.path} className="bo-link">
              {t(p.label)}
            </Link>
          </li>
        ))}
      </ul>
    </ConfigPage>
  );
}
