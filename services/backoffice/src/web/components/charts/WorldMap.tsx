// Players by country on a map of the world (FR-032; contracts/design.md): the countries in
// --surface-2, each with players filled by one hue of --primary (more players, more of it), and a
// table beside it of every country and its figure, which is what the keyboard and a screen reader use.
// The map is bundled (Natural Earth 1:110m from `world-atlas`); a country it is too small to draw is
// still in the table.
import { useMemo, type ReactNode } from "react";
import { geoEqualEarth, geoPath } from "d3-geo";
import { feature } from "topojson-client";
import type { Topology, GeometryCollection } from "topojson-specification";
import type { Feature, FeatureCollection, Geometry } from "geojson";
import world from "world-atlas/countries-110m.json" with { type: "json" };
import codes from "./country-codes.json" with { type: "json" };
import "./charts.css";
import { t } from "../../../shared/messages/index.ts";

/** The 1:110m map's numeric ids (ISO 3166-1) to alpha-2; Kosovo, which has no numeric code, by its name. */
const alpha2: Record<string, string> = codes;

export interface CountryFigure {
  /** ISO 3166-1 alpha-2, as reporting answers it. */
  code: string;
  count: number;
  /** What a person reads for it, when not the count itself. */
  text?: ReactNode;
}

type Shape = Feature<Geometry, { name: string }> & { code: string | undefined };

const W = 800;
const H = 400;

let shapes: { d: string; code: string | undefined }[] | undefined;

/** The countries as paths, made once. */
export function countryPaths(): { d: string; code: string | undefined }[] {
  if (shapes) return shapes;
  const topology = world as unknown as Topology<{ countries: GeometryCollection<{ name: string }> }>;
  const all = feature(topology, topology.objects.countries) as FeatureCollection<Geometry, { name: string }>;
  const features: Shape[] = all.features.map((f) => ({ ...f, code: f.id !== undefined ? alpha2[String(f.id)] : f.properties.name === "Kosovo" ? "XK" : undefined }));
  const projection = geoEqualEarth().fitSize([W, H], { type: "FeatureCollection", features: features.filter((f) => f.properties.name !== "Antarctica") });
  const path = geoPath(projection);
  shapes = features.filter((f) => f.properties.name !== "Antarctica").map((f) => ({ d: path(f) ?? "", code: f.code }));
  return shapes;
}

let names: Intl.DisplayNames | undefined;

/** A country's name in English from its code; the code itself when the runtime has no name for it. */
export function countryName(code: string): string {
  try {
    names ??= new Intl.DisplayNames(["en-GB"], { type: "region" });
    return names.of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
}

/** The share of --primary a country is filled with: 25% for the fewest players to 100% for the most. */
export function fillOf(count: number, max: number): string {
  const share = max <= 0 ? 0 : Math.round(25 + 75 * Math.sqrt(count / max));
  return `color-mix(in oklch, var(--primary) ${share}%, var(--surface-2))`;
}

export function WorldMap({ title, countries, figure }: { title: string; countries: CountryFigure[]; figure: string }) {
  const paths = useMemo(countryPaths, []);
  const shown = countries.filter((c) => c.count > 0);
  const byCode = new Map(shown.map((c) => [c.code.toUpperCase(), c]));
  const max = Math.max(0, ...shown.map((c) => c.count));
  const sorted = [...shown].sort((a, b) => b.count - a.count || a.code.localeCompare(b.code));
  return (
    <figure className="bo-chart bo-map-figure">
      <figcaption className="bo-sr-only">{title}</figcaption>
      <div className="bo-map">
        <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" data-chart="map">
          {paths.map((p, i) => {
            const c = p.code ? byCode.get(p.code) : undefined;
            return (
              <path
                key={`${p.code ?? "x"}-${i}`}
                className="bo-map-country"
                d={p.d}
                data-country={p.code}
                data-players={c ? c.count : undefined}
                style={c ? { fill: fillOf(c.count, max) } : undefined}
              />
            );
          })}
        </svg>
        <table className="bo-chart-table">
          <caption className="bo-sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{t("chart.country")}</th>
              <th scope="col" data-numeric>
                {figure}
              </th>
            </tr>
          </thead>
          <tbody>
            {sorted.length === 0 ? (
              <tr>
                <td colSpan={2}>{t("chart.none")}</td>
              </tr>
            ) : (
              sorted.map((c) => (
                <tr key={c.code} data-country={c.code.toUpperCase()}>
                  <th scope="row">{countryName(c.code)}</th>
                  <td data-numeric>{c.text ?? c.count}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </figure>
  );
}
