// A record links to the records it names (FR-024): a deposit to its payment, a win to its round, an
// adjustment to its cause, a withdrawal to its payment attempts. The page's routes are
// contracts/routes.md's; a record that lives on a player's page is reached there, opened by its id in
// the query. No link names the brand: it is this brand's backoffice by its address.
import { createContext, useContext, type MouseEvent, type ReactNode } from "react";
import "./components.css";
import { t, type MessageKey } from "../../shared/messages/index.ts";

export type RecordRef =
  | { kind: "player"; id: string }
  | { kind: "withdrawal"; id: string }
  | { kind: "round"; player: string; id: string }
  | { kind: "gameSession"; player: string; id: string }
  | { kind: "payment"; player: string; id: string }
  | { kind: "adjustment"; player: string; id: string }
  | { kind: "reward"; player: string; id: string }
  | { kind: "document"; player: string; id: string }
  | { kind: "case"; player: string; id: string }
  | { kind: "fraudCase"; id: string };

const e = encodeURIComponent;

/** Where a record is shown. */
export function hrefOf(ref: RecordRef): string {
  switch (ref.kind) {
    case "player":
      return `/players/${e(ref.id)}`;
    case "withdrawal":
      return `/withdrawals/${e(ref.id)}`;
    case "round":
      return `/players/${e(ref.player)}/games?round=${e(ref.id)}`;
    case "gameSession":
      return `/players/${e(ref.player)}/sessions?gameSession=${e(ref.id)}`;
    case "payment":
      return `/players/${e(ref.player)}/wallets?payment=${e(ref.id)}`;
    case "adjustment":
      return `/players/${e(ref.player)}/wallets?adjustment=${e(ref.id)}`;
    case "reward":
      return `/players/${e(ref.player)}/rewards?reward=${e(ref.id)}`;
    case "document":
      return `/players/${e(ref.player)}/identity?document=${e(ref.id)}`;
    case "case":
      return `/players/${e(ref.player)}/protection?case=${e(ref.id)}`;
    case "fraudCase":
      return `/fraud?case=${e(ref.id)}`;
  }
}

const labels: Record<RecordRef["kind"], MessageKey> = {
  player: "record.player",
  withdrawal: "record.withdrawal",
  round: "record.round",
  gameSession: "record.gameSession",
  payment: "record.payment",
  adjustment: "record.adjustment",
  reward: "record.reward",
  document: "record.document",
  case: "record.case",
  fraudCase: "record.fraudCase",
};

/**
 * How a link is followed. The frame gives its router's navigation so a link does not reload the page;
 * without it a link is an ordinary one.
 */
export const NavigateContext = createContext<((href: string) => void) | undefined>(undefined);

export function RecordLink({ to, children }: { to: RecordRef; children?: ReactNode }) {
  const navigate = useContext(NavigateContext);
  const href = hrefOf(to);
  const follow = (ev: MouseEvent<HTMLAnchorElement>) => {
    if (!navigate || ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
    ev.preventDefault();
    navigate(href);
  };
  return (
    <a className="bo-link" href={href} onClick={follow} data-record={to.kind}>
      {children ?? (
        <>
          <span>{t(labels[to.kind])}</span> <span>{to.id}</span>
        </>
      )}
    </a>
  );
}

/** The records a record names, each a link; nothing when it names none. */
export function RecordLinks({ refs }: { refs: readonly RecordRef[] }) {
  if (refs.length === 0) return null;
  return (
    <ul className="bo-links" aria-label={t("record.names")}>
      {refs.map((r) => (
        <li key={`${r.kind}/${r.id}`}>
          <RecordLink to={r} />
        </li>
      ))}
    </ul>
  );
}
