// The providers' outcomes not delivered (T113; spec 005 FR-016, spec 030 T1): psp-gateway's deposits and
// payouts whose outcome its receiver has not taken, and those the receiver refused, oldest first with the
// total in each state, however many are listed. Each is re-driven by the operator: delivered again now.
// psp-gateway refuses one that is neither any more (`not_redrivable`, naming where it is).
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { pspGateway } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { Rows, useChanged, usePayments, type Col } from "./parts.tsx";

export interface Outcome {
  reference: string;
  kind: string;
  provider: string;
  receiver: string;
  outcome: string;
  attempts: number;
  lastAnswer?: string | null;
  since: string;
}

const Limit = 200;

function OutcomeList({ which, heading }: { which: "undelivered" | "refused"; heading: MessageKey }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const done = useChanged("outcomes");
  const q = usePayments<{ outcomes: Outcome[]; total: number }>(["outcomes", which], () =>
    (which === "undelivered" ? pspGateway.getPspOutcomesUndelivered : pspGateway.getPspOutcomesRefused)({ query: { limit: String(Limit) } }),
  );
  const cols: Col<Outcome>[] = [
    { header: "payments.outcomes.since", cell: (o) => <DateTime instant={o.since} zone={tz} /> },
    { header: "payments.outcomes.kind", cell: (o) => word("payments.outcomes.kindOf", o.kind) },
    { header: "payments.outcomes.provider", cell: (o) => o.provider },
    { header: "payments.outcomes.outcome", cell: (o) => o.outcome },
    { header: "payments.outcomes.receiver", cell: (o) => o.receiver },
    { header: "payments.outcomes.attempts", numeric: true, cell: (o) => o.attempts },
    { header: "payments.outcomes.lastAnswer", cell: (o) => o.lastAnswer ?? "" },
    { header: "payments.outcomes.reference", cell: (o) => o.reference },
    ...(readOnly
      ? []
      : [
          {
            header: "payments.act" as const,
            cell: (o: Outcome) => (
              <Action
                action={Actions["payments.outcome.redrive"]}
                me={me}
                bags={bags}
                subject={<p>{t("payments.outcomes.subject", { kind: word("payments.outcomes.kindOf", o.kind), reference: o.reference, outcome: o.outcome })}</p>}
                run={() => pspGateway.postPspOutcomesRedrive({ reference: o.reference })}
                onDone={done}
              />
            ),
          },
        ]),
  ];
  return (
    <section aria-labelledby={`pay-${which}`}>
      <h2 id={`pay-${which}`}>{t(heading)}</h2>
      {q.isError ? <RefusalNotice refusal={q.error as never} /> : null}
      {q.data ? (
        <>
          {q.data.total > q.data.outcomes.length ? <p className="bo-muted">{t("payments.outcomes.more", { count: q.data.outcomes.length, total: q.data.total })}</p> : null}
          <Rows caption={heading} cols={cols} rows={q.data.outcomes} rowKey={(o) => o.reference} empty="payments.outcomes.none" />
        </>
      ) : null}
    </section>
  );
}

export function OutcomesSection() {
  const zone = useZone();
  return (
    <div>
      <p className="bo-muted">{t("payments.outcomes.note")}</p>
      <ZoneNote zone={zone} />
      <OutcomeList which="undelivered" heading="payments.outcomes.undelivered" />
      <OutcomeList which="refused" heading="payments.outcomes.refused" />
    </div>
  );
}
