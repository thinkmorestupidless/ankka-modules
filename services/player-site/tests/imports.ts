import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

/**
 * The import rule (research R12): no page under `src/` but `server/` calls a service itself or
 * imports a module client; it reaches the brand only through the SDK's behaviours. Answers each
 * file and what it was found doing.
 */
const rules: Array<[RegExp, string]> = [
  [/\bfetch\s*\(/, "fetch"],
  [/\bXMLHttpRequest\b/, "XMLHttpRequest"],
  [/\bEventSource\b/, "EventSource"],
  [/\bsendBeacon\b/, "sendBeacon"],
  [/from\s+["']@eitheror\/player-sdk\/(core|casino|engagement|support)\/client(\.ts)?["']/, "a module client"],
  [/\b(coreClient|casinoClient|engagementClient|supportClient)\b/, "a module client"],
  [/from\s+["'][^"']*sdk\/player\/src\//, "the SDK's sources"],
];

export function forbidden(src: string): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const file = path.join(dir, name);
      if (statSync(file).isDirectory()) {
        if (path.relative(src, file) !== "server") walk(file);
      } else if (/\.(ts|tsx)$/.test(name)) {
        const text = readFileSync(file, "utf8");
        for (const [rule, what] of rules) if (rule.test(text)) found.push(`${path.relative(src, file)}: ${what}`);
      }
    }
  };
  walk(src);
  return found;
}
