// A routing tree drawn as the original's routing canvas draws it (T113, FR-035): from the payment, left to
// right, each decision a card naming its condition with one row per branch (its conditions, as
// "Country in BR"; a branch with none is "Otherwise"), each row leading to the node it names; each leaf a
// card listing the terminals a deposit reaching it is offered. Editing works on the same drawing: a
// branch's conditions and target, branches added and removed, a node made a leaf or a decision.
import { useId, type ReactNode } from "react";
import { t } from "../../../shared/messages/index.ts";
import { word } from "../word.ts";

export interface Condition {
  conditionType: string;
  operator: string;
  value: string;
}
export interface Branch {
  conditions: Condition[];
  nextNodeId: string;
}
export interface TreeNode {
  nodeId: string;
  branches?: Branch[] | null;
  terminals?: string[] | null;
}
export interface Tree {
  rootNodeId: string;
  nodes: Record<string, TreeNode>;
}

export const ConditionTypes = ["CURRENCY", "COUNTRY", "AMOUNT", "DEPOSIT_COUNT", "CUSTOMER_ID"] as const;
/** The operators merchant's evaluator compares each condition by (`Evaluator.holds`). */
export const OperatorsByType: Record<string, readonly string[]> = {
  CURRENCY: ["EQ", "NEQ", "IN"],
  COUNTRY: ["EQ", "NEQ", "IN"],
  CUSTOMER_ID: ["EQ", "NEQ", "IN"],
  AMOUNT: ["LT", "LTE", "GT", "GTE", "EQ", "NEQ"],
  DEPOSIT_COUNT: ["LT", "LTE", "GT", "GTE", "EQ", "NEQ"],
};

/** A condition as the original labels it: "Country in BR", "Amount ≥ 1000". */
export function conditionLabel(c: Condition): string {
  return t("payments.routing.condition", { kind: word("payments.routing.type", c.conditionType), operator: word("payments.routing.op", c.operator), value: c.value });
}

export function branchLabel(b: Branch): string {
  return b.conditions.length === 0 ? t("payments.routing.otherwise") : b.conditions.map(conditionLabel).join(t("payments.routing.and"));
}

const isLeaf = (n: TreeNode | undefined) => n !== undefined && n.terminals !== undefined && n.terminals !== null;

/** A new node's id: `n<k>`, the first not taken. */
function freshId(tree: Tree): string {
  let k = Object.keys(tree.nodes).length + 1;
  while (tree.nodes[`n${k}`]) k++;
  return `n${k}`;
}

function withNode(tree: Tree, node: TreeNode): Tree {
  return { ...tree, nodes: { ...tree.nodes, [node.nodeId]: node } };
}

/** An empty draft: one decision with nothing under it. */
export const emptyTree = (): Tree => ({ rootNodeId: "root", nodes: { root: { nodeId: "root", branches: [] } } });

/**
 * The deposits a simulation tries first: one per leaf, made to meet the conditions on the way to it where
 * a value can be read from them, so each terminal the tree names is tried.
 */
export interface Sample {
  currency: string;
  country: string;
  amountCents: string;
  depositCount: string;
  customerId: string;
}

export const blankSample = (): Sample => ({ currency: "", country: "", amountCents: "0", depositCount: "0", customerId: "" });

export function samplesOf(tree: Tree | null | undefined): Sample[] {
  if (!tree) return [blankSample()];
  const out: Sample[] = [];
  const walk = (id: string, path: Condition[], seen: Set<string>) => {
    const n = tree.nodes[id];
    if (!n || seen.has(id)) return;
    if (isLeaf(n)) {
      out.push(meeting(path));
      return;
    }
    for (const b of n.branches ?? []) walk(b.nextNodeId, [...path, ...b.conditions], new Set([...seen, id]));
  };
  walk(tree.rootNodeId, [], new Set());
  return out.length > 0 ? out.slice(0, 10) : [blankSample()];
}

function meeting(conditions: Condition[]): Sample {
  const s = blankSample();
  for (const c of conditions) {
    const first = c.value.split(",")[0]?.trim() ?? "";
    const n = Number.parseInt(c.value.trim(), 10);
    const number = Number.isFinite(n) ? n : 0;
    const whole = c.operator === "GT" ? number + 1 : c.operator === "LT" ? Math.max(0, number - 1) : number;
    if (c.operator === "NEQ") continue;
    if (c.conditionType === "CURRENCY") s.currency = first;
    else if (c.conditionType === "COUNTRY") s.country = first;
    else if (c.conditionType === "CUSTOMER_ID") s.customerId = first;
    else if (c.conditionType === "AMOUNT") s.amountCents = String(whole);
    else if (c.conditionType === "DEPOSIT_COUNT") s.depositCount = String(whole);
  }
  return s;
}

export interface TreeViewProps {
  tree: Tree;
  /** Given, the drawing is editable and every change is passed here. */
  onChange?(tree: Tree): void;
  /** The terminals a leaf may name; free text when not known. */
  terminals?: readonly string[];
  /** The terminals a simulation reached, marked on the leaf that names them. */
  reached?: readonly string[];
  label: string;
}

export function TreeView({ tree, onChange, terminals, reached, label }: TreeViewProps) {
  return (
    <figure className="bo-tree" aria-label={label}>
      <div className="bo-tree-row">
        <div className="bo-tree-start" aria-hidden="true">
          {t("payments.routing.payment")}
        </div>
        <span className="bo-tree-edge" aria-hidden="true" />
        <Subtree id={tree.rootNodeId} tree={tree} seen={new Set()} onChange={onChange} terminals={terminals} reached={reached} />
      </div>
    </figure>
  );
}

interface SubtreeProps {
  id: string;
  tree: Tree;
  seen: Set<string>;
  onChange?(tree: Tree): void;
  terminals?: readonly string[];
  reached?: readonly string[];
}

function Subtree({ id, tree, seen, onChange, terminals, reached }: SubtreeProps) {
  const node = tree.nodes[id];
  if (!node) return <div className="bo-tree-node" data-missing>{t("payments.routing.missingNode", { id })}</div>;
  if (seen.has(id)) return <div className="bo-tree-node" data-loop>{t("payments.routing.loop", { id })}</div>;
  const next = new Set([...seen, id]);
  if (isLeaf(node)) return <Leaf node={node} tree={tree} onChange={onChange} terminals={terminals} reached={reached} />;
  const branches = node.branches ?? [];
  const setBranches = (bs: Branch[]) => onChange?.(withNode(tree, { nodeId: node.nodeId, branches: bs }));
  return (
    <div className="bo-tree-row">
      <div className="bo-tree-node" data-kind="decision" data-node={id}>
        <strong>{t("payments.routing.decision")}</strong>
        {onChange ? (
          <span className="bo-actions">
            <button type="button" className="bo-button" onClick={() => onChange(withNode(tree, { nodeId: node.nodeId, terminals: [] }))}>
              {t("payments.routing.makeLeaf")}
            </button>
            <button
              type="button"
              className="bo-button"
              onClick={() => {
                const leaf = freshId(tree);
                const grown = withNode(tree, { nodeId: leaf, terminals: [] });
                onChange(withNode(grown, { nodeId: node.nodeId, branches: [...branches, { conditions: [], nextNodeId: leaf }] }));
              }}
            >
              {t("payments.routing.addBranch")}
            </button>
          </span>
        ) : null}
      </div>
      <ul className="bo-tree-branches">
        {branches.length === 0 ? <li className="bo-muted">{t("payments.routing.noBranches")}</li> : null}
        {branches.map((b, i) => (
          <li key={`${i}-${b.nextNodeId}`} className="bo-tree-row">
            <span className="bo-tree-edge" aria-hidden="true" />
            <div className="bo-tree-branch" data-branch={i}>
              {onChange ? (
                <BranchEditor
                  branch={b}
                  nodes={Object.keys(tree.nodes).filter((n) => n !== id)}
                  onChange={(nb) => setBranches(branches.map((x, j) => (j === i ? nb : x)))}
                  onRemove={() => setBranches(branches.filter((_, j) => j !== i))}
                />
              ) : (
                <span>{branchLabel(b)}</span>
              )}
            </div>
            <span className="bo-tree-edge" aria-hidden="true" />
            <Subtree id={b.nextNodeId} tree={tree} seen={next} onChange={onChange} terminals={terminals} reached={reached} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function Leaf({ node, tree, onChange, terminals, reached }: { node: TreeNode; tree: Tree; onChange?(tree: Tree): void; terminals?: readonly string[]; reached?: readonly string[] }) {
  const id = useId();
  const named = node.terminals ?? [];
  const hit = reached !== undefined && named.some((x) => reached.includes(x));
  const set = (ts: string[]) => onChange?.(withNode(tree, { nodeId: node.nodeId, terminals: ts }));
  let editor: ReactNode = null;
  if (onChange) {
    editor =
      terminals && terminals.length > 0 ? (
        <fieldset className="bo-tree-terminals">
          <legend>{t("payments.routing.terminals")}</legend>
          {terminals.map((x) => (
            <label key={x}>
              <input type="checkbox" checked={named.includes(x)} onChange={(e) => set(e.target.checked ? [...named, x] : named.filter((n) => n !== x))} /> {x}
            </label>
          ))}
        </fieldset>
      ) : (
        <div className="bo-field">
          <label htmlFor={id}>{t("payments.routing.terminalsText")}</label>
          <input id={id} className="bo-input" value={named.join(", ")} onChange={(e) => set(e.target.value.split(",").map((s) => s.trim()).filter((s) => s !== ""))} />
        </div>
      );
  }
  return (
    <div className="bo-tree-node" data-kind="leaf" data-node={node.nodeId} data-reached={hit ? "" : undefined}>
      <strong>{t("payments.routing.leaf")}</strong>
      {named.length === 0 ? <span className="bo-muted">{t("payments.routing.noTerminals")}</span> : <ul>{named.map((x) => <li key={x}>{x}</li>)}</ul>}
      {hit ? <span className="bo-status" data-tone="positive">{t("payments.routing.reached")}</span> : null}
      {editor}
      {onChange ? (
        <button type="button" className="bo-button" onClick={() => onChange(withNode(tree, { nodeId: node.nodeId, branches: [] }))}>
          {t("payments.routing.makeDecision")}
        </button>
      ) : null}
    </div>
  );
}

function BranchEditor({ branch, nodes, onChange, onRemove }: { branch: Branch; nodes: string[]; onChange(b: Branch): void; onRemove(): void }) {
  const id = useId();
  const setCondition = (i: number, c: Condition) => onChange({ ...branch, conditions: branch.conditions.map((x, j) => (j === i ? c : x)) });
  return (
    <div className="bo-tree-edit">
      {branch.conditions.length === 0 ? <span>{t("payments.routing.otherwise")}</span> : null}
      {branch.conditions.map((c, i) => (
        <div key={i} className="bo-tree-condition">
          <select aria-label={t("payments.routing.conditionType")} value={c.conditionType} onChange={(e) => setCondition(i, { ...c, conditionType: e.target.value, operator: OperatorsByType[e.target.value]![0]! })}>
            {ConditionTypes.map((k) => (
              <option key={k} value={k}>
                {word("payments.routing.type", k)}
              </option>
            ))}
          </select>
          <select aria-label={t("payments.routing.operator")} value={c.operator} onChange={(e) => setCondition(i, { ...c, operator: e.target.value })}>
            {(OperatorsByType[c.conditionType] ?? []).map((o) => (
              <option key={o} value={o}>
                {word("payments.routing.op", o)}
              </option>
            ))}
          </select>
          <input aria-label={t("payments.routing.value")} className="bo-input" value={c.value} onChange={(e) => setCondition(i, { ...c, value: e.target.value })} />
          <button type="button" className="bo-button" onClick={() => onChange({ ...branch, conditions: branch.conditions.filter((_, j) => j !== i) })}>
            {t("payments.routing.removeCondition")}
          </button>
        </div>
      ))}
      <div className="bo-tree-condition">
        <button type="button" className="bo-button" onClick={() => onChange({ ...branch, conditions: [...branch.conditions, { conditionType: "COUNTRY", operator: "IN", value: "" }] })}>
          {t("payments.routing.addCondition")}
        </button>
        <label htmlFor={id}>{t("payments.routing.leadsTo")}</label>
        <select id={id} value={branch.nextNodeId} onChange={(e) => onChange({ ...branch, nextNodeId: e.target.value })}>
          {nodes.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
        <button type="button" className="bo-button" onClick={onRemove}>
          {t("payments.routing.removeBranch")}
        </button>
      </div>
    </div>
  );
}
