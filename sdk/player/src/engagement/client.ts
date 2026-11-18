import type { StreamEvent } from "../eventStream.ts";
import type { Result } from "../client.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";

/** A push connection: its events until it ends, and a way to close it. */
export interface Push {
  readonly events: AsyncIterable<StreamEvent>;
  close(): void;
}

/** `engagement`'s client (contracts/module-clients.md): the push connection, under the player's sign-in. */
export interface EngagementClient {
  push(): Promise<Result<Push>>;
}

export function engagementClient(transport: Transport): EngagementClient {
  const push = routes.find((r) => r.id === "push")!;
  return {
    async push() {
      const r = await transport.stream(push);
      return r.ok ? { ok: true, value: { events: r.events, close: r.close } } : r;
    },
  };
}
