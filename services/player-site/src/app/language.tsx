import { chooseLanguage, type RefusalCode } from "@eitheror/player-sdk";
import { createContext, useContext, useState, type ReactNode } from "react";
import { languages, words, type TextKey } from "../words/index.ts";

/** The visitor's kept choice: the `eo_lang` cookie (contracts/site-server.md). */
const kept = () => /(?:^|;\s*)eo_lang=([^;]+)/.exec(document.cookie)?.[1] ?? null;

interface Language {
  readonly language: string;
  choose(language: string): void;
  t(key: TextKey, fields?: Readonly<Record<string, unknown>>): string;
  refusal(code: RefusalCode, fields?: Readonly<Record<string, unknown>>): string;
}

const Context = createContext<Language | null>(null);

export function LanguageProvider({ offered, currency, children }: { offered: readonly string[]; currency: string; children: ReactNode }) {
  const site = offered.filter((l) => languages.includes(l));
  const [language, setLanguage] = useState(() => chooseLanguage(site.length ? site : languages, kept(), navigator.languages));
  document.documentElement.lang = language;
  const value: Language = {
    language,
    choose(next) {
      document.cookie = `eo_lang=${next}; Secure; SameSite=Lax; Path=/; Max-Age=31536000`;
      setLanguage(next);
    },
    t: (key, fields) => words.text(key, language, fields, currency),
    refusal: (code, fields) => words.refusal(code, language, fields, currency),
  };
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useLanguage(): Language {
  const l = useContext(Context);
  if (!l) throw new Error("useLanguage outside a LanguageProvider");
  return l;
}
