// The affiliate programme's area (US10, FR-037): the brand's programme as its managers run it, from the
// shared project the brand's values name, for this backoffice's brand only. Each section is a route,
// `/affiliates/<section>`; an account is `/affiliates/accounts/<id>` (account.tsx). The managers'
// section is offered to those the programme lets add managers (brand_admin, platform_admin).
import { Link, useParams } from "@tanstack/react-router";
import type { ReactElement } from "react";
import "./affiliates.css";
import { holds } from "../../../shared/access.ts";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { useOperator } from "../../frame/context.ts";
import { Placeholder } from "../../frame/pages.tsx";
import { CreativesSection } from "./creatives.tsx";
import { DashboardSection } from "./dashboard.tsx";
import { LinksSection } from "./links.tsx";
import { ManagersSection } from "./managers.tsx";
import { MessagesSection } from "./messages.tsx";
import { PlansSection } from "./plans.tsx";
import { SettingsSection } from "./settings.tsx";

interface Section {
  id: string;
  label: MessageKey;
  permission: string;
  show(): ReactElement;
}

/** A section T119 builds. */
const notYet = (key: MessageKey) => <Placeholder label={key} />;

export const Sections: readonly Section[] = [
  { id: "dashboard", label: "affiliates.section.dashboard", permission: "programme.manage", show: () => <DashboardSection /> },
  { id: "accounts", label: "affiliates.section.accounts", permission: "programme.manage", show: () => notYet("affiliates.section.accounts") },
  { id: "plans", label: "affiliates.section.plans", permission: "programme.manage", show: () => <PlansSection /> },
  { id: "links", label: "affiliates.section.links", permission: "programme.manage", show: () => <LinksSection /> },
  { id: "creatives", label: "affiliates.section.creatives", permission: "programme.manage", show: () => <CreativesSection /> },
  { id: "payouts", label: "affiliates.section.payouts", permission: "programme.manage", show: () => notYet("affiliates.section.payouts") },
  { id: "fraud", label: "affiliates.section.fraud", permission: "programme.manage", show: () => notYet("affiliates.section.fraud") },
  { id: "messages", label: "affiliates.section.messages", permission: "programme.manage", show: () => <MessagesSection /> },
  { id: "settings", label: "affiliates.section.settings", permission: "programme.manage", show: () => <SettingsSection /> },
  { id: "managers", label: "affiliates.section.managers", permission: "programme.managers", show: () => <ManagersSection /> },
];

/** The sections as links, the current one marked; on every page of the area, an account's too. */
export function SectionNav({ current }: { current: string }) {
  const me = useOperator();
  return (
    <nav className="bo-aff-sections" aria-label={t("affiliates.sections")}>
      <ul>
        {Sections.filter((s) => holds(me, s.permission)).map((s) => (
          <li key={s.id}>
            <Link className="bo-aff-pill" to={`/affiliates/${s.id}`} aria-current={s.id === current ? "page" : undefined}>
              {t(s.label)}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** The heading every page of the area shows: the programme, and the brand it is this backoffice's for. */
export function ProgrammeHeading() {
  const me = useOperator();
  return (
    <header className="bo-aff-head">
      <h1>{t("area.affiliates")}</h1>
      <p className="bo-muted">{t("affiliates.forBrand", { brand: me.brand.name })}</p>
    </header>
  );
}

export function AffiliatesArea() {
  const me = useOperator();
  const params = useParams({ strict: false }) as { area?: string };
  const id = params.area ?? "dashboard";
  const section = Sections.find((s) => s.id === id && holds(me, s.permission));
  return (
    <section className="bo-aff" data-section={id}>
      <ProgrammeHeading />
      <SectionNav current={id} />
      {section ? <div key={section.id}>{section.show()}</div> : <p role="alert">{t("frame.notFound")}</p>}
    </section>
  );
}
