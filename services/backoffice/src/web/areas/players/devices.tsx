// A player's devices (T114; spec 030 P8, research *players' tags, devices and games*): each device the player
// logged in from, last seen first: its type, platform and browser, the country its address resolved to,
// when it was first and last seen. players keeps at most 50, and none for an erased player.
import { useQuery } from "@tanstack/react-query";
import "../payments/payments.css";
import { t } from "../../../shared/messages/index.ts";
import { players } from "../../../shared/owners/index.ts";
import { RefusalNotice } from "../../components/action/index.ts";
import { DateTime, ZoneNote, useZone } from "../../components/date.tsx";
import { read } from "../configuration/page.tsx";

export interface Device {
  platform: string;
  browser: string;
  deviceType: string;
  country: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
}


export function PlayerDevices({ player }: { player: string }) {
  const tz = useZone();
  const q = useQuery({ queryKey: ["players", "devices", player], queryFn: () => read<{ devices: Device[] }>(players.getPlayersDevices({ playerId: player })) });
  if (q.isError) return <RefusalNotice refusal={q.error} />;
  if (!q.data) return <p role="status">{t("list.loading")}</p>;
  const devices = q.data.devices;
  return (
    <section aria-labelledby="player-devices">
      <h2 id="player-devices">{t("players.devices.title")}</h2>
      <ZoneNote zone={tz} />
      <table className="bo-pay-table">
        <caption>
          {t("players.devices.caption")} <span className="bo-muted">{t("payments.count", { count: devices.length })}</span>
        </caption>
        <thead>
          <tr>
            <th scope="col">{t("players.devices.type")}</th>
            <th scope="col">{t("players.devices.platform")}</th>
            <th scope="col">{t("players.devices.browser")}</th>
            <th scope="col">{t("players.devices.country")}</th>
            <th scope="col">{t("players.devices.firstSeen")}</th>
            <th scope="col">{t("players.devices.lastSeen")}</th>
          </tr>
        </thead>
        <tbody>
          {devices.length === 0 ? (
            <tr>
              <td colSpan={6} className="bo-muted">
                {t("players.devices.none")}
              </td>
            </tr>
          ) : (
            devices.map((d) => (
              <tr key={`${d.deviceType}/${d.platform}/${d.browser}/${d.firstSeenAt}`}>
                <td>{d.deviceType}</td>
                <td>{d.platform}</td>
                <td>{d.browser}</td>
                <td>{d.country ?? t("players.devices.unresolved")}</td>
                <td>
                  <DateTime instant={d.firstSeenAt} zone={tz} />
                </td>
                <td>
                  <DateTime instant={d.lastSeenAt} zone={tz} />
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}
