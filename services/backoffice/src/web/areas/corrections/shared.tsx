// What the corrections screens share (US5): the reasons an adjustment is filed under, the cause it
// answers, the wallet's major-unit text read as minor units, and the reads through the page's server.
//
// The reasons: no owner holds a brand's list of adjustment reasons (the original took free text only;
// the wallet takes a free `category` beside the operator's `reason` and publishes only the category).
// So the list is the backoffice's catalogue below, with free text beside it, until a brand's own list
// is kept somewhere the backoffice can read (research, *Found during implementation*).
import { useId } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { explain, type Refusal } from "../../../shared/refusals.ts";
import { gameProvider } from "../../../shared/owners/index.ts";
import { Glyph, type GlyphName } from "../../components/glyph.tsx";
import { moneyText } from "../../components/money.tsx";
import { statusWord } from "../../components/status.tsx";

export const Categories = ["compensation", "goodwill", "error_correction", "provider_failure", "chargeback", "fraud_recovery", "other"] as const;
export type Category = (typeof Categories)[number];

export const CauseKinds = ["round", "provider_transaction", "payment", "incident"] as const;
export type CauseKind = (typeof CauseKinds)[number];
export interface Cause {
  kind: CauseKind;
  ref: string;
}

export { walletMinor, walletText } from "../../../shared/money.ts";

/** An owner's answer as JSON; a refusal thrown as `explain` gives it. */
export async function readJson<T>(answer: Promise<Response>): Promise<T> {
  const r = await answer;
  const text = await r.text();
  if (!r.ok) throw explain(r.status, text) satisfies Refusal;
  return JSON.parse(text) as T;
}

/** A refusal thrown by a read, or the server not reached. */
export function refusalOf(e: unknown): Refusal {
  if (e && typeof e === "object" && "message" in e && typeof (e as Refusal).message === "object") return e as Refusal;
  return { status: 0, code: "unreachable", message: { key: "action.unreachable" }, next: { key: "action.unreachable.next" } };
}

export function CategorySelect({ value, onChange, disabled }: { value: Category; onChange(c: Category): void; disabled?: boolean }) {
  const id = useId();
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t("corrections.category")}</label>
      <select id={id} className="bo-input" value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as Category)}>
        {Categories.map((c) => (
          <option key={c} value={c}>
            {t(`corrections.category.${c}` as MessageKey)}
          </option>
        ))}
      </select>
    </div>
  );
}

interface RoundRow {
  roundId: string;
  gameCode: string;
  status: string;
  currency: string;
  totalStake: number;
  openedAt: string | null;
}

/** The player's most recent rounds at game-provider, newest first, to name one as the cause. */
function PlayerRounds({ player, value, onPick }: { player: string; value: string; onPick(ref: string): void }) {
  const id = useId();
  const q = useQuery({
    queryKey: ["game-provider", "rounds", player],
    queryFn: () => readJson<{ rounds: RoundRow[] }>(gameProvider.getRounds({ query: { playerId: player, page: "1", pageSize: "20" } })),
  });
  if (q.isError) return <p className="bo-field-hint">{t("corrections.cause.roundsUnread")}</p>;
  const rounds = q.data?.rounds ?? [];
  return (
    <div className="bo-field">
      <label htmlFor={id}>{t("corrections.cause.rounds")}</label>
      <select id={id} className="bo-input" value={rounds.some((r) => r.roundId === value) ? value : ""} onChange={(e) => onPick(e.target.value)}>
        <option value="">{t("corrections.cause.roundsChoose")}</option>
        {rounds.map((r) => (
          <option key={r.roundId} value={r.roundId}>
            {t("corrections.cause.roundOption", { name: r.gameCode || r.roundId, kind: `${statusWord(r.status)} · ${moneyText({ minor: String(r.totalStake), currency: r.currency })}`, id: r.roundId })}
          </option>
        ))}
      </select>
    </div>
  );
}

/** What the adjustment corrects: a round, a provider transaction, a payment or an incident, by its reference. */
export function CauseFields({ kind, reference, onChange, player, disabled }: {
  kind: CauseKind | "";
  reference: string;
  onChange(kind: CauseKind | "", reference: string): void;
  /** The player whose rounds are offered when the cause is a round. */
  player?: string;
  disabled?: boolean;
}) {
  const kindId = useId();
  const refId = useId();
  return (
    <fieldset className="bo-corrections-cause" disabled={disabled}>
      <div className="bo-field">
        <label htmlFor={kindId}>{t("corrections.cause.kind")}</label>
        <select id={kindId} className="bo-input" value={kind} onChange={(e) => onChange(e.target.value as CauseKind | "", e.target.value === "" ? "" : reference)}>
          <option value="">{t("corrections.cause.none")}</option>
          {CauseKinds.map((k) => (
            <option key={k} value={k}>
              {t(`corrections.cause.${k}` as MessageKey)}
            </option>
          ))}
        </select>
      </div>
      {kind === "round" && player ? <PlayerRounds player={player} value={reference} onPick={(ref) => onChange(kind, ref)} /> : null}
      {kind !== "" ? (
        <div className="bo-field">
          <label htmlFor={refId}>{t("corrections.cause.ref")}</label>
          <input id={refId} className="bo-input" value={reference} maxLength={200} onChange={(e) => onChange(kind, e.target.value)} />
        </div>
      ) : null}
    </fieldset>
  );
}

/** The cause as the wallet takes it, or none when no kind or no reference is given. */
export function causeOf(kind: CauseKind | "", reference: string): Cause | undefined {
  const ref = reference.trim();
  return kind === "" || ref === "" ? undefined : { kind, ref };
}

const outcomeGlyph: Record<string, GlyphName> = { made: "check", refused: "cross", already_made: "dot", waiting: "clock" };
const outcomeTone: Record<string, string> = { made: "positive", refused: "negative", already_made: "neutral", waiting: "waiting" };

/** A row's outcome as a word and an icon, never colour alone (FR-040). */
export function Outcome({ value }: { value: "made" | "refused" | "already_made" | "waiting" }) {
  return (
    <span className="bo-status" data-tone={outcomeTone[value]}>
      <Glyph name={outcomeGlyph[value]!} />
      <span>{t(`corrections.batch.outcome.${value}` as MessageKey)}</span>
    </span>
  );
}

/** The corrections area's own pages: a batch, and the non-player accounts. */
export function CorrectionsNav({ here }: { here: "batch" | "accounts" }) {
  return (
    <nav className="bo-corrections-nav" aria-label={t("area.adjustments")}>
      <Link to="/adjustments/batch" aria-current={here === "batch" ? "page" : undefined}>
        {t("corrections.nav.batch")}
      </Link>
      <Link to="/adjustments/accounts" aria-current={here === "accounts" ? "page" : undefined}>
        {t("corrections.nav.accounts")}
      </Link>
    </nav>
  );
}
