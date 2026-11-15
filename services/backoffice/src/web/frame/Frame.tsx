// The frame around every screen: the rail, the top bar, the read-only notice below 1280 px, and the page.
import type { ReactNode } from "react";
import { Outlet, useRouterState } from "@tanstack/react-router";
import { t } from "../../shared/messages/index.ts";
import type { Me } from "../../shared/access.ts";
import { render } from "../../shared/messages/index.ts";
import { NotInBrand } from "../areas/access/NotInBrand.tsx";
import { areaOf, areasFor } from "./areas.ts";
import { MeContext, ReadOnlyContext } from "./context.ts";
import { Rail } from "./Rail.tsx";
import { TopBar } from "./TopBar.tsx";
import { useMe, useReadOnly, useTheme, type MeState } from "./session.ts";

export function Frame({ me, readOnly, children }: { me: Me; readOnly: boolean; children?: ReactNode }) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  return (
    <MeContext.Provider value={me}>
      <ReadOnlyContext.Provider value={readOnly}>
        <a className="bo-skip" href="#page">
          {t("frame.skip")}
        </a>
        <div className="bo-frame" data-read-only={readOnly || undefined}>
          <Rail areas={areasFor(me)} current={areaOf(pathname)} collapsed={readOnly} />
          <div className="bo-body">
            <TopBar me={me} />
            {readOnly ? (
              <p role="status" className="bo-notice">
                {t("frame.readOnly")}
              </p>
            ) : null}
            <main id="page" className="bo-page" tabIndex={-1}>
              {children ?? <Outlet />}
            </main>
          </div>
        </div>
      </ReadOnlyContext.Provider>
    </MeContext.Provider>
  );
}

/** What the page shows before and instead of the frame. */
export function Outside({ state }: { state: Exclude<MeState, { state: "signedIn" }> }) {
  if (state.state === "loading") return <p className="bo-centre">{t("frame.loading")}</p>;
  if (state.state === "unreachable") return <p className="bo-centre" role="alert">{t("frame.unreachable")}</p>;
  if (state.state === "notInBrand") return <NotInBrand brand={state.brand} lost={state.lost} />;
  return (
    <div className="bo-centre" role="alert">
      <p>{render(state.refusal.message)}</p>
      {state.refusal.next ? <p>{render(state.refusal.next)}</p> : null}
    </div>
  );
}

/** The root: the operator read from `/api/me`, then the frame; signed out goes to the sign-in. */
export function Shell() {
  const state = useMe();
  const readOnly = useReadOnly();
  useTheme(state.state === "signedIn" ? state.me : undefined);
  if (state.state !== "signedIn") return <Outside state={state} />;
  return <Frame me={state.me} readOnly={readOnly} />;
}
