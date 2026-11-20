import tseslint from "typescript-eslint";

/**
 * FR-037: a file that handles amounts never turns one into a number. In a file importing
 * `amount.ts`, `Number(…)`, `parseFloat`, `parseInt` and a multiplication by 100 are refused.
 */
const amounts = {
  rules: {
    "no-number-amounts": {
      meta: { type: "problem", schema: [] },
      create(context) {
        let importsAmounts = false;
        const found = [];
        return {
          ImportDeclaration(node) {
            if (/(^|\/)amount\.ts$/.test(String(node.source.value))) importsAmounts = true;
          },
          CallExpression(node) {
            const callee = node.callee;
            const name = callee.type === "Identifier" ? callee.name
              : callee.type === "MemberExpression" && callee.property.type === "Identifier" && callee.object.type === "Identifier" && callee.object.name === "Number" ? `Number.${callee.property.name}` : null;
            if (name && ["Number", "parseFloat", "parseInt", "Number.parseFloat", "Number.parseInt"].includes(name)) found.push([node, name]);
          },
          BinaryExpression(node) {
            if (node.operator === "*" && [node.left, node.right].some((n) => n.type === "Literal" && n.value === 100)) found.push([node, "* 100"]);
          },
          "Program:exit"() {
            if (!importsAmounts) return;
            for (const [node, name] of found) context.report({ node, message: `${name} in a file that handles amounts: read and write them with amount.ts (FR-037)` });
          },
        };
      },
    },
  },
};

export default tseslint.config(
  { ignores: ["node_modules/**"] },
  ...tseslint.configs.recommended,
  {
    files: ["**/*.ts", "**/*.tsx"],
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
    },
  },
  {
    files: ["src/**/*.ts", "src/**/*.tsx"],
    plugins: { amounts },
    rules: { "amounts/no-number-amounts": "error" },
  },
  // Spec 031 (SC-006): in a module, the wire's shapes and the services' paths are generated. Zod is
  // imported only by `shapes.ts` (generated) and `events.ts` (a stream's events, by hand); a path is
  // written only in `routes.ts` (generated) and `selection.ts` (the operation a row names).
  {
    files: ["src/{core,casino,engagement,support}/**/*.ts", "src/{core,casino,engagement,support}/**/*.tsx"],
    ignores: ["src/*/shapes.ts", "src/*/events.ts"],
    rules: {
      "no-restricted-imports": ["error", { paths: [{ name: "zod", message: "a module's shapes are generated: import them from ./shapes.ts (a stream's events from ./events.ts)" }] }],
    },
  },
  {
    files: ["src/{core,casino,engagement,support}/**/*.ts", "src/{core,casino,engagement,support}/**/*.tsx"],
    ignores: ["src/*/routes.ts", "src/*/selection.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        { selector: "Literal[value=/^\\/v1\\//]", message: "a service's path is written in routes.ts, generated from selection.ts" },
        { selector: "TemplateElement[value.raw=/^\\/v1\\//]", message: "a service's path is written in routes.ts, generated from selection.ts" },
      ],
    },
  },
);
