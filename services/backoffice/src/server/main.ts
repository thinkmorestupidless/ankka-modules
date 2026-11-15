// The page's server: one program on PORT. It serves the built page and, from T036 on, the sign-in,
// the pass-through and the composite routes of contracts/routes.md. Node runs this file directly.
import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const types: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".json": "application/json",
  ".woff2": "font/woff2",
};

async function isFile(file: string): Promise<boolean> {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

/** A built file, immutable under assets/; any other path that is not a file is the page. */
async function serveBuilt(dist: string, pathname: string, res: http.ServerResponse): Promise<void> {
  const file = path.join(dist, decodeURIComponent(pathname));
  if (!file.startsWith(dist)) return void res.writeHead(404).end();
  if (await isFile(file)) {
    const immutable = file.startsWith(path.join(dist, "assets") + path.sep);
    res.writeHead(200, {
      "Content-Type": types[path.extname(file)] ?? "application/octet-stream",
      "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
    });
    return void res.end(await readFile(file));
  }
  if (path.extname(pathname) !== "") return void res.writeHead(404).end();
  res.writeHead(200, { "Content-Type": types[".html"], "Cache-Control": "no-cache" });
  res.end(await readFile(path.join(dist, "index.html")));
}

async function main(): Promise<void> {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
  const port = Number(process.env.PORT ?? 3000);
  const production = process.env.NODE_ENV === "production";
  const dist = path.join(root, "dist");
  let middleware: ((req: http.IncomingMessage, res: http.ServerResponse, next: () => void) => void) | undefined;
  if (!production) {
    const { createServer } = await import("vite");
    middleware = (await createServer({ configFile: path.join(root, "vite.config.ts"), server: { middlewareMode: true }, appType: "spa" })).middlewares;
  }
  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://backoffice");
    if (url.pathname === "/healthz") return void res.end("ok");
    if (production) return void serveBuilt(dist, url.pathname, res);
    middleware!(req, res, () => void res.writeHead(404).end());
  });
  server.listen(port, () => console.log(`backoffice on ${port}`));
  process.on("SIGTERM", () => {
    server.close(() => process.exit(0));
    server.closeIdleConnections();
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
