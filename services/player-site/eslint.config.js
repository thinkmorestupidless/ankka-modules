import tseslint from "typescript-eslint";

// The import rule (research R12), as tests/imports.test.ts checks it: a page reaches the brand only
// through the SDK's behaviours; only the site server under src/server calls anything itself.
const pageRule = {
  "no-restricted-globals": ["error", ...["fetch", "XMLHttpRequest", "EventSource"].map((name) => ({ name, message: "a page reaches the brand only through the SDK's behaviours (research R12)" }))],
  "no-restricted-properties": ["error", { object: "navigator", property: "sendBeacon", message: "a page reaches the brand only through the SDK's behaviours (research R12)" }],
  "no-restricted-imports": ["error", { patterns: [{ regex: "^@eitheror/player-sdk/(core|casino|engagement|support)/client", message: "a module client is the SDK's, not a page's" }, { regex: "sdk/player/src/", message: "import the SDK as a package" }] }],
};

export default tseslint.config(
  { ignores: ["dist/**", "test-results/**", "playwright-report/**", "tests/features/.generated/**"] },
  ...tseslint.configs.recommended,
  { files: ["src/**/*.{ts,tsx}"], ignores: ["src/server/**"], rules: pageRule },
);
