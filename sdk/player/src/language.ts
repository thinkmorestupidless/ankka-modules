import type { RefusalCode } from "./codes.ts";

/**
 * The page's language (FR-041): the visitor's kept choice when the site offers it, else the first
 * of the browser's languages the site offers (by its primary subtag, so `en-GB` is `en`), else the
 * site's first.
 */
export function chooseLanguage(offered: readonly string[], kept: string | null | undefined, browser: readonly string[] | string | null | undefined): string {
  const first = offered[0];
  if (first === undefined) throw new Error("a site offers at least one language");
  const primary = (tag: string) => tag.trim().toLowerCase().split(/[-_]/)[0] ?? "";
  const offers = (tag: string | null | undefined) => (tag ? offered.find((o) => primary(o) === primary(tag)) : undefined);
  const fromKept = offers(kept);
  if (fromKept) return fromKept;
  const asked = typeof browser === "string" ? [browser] : (browser ?? []);
  for (const tag of asked) {
    const found = offers(tag);
    if (found) return found;
  }
  return first;
}

/**
 * A site's words in its languages (FR-042). The first language's words are whole; another
 * language's may lack a text, which is then shown in the first language. With `W` a
 * `Record<RefusalCode, string>` (see `RefusalWords`), a code with no words in the first language
 * does not compile.
 */
export function catalogue<W extends Record<string, string>>(first: { language: string; words: W }, others: Readonly<Record<string, Partial<W>>> = {}) {
  return {
    languages: [first.language, ...Object.keys(others).filter((l) => l !== first.language)],
    /** The words in `language`, each missing one in the first language's. */
    in(language: string): W {
      const own = language === first.language ? undefined : others[language];
      if (!own) return first.words;
      return { ...first.words, ...Object.fromEntries(Object.entries(own).filter(([, v]) => typeof v === "string")) } as W;
    },
    /** The texts `language` lacks, which are shown in the first language. */
    missing(language: string): (keyof W)[] {
      const own = others[language];
      if (language === first.language) return [];
      return (Object.keys(first.words) as (keyof W)[]).filter((k) => typeof own?.[k] !== "string");
    },
  };
}

/** The words for every refusal code: what a site writes in each of its languages (SC-006). */
export type RefusalWords = Record<RefusalCode, string>;
