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
/** A second brand, with a backoffice of its own beside the seeded brand's (spec 032: roles held per brand). */
export const OtherBrand = "spinvibe";
export const OtherServerPort = Number(process.env.BACKOFFICE_OTHER_PORT ?? ServerPort + 1);
export const OtherBackofficeUrl = `http://localhost:${OtherServerPort}`;
/** The backoffice of `brand`: the seeded brand's or the second brand's. */
export function backofficeUrlOf(brand: string): string {
  if (brand === Brand) return BackofficeUrl;
  if (brand === OtherBrand) return OtherBackofficeUrl;
  throw new Error(`the suites run no backoffice for ${brand}`);
}

/** The page's server's environment, as the platform would give it. */
export const serverEnv: Record<string, string> = {
  PORT: String(ServerPort),
  ANKKA_SERVICES_URL: `http://127.0.0.1:${DoublesPort}`,
  CASINO_BRAND: Brand,
  BACKOFFICE_SHARED_PROJECTS: "payments=payments,programme=programme,reasoner=reasoner,games=games",
  ANKKA_AUTH_STAFF_ISSUER: `${DoublesUrl}/realms/eitheror`,
  BACKOFFICE_HOST: BackofficeUrl,
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
  {
    command: "node src/server/main.ts",
    cwd: Root,
    url: `http://127.0.0.1:${OtherServerPort}/healthz`,
    env: { ...serverEnv, PORT: String(OtherServerPort), CASINO_BRAND: OtherBrand, BACKOFFICE_HOST: OtherBackofficeUrl },
    reuseExistingServer: !process.env.CI,
    stdout: "ignore" as const,
  },
];

/** Who the world can sign in as: a default role of the brand, an operator of another brand only, or the platform's administrator. */
export type Who = "brand_support" | "brand_finance" | "brand_compliance" | "brand_admin" | "brand-less" | "platform_admin";

export function operator(who: Who | string, brand = Brand): Identity {
  if (who === "platform_admin") return { sub: "op-platform-admin", name: "Paula Admin", roles: ["platform_admin"] };
  if (who === "brand-less") return { sub: "op-spinvibe-support", name: "Sam Other", roles: [`brand_${OtherBrand}`, `brand_support@${OtherBrand}`] };
  return { sub: `op-${who.replace(/_/g, "-")}`, name: `${who} operator`, roles: [`brand_${brand}`, `${who}@${brand}`] };
}
