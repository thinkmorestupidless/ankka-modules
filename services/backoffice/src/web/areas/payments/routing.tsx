// The routing rules (T113, FR-035; spec 004 FR-010, FR-011; the original's routing page): the tree kept at
// the level the picker names (a holding, a company or a sub-brand), drawn as the original draws it. A
// payments administrator drafts it (edits saved as the draft), simulates the draft with sample deposits
// (each answered with the terminals it would reach, merchant's evaluator walking the tree the request
// carries), deploys it (the draft becomes the next version, live), retires what is live (the level then
// falls back to the one above), and rolls back to an earlier version, which merchant makes the draft for
// the operator to deploy. A sub-brand with nothing live of its own shows the tree it falls back to.
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime } from "../../components/date.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { read } from "../configuration/page.tsx";
import { word } from "../word.ts";
import { useScope, type Level } from "./hierarchy.tsx";
import { blankSample, emptyTree, samplesOf, TreeView, type Sample, type Tree } from "./tree.tsx";

export interface RoutingState {
  liveVersion: number | null;
  liveDeployedAt: string | null;
  history: { version: number; deployedAt: string }[];
  draft: Tree | null;
  liveTree?: Tree | null;
  resolvedFrom?: { level: string; entityId: string; name: string };
}

interface Tried {
  sample: Sample;
  outcome: string;
  terminals: string[];
}

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/**
 * The state after a deploy, as merchant's tree folds `Deployed`: the draft is the new version, live, and
 * added to the history; nothing is left drafted.
 */
export function deployed(s: RoutingState, answer: { version: number; deployedAt: string }): RoutingState {
  return {
    ...s,
    liveVersion: answer.version,
    liveDeployedAt: answer.deployedAt,
    history: [...s.history.filter((v) => v.version !== answer.version), { version: answer.version, deployedAt: answer.deployedAt }],
    liveTree: s.draft ?? s.liveTree ?? null,
    draft: null,
    resolvedFrom: undefined,
  };
}

function SampleRows({ samples, set }: { samples: Sample[]; set(s: Sample[]): void }) {
  const field = (i: number, k: keyof Sample, label: Parameters<typeof t>[0]) => (
    <input aria-label={t(label, { line: i + 1 })} className="bo-input" value={samples[i]![k]} onChange={(e) => set(samples.map((s, j) => (j === i ? { ...s, [k]: e.target.value } : s)))} />
  );
  return (
    <table className="bo-pay-table">
      <caption>{t("payments.routing.samples")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("payments.routing.sample.currency")}</th>
          <th scope="col">{t("payments.routing.sample.country")}</th>
          <th scope="col">{t("payments.routing.sample.amount")}</th>
          <th scope="col">{t("payments.routing.sample.deposits")}</th>
          <th scope="col">{t("payments.routing.sample.customer")}</th>
          <th scope="col">
            <span className="bo-sr-only">{t("payments.routing.sample.remove")}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {samples.map((_, i) => (
          <tr key={i}>
            <td>{field(i, "currency", "payments.routing.sample.currencyOf")}</td>
            <td>{field(i, "country", "payments.routing.sample.countryOf")}</td>
            <td>{field(i, "amountCents", "payments.routing.sample.amountOf")}</td>
            <td>{field(i, "depositCount", "payments.routing.sample.depositsOf")}</td>
            <td>{field(i, "customerId", "payments.routing.sample.customerOf")}</td>
            <td>
              {samples.length > 1 ? (
                <button type="button" className="bo-button" onClick={() => set(samples.filter((__, j) => j !== i))}>
                  {t("payments.routing.sample.remove")}
                </button>
              ) : null}
            </td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <td colSpan={6}>
            <button type="button" className="bo-button" onClick={() => set([...samples, blankSample()])}>
              {t("payments.routing.sample.add")}
            </button>
          </td>
        </tr>
      </tfoot>
    </table>
  );
}

const whole = (s: string) => /^\d+$/.test(s.trim());
const sampleText = (s: Sample) =>
  [s.currency || t("payments.routing.sample.anyCurrency"), s.country || t("payments.routing.sample.anyCountry"), t("payments.routing.sample.minor", { minor: s.amountCents })].join(" · ");

export function RoutingSection() {
  const scope = useScope();
  if (scope.level === null || scope.id === null) return <p role="status">{t("payments.routing.chooseLevel")}</p>;
  return <Routing key={`${scope.level}/${scope.id}`} level={scope.level} entityId={scope.id} name={scope.name ?? scope.id} />;
}

function Routing({ level, entityId, name }: { level: Level; entityId: string; name: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const tz = zone();
  const key = ["payments", "routing", level, entityId];
  const query = { level, entityId };
  const q = useQuery({ queryKey: key, queryFn: () => read<RoutingState>(merchant.getPaymentsRoutingRulesState({ query })) });
  const [edited, setEdited] = useState<Tree | undefined>(undefined);
  const [samples, setSamples] = useState<Sample[] | undefined>(undefined);
  const [tried, setTried] = useState<Tried[] | undefined>(undefined);
  const [rolledBack, setRolledBack] = useState<number | undefined>(undefined);
  const terminals = useQuery({
    queryKey: ["payments", "terminals", entityId],
    queryFn: () => read<{ terminals: { terminalEntityId: string; name: string }[] }>(merchant.getPaymentsTerminals({ query: { merchantId: entityId } })),
    enabled: level === "subBrand" && edited !== undefined,
    retry: false,
  });

  if (q.isError) return <RefusalNotice refusal={q.error as never} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const s = q.data;
  const draft = edited ?? s.draft ?? null;
  const shownSamples = samples ?? samplesOf(draft);
  const reached = tried?.flatMap((x) => x.terminals);
  const refresh = () => void client.invalidateQueries({ queryKey: key });
  const save = () => merchant.postPaymentsRoutingRules({ body: { level, entityId, tree: draft } });
  const act = !readOnly;
  const history = [...s.history].sort((a, b) => b.version - a.version);

  return (
    <div className="bo-pay-routing" data-level={level}>
      <p>
        {s.liveVersion !== null && s.liveVersion !== undefined ? (
          <>
            {t("payments.routing.live", { value: s.liveVersion })} {s.liveDeployedAt ? <DateTime instant={s.liveDeployedAt} zone={tz} /> : null}
          </>
        ) : (
          t("payments.routing.nothingLive", { name })
        )}
      </p>
      {s.resolvedFrom ? <p className="bo-muted">{t("payments.routing.fallsBack", { level: word("payments.level", s.resolvedFrom.level), name: s.resolvedFrom.name })}</p> : null}

      <section aria-labelledby="routing-draft">
        <h2 id="routing-draft">{t("payments.routing.draft")}</h2>
        {draft ? (
          <TreeView
            tree={draft}
            label={t("payments.routing.draft")}
            onChange={act && edited !== undefined ? setEdited : undefined}
            terminals={terminals.data?.terminals.map((x) => x.terminalEntityId)}
            reached={reached}
          />
        ) : (
          <p className="bo-muted">{t("payments.routing.noDraft")}</p>
        )}
        {rolledBack !== undefined ? <p role="status">{t("payments.routing.rolledBack", { value: rolledBack })}</p> : null}
        {act ? (
          <div className="bo-actions">
            {edited === undefined ? (
              <button type="button" className="bo-button" onClick={() => setEdited(structuredClone(draft ?? s.liveTree ?? emptyTree()))}>
                {draft ? t("payments.routing.edit") : t("payments.routing.start")}
              </button>
            ) : (
              <button type="button" className="bo-button" onClick={() => setEdited(undefined)}>
                {t("payments.routing.discardEdits")}
              </button>
            )}
            <Action
              action={Actions["routing.saveDraft"]}
              me={me}
              bags={bags}
              state={edited ? "edited" : "unchanged"}
              run={save}
              onDone={() => {
                setEdited(undefined);
                refresh();
              }}
            />
            <Action
              action={Actions["routing.simulate"]}
              me={me}
              bags={bags}
              state={draft ? "drafted" : "none"}
              ready={shownSamples.length > 0 && shownSamples.every((x) => whole(x.amountCents) && whole(x.depositCount))}
              subject={<SampleRows samples={shownSamples} set={setSamples} />}
              run={async () => {
                const out: Tried[] = [];
                for (const sample of shownSamples) {
                  const r = await merchant.postPaymentsRoutingRulesSimulate({
                    query,
                    body: {
                      tree: draft,
                      currency: sample.currency.trim().toUpperCase(),
                      amountCents: Number(sample.amountCents),
                      country: sample.country.trim().toUpperCase(),
                      customerId: sample.customerId.trim() === "" ? null : sample.customerId.trim(),
                      depositCount: Number(sample.depositCount),
                    },
                  });
                  if (!r.ok) return r;
                  const a = (await r.json()) as { outcome: string; terminals: string[] };
                  out.push({ sample, outcome: a.outcome, terminals: a.terminals ?? [] });
                }
                setTried(out);
                return new Response("{}", { status: 200 });
              }}
            />
            <Action
              action={Actions["routing.deploy"]}
              me={me}
              bags={bags}
              state={draft ? "drafted" : "none"}
              subject={edited ? <p>{t("payments.routing.deploySaves")}</p> : undefined}
              run={async () => {
                if (edited) {
                  const saved = await save();
                  if (!saved.ok) return saved;
                }
                return merchant.postPaymentsRoutingRulesDeploy({ query });
              }}
              onDone={(answer) => {
                void answer.json().then((a: { version: number; deployedAt: string }) => {
                  client.setQueryData<RoutingState>(key, (prev) => (prev ? deployed({ ...prev, draft }, a) : prev));
                  setEdited(undefined);
                  setRolledBack(undefined);
                });
              }}
            />
          </div>
        ) : null}
        {tried ? (
          <table className="bo-pay-table" data-simulated>
            <caption>{t("payments.routing.tried")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("payments.routing.sample.deposit")}</th>
                <th scope="col">{t("payments.routing.outcome")}</th>
                <th scope="col">{t("payments.routing.reaches")}</th>
              </tr>
            </thead>
            <tbody>
              {tried.map((x, i) => (
                <tr key={i}>
                  <td>{sampleText(x.sample)}</td>
                  <td>{word("payments.routing.outcome", x.outcome)}</td>
                  <td>{x.terminals.length > 0 ? x.terminals.join(", ") : t("payments.routing.noTerminal")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>

      <section aria-labelledby="routing-live">
        <h2 id="routing-live">{t("payments.routing.liveTree")}</h2>
        {s.liveTree ? <TreeView tree={s.liveTree} label={t("payments.routing.liveTree")} /> : <p className="bo-muted">{t("payments.routing.noLiveTree")}</p>}
        {act ? (
          <div className="bo-actions">
            <Action action={Actions["routing.retire"]} me={me} bags={bags} state={s.liveVersion !== null && s.liveVersion !== undefined ? "live" : "none"} run={() => merchant.postPaymentsRoutingRulesRetire({ query })} onDone={refresh} />
          </div>
        ) : null}
      </section>

      <section aria-labelledby="routing-history">
        <h2 id="routing-history">{t("payments.routing.history")}</h2>
        {history.length === 0 ? (
          <p className="bo-muted">{t("payments.routing.noHistory")}</p>
        ) : (
          <table className="bo-pay-table">
            <thead>
              <tr>
                <th scope="col">{t("payments.routing.version")}</th>
                <th scope="col">{t("payments.routing.deployedAt")}</th>
                <th scope="col">{t("payments.routing.inForce")}</th>
                {act ? <th scope="col">{t("payments.act")}</th> : null}
              </tr>
            </thead>
            <tbody>
              {history.map((v) => (
                <tr key={v.version} data-version={v.version}>
                  <td className="figures">{v.version}</td>
                  <td>
                    <DateTime instant={v.deployedAt} zone={tz} />
                  </td>
                  <td>{v.version === s.liveVersion ? t("payments.routing.isLive") : ""}</td>
                  {act ? (
                    <td>
                      <Action
                        action={Actions["routing.rollback"]}
                        me={me}
                        bags={bags}
                        state={v.version === s.liveVersion ? "live" : "earlier"}
                        subject={<p>{t("payments.routing.rollbackTo", { value: v.version })}</p>}
                        run={() => merchant.postPaymentsRoutingRulesRollbackByVersion({ version: String(v.version) }, { query })}
                        onDone={() => {
                          setRolledBack(v.version);
                          setEdited(undefined);
                          refresh();
                        }}
                      />
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
