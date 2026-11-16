// The brand's games (T110; parity *Game providers* and *Game catalogue*): each configured provider and its
// configuration, set again as a whole (game-provider's `PUT providers/{p}` takes every field, the operator
// key included, which it never answers); the catalogue's refresh and its state; and the catalogue's
// configuration: developers and categories turned off, and a game's own override. game-provider's
// defaults (CatalogueConfiguration): nothing turned off and no override, so each of those is marked as
// differing from the brand's default (FR-034); a provider's configuration has no default.
import { useId, useState } from "react";
import { holds } from "../../../shared/access.ts";
import { t } from "../../../shared/messages/index.ts";
import { gameProvider } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { ConfigForm, type ConfigField } from "../../components/config-form.tsx";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { ConfigPage, keyOf, Loaded, useRead, useSaved } from "./page.tsx";

interface Provider {
  provider: string;
  apiUrl: string;
  site: string;
  providerPublicKey?: string | null;
  providerKeyDocumentUrl?: string | null;
  jurisdiction: string;
  lobbyUrl: string;
  depositUrl: string;
  walletUrl?: string | null;
  updatedAt?: string | null;
  operatorPublicKey?: string | null;
}

interface Refresh {
  provider: string;
  status: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  count?: number | null;
  removed?: number | null;
  error?: string | null;
}

export interface Game {
  code: string;
  provider: string;
  name: string;
  category: string;
  developer: string;
  st8Enabled: boolean;
  removedFromFeed: boolean;
  individualOverride: boolean | null;
  categoryDisabled: boolean;
  developerDisabled: boolean;
  effectiveEnabled: boolean;
}

export interface CatalogueConfig {
  disabledDevelopers: string[];
  disabledCategories: string[];
  gameOverrides: Record<string, boolean>;
}


/** The fields game-provider requires of a provider's configuration (ProviderConfiguration.problem). */
const ProviderFields: ConfigField[] = [
  { name: "apiUrl", label: "config.games.apiUrl", kind: "text" },
  { name: "site", label: "config.games.site", kind: "text" },
  { name: "operatorPrivateKey", label: "config.games.operatorPrivateKey", kind: "text", hint: "config.games.operatorPrivateKey.hint" },
  { name: "providerPublicKey", label: "config.games.providerPublicKey", kind: "text", optional: true, hint: "config.games.providerKey.hint" },
  { name: "providerKeyDocumentUrl", label: "config.games.providerKeyDocumentUrl", kind: "text", optional: true },
  { name: "jurisdiction", label: "config.games.jurisdiction", kind: "text" },
  { name: "lobbyUrl", label: "config.games.lobbyUrl", kind: "text" },
  { name: "depositUrl", label: "config.games.depositUrl", kind: "text" },
  { name: "walletUrl", label: "config.games.walletUrl", kind: "text", optional: true },
];

function ProviderConfig({ p, done }: { p: Provider; done(): void }) {
  const values = {
    apiUrl: p.apiUrl,
    site: p.site,
    operatorPrivateKey: "",
    providerPublicKey: p.providerPublicKey ?? null,
    providerKeyDocumentUrl: p.providerKeyDocumentUrl ?? null,
    jurisdiction: p.jurisdiction,
    lobbyUrl: p.lobbyUrl,
    depositUrl: p.depositUrl,
    walletUrl: p.walletUrl ?? null,
  };
  // the whole configuration is sent: game-provider replaces it, and refuses one without the operator key
  const save = (_: unknown, next: Record<string, unknown>) =>
    gameProvider.putProvidersByProvider({ provider: p.provider }, { body: Object.fromEntries(Object.entries(next).filter(([, v]) => v !== null)) });
  return <ConfigForm key={keyOf(values)} fields={ProviderFields} values={values} action={Actions["games.configureProvider"]} save={save} onDone={done} />;
}

function RefreshState({ provider }: { provider: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const q = useRead<Refresh>(["game-provider", "refresh", provider], () => gameProvider.getProvidersCatalogueRefresh({ provider }));
  const saved = useSaved();
  const tz = useZone();
  return (
    <div data-refresh={provider}>
      <Loaded q={q}>
        {(r) => (
          <p>
            <Status value={r.status} />{" "}
            {r.finishedAt ? (
              <span>
                {t("config.games.refreshedAt")} <DateTime instant={r.finishedAt} zone={tz} />
              </span>
            ) : r.startedAt ? (
              <span>
                {t("config.games.refreshStarted")} <DateTime instant={r.startedAt} zone={tz} />
              </span>
            ) : null}{" "}
            {r.count != null ? t("config.games.refreshCount", { count: r.count, value: r.removed ?? 0 }) : null} {r.error ? <span className="bo-field-error">{r.error}</span> : null}
          </p>
        )}
      </Loaded>
      {readOnly ? null : <Action action={Actions["games.refreshCatalogue"]} me={me} bags={bags} run={() => gameProvider.postProvidersCatalogueRefresh({ provider })} onDone={saved} />}
    </div>
  );
}

function Providers() {
  const q = useRead<{ providers: Provider[] }>(["game-provider", "providers"], () => gameProvider.getProviders());
  const saved = useSaved();
  const [adding, setAdding] = useState("");
  const addId = useId();
  const tz = useZone();
  return (
    <>
      <h2>{t("config.games.providers")}</h2>
      <ZoneNote zone={tz} />
      <Loaded q={q}>
        {(a) => (
          <>
            {a.providers.length === 0 ? <p>{t("config.games.noProviders")}</p> : null}
            {a.providers.map((p) => (
              <section key={p.provider} data-provider={p.provider} aria-labelledby={`provider-${p.provider}`}>
                <h3 id={`provider-${p.provider}`}>{p.provider}</h3>
                {p.updatedAt ? (
                  <p className="bo-muted">
                    {t("config.games.updatedAt")} <DateTime instant={p.updatedAt} zone={tz} />
                  </p>
                ) : null}
                {p.operatorPublicKey ? (
                  <p>
                    {t("config.games.operatorPublicKey")} <code>{p.operatorPublicKey}</code>
                  </p>
                ) : null}
                <ProviderConfig p={p} done={saved} />
                <RefreshState provider={p.provider} />
              </section>
            ))}
          </>
        )}
      </Loaded>
      <h3>{t("config.games.addProvider")}</h3>
      <div className="bo-field">
        <label htmlFor={addId}>{t("config.games.providerName")}</label>
        <input id={addId} className="bo-input" value={adding} onChange={(e) => setAdding(e.target.value.trim())} />
        <span className="bo-field-hint">{t("config.games.providerName.hint")}</span>
      </div>
      {adding !== "" ? (
        <ProviderConfig key={adding} p={{ provider: adding, apiUrl: "", site: "", jurisdiction: "", lobbyUrl: "", depositUrl: "" }} done={saved} />
      ) : null}
    </>
  );
}

/** The catalogue's configuration after turning one developer or category on or off. */
export function toggled(c: CatalogueConfig, kind: "disabledDevelopers" | "disabledCategories", name: string, on: boolean): CatalogueConfig {
  const set = new Set(c[kind]);
  if (on) set.delete(name);
  else set.add(name);
  return { ...c, [kind]: [...set].sort() };
}

/** A game's override set to the provider's flag (`undefined`), on or off; keyed `<provider>/<code>` as game-provider keys it. */
export function overridden(c: CatalogueConfig, g: Pick<Game, "provider" | "code">, to: boolean | undefined): CatalogueConfig {
  const key = `${g.provider}/${g.code}`;
  const overrides = { ...c.gameOverrides };
  delete overrides[g.code];
  if (to === undefined) delete overrides[key];
  else overrides[key] = to;
  return { ...c, gameOverrides: overrides };
}

const overrideOf = (c: CatalogueConfig, g: Game): boolean | undefined => c.gameOverrides[`${g.provider}/${g.code}`] ?? c.gameOverrides[g.code];
const Shown = 100;

function Catalogue() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const editable = !readOnly && holds(me, "config.games");
  const config = useRead<CatalogueConfig>(["game-provider", "catalog-config"], () => gameProvider.getGameCatalogConfig());
  const games = useRead<{ games: Game[] }>(["game-provider", "catalog"], () => gameProvider.getGameCatalog());
  const saved = useSaved();
  const [draft, setDraft] = useState<CatalogueConfig | undefined>(undefined);
  const [search, setSearch] = useState("");
  const searchId = useId();
  return (
    <Loaded q={config}>
      {(held) => {
        const c = draft ?? held;
        const changed = keyOf(c) !== keyOf(held);
        const all = games.data?.games ?? [];
        const developers = [...new Set([...all.map((g) => g.developer), ...held.disabledDevelopers])].filter(Boolean).sort();
        const categories = [...new Set([...all.map((g) => g.category), ...held.disabledCategories])].filter(Boolean).sort();
        const needle = search.trim().toLowerCase();
        const matching = all.filter((g) => needle === "" || g.name.toLowerCase().includes(needle) || g.code.toLowerCase().includes(needle));
        const toggles = (kind: "disabledDevelopers" | "disabledCategories", names: string[], heading: "config.games.developers" | "config.games.categories") => (
          <fieldset className="bo-permissions" data-toggles={kind}>
            <legend>{t(heading)}</legend>
            {names.map((n) => {
              const on = !c[kind].includes(n);
              return (
                <label key={n}>
                  <input type="checkbox" checked={on} disabled={!editable} onChange={(e) => setDraft(toggled(c, kind, n, e.target.checked))} /> <span>{n}</span>{" "}
                  {held[kind].includes(n) ? (
                    <span className="bo-status" data-tone="warning" data-differs="default">
                      {t("config.games.offDiffers")}
                    </span>
                  ) : null}
                </label>
              );
            })}
          </fieldset>
        );
        return (
          <>
            <h2>{t("config.games.catalogue")}</h2>
            {toggles("disabledDevelopers", developers, "config.games.developers")}
            {toggles("disabledCategories", categories, "config.games.categories")}
            <h3>{t("config.games.overrides")}</h3>
            <div className="bo-field">
              <label htmlFor={searchId}>{t("config.games.search")}</label>
              <input id={searchId} className="bo-input" value={search} onChange={(e) => setSearch(e.target.value)} />
            </div>
            <Loaded q={games}>
              {() => (
                <table className="bo-table">
                  <caption>{t("config.games.shown", { count: Math.min(Shown, matching.length), value: matching.length })}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("config.games.game")}</th>
                      <th scope="col">{t("config.games.developer")}</th>
                      <th scope="col">{t("config.games.category")}</th>
                      <th scope="col">{t("config.games.providerFlag")}</th>
                      <th scope="col">{t("config.games.override")}</th>
                      <th scope="col">{t("config.games.listed")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {matching.slice(0, Shown).map((g) => {
                      const o = overrideOf(c, g);
                      const label = t("config.games.overrideOf", { name: g.name });
                      return (
                        <tr key={`${g.provider}/${g.code}`} data-game={g.code}>
                          <th scope="row">
                            {g.name} <code>{g.code}</code>
                          </th>
                          <td>{g.developer}</td>
                          <td>{g.category}</td>
                          <td>{g.st8Enabled ? t("config.form.yes") : t("config.form.no")}</td>
                          <td>
                            <select
                              aria-label={label}
                              value={o === undefined ? "" : String(o)}
                              disabled={!editable || g.removedFromFeed}
                              onChange={(e) => setDraft(overridden(c, g, e.target.value === "" ? undefined : e.target.value === "true"))}
                            >
                              <option value="">{t("config.games.override.provider")}</option>
                              <option value="true">{t("config.games.override.on")}</option>
                              <option value="false">{t("config.games.override.off")}</option>
                            </select>{" "}
                            {overrideOf(held, g) !== undefined ? (
                              <span className="bo-status" data-tone="warning" data-differs="default">
                                {t("config.games.overrideDiffers")}
                              </span>
                            ) : null}
                          </td>
                          <td>{g.removedFromFeed ? t("config.games.removed") : g.effectiveEnabled ? t("config.form.yes") : t("config.form.no")}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </Loaded>
            {changed ? (
              <span className="bo-status" data-changed>
                {t("config.unsaved")}
              </span>
            ) : null}
            {readOnly ? null : (
              <Action
                action={Actions["games.catalogue"]}
                me={me}
                bags={bags}
                ready={changed}
                subject={<p>{t("config.games.catalogueChange", { fields: c.disabledDevelopers.join(", ") || t("config.form.none"), categories: c.disabledCategories.join(", ") || t("config.form.none"), count: Object.keys(c.gameOverrides).length })}</p>}
                run={() => gameProvider.putGameCatalogConfig({ body: c })}
                onDone={() => {
                  setDraft(undefined);
                  saved();
                }}
              />
            )}
          </>
        );
      }}
    </Loaded>
  );
}

export function GamesPage() {
  return (
    <ConfigPage heading="config.games.title">
      <Providers />
      <Catalogue />
    </ConfigPage>
  );
}
