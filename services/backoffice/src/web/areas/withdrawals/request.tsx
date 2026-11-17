// A withdrawal request's page (T082; US4, FR-014, FR-026, SC-003): its whole story in one place. The
// amounts requested, held, paid, returned and voided, and why a part was voided; the player's balances
// when it was made and now; the identity status then and now, and the recent deposits; the terminal
// and the destination; every state it passed through with when and who moved it; the payment's
// attempts; and exactly the actions its state allows, each stating its effect beside it and again before
// it is confirmed. payouts decides every rule; a neighbour that cannot be read is said to be unavailable.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { useId, type ReactNode } from "react";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { payouts } from "../../../shared/owners/index.ts";
import { Actions, offeredIn, type ActionId } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { RecordLink } from "../../components/links.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { word } from "../word.ts";
import { actionStateOf, amountsOf, readBalances, readDeposits, readIdentity, readRequest, type Request, type Transition } from "./read.ts";
import "./withdrawals.css";


const Offered = ["withdrawal.approve", "withdrawal.reject", "withdrawal.confirmPayout", "withdrawal.markPayoutFailed"] as const satisfies readonly ActionId[];
type Offered = (typeof Offered)[number];

function Section({ heading, children }: { heading: MessageKey; children: ReactNode }) {
  const id = useId();
  return (
    <section className="bo-withdrawal-section" aria-labelledby={id}>
      <h2 id={id}>{t(heading)}</h2>
      {children}
    </section>
  );
}

const Unavailable = () => <p className="bo-muted" role="status">{t("withdrawals.unavailable")}</p>;

function Amounts({ r }: { r: Request }) {
  return (
    <Section heading="withdrawals.request.amounts">
      <dl className="bo-withdrawal-facts">
        {amountsOf(r).map((a) => (
          <div key={a.which} data-amount={a.which}>
            <dt>{t(`withdrawals.amount.${a.which}` as MessageKey)}</dt>
            <dd>
              <Money value={{ minor: a.minor, currency: r.currency }} />
              {a.which === "voided" ? <p className="bo-muted">{t("withdrawals.voided.cap")}</p> : null}
            </dd>
          </div>
        ))}
        {r.bonusForfeited ? (
          <div data-amount="forfeited">
            <dt>{t("withdrawals.amount.forfeited")}</dt>
            <dd>
              <Money value={{ minor: r.forfeitedAmount ?? 0, currency: r.currency }} />
              <p className="bo-muted">{t("withdrawals.forfeited.why")}</p>
            </dd>
          </div>
        ) : null}
        {(r.clawbackAmount ?? 0) > 0 ? (
          <div data-amount="clawback">
            <dt>{t("withdrawals.amount.clawback")}</dt>
            <dd>
              <Money value={{ minor: r.clawbackAmount!, currency: r.currency }} />
              <p className="bo-muted">{t("withdrawals.clawback.why")}</p>
            </dd>
          </div>
        ) : null}
      </dl>
    </Section>
  );
}

/** A figure then and now: then as payouts recorded it, now as the wallet answers. */
function Figure({ then, now, currency, pending }: { then: number | null | undefined; now: string | undefined; currency: string; pending: boolean }) {
  return (
    <>
      <td>{then === null || then === undefined ? <span className="bo-muted">{t("withdrawals.balances.notRecorded")}</span> : <Money value={{ minor: then, currency }} />}</td>
      <td>{now !== undefined ? <Money value={{ minor: now, currency }} /> : pending ? t("list.loading") : <span className="bo-muted">{t("withdrawals.unavailable")}</span>}</td>
    </>
  );
}

function Balances({ r }: { r: Request }) {
  const q = useQuery({ queryKey: ["wallet", "balance", r.playerId, r.currency], queryFn: () => readBalances(r.playerId, r.currency), retry: false });
  return (
    <Section heading="withdrawals.request.balances">
      <table className="bo-withdrawal-table">
        <thead>
          <tr>
            <th scope="col">{t("withdrawals.balances.which")}</th>
            <th scope="col">{t("withdrawals.balances.then")}</th>
            <th scope="col">{t("withdrawals.balances.now")}</th>
          </tr>
        </thead>
        <tbody>
          <tr data-balance="real">
            <th scope="row">{t("withdrawals.balances.real")}</th>
            <Figure then={r.balancesAtRequest} now={q.data?.real} currency={r.currency} pending={q.isPending} />
          </tr>
          <tr data-balance="restricted">
            <th scope="row">{t("withdrawals.balances.restricted")}</th>
            <Figure then={r.restrictedAtRequest} now={q.data?.restricted} currency={r.currency} pending={q.isPending} />
          </tr>
        </tbody>
      </table>
    </Section>
  );
}

function Player({ r }: { r: Request }) {
  const tz = useZone();
  const identity = useQuery({ queryKey: ["kyc", "status", r.playerId], queryFn: () => readIdentity(r.playerId), retry: false });
  const deposits = useQuery({ queryKey: ["wallet", "deposits", r.playerId, r.currency], queryFn: () => readDeposits(r.playerId, r.currency), retry: false });
  return (
    <Section heading="withdrawals.request.player">
      <dl className="bo-withdrawal-facts">
        <div>
          <dt>{t("withdrawals.player")}</dt>
          <dd>
            <RecordLink to={{ kind: "player", id: r.playerId }} />
          </dd>
        </div>
        <div data-identity="then">
          <dt>{t("withdrawals.identity.then")}</dt>
          <dd>
            <Status value={r.kycStatus || "unverified"} />
          </dd>
        </div>
        <div data-identity="now">
          <dt>{t("withdrawals.identity.now")}</dt>
          <dd>{identity.data ? <Status value={identity.data} /> : identity.isPending ? t("list.loading") : <span className="bo-muted">{t("withdrawals.unavailable")}</span>}</dd>
        </div>
      </dl>
      <h3>{t("withdrawals.deposits")}</h3>
      {deposits.isError ? (
        <Unavailable />
      ) : deposits.data?.length === 0 ? (
        <p className="bo-muted">{t("withdrawals.deposits.none")}</p>
      ) : (
        <ul className="bo-withdrawal-deposits" aria-label={t("withdrawals.deposits")}>
          {(deposits.data ?? []).map((d) => (
            <li key={d.transactionId}>
              {d.amount !== undefined ? <Money value={{ minor: d.amount, currency: r.currency }} /> : null} <DateTime instant={d.createdAt} zone={tz} /> <Status value={d.status} />
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function Where({ r }: { r: Request }) {
  const d = r.destination;
  const holder = [d?.firstName, d?.lastName].filter((n): n is string => typeof n === "string" && n !== "").join(" ");
  return (
    <Section heading="withdrawals.request.where">
      <dl className="bo-withdrawal-facts">
        <div>
          <dt>{t("withdrawals.terminal")}</dt>
          <dd>{r.terminal ? t("withdrawals.terminal.is", { name: r.terminal.name, gateway: r.terminal.gateway, method: r.terminal.scheme }) : t("withdrawals.none")}</dd>
        </div>
        <div>
          <dt>{t("withdrawals.destination")}</dt>
          <dd>
            {d
              ? [holder, d.ibanLastFour ? t("withdrawals.destination.lastFour", { lastFour: d.ibanLastFour }) : ""].filter((s) => s !== "").join(" · ") || d.destinationId
              : t("withdrawals.none")}
          </dd>
        </div>
        {r.providerReference ? (
          <div>
            <dt>{t("withdrawals.reference")}</dt>
            <dd>
              <code>{r.providerReference}</code>
            </dd>
          </div>
        ) : null}
      </dl>
    </Section>
  );
}

/** Who moved a state: the player, the system, the provider, or an operator by id (`you` for the one signed in). */
export function actorOf(tr: { by: Transition["by"]; operatorId?: string | null }, me: string | null | undefined): string {
  if (tr.by === "operator") return tr.operatorId ? (tr.operatorId === me ? t("withdrawals.actor.you") : tr.operatorId) : t("withdrawals.actor.operator");
  return word("withdrawals.actor", tr.by);
}

function History({ r }: { r: Request }) {
  const me = useOperator();
  const tz = useZone();
  const transitions = r.transitions ?? [];
  return (
    <Section heading="withdrawals.request.history">
      {transitions.length === 0 ? (
        <p className="bo-muted">{t("withdrawals.history.none")}</p>
      ) : (
        <ol className="bo-withdrawal-history" aria-label={t("withdrawals.request.history")}>
          {transitions.map((tr, i) => (
            <li key={`${tr.status}-${tr.at}-${i}`} data-status={tr.status}>
              <Status value={tr.status} /> <DateTime instant={tr.at} zone={tz} /> <span data-actor>{t("withdrawals.history.by", { actor: actorOf(tr, me.subject) })}</span>
              {tr.reason ? <span className="bo-muted"> · {word("withdrawals.reason", tr.reason)}</span> : null}
            </li>
          ))}
        </ol>
      )}
    </Section>
  );
}

function Attempts({ r }: { r: Request }) {
  const me = useOperator();
  const tz = useZone();
  const outcomes = r.outcomes ?? [];
  return (
    <Section heading="withdrawals.request.attempts">
      {outcomes.length === 0 ? (
        <p className="bo-muted">{t("withdrawals.attempts.none")}</p>
      ) : (
        <table className="bo-withdrawal-table">
          <thead>
            <tr>
              <th scope="col">{t("withdrawals.attempts.at")}</th>
              <th scope="col">{t("withdrawals.attempts.source")}</th>
              <th scope="col">{t("withdrawals.attempts.answer")}</th>
              <th scope="col">{t("withdrawals.attempts.reason")}</th>
              <th scope="col">{t("withdrawals.attempts.reference")}</th>
            </tr>
          </thead>
          <tbody>
            {outcomes.map((o, i) => (
              <tr key={`${o.at}-${i}`} data-source={o.source}>
                <td>
                  <DateTime instant={o.at} zone={tz} />
                </td>
                <td>{o.operatorId ? actorOf({ by: "operator", operatorId: o.operatorId }, me.subject) : word("withdrawals.source", o.source)}</td>
                <td>{word("withdrawals.answer", o.answer)}</td>
                <td>{o.reasonCode ? word("withdrawals.reason", o.reasonCode) : null}</td>
                <td>{o.reference ? <code>{o.reference}</code> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  );
}

/** Exactly the actions the request's state allows (FR-026), each with its effect stated beside it. */
function Actions_({ r }: { r: Request }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const id = useId();
  const state = actionStateOf(r);
  const offered = Offered.filter((a) => offeredIn(Actions[a], state));
  const key = { requestId: r.requestId };
  const done = () => void client.invalidateQueries({ queryKey: ["payouts", "request", r.requestId] });
  // a refusal because the request moved on is followed by a read of it
  const run = (call: () => Promise<Response>) => async () => {
    const answer = await call();
    if (answer.status === 409 || answer.status === 404) done();
    return answer;
  };
  const calls: Record<Offered, (reason: string | undefined) => Promise<Response>> = {
    "withdrawal.approve": () => run(() => payouts.postWithdrawalRequestsApprove(key))(),
    "withdrawal.reject": (reason) => run(() => payouts.postWithdrawalRequestsReject(key, { body: { reason } }))(),
    "withdrawal.confirmPayout": (note) => run(() => payouts.postWithdrawalRequestsConfirmPayout(key, { body: note ? { note } : {} }))(),
    "withdrawal.markPayoutFailed": (reason) => run(() => payouts.postWithdrawalRequestsMarkPayoutFailed(key, { body: { reason } }))(),
  };
  const subject = <Money value={{ minor: r.amount, currency: r.currency }} />;
  return (
    <section className="bo-withdrawal-section bo-withdrawal-actions" aria-labelledby={id}>
      <h2 id={id}>{t("withdrawals.request.actions")}</h2>
      {offered.length === 0 ? (
        <p className="bo-muted">{t("withdrawals.request.noActions")}</p>
      ) : readOnly ? (
        <p className="bo-muted">{t("withdrawals.request.readOnly")}</p>
      ) : (
        <ul>
          {offered.map((a) => (
            <li key={a}>
              <Action action={Actions[a]} me={me} bags={bags} state={state} subject={subject} run={calls[a]} onDone={done} />
              <p className="bo-muted" data-effect-of={a}>
                {t(Actions[a].effect)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function WithdrawalRequestPage() {
  const { request } = useParams({ strict: false }) as { request?: string };
  const id = request ?? "";
  const tz = useZone();
  const q = useQuery({ queryKey: ["payouts", "request", id], queryFn: () => readRequest(id), refetchInterval: 10_000, enabled: id !== "" });
  const r = q.data;
  return (
    <section className="bo-withdrawal">
      <h1>{t("withdrawals.request.title", { id })}</h1>
      {q.isError && !r ? <RefusalNotice refusal={q.error} /> : null}
      {!r ? (
        q.isError ? null : <p>{t("list.loading")}</p>
      ) : (
        <>
          <p className="bo-withdrawal-state">
            <Status value={r.status.toLowerCase()} />
            {r.payoutStatus.toLowerCase() !== "none" ? (
              <>
                {" "}
                <Status value={r.payoutStatus.toLowerCase()} />
              </>
            ) : null}{" "}
            <span className="bo-muted">{t("withdrawals.request.made")}</span> <DateTime instant={r.createdAt} zone={tz} />
          </p>
          <ZoneNote zone={tz} />
          <div className="bo-withdrawal-grid">
            <div>
              <Amounts r={r} />
              <Balances r={r} />
              <Player r={r} />
              <Where r={r} />
            </div>
            <div>
              <Actions_ r={r} />
              <History r={r} />
              <Attempts r={r} />
            </div>
          </div>
        </>
      )}
    </section>
  );
}
