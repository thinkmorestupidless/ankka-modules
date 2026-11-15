// The player's identity and protection tabs as routes of their own (`/players/$player/identity`,
// `/players/$player/protection`) until the player's page (T075) puts them in its tabs.
import { useParams } from "@tanstack/react-router";
import { PlayerIdentity } from "./identity.tsx";
import { PlayerProtection } from "./protection.tsx";

const usePlayer = () => (useParams({ strict: false }) as { player?: string }).player;

export function IdentityTab() {
  const player = usePlayer();
  return <PlayerIdentity key={player} player={player ?? ""} />;
}

export function ProtectionTab() {
  const player = usePlayer();
  return <PlayerProtection key={player} player={player ?? ""} />;
}
