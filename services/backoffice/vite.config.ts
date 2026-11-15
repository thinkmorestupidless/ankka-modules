// The page is built from src/web into dist/. Vite names every built file by its content (assets/), so
// a page from an old build never asks a new instance for a file it lacks; the server serves index.html
// uncached (FR-042).
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

const here = path.dirname(new URL(import.meta.url).pathname);

export default defineConfig({
  root: path.join(here, "src/web"),
  plugins: [react(), tailwindcss()],
  build: {
    outDir: path.join(here, "dist"),
    emptyOutDir: true,
    assetsDir: "assets",
  },
  resolve: {
    alias: { "@shared": path.join(here, "src/shared") },
  },
});
