// How fresh a figure read from the brand's views is, and whether it is provisional (FR-011): the views
// are degraded, rebuilding or catching up. Provisional is a word and a dashed underline, never colour
// alone (contracts/design.md).
import type { ReactNode } from "react";
import "../components.css";
import { t } from "../../../shared/messages/index.ts";
import { DateTime } from "../date.tsx";
import { Glyph } from "../glyph.tsx";

export interface Freshness {
  /** When the views last applied a change; null while they have applied none. */
  at: string | number | null;
  provisional: boolean;
}

export function FreshnessNote({ freshness, zone }: { freshness: Freshness; zone: string }) {
  return (
    <span className="bo-status" data-freshness={freshness.provisional ? "provisional" : "current"}>
      <Glyph name={freshness.provisional ? "alert" : "clock"} />
      {freshness.at !== null && (
        <>
          <span>{t("list.updated")}</span> <DateTime instant={freshness.at} zone={zone} />
        </>
      )}
      {freshness.provisional && (
        <>
          {freshness.at !== null && " · "}
          <span className="bo-provisional">{t("list.provisional")}</span>
        </>
      )}
    </span>
  );
}

/** A figure while it is provisional: underlined dashed and named so; as itself otherwise. */
export function Provisional({ provisional, children }: { provisional: boolean; children: ReactNode }) {
  if (!provisional) return <>{children}</>;
  return (
    <span className="bo-provisional" data-provisional>
      {children} <span>{t("list.provisionalMark")}</span>
    </span>
  );
}
