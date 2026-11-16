// contracts/design.md's tokens, as tokens.css holds them, and every named pair at WCAG 2.2 AA: 4.5:1
// for text, 3:1 for marks and focus. A value that fails is changed in design.md, not excused here.
import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";

const read = (p: string) => readFileSync(new URL(p, import.meta.url), "utf8");
type Oklch = [number, number, number];
type Mode = "dark" | "light";

const parse = (v: string): Oklch => {
  const m = /oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)/.exec(v);
  if (!m) throw new Error(`not oklch: ${v}`);
  return [Number(m[1]), Number(m[2]), Number(m[3])];
};

/** design.md's table: token → [dark, light]. */
function designTable(): Record<string, Record<Mode, Oklch>> {
  const out: Record<string, Record<Mode, Oklch>> = {};
  for (const line of read("../../../../specs/030-backoffice/contracts/design.md").split("\n")) {
    const m = /^\| `--([\w-]+)` \| `(oklch\([^)]*\))` \| `(oklch\([^)]*\))` \|/.exec(line);
    if (m) out[m[1]!] = { dark: parse(m[2]!), light: parse(m[3]!) };
  }
  return out;
}

/** tokens.css: the dark block (`:root, [data-theme="dark"]`) and the light one. */
function cssTable(): Record<string, Record<Mode, Oklch>> {
  const css = read("../../src/web/tokens.css");
  const block = (selector: RegExp) => {
    const body = selector.exec(css)?.[1] ?? "";
    return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(oklch\([^)]*\))/g)].map((m) => [m[1]!, parse(m[2]!)]));
  };
  const dark = block(/\[data-theme="dark"\]\s*\{([^}]*)\}/);
  const light = block(/\[data-theme="light"\]\s*\{([^}]*)\}/);
  return Object.fromEntries(Object.keys(dark).map((k) => [k, { dark: dark[k]!, light: light[k]! }]));
}

/** OKLCH to sRGB (gamut-clipped) to WCAG relative luminance. */
function luminance([L, C, h]: Oklch): number {
  const a = C * Math.cos((h * Math.PI) / 180);
  const b = C * Math.sin((h * Math.PI) / 180);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const clip = (x: number) => Math.min(1, Math.max(0, x));
  const r = clip(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
  const g = clip(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
  const bl = clip(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s);
  return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
}

const contrast = (x: Oklch, y: Oklch) => {
  const [a, b] = [luminance(x), luminance(y)];
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
};

const tokens = designTable();
const surfaces = ["surface-0", "surface-1", "surface-2"];
const pairs: [string, string, number][] = [
  // series-3 is also the warning tone's text (components/status.tsx): text on every surface
  ...surfaces.flatMap((s) => [["text", s, 4.5], ["text-muted", s, 4.5], ["series-3", s, 4.5]] as [string, string, number][]),
  ["on-primary", "primary", 4.5],
  ...["surface-0", "surface-1"].flatMap(
    (s) =>
      [
        ["primary", s, 4.5],
        ["danger", s, 4.5],
        ["series-2", s, 3],
        ["series-3", s, 3],
        ["focus", s, 3],
      ] as [string, string, number][],
  ),
];

describe("the tokens", () => {
  test("design.md names the colour tokens", () => expect(Object.keys(tokens).length).toBe(11));

  test("tokens.css holds design.md's values in both themes", () => expect(cssTable()).toEqual(tokens));
});

describe.each(["dark", "light"] as const)("in the %s theme", (mode) => {
  test.each(pairs)("%s on %s is at least %d:1", (fg, bg, min) => {
    expect(contrast(tokens[fg]![mode], tokens[bg]![mode])).toBeGreaterThanOrEqual(min);
  });
});
