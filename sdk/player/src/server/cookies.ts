import type { SiteConfig } from "./config.ts";

/** The cookies of contracts/site-server.md, by role. */
export type CookieRole = "session" | "refresh" | "player" | "language";

const names: Record<CookieRole, { prefixed: string; plain: string }> = {
  session: { prefixed: "__Host-eo_session", plain: "eo_session" },
  refresh: { prefixed: "__Secure-eo_refresh", plain: "eo_refresh" },
  player: { prefixed: "__Host-eo_player", plain: "eo_player" },
  language: { prefixed: "eo_lang", plain: "eo_lang" },
};

export const ThirtyDays = 30 * 24 * 60 * 60;
export const OneYear = 365 * 24 * 60 * 60;

export function cookieName(config: SiteConfig, role: CookieRole): string {
  return config.local ? names[role].plain : names[role].prefixed;
}

/** A `Set-Cookie` value: `HttpOnly` (but the language), `Secure` (but locally), `SameSite=Lax` (research R3). */
export function setCookie(config: SiteConfig, role: CookieRole, value: string, maxAgeSeconds: number): string {
  const path = role === "refresh" ? "/api/session" : "/";
  const parts = [`${cookieName(config, role)}=${encodeURIComponent(value)}`, `Path=${path}`, `Max-Age=${Math.max(0, Math.floor(maxAgeSeconds))}`, "SameSite=Lax"];
  if (role !== "language") parts.push("HttpOnly");
  if (!config.local) parts.push("Secure");
  return parts.join("; ");
}

export function clearCookie(config: SiteConfig, role: CookieRole): string {
  return setCookie(config, role, "", 0);
}

export function readCookies(header: string | null | undefined): Map<string, string> {
  const out = new Map<string, string>();
  for (const part of (header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    const name = part.slice(0, i).trim();
    try {
      out.set(name, decodeURIComponent(part.slice(i + 1).trim()));
    } catch {
      // a value that is not well encoded is no value
    }
  }
  return out;
}

/** The seconds a token has left by the `exp` it carries; `fallback` when it carries none. */
export function secondsLeft(token: string, now: number, fallback = 300): number {
  const payload = token.split(".")[1];
  if (!payload) return fallback;
  try {
    const exp = (JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as { exp?: unknown }).exp;
    return typeof exp === "number" ? Math.max(0, exp - Math.floor(now / 1000)) : fallback;
  } catch {
    return fallback;
  }
}
