// The pages the frame routes to before their areas are built, the modules page, and not found. Each
// area's task replaces its placeholder with the area's own page.
import { t, type MessageKey } from "../../shared/messages/index.ts";
import { Areas } from "./areas.ts";
import { useOperator } from "./context.ts";

export function Placeholder({ label }: { label: MessageKey }) {
  return (
    <section>
      <h1>{t(label)}</h1>
      <p className="bo-muted">{t("frame.notBuilt")}</p>
    </section>
  );
}

/** Why an area is absent: the brand's modules (R17). */
export function ModulesPage() {
  const me = useOperator();
  return (
    <section>
      <h1>{t("area.configuration")}</h1>
      <p>
        {me.brand.modules === null ? t("frame.modulesUnknown") : t("frame.modulesWhy", { modules: me.brand.modules.join(", ") })}
      </p>
      <ul>
        {Areas.filter((a) => me.brand.modules !== null && !me.brand.modules.includes(a.module)).map((a) => (
          <li key={a.id}>{t("frame.moduleOff", { module: `${t(a.label)} (${a.module})` })}</li>
        ))}
      </ul>
    </section>
  );
}

export function NotFound() {
  return <p role="alert">{t("frame.notFound")}</p>;
}
