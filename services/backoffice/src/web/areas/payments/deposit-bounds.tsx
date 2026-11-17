// The deposit bounds of the sub-brand worked on (T112; spec 030 H3, research *Deposit bounds*): per method
// (the catalogue's category of the terminal's scheme) and currency, the least and the most a deposit may
// be, both inclusive, either absent. Kept at the sub-brand only and not inherited, so a bound's default is
// none. The form is the configuration form (FR-034): each amount marked when set, and merchant takes the
// whole list back, so a bound left with neither amount is removed.
import { useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { ConfigForm, type ConfigField, type ConfigValue } from "../../components/config-form.tsx";
import { useReadOnlyMode } from "../../frame/context.ts";
import { word } from "../word.ts";
import { keyOf } from "../configuration/page.tsx";
import { Choice, Field, useChanged, usePayments } from "./parts.tsx";
import { NeedsSubBrand, useScope } from "./hierarchy.tsx";

export interface Bound {
  method: string;
  currency: string;
  minimumAmountCents: number | null;
  maximumAmountCents: number | null;
}

interface Bounds {
  subBrandId: string;
  bounds: Bound[];
  methods: string[];
}

const nameOf = (b: Pick<Bound, "method" | "currency">, end: "min" | "max") => `${b.method}/${b.currency}/${end}`;

/** The form's fields: a least and a most per bound, in its currency; none is the default. */
export function boundFields(bounds: readonly Pick<Bound, "method" | "currency">[]): ConfigField[] {
  return bounds.flatMap((b) =>
    (["min", "max"] as const).map((end) => ({
      name: nameOf(b, end),
      label: end === "min" ? ("payments.bounds.minimum" as const) : ("payments.bounds.maximum" as const),
      labelValues: { method: word("payments.bounds.method", b.method), currency: b.currency },
      kind: "money" as const,
      currency: b.currency,
      optional: true,
      default: null,
    })),
  );
}

/** The bounds merchant takes from the form's values: a bound with neither amount is dropped. */
export function boundsFrom(rows: readonly Pick<Bound, "method" | "currency">[], values: Record<string, ConfigValue>): Bound[] {
  return rows
    .map((b) => ({ method: b.method, currency: b.currency, minimumAmountCents: (values[nameOf(b, "min")] as number | null) ?? null, maximumAmountCents: (values[nameOf(b, "max")] as number | null) ?? null }))
    .filter((b) => b.minimumAmountCents !== null || b.maximumAmountCents !== null);
}

export function DepositBoundsSection() {
  const scope = useScope();
  if (scope.level !== "subBrand" || !scope.id) return <NeedsSubBrand />;
  return <BoundsForm key={scope.id} subBrand={scope.id} />;
}

function BoundsForm({ subBrand }: { subBrand: string }) {
  const readOnly = useReadOnlyMode();
  const done = useChanged("bounds", subBrand);
  const q = usePayments<Bounds>(["bounds", subBrand], () => merchant.getSubBrandsDepositBounds({ id: subBrand }));
  const [added, setAdded] = useState<Pick<Bound, "method" | "currency">[]>([]);
  const [method, setMethod] = useState("");
  const [currency, setCurrency] = useState("");
  if (q.isError) return <RefusalNotice refusal={q.error} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const held = q.data.bounds;
  const rows = [...held, ...added.filter((a) => !held.some((b) => b.method === a.method && b.currency === a.currency))];
  const values: Record<string, ConfigValue> = Object.fromEntries(held.flatMap((b) => [[nameOf(b, "min"), b.minimumAmountCents], [nameOf(b, "max"), b.maximumAmountCents]]));
  const chosenMethod = method || q.data.methods[0] || "";
  const code = currency.trim().toUpperCase();
  return (
    <div>
      <p className="bo-muted">{t("payments.bounds.note")}</p>
      {rows.length === 0 ? <p className="bo-muted">{t("payments.bounds.none")}</p> : null}
      <ConfigForm
        key={keyOf([held, rows.length])}
        fields={boundFields(rows)}
        values={values}
        action={Actions["payments.bounds"]}
        save={(_, next) => merchant.putSubBrandsDepositBounds({ id: subBrand }, { body: { bounds: boundsFrom(rows, next) } })}
        onDone={() => {
          setAdded([]);
          done();
        }}
      />
      {readOnly ? null : (
        <fieldset className="bo-pay-add">
          <legend>{t("payments.bounds.add")}</legend>
          <Choice labelKey="payments.bounds.methodLabel" value={chosenMethod} set={setMethod} choices={q.data.methods} word={(m) => word("payments.bounds.method", m)} />
          <Field labelKey="payments.bounds.currency" value={currency} set={setCurrency} />
          <button
            type="button"
            className="bo-button"
            disabled={!/^[A-Z]{3}$/.test(code) || chosenMethod === ""}
            onClick={() => {
              setAdded([...added, { method: chosenMethod, currency: code }]);
              setCurrency("");
            }}
          >
            {t("payments.bounds.addRow")}
          </button>
        </fieldset>
      )}
    </div>
  );
}
