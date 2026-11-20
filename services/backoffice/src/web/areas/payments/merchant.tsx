// The merchant (T112; spec 004 FR-004; the original's merchant page: its "merchant" is the sub-brand): the
// sub-brand worked on, its merchant keys (made, the raw key shown once and never again, and disabled),
// the merchant edge's rate limit (ingest, per minute, its default the installation's), and the edge's
// request log for the last day or one request by its id.
import { useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { ingest, merchant } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { ConfigForm } from "../../components/config-form.tsx";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { keyOf } from "../configuration/page.tsx";
import { Field, Rows, useChanged, usePayments, type Col } from "./parts.tsx";
import { NeedsSubBrand, useScope, type SubBrand } from "./hierarchy.tsx";

interface Key {
  keyId: string;
  name: string;
  active: boolean;
  createdAt: string;
}

export interface EdgeRequest {
  recordId: string;
  receivedAt?: string;
  route?: string;
  requestId?: string;
  amount?: number | null;
  currency?: string | null;
  country?: string | null;
  outcome?: string | null;
  refusal?: string | null;
  paymentId?: string | null;
}


export function MerchantSection() {
  const scope = useScope();
  if (scope.level !== "subBrand" || !scope.id) return <NeedsSubBrand />;
  return <Merchant key={scope.id} subBrand={scope.id} />;
}

function Merchant({ subBrand }: { subBrand: string }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const tz = useZone();
  const sb = usePayments<SubBrand>(["merchant", subBrand], () => merchant.getMerchantsById({ id: subBrand }));
  const keys = usePayments<{ keys: Key[] }>(["keys", subBrand], () => merchant.getMerchantsKeys({ id: subBrand }));
  const rate = usePayments<{ perMinute: number; default: number }>(["rate", subBrand], () => ingest.getIngestSubBrandsRate({ subBrand }));
  const [requestId, setRequestId] = useState("");
  // The id is asked for when the operator submits it, not on every keystroke.
  const [asked, setAsked] = useState("");
  const requests = usePayments<{ requests: EdgeRequest[] }>(["requests", subBrand, asked], () =>
    ingest.getIngestSubBrandsRequests({ subBrand }, { query: asked ? { requestId: asked } : {} }),
  );
  const keysChanged = useChanged("keys", subBrand);
  const rateChanged = useChanged("rate", subBrand);
  const [keyName, setKeyName] = useState("");
  const [made, setMade] = useState<{ name: string; rawKey: string } | undefined>(undefined);

  const keyCols: Col<Key>[] = [
    { header: "payments.merchant.keyName", cell: (k) => k.name },
    { header: "payments.hierarchy.status", cell: (k) => <Status value={k.active ? "active" : "disabled"} /> },
    { header: "payments.hierarchy.created", cell: (k) => <DateTime instant={k.createdAt} zone={tz} /> },
    ...(readOnly
      ? []
      : [
          {
            header: "payments.act" as const,
            cell: (k: Key) => (
              <Action
                action={Actions["payments.key.disable"]}
                me={me}
                bags={bags}
                state={k.active ? "active" : "disabled"}
                subject={<p>{t("payments.merchant.keySubject", { name: k.name })}</p>}
                run={() => merchant.postMerchantsKeysDisable({ id: subBrand, keyId: k.keyId })}
                onDone={keysChanged}
              />
            ),
          },
        ]),
  ];
  const requestCols: Col<EdgeRequest>[] = [
    { header: "payments.merchant.received", cell: (r) => (r.receivedAt ? <DateTime instant={r.receivedAt} zone={tz} /> : null) },
    { header: "payments.merchant.route", cell: (r) => r.route ?? "" },
    { header: "payments.merchant.requestId", cell: (r) => r.requestId ?? "" },
    // ingest keeps the amount in minor units (RequestRecord.amount), so it is shown by the one rule (FR-013).
    { header: "payments.merchant.amount", numeric: true, cell: (r) => (r.amount === null || r.amount === undefined ? "" : r.currency ? <Money value={{ minor: String(r.amount), currency: r.currency }} /> : <span className="figures">{String(r.amount)}</span>) },
    { header: "payments.merchant.outcome", cell: (r) => [r.outcome, r.refusal].filter(Boolean).join(": ") },
    { header: "payments.merchant.payment", cell: (r) => r.paymentId ?? "" },
  ];

  return (
    <div>
      <section aria-labelledby="pay-merchant">
        <h2 id="pay-merchant">{t("payments.merchant.details")}</h2>
        {sb.isError ? <RefusalNotice refusal={sb.error} /> : null}
        {sb.data ? (
          <dl className="bo-pay-dl">
            <dt>{t("payments.hierarchy.name")}</dt>
            <dd>{sb.data.name}</dd>
            <dt>{t("payments.merchant.id")}</dt>
            <dd className="figures">{subBrand}</dd>
            <dt>{t("payments.hierarchy.type")}</dt>
            <dd>{sb.data.type ?? ""}</dd>
            <dt>{t("payments.hierarchy.brand")}</dt>
            <dd>{sb.data.brand ?? t("payments.hierarchy.noBrand")}</dd>
            <dt>{t("payments.hierarchy.status")}</dt>
            <dd>
              <Status value={sb.data.status} />
            </dd>
          </dl>
        ) : null}
      </section>

      <section aria-labelledby="pay-keys">
        <h2 id="pay-keys">{t("payments.merchant.keys")}</h2>
        {made ? (
          <p role="status" data-raw-key>
            {t("payments.merchant.madeKey", { name: made.name })} <code>{made.rawKey}</code>
          </p>
        ) : null}
        {keys.isError ? <RefusalNotice refusal={keys.error} /> : null}
        {keys.data ? <Rows caption="payments.merchant.keys" cols={keyCols} rows={keys.data.keys} rowKey={(k) => k.keyId} empty="payments.merchant.noKeys" /> : null}
        {readOnly ? null : (
          <Action
            action={Actions["payments.key.generate"]}
            me={me}
            bags={bags}
            ready={keyName.trim() !== ""}
            subject={<Field labelKey="payments.merchant.keyName" value={keyName} set={setKeyName} />}
            run={() => merchant.postMerchantsKeys({ id: subBrand }, { body: { name: keyName.trim() } })}
            onDone={(answer) =>
              void answer.json().then((k: { name: string; rawKey: string }) => {
                setMade(k);
                setKeyName("");
                keysChanged();
              })
            }
          />
        )}
      </section>

      <section aria-labelledby="pay-rate">
        <h2 id="pay-rate">{t("payments.merchant.rate")}</h2>
        {rate.isError ? <RefusalNotice refusal={rate.error} /> : null}
        {rate.data ? (
          <ConfigForm
            key={keyOf(rate.data)}
            fields={[{ name: "perMinute", label: "payments.merchant.perMinute", kind: "whole", default: rate.data.default }]}
            values={{ perMinute: rate.data.perMinute }}
            action={Actions["payments.rate"]}
            save={(change) => ingest.putIngestSubBrandsRate({ subBrand }, { body: { perMinute: change.perMinute } })}
            onDone={rateChanged}
          />
        ) : null}
      </section>

      <section aria-labelledby="pay-requests">
        <h2 id="pay-requests">{t("payments.merchant.requests")}</h2>
        <p className="bo-muted">{t("payments.merchant.requestsNote")}</p>
        <ZoneNote zone={tz} />
        <form
          className="bo-actions"
          onSubmit={(e) => {
            e.preventDefault();
            setAsked(requestId.trim());
          }}
        >
          <Field labelKey="payments.merchant.findRequest" value={requestId} set={setRequestId} />
          <button type="submit" className="bo-button">
            {t("payments.merchant.find")}
          </button>
        </form>
        {requests.isError ? <RefusalNotice refusal={requests.error} /> : null}
        {requests.data ? <Rows caption="payments.merchant.requests" cols={requestCols} rows={requests.data.requests} rowKey={(r) => r.recordId} empty="payments.merchant.noRequests" /> : null}
      </section>
    </div>
  );
}
