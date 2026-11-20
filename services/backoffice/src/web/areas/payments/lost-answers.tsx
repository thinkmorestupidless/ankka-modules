// The lost answers (T113; spec 006, the merchant edge): a merchant's request ingest answered after the
// merchant had stopped waiting, so the merchant may not know the payment it started. Each names the
// request, when it was answered, the ticket and the payment; an operator marks one seen once the merchant
// has been told. The sub-brand worked on narrows the list; with none chosen, every one the operator's
// level covers is listed.
import { t } from "../../../shared/messages/index.ts";
import { ingest } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import type { EdgeRequest } from "./merchant.tsx";
import { Rows, useChanged, usePayments, type Col } from "./parts.tsx";
import { useScope } from "./hierarchy.tsx";

export interface LostAnswer extends EdgeRequest {
  subBrand?: string;
  ticket?: string | null;
  lostAnswer?: { answeredAt: string; ticket?: string | null; paymentId?: string | null } | null;
  seen?: boolean | null;
}


export function LostAnswersSection() {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const scope = useScope();
  const tz = useZone();
  const subBrand = scope.level === "subBrand" ? (scope.id ?? "") : "";
  const done = useChanged("lost-answers");
  const q = usePayments<{ lostAnswers: LostAnswer[] }>(["lost-answers", subBrand], () => ingest.getIngestLostAnswers({ query: subBrand ? { subBrand } : {} }));
  const cols: Col<LostAnswer>[] = [
    { header: "payments.lost.answeredAt", cell: (r) => (r.lostAnswer?.answeredAt ? <DateTime instant={r.lostAnswer.answeredAt} zone={tz} /> : null) },
    { header: "payments.merchant.received", cell: (r) => (r.receivedAt ? <DateTime instant={r.receivedAt} zone={tz} /> : null) },
    { header: "payments.lost.subBrand", cell: (r) => r.subBrand ?? "" },
    { header: "payments.merchant.route", cell: (r) => r.route ?? "" },
    { header: "payments.merchant.requestId", cell: (r) => r.requestId ?? "" },
    { header: "payments.lost.ticket", cell: (r) => r.lostAnswer?.ticket ?? r.ticket ?? "" },
    { header: "payments.merchant.payment", cell: (r) => r.lostAnswer?.paymentId ?? r.paymentId ?? "" },
    { header: "payments.lost.seen", cell: (r) => <Status value={r.seen ? "seen" : "unseen"} tone={r.seen ? "positive" : "waiting"} /> },
    ...(readOnly
      ? []
      : [
          {
            header: "payments.act" as const,
            cell: (r: LostAnswer) => (
              <Action
                action={Actions["payments.lost.seen"]}
                me={me}
                bags={bags}
                state={r.seen ? "seen" : "unseen"}
                subject={<p>{t("payments.lost.subject", { id: r.requestId ?? r.recordId })}</p>}
                run={() => ingest.postIngestLostAnswersSeen({ recordId: r.recordId })}
                onDone={done}
              />
            ),
          },
        ]),
  ];
  return (
    <div>
      <p className="bo-muted">{t("payments.lost.note")}</p>
      <ZoneNote zone={tz} />
      {q.isError ? <RefusalNotice refusal={q.error} /> : null}
      {q.data ? <Rows caption="payments.lost.caption" cols={cols} rows={q.data.lostAnswers} rowKey={(r) => r.recordId} empty="payments.lost.none" /> : null}
    </div>
  );
}
