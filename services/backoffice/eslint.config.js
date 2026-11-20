// The page's lint (FR-040a): no string literal in JSX outside the message catalogue, so every string
// the interface shows is a key of src/shared/messages/en.ts and another language is a translation.
import tseslint from "typescript-eslint";
import react from "eslint-plugin-react";

/** Marks that carry no words. */
const allowedStrings = ["·", "—", "–", "/", "(", ")", ":", "%", "×", "…", "|", "+", "-", "*", "#"];

/** FR-015: a date is shown in the brand's zone (`useZone()`, components/date.tsx), never the browser's. */
const browserZone = {
  selector: "MemberExpression[property.name='timeZone'][object.callee.property.name='resolvedOptions']",
  message: "Show dates in the brand's zone: useZone() from components/date.tsx (FR-015), never the browser's.",
};

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
        browserZone,
      ],
    },
  },
  { files: ["src/web/**/*.ts"], rules: { "no-restricted-syntax": ["error", browserZone] } },
);
