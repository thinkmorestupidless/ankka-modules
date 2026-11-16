// A player's page (T075, T077; FR-020, FR-024, FR-025, edge case 5): the standing at the top, fitting a
// 1440 by 900 screen above the tabs (identity, account status, identity status, limits and breaks,
// balances with restricted funds, lifetime figures, pinned notes, flags and presence), each part from
// its own owner and named when that owner does not answer; the standing's actions, each offered in the
// states its owner accepts it in; and the tabs, each a route of its own (`/players/$player/$tab`, or
// `?tab=`). An erased player is shown under the pseudonym only: no personal data is answered for them.
import type { ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams, useRouterState } from "@tanstack/react-router";
import "./players.css";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action, RefusalNotice } from "../../components/action/index.ts";
import { DateTime } from "../../components/date.tsx";
import { Money } from "../../components/money.tsx";
import { Status } from "../../components/status.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { refusalOf } from "../corrections/shared.tsx";
import { word } from "../word.ts";
import { readStanding, type Part, type Standing } from "./read.ts";
import { PlayerTimeline } from "./timeline.tsx";
import { PlayerProfile } from "./profile.tsx";
import { PlayerNotes } from "./notes.tsx";
import { PlayerWallets } from "./wallets.tsx";
import { PlayerRounds } from "./rounds.tsx";
import { PlayerSessions } from "./sessions.tsx";
import { PlayerIdentity } from "./identity.tsx";
import { PlayerProtection } from "./protection.tsx";
import { PlayerWithdrawals } from "./withdrawals.tsx";
import { ExtraTabs } from "./tabs.tsx";

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
const json = (body: unknown) => ({ body });

/** The tabs in order, each with the component it shows; `ExtraTabs` are other areas' (rewards). */
const Tabs: { id: string; label: MessageKey; show(player: string): ReactNode }[] = [
  { id: "timeline", label: "players.tab.timeline", show: (p) => <PlayerTimeline player={p} /> },
  { id: "profile", label: "players.tab.profile", show: (p) => <PlayerProfile player={p} /> },
  { id: "notes", label: "players.tab.notes", show: (p) => <PlayerNotes player={p} /> },
  { id: "wallets", label: "players.tab.wallets", show: (p) => <PlayerWallets player={p} /> },
  { id: "games", label: "players.tab.games", show: (p) => <PlayerRounds player={p} /> },
  { id: "sessions", label: "players.tab.sessions", show: (p) => <PlayerSessions player={p} /> },
  { id: "withdrawals", label: "players.tab.withdrawals", show: (p) => <PlayerWithdrawals player={p} /> },
  ...ExtraTabs,
  { id: "identity", label: "players.tab.identity", show: (p) => <PlayerIdentity player={p} /> },
  { id: "protection", label: "players.tab.protection", show: (p) => <PlayerProtection player={p} /> },
];

/** A part of the standing: its heading, and its data or why its owner did not answer. */
function Card<T>({ id, heading, part, children, wide }: { id: string; heading: MessageKey; part: Part<T>; children(data: T): ReactNode; wide?: boolean }) {
  return (
    <section className="bo-standing-part" data-part={id} data-wide={wide ? "" : undefined} aria-labelledby={`standing-${id}`}>
      <h2 id={`standing-${id}`}>{t(heading)}</h2>
      {part.state === "ok" ? children(part.data) : <p role="status" data-unavailable>{t("players.standing.unavailable", { reason: word("players.reason", part.reason) })}</p>}
    </section>
  );
}

function Field({ name, children }: { name: MessageKey; children: ReactNode }) {
  return (
    <div>
      <dt>{t(name)}</dt>
      <dd>{children ?? "—"}</dd>
    </div>
  );
}

const shown = (v: string | undefined | null) => (v === undefined || v === null || v === "" ? null : v);

function StandingView({ player, s }: { player: string; s: Standing }) {
  const me = useOperator();
  const bags = useBags();
  const readOnly = useReadOnlyMode();
  const client = useQueryClient();
  const tz = zone();
  const done = () => void client.invalidateQueries({ queryKey: ["players", "standing", player] });
  const p = s.parts;
  const status = p.account.state === "ok" ? p.account.data.status : undefined;
  const act = (node: ReactNode) => (readOnly ? null : node);
  const reasoned = (f: (playerId: { playerId: string }, o: { body: unknown }) => Promise<Response>) => (reason: string | undefined) => f({ playerId: player }, json(reason ? { reason } : {}));
  return (
    <div className="bo-standing">
      <Card id="identity" heading="players.standing.identity" part={p.profile}>
        {(d) =>
          d.erased ? (
            <p data-erased>{t("players.erased", { pseudonym: d.pseudonym ?? player })}</p>
          ) : (
            <dl className="bo-standing-fields">
              <Field name="players.field.name">{shown([d.firstName, d.lastName].filter(Boolean).join(" ")) ?? shown(d.displayName)}</Field>
              <Field name="players.field.email">{shown(d.email)}</Field>
              <Field name="players.field.dateOfBirth">{shown(d.dateOfBirth)}</Field>
              <Field name="players.field.country">{shown(d.country)}</Field>
              <Field name="players.field.currency">{shown(d.currency)}</Field>
              <Field name="players.field.id">{player}</Field>
            </dl>
          )
        }
      </Card>
      <Card id="account" heading="players.standing.account" part={p.account}>
        {(d) => (
          <>
            <p>
              <Status value={d.status ?? "unknown"} /> {d.isTestAccount ? <span className="bo-chip">{t("players.testAccount")}</span> : null}
              {d.vipTier ? <span className="bo-chip">{t("players.vipTier", { tier: d.vipTier })}</span> : null}
            </p>
            <dl className="bo-standing-fields">
              <Field name="players.field.registered">{d.registeredAt ? <DateTime instant={d.registeredAt} zone={tz} /> : null}</Field>
              <Field name="players.field.lastLogin">{d.lastLoginAt ? <DateTime instant={d.lastLoginAt} zone={tz} /> : null}</Field>
            </dl>
            {act(
              <div className="bo-actions" data-standing-actions>
                <Action action={Actions["player.suspend"]} me={me} bags={bags} state={status} run={reasoned(players.patchPlayersSuspend)} onDone={done} />
                <Action action={Actions["player.reinstate"]} me={me} bags={bags} state={status} run={() => players.patchPlayersReinstate({ playerId: player }, json({}))} onDone={done} />
                <Action action={Actions["player.close"]} me={me} bags={bags} state={status} run={reasoned(players.postPlayersClose)} onDone={done} />
                <Action action={Actions["player.reopen"]} me={me} bags={bags} state={status} run={reasoned(players.postPlayersReopen)} onDone={done} />
                <Action action={Actions["player.resetPassword"]} me={me} bags={bags} run={() => players.postPlayersResetPassword({ playerId: player })} onDone={done} />
                <VerificationLink player={player} status={status} />
                <Action action={Actions["player.requestErasure"]} me={me} bags={bags} state={status} run={() => players.postPlayersErasure({ playerId: player })} onDone={done} />
              </div>,
            )}
          </>
        )}
      </Card>
      <Card id="identity-status" heading="players.standing.identityStatus" part={p.identity}>
        {(d) => (
          <>
            <p>
              <Status value={d.status ?? "unknown"} /> <span className="bo-muted">{word("identity.mode", d.mode ?? "")}</span>
            </p>
            {d.openFlags.length > 0 ? <p>{t("players.identity.flags", { count: d.openFlags.length })}</p> : null}
          </>
        )}
      </Card>
      <Card id="protection" heading="players.standing.protection" part={p.protection}>
        {(d) => (
          <>
            {d.limits.length === 0 ? <p className="bo-muted">{t("players.protection.noLimits")}</p> : (
              <ul className="bo-standing-list">
                {d.limits.map((l) => (
                  <li key={`${l.limitType}/${l.currency ?? ""}`} data-limit={l.limitType}>
                    {word("protection.limit", l.limitType ?? "")}{" "}
                    {typeof l.value === "number" && l.currency ? <Money value={{ minor: l.value, currency: l.currency }} /> : <span className="figures">{String(l.value ?? "")}</span>}
                  </li>
                ))}
              </ul>
            )}
            {d.isCoolingOff ? <p>{d.coolingOffUntil ? t("players.protection.breakUntil", { until: new Date(d.coolingOffUntil).toISOString().slice(0, 10) }) : t("players.protection.break")}</p> : null}
            {d.exclusion ? <p>{d.exclusion.permanent ? t("players.protection.excludedForever") : t("players.protection.excluded", { until: (d.exclusion.until ?? "").slice(0, 10) })}</p> : null}
            {d.openCases > 0 ? <p>{t("players.protection.cases", { count: d.openCases })}</p> : null}
            <Link className="bo-link" to={`/players/${encodeURIComponent(player)}/protection`}>
              {t("players.protection.open")}
            </Link>
          </>
        )}
      </Card>
      <Card id="balances" heading="players.standing.balances" part={p.balances}>
        {(rows) => (
          <div className="bo-standing-currencies">
            {rows.map((b) => (
              <dl key={b.currency} className="bo-standing-fields" data-currency={b.currency}>
                <Field name="players.balance.real">{b.real !== undefined ? <Money value={{ minor: b.real, currency: b.currency }} /> : null}</Field>
                <div data-restricted>
                  <dt>{t("players.balance.restricted")}</dt>
                  <dd>{b.restricted !== null ? <Money value={{ minor: b.restricted, currency: b.currency }} /> : t("players.standing.unavailable", { reason: word("players.reason", b.restrictedReason ?? "unavailable") })}</dd>
                </div>
                {b.reserved !== undefined && b.reserved !== "0" ? <Field name="players.balance.reserved"><Money value={{ minor: b.reserved, currency: b.currency }} /></Field> : null}
              </dl>
            ))}
          </div>
        )}
      </Card>
      <Card id="lifetime" heading="players.standing.lifetime" part={p.lifetime}>
        {(rows) => (
          <div className="bo-standing-currencies">
            {rows.map((l) => (
              <dl key={l.currency} className="bo-standing-fields" data-currency={l.currency}>
                <Field name="players.lifetime.deposited">{l.deposited !== undefined ? <Money value={{ minor: l.deposited, currency: l.currency }} /> : null}</Field>
                <Field name="players.lifetime.wagered">{l.wagered !== undefined ? <Money value={{ minor: l.wagered, currency: l.currency }} /> : null}</Field>
                <Field name="players.lifetime.won">{l.won !== undefined ? <Money value={{ minor: l.won, currency: l.currency }} /> : null}</Field>
              </dl>
            ))}
          </div>
        )}
      </Card>
      <Card id="notes" heading="players.standing.notes" part={p.notes}>
        {(notes) =>
          notes.length === 0 ? <p className="bo-muted">{t("players.notes.nonePinned")}</p> : (
            <ul className="bo-standing-list">
              {notes.slice(0, 3).map((n) => (
                <li key={n.noteId} data-note={n.noteId}>
                  <q>{n.text}</q> {n.author ? <span className="bo-muted">{t("players.notes.by", { operator: n.author })}</span> : null}
                </li>
              ))}
            </ul>
          )
        }
      </Card>
      <Card id="flags" heading="players.standing.flags" part={p.flags}>
        {(f) => (
          <>
            {!f.suspectedDuplicate && !f.noDepositOfferBlocked && !f.profileIncomplete && !f.emailUnverified ? <p className="bo-muted">{t("players.flags.none")}</p> : null}
            <ul className="bo-standing-list">
              {f.suspectedDuplicate ? <li data-flag="suspected_duplicate">{t("players.flags.duplicate", { count: f.linkedPlayerIds.length })}</li> : null}
              {f.noDepositOfferBlocked ? <li data-flag="no_deposit_block">{t("players.flags.noDepositBlock")}</li> : null}
              {f.profileIncomplete ? <li data-flag="profile_incomplete">{t("players.flags.profileIncomplete")}</li> : null}
              {f.emailUnverified ? <li data-flag="email_unverified">{t("players.flags.emailUnverified")}</li> : null}
            </ul>
            {act(
              <div className="bo-actions">
                <Action action={Actions["player.clearSuspectFlag"]} me={me} bags={bags} state={f.suspectedDuplicate ? "flagged" : "clear"} run={() => players.postPlayersClearSuspectFlag({ playerId: player })} onDone={done} />
                <Action action={Actions["player.clearNoDepositBlock"]} me={me} bags={bags} state={f.noDepositOfferBlocked ? "blocked" : "clear"} run={() => players.postPlayersClearNoDepositBlock({ playerId: player })} onDone={done} />
              </div>,
            )}
          </>
        )}
      </Card>
      <Card id="presence" heading="players.standing.presence" part={p.presence}>
        {(d) =>
          d.online ? (
            <ul className="bo-standing-list">
              <li>
                <Status value="online" tone="positive" /> {t("players.presence.sessions", { count: d.sessions.length })}
              </li>
              {d.sessions.slice(0, 2).map((x) => (
                <li key={x.sessionId}>
                  {x.gameName ? <span>{x.gameName} </span> : null}
                  {x.startedAt ? <DateTime instant={x.startedAt} zone={tz} /> : null}
                </li>
              ))}
            </ul>
          ) : (
            <p><Status value="offline" tone="neutral" /></p>
          )
        }
      </Card>
    </div>
  );
}

/** The link a player who has not verified their email is sent, shown to the operator (a read, so no confirmation). */
function VerificationLink({ player, status }: { player: string; status: string | undefined }) {
  const me = useOperator();
  const bags = useBags();
  return (
    <Action
      action={Actions["player.verificationLink"]}
      me={me}
      bags={bags}
      state={status}
      run={async () => {
        const r = await players.getPlayersVerificationLink({ playerId: player });
        if (!r.ok) return r;
        const b = (await r.clone().json()) as { link?: string };
        if (b.link) void navigator.clipboard?.writeText(b.link).catch(() => undefined);
        return r;
      }}
    />
  );
}

export function PlayerPage() {
  const params = useParams({ strict: false }) as { player?: string; tab?: string };
  const asked = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).tab });
  const player = params.player ?? "";
  const tabId = params.tab ?? (typeof asked === "string" ? asked : "timeline");
  const tab = Tabs.find((x) => x.id === tabId);
  const q = useQuery({ queryKey: ["players", "standing", player], queryFn: () => readStanding(player), enabled: player !== "" });
  const profile = q.data?.parts.profile;
  const name =
    profile?.state === "ok"
      ? profile.data.erased
        ? (profile.data.pseudonym ?? player)
        : [profile.data.firstName, profile.data.lastName].filter(Boolean).join(" ") || profile.data.email || player
      : player;
  return (
    <section className="bo-player" data-player={player}>
      <h1>{t("players.page.title", { name })}</h1>
      {q.isError ? <RefusalNotice refusal={refusalOf(q.error)} /> : !q.data ? <p role="status">{t("list.loading")}</p> : <StandingView player={player} s={q.data} />}
      <div role="group" className="bo-player-tabs" aria-label={t("players.tabs")}>
        <ul>
          {Tabs.map((x) => (
            <li key={x.id}>
              <Link className="bo-pill" to={`/players/${encodeURIComponent(player)}/${x.id}`} aria-current={x.id === tab?.id ? "page" : undefined}>
                {t(x.label)}
              </Link>
            </li>
          ))}
        </ul>
      </div>
      <div className="bo-player-tab" data-tab={tab?.id}>
        {tab ? <div key={`${player}/${tab.id}`}>{tab.show(player)}</div> : <p role="alert">{t("frame.notFound")}</p>}
      </div>
    </section>
  );
}
