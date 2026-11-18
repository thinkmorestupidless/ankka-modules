// The first page's script, compressed (plan.md Performance Goals, T054): every script dist/index.html
// loads, and every module it preloads, gzipped as a server would send them. Fails over 150 KB.
import { readFileSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const Budget = 150 * 1024;
const dist = path.resolve(import.meta.dirname, "../dist");
const page = readFileSync(path.join(dist, "index.html"), "utf8");
const scripts = [...page.matchAll(/<(?:script[^>]*\ssrc|link[^>]*rel="modulepreload"[^>]*\shref)="([^"]+\.js)"/g)].map((m) => m[1]!);
if (scripts.length === 0) throw new Error("dist/index.html loads no script: build first (npm run build)");
let total = 0;
for (const src of scripts) {
  const size = gzipSync(readFileSync(path.join(dist, src))).length;
  total += size;
  console.log(`${src}: ${(size / 1024).toFixed(1)} KB compressed`);
}
console.log(`the first page: ${(total / 1024).toFixed(1)} KB of script compressed, of ${Budget / 1024} KB`);
if (total > Budget) {
  console.error("over the plan's budget");
  process.exit(1);
}
