import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/**", "test-results/**", "playwright-report/**", ".features-gen/**"] },
  ...tseslint.configs.recommended,
);
