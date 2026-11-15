// The 64 px icon rail (contracts/design.md): the current area a filled circle, each label shown on hover
// and focus and always read by a screen reader. Collapsed below 1280 px.
import { Link } from "@tanstack/react-router";
import { t } from "../../shared/messages/index.ts";
import type { Area } from "./areas.ts";
import { AreaIcon } from "./icons.tsx";

export function Rail({ areas, current, collapsed }: { areas: Area[]; current: Area | undefined; collapsed: boolean }) {
  return (
    <nav className="bo-rail" data-collapsed={collapsed || undefined} aria-label={t("frame.areas")}>
      <ul>
        {areas.map((a) => (
          <li key={a.id}>
            <Link to={a.path} className="bo-rail-item" aria-current={a.id === current?.id ? "page" : undefined}>
              <AreaIcon icon={a.icon} />
              <span className="bo-rail-label">{t(a.label)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
