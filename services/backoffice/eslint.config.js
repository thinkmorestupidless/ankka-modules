// The page's lint (FR-040a): no string literal in JSX outside the message catalogue, so every string
// the interface shows is a key of src/shared/messages/en.ts and another language is a translation.
import tseslint from "typescript-eslint";
import react from "eslint-plugin-react";

/** Marks that carry no words. */
const allowedStrings = ["·", "—", "–", "/", "(", ")", ":", "%", "×", "…", "|", "+", "-", "*", "#"];

export default tseslint.config(
  { ignores: ["dist/**", "node_modules/**", ".features-gen/**", "src/shared/generated/**"] },
  ...tseslint.configs.recommended,
  // a parameter named with a leading underscore is there for its signature
  { rules: { "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }] } },
  {
    files: ["src/web/**/*.tsx"],
    plugins: { react },
    languageOptions: { parserOptions: { ecmaFeatures: { jsx: true } } },
    rules: {
      "react/jsx-no-literals": ["error", { noStrings: true, ignoreProps: true, allowedStrings }],
      // the attributes a person reads or hears: a catalogue message, never a literal
      "no-restricted-syntax": [
        "error",
        {
          selector: "JSXAttribute[name.name=/^(aria-label|aria-description|title|placeholder|alt|label)$/] > Literal",
          message: "Use a message from src/shared/messages (FR-040a).",
        },
      ],
    },
  },
);
