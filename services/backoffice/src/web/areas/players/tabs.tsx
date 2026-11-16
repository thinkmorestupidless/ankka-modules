// The tabs other areas add to the player's page (`page.tsx`, T075): an area's tab is listed in `ExtraTabs`
// and shown under the player's standing at `/players/$player/<id>`. The identity, protection and withdrawals
// tabs below are the page's own now; their wrappers stay for a route that shows one alone.
import type { ReactNode } from "react";
import { useParams } from "@tanstack/react-router";
import type { MessageKey } from "../../../shared/messages/index.ts";
import { PlayerIdentity } from "./identity.tsx";
import { PlayerMessages } from "./messages.tsx";
import { PlayerProtection } from "./protection.tsx";
import { PlayerRewards } from "./rewards.tsx";
import { PlayerWithdrawals } from "./withdrawals.tsx";

export interface ExtraTab {
  id: string;
  label: MessageKey;
  show(player: string): ReactNode;
}

/** Other areas' tabs of the player's page, in the order shown after withdrawals. */
export const ExtraTabs: ExtraTab[] = [
  { id: "rewards", label: "players.tab.rewards", show: (player) => <PlayerRewards player={player} /> },
  { id: "messages", label: "players.tab.messages", show: (player) => <PlayerMessages player={player} /> },
];

const usePlayer = () => (useParams({ strict: false }) as { player?: string }).player;

export function IdentityTab() {
  const player = usePlayer();
  return <PlayerIdentity key={player} player={player ?? ""} />;
}

export function ProtectionTab() {
  const player = usePlayer();
  return <PlayerProtection key={player} player={player ?? ""} />;
}

export function WithdrawalsTab() {
  const player = usePlayer();
  return <PlayerWithdrawals key={player} player={player ?? ""} />;
}

export function RewardsTab() {
  const player = usePlayer();
  return <PlayerRewards key={player} player={player ?? ""} />;
}
