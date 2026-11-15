// Where the doubles and the page's server listen for the browser suites, and how both are started.
// The server runs as `npm run dev` would, with the calling address at the doubles: no `ankka local web`
// (README.md, V3). Both on `localhost`, which Chromium holds a secure context, so the `__Host-`
// cookies, which are `Secure`, are kept over plain http.
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { Identity } from "./realm.ts";

export const Root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const DoublesPort = Number(process.env.DOUBLES_PORT ?? 4710);
export const ServerPort = Number(process.env.BACKOFFICE_PORT ?? 4700);
export const DoublesUrl = `http://localhost:${DoublesPort}`;
export const BackofficeUrl = `http://localhost:${ServerPort}`;
export const Brand = "goldvibe";

/** The page's server's environment, as the platform would give it. */
export const serverEnv: Record<string, string> = {
  PORT: String(ServerPort),
  ANKKA_SERVICES_URL: `http://127.0.0.1:${DoublesPort}`,
  CASINO_BRAND: Brand,
  BACKOFFICE_SHARED_PROJECTS: "payments=payments,programme=programme,reasoner=reasoner,games=games",
  ANKKA_AUTH_STAFF_ISSUER: `${DoublesUrl}/realms/eitheror`,
  BACKOFFICE_HOST: BackofficeUrl,
  BACKOFFICE_DOCUMENT_STORE: `http://127.0.0.1:${DoublesPort}/document-store/${Brand}/`,
  NODE_ENV: "development",
};

/** Playwright's `webServer`: the doubles, then the server against them. */
export const webServer = [
  {
    command: "node tests/doubles/server.ts",
    cwd: Root,
    url: `http://127.0.0.1:${DoublesPort}/healthz`,
    env: { DOUBLES_PORT: String(DoublesPort) },
    reuseExistingServer: !process.env.CI,
    stdout: "ignore" as const,
  },
  {
    command: "node src/server/main.ts",
    cwd: Root,
    url: `http://127.0.0.1:${ServerPort}/healthz`,
    env: serverEnv,
    reuseExistingServer: !process.env.CI,
    stdout: "ignore" as const,
  },
];

/** Who the world can sign in as: a default role of the brand, an operator of another brand only, or the platform's administrator. */
export type Who = "brand_support" | "brand_finance" | "brand_compliance" | "brand_admin" | "brand-less" | "platform_admin";

export function operator(who: Who | string, brand = Brand): Identity {
  if (who === "platform_admin") return { sub: "op-platform-admin", name: "Paula Admin", roles: ["platform_admin"] };
  if (who === "brand-less") return { sub: "op-spinvibe-support", name: "Sam Other", roles: ["brand_spinvibe", "brand_support"] };
  return { sub: `op-${who.replace(/_/g, "-")}`, name: `${who} operator`, roles: [`brand_${brand}`, who] };
}
