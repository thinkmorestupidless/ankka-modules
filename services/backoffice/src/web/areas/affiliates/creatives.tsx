// The brand's creatives (parity *Creatives*; the programme's O50 to O52): what affiliates may use, each
// an address, not a file (the programme stores no file), made with a name and removed. The address is
// shown as text, never fetched or embedded by the backoffice.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { t } from "../../../shared/messages/index.ts";
import { affiliateProgramme as programme } from "../../../shared/owners/index.ts";
import { Actions } from "../../actions/index.ts";
import { Action } from "../../components/action/index.ts";
import { DateTime, ZoneNote } from "../../components/date.tsx";
import { useOperator, useReadOnlyMode } from "../../frame/context.ts";
import { useBags } from "../bags.ts";
import { Read } from "./parts.tsx";
import { json, zone, type Creative } from "./programme.ts";

function NewCreative({ done }: { done(): void }) {
  const me = useOperator();
  const bags = useBags();
  const ids = { name: useId(), type: useId(), url: useId(), dims: useId(), desc: useId() };
  const empty = { name: "", creativeType: "", fileUrl: "", dimensions: "", description: "" };
  const [f, setF] = useState(empty);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const text = (v: string) => (v.trim() === "" ? undefined : v.trim());
  return (
    <div className="bo-aff-form">
      <div className="bo-field">
        <label htmlFor={ids.name}>{t("affiliates.creative.name")}</label>
        <input id={ids.name} value={f.name} onChange={set("name")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.type}>{t("affiliates.creative.type")}</label>
        <input id={ids.type} value={f.creativeType} onChange={set("creativeType")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.url}>{t("affiliates.creative.url")}</label>
        <input id={ids.url} type="url" value={f.fileUrl} onChange={set("fileUrl")} />
      </div>
      <div className="bo-field">
        <label htmlFor={ids.dims}>{t("affiliates.creative.dimensions")}</label>
        <input id={ids.dims} value={f.dimensions} onChange={set("dimensions")} />
      </div>
      <div className="bo-field bo-field-wide">
        <label htmlFor={ids.desc}>{t("affiliates.creative.description")}</label>
        <textarea id={ids.desc} rows={2} value={f.description} onChange={set("description")} />
      </div>
      <div className="bo-aff-actions bo-field-wide">
        <Action
          action={Actions["programme.creative.create"]}
          me={me}
          bags={bags}
          ready={f.name.trim() !== ""}
          subject={<p>{f.name.trim()}</p>}
          run={() =>
            programme.postCreatives({ body: { name: f.name.trim(), creativeType: text(f.creativeType), fileUrl: text(f.fileUrl), dimensions: text(f.dimensions), description: text(f.description) } })
          }
          onDone={() => {
            setF(empty);
            done();
          }}
        />
      </div>
    </div>
  );
}

export function CreativesSection() {
  const me = useOperator();
  const bags = useBags();
  const client = useQueryClient();
  const readOnly = useReadOnlyMode();
  const tz = zone();
  const q = useQuery({ queryKey: ["programme", "creatives"], queryFn: () => json<Creative[]>(programme.getCreatives()) });
  const done = () => void client.invalidateQueries({ queryKey: ["programme", "creatives"] });
  return (
    <div>
      <h2>{t("affiliates.section.creatives")}</h2>
      <ZoneNote zone={tz} />
      <Read q={q}>
        {(rows) =>
          rows.length === 0 ? (
            <p className="bo-muted">{t("affiliates.creative.none")}</p>
          ) : (
            <table className="bo-aff-table">
              <caption className="bo-sr-only">{t("affiliates.section.creatives")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("affiliates.creative.name")}</th>
                  <th scope="col">{t("affiliates.creative.type")}</th>
                  <th scope="col">{t("affiliates.creative.url")}</th>
                  <th scope="col">{t("affiliates.creative.dimensions")}</th>
                  <th scope="col">{t("affiliates.created")}</th>
                  <th scope="col">
                    <span className="bo-sr-only">{t("affiliates.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.creativeId} data-creative={c.creativeId}>
                    <th scope="row">
                      {c.name}
                      {c.description ? <div className="bo-muted">{c.description}</div> : null}
                    </th>
                    <td>{c.creativeType ?? ""}</td>
                    <td>
                      <code>{c.fileUrl ?? ""}</code>
                    </td>
                    <td>{c.dimensions ?? ""}</td>
                    <td>{c.createdAt ? <DateTime instant={c.createdAt} zone={tz} /> : null}</td>
                    <td>
                      {readOnly ? null : (
                        <Action
                          action={Actions["programme.creative.delete"]}
                          me={me}
                          bags={bags}
                          subject={<p>{c.name}</p>}
                          run={() => programme.deleteCreativesByCreative({ creative: c.creativeId })}
                          onDone={done}
                        />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </Read>
      {readOnly ? null : (
        <>
          <h3>{t("affiliates.creative.new")}</h3>
          <NewCreative done={done} />
        </>
      )}
    </div>
  );
}
