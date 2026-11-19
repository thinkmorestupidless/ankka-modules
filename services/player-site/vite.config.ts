// The pages are built into dist/. Vite names every built file by its content (assets/), so a page
// from an old build never asks a new instance for a file it lacks; the site server serves the index
// uncached and assets/ immutable (FR-045, ankka web hosting rule 5).
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import path from "node:path";
import { defineConfig } from "vite";

const here = path.dirname(new URL(import.meta.url).pathname);

export default defineConfig({
  root: here,
  plugins: [react(), tailwindcss()],
  build: {
    outDir: path.join(here, "dist"),
    emptyOutDir: true,
    assetsDir: "assets",
  },
});
