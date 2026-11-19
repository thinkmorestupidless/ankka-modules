import { readFile } from "node:fs/promises";
import { extname, join, normalize, sep } from "node:path";
import { Hono } from "hono";

const types: Record<string, string> = {
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
  ".ico": "image/x-icon",
  ".map": "application/json",
};

/**
 * The built site (FR-045): `/assets/*`, named by their content, kept for a year; every other `GET`
 * is the first page, never kept, so a page from an old build never asks a new instance for a file
 * it no longer has.
 */
export function staticFiles(root: string): Hono {
  const app = new Hono();
  const base = normalize(root);
  app.get("/assets/*", async (c) => {
    let decoded: string;
    try {
      decoded = decodeURIComponent(new URL(c.req.url).pathname);
    } catch {
      return c.notFound(); // a malformed escape names no file (review)
    }
    const path = normalize(join(base, decoded));
    if (!path.startsWith(base + sep)) return c.notFound();
    try {
      const file = await readFile(path);
      return new Response(file, { headers: { "content-type": types[extname(path)] ?? "application/octet-stream", "cache-control": "public, max-age=31536000, immutable" } });
    } catch {
      return c.notFound();
    }
  });
  app.get("*", async (c) => {
    try {
      const page = await readFile(join(base, "index.html"));
      return new Response(page, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache" } });
    } catch {
      return c.notFound();
    }
  });
  return app;
}
