import { z } from "zod";
import { ask, row, type Result } from "../client.ts";
import type { Transport } from "../transport.ts";
import { routes } from "./routes.ts";

/** What a visitor gives to register (players' `POST /register`). */
export interface Registration {
  readonly email: string;
  readonly password: string;
  readonly currency: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly displayName?: string;
  readonly country?: string;
  readonly language?: string;
  readonly dateOfBirth?: string;
  readonly emailMarketingConsent?: boolean;
  readonly smsMarketingConsent?: boolean;
  readonly deviceFingerprint?: string;
}

const Registered = z.object({ playerId: z.string(), status: z.string() });
const Verified = z.object({ status: z.string() }).partial().passthrough();
const Resent = z.object({ result: z.string() }).partial().passthrough();
const Anything = z.unknown();

/** `core`'s client (contracts/module-clients.md): one function per row a page may call. */
export interface CoreClient {
  register(r: Registration): Promise<Result<z.infer<typeof Registered>>>;
  verifyEmail(token: string): Promise<Result<z.infer<typeof Verified>>>;
  resendVerification(email: string): Promise<Result<z.infer<typeof Resent>>>;
  requestPasswordReset(email: string): Promise<Result<unknown>>;
}

export function coreClient(transport: Transport): CoreClient {
  return {
    register: (r) => ask(transport, row(routes, "register"), Registered, { body: r }),
    verifyEmail: (token) => ask(transport, row(routes, "verifyEmail"), Verified, { body: { token } }),
    resendVerification: (email) => ask(transport, row(routes, "resendVerification"), Resent, { body: { email } }),
    requestPasswordReset: (email) => ask(transport, row(routes, "requestPasswordReset"), Anything, { body: { email } }),
  };
}
