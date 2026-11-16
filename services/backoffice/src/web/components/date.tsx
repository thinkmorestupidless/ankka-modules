// An instant in the brand's zone with the zone named (FR-015): `formatInstant` names it short in the
// text (`GMT-3`), and the element's title names it in full (`America/Sao_Paulo`).
import "./components.css";
import { formatInstant } from "../../shared/dates.ts";
import { t } from "../../shared/messages/index.ts";
import { useOperator } from "../frame/context.ts";

/** The brand's zone, from `/api/me`: every date on a page is shown in it, never in the browser's (FR-015). */
export function useZone(): string {
  return useOperator().brand.zone;
}

export function DateTime({ instant, zone }: { instant: string | number; zone: string }) {
  const iso = new Date(instant).toISOString();
  return (
    <time className="bo-date" dateTime={iso} title={t("date.zone", { zone })} data-zone={zone}>
      {formatInstant(instant, zone)}
    </time>
  );
}

/** The sentence a page shows once, above its dates: "Times are in America/Sao_Paulo." */
export function ZoneNote({ zone }: { zone: string }) {
  return <p className="bo-field-hint">{t("date.zone", { zone })}</p>;
}
