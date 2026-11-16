// The payments area (US9, T112, T113): the shared payments' merchant, psp-gateway and ingest as this
// brand's operators work them. Each section is a route, `/payments/<section>`. Every section but the
// deposits is merchant's payments backoffice, admitted by a payments role at a level (contract
// `Routes.paymentsOperator`), so the level picker and its banner are shown here and nowhere else (FR-004).
// A brand operator holding `wallet.read` reads the deposits of the sub-brands that name their brand.
import { Link, useParams } from "@tanstack/react-router";
import type { ReactElement } from "react";
import "./payments.css";
import { holds } from "../../../shared/access.ts";
import { t, type MessageKey } from "../../../shared/messages/index.ts";
import { useOperator } from "../../frame/context.ts";
import { DepositBoundsSection } from "./deposit-bounds.tsx";
import { HierarchySection } from "./hierarchy.tsx";
import { MerchantSection } from "./merchant.tsx";
import { ProvidersSection } from "./providers.tsx";
import { LevelPicker, ScopeBanner } from "./hierarchy.tsx";
import { TerminalsSection } from "./terminals.tsx";

interface Section {
  id: string;
  label: MessageKey;
  /** Any of these opens it. */
  permissions: readonly string[];
  show(): ReactElement;
}

export const Sections: readonly Section[] = [
  { id: "hierarchy", label: "payments.section.hierarchy", permissions: ["payments.read"], show: () => <HierarchySection /> },
  { id: "merchant", label: "payments.section.merchant", permissions: ["payments.read"], show: () => <MerchantSection /> },
  { id: "providers", label: "payments.section.providers", permissions: ["payments.read"], show: () => <ProvidersSection /> },
  { id: "terminals", label: "payments.section.terminals", permissions: ["payments.read"], show: () => <TerminalsSection /> },
  { id: "deposit-bounds", label: "payments.section.depositBounds", permissions: ["payments.read"], show: () => <DepositBoundsSection /> },
];

export function PaymentsArea() {
  const me = useOperator();
  const params = useParams({ strict: false }) as { area?: string };
  const id = params.area ?? "hierarchy";
  const open = Sections.filter((s) => s.permissions.some((p) => holds(me, p)));
  const section = open.find((s) => s.id === id);
  const payments = holds(me, "payments.read");
  return (
    <section className="bo-pay" data-section={id}>
      <header className="bo-pay-head">
        <h1>{section ? t(section.label) : t("area.payments")}</h1>
        <p className="bo-muted">{payments ? t("payments.note") : t("payments.noteBrand", { brand: me.brand.name })}</p>
      </header>
      <nav className="bo-pay-sections" aria-label={t("payments.sections")}>
        <ul>
          {open.map((s) => (
            <li key={s.id}>
              <Link className="bo-pay-pill" to={`/payments/${s.id}`} search={(prev: Record<string, unknown>) => prev} aria-current={s.id === id ? "page" : undefined}>
                {t(s.label)}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      {payments ? (
        <>
          <LevelPicker />
          <ScopeBanner />
        </>
      ) : null}
      {section ? <div key={section.id}>{section.show()}</div> : <p role="alert">{t("frame.notFound")}</p>}
    </section>
  );
}
