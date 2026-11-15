// An operator whose roles do not name this backoffice's brand sees nothing of it: only the role that
// would let them in (FR-002). One who held it and lost it is signed out of the brand and told why
// (edge case 1).
import { useEffect, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { signOut } from "../../frame/TopBar.tsx";

/** The realm role that names a brand (the server's `inBrand`). */
export const brandRole = (brand: string) => `brand_${brand}`;

/** Ends the session on the server without leaving the page, so the reason stays on screen. */
export async function endSession(fetchFn: typeof fetch = fetch): Promise<void> {
  await fetchFn("/auth/sign-out", { method: "POST", headers: { "X-Backoffice": "1" } }).catch(() => undefined);
}

export function NotInBrand({ brand, lost, end = endSession }: { brand: string; lost: boolean; end?: () => Promise<void> }) {
  const [ended, setEnded] = useState(!lost);
  useEffect(() => {
    if (lost) void end().then(() => setEnded(true));
  }, [lost, end]);
  return (
    <section className="bo-centre" role="alert" aria-busy={!ended || undefined}>
      <div>
        {lost ? <p>{t("access.lost", { brand })}</p> : null}
        <p>{t("access.needRole", { brand, role: brandRole(brand) })}</p>
        <p className="bo-muted">{t("access.askAdmin", { brand })}</p>
        <p>
          {lost ? (
            <a className="bo-button" href="/auth/sign-in">
              {t("access.signIn")}
            </a>
          ) : (
            <button type="button" className="bo-button" onClick={() => void signOut()}>
              {t("access.otherOperator")}
            </button>
          )}
        </p>
      </div>
    </section>
  );
}
