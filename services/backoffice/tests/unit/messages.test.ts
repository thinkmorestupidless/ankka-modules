import { describe, expect, test } from "vitest";
import { ESLint } from "eslint";
import { fileURLToPath } from "node:url";
import { en } from "../../src/shared/messages/en.ts";
import { render, t } from "../../src/shared/messages/index.ts";
import { explain } from "../../src/shared/refusals.ts";
import { OperatorBags } from "./bags.ts";

describe("the catalogue", () => {
  test("every message parses and renders with its values", () => {
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(() => t(key, { permission: "p", roles: "r", source: "s", code: "c", given: "g", detail: "d", module: "m", minor: "1", currency: "EUR", zone: "UTC" }), key).not.toThrow();
    }
  });

  test("a value is put in its place", () => {
    expect(t("refusal.forbidden", { permission: "adjustment.make" })).toBe("You need the adjustment.make permission for this.");
  });
});

describe("a refusal shown", () => {
  test("forbidden names the permission and, from the bags as they are now, the roles that give it", () => {
    const r = explain(403, '{"error":"forbidden","message":"forbidden: the adjustment.make permission is needed"}', OperatorBags);
    expect(render(r.message)).toBe("You need the adjustment.make permission for this.");
    expect(render(r.next!)).toBe("A role that gives it: brand_finance, brand_admin.");
  });

  test("forbidden as plain text reads the same", () => {
    const r = explain(403, "forbidden: the tier.change permission is needed", OperatorBags);
    expect(r.code).toBe("forbidden");
    expect(render(r.message)).toBe("You need the tier.change permission for this.");
  });

  test("a permission no role carries says so", () => {
    const r = explain(403, "forbidden: the tier.change permission is needed", { brand_support: ["player.read"] });
    expect(render(r.next!)).toBe("No role of this brand gives it; ask the brand's admin.");
  });

  test("a source that is not answering is named", () => {
    const r = explain(503, '{"error":"source_unavailable","source":"wallet"}');
    expect(render(r.message)).toBe("wallet is not answering.");
    expect(r.next).toBeDefined();
  });

  test("a known code has its message and next step", () => {
    const r = explain(400, '{"error":"RESERVED_ROLE","message":"platform_admin is the realm\'s"}');
    expect(render(r.message)).toBe("That role name is the realm's, not the brand's.");
    expect(render(r.next!)).toBe("Choose another name.");
  });

  test("a code the backoffice does not know is shown as given", () => {
    const r = explain(409, '{"error":"turnover_outstanding","message":"the bonus still needs 3,000 of turnover"}');
    expect(render(r.message)).toBe("turnover_outstanding: the bonus still needs 3,000 of turnover");
  });

  test("a body that is neither is shown as given", () => {
    const r = explain(500, "boom");
    expect(render(r.message)).toBe("500: boom");
  });
});

describe("the page's lint", () => {
  const eslint = new ESLint({ cwd: fileURLToPath(new URL("../..", import.meta.url)) });
  const lint = async (code: string) => (await eslint.lintText(code, { filePath: "src/web/Lint.tsx" }))[0]!.messages;

  test("refuses a string literal in JSX", async () => {
    expect((await lint("export const A = () => <p>Withdrawals</p>;\n")).map((m) => m.ruleId)).toContain("react/jsx-no-literals");
  });

  test("refuses a literal a person reads in an attribute", async () => {
    expect((await lint('export const A = () => <input aria-label="Search" />;\n')).map((m) => m.ruleId)).toContain("no-restricted-syntax");
  });

  test("admits a catalogue message", async () => {
    const code = 'import { t } from "../shared/messages/index.ts";\nexport const A = () => <p aria-label={t("frame.search")}>{t("frame.signIn")}</p>;\n';
    expect(await lint(code)).toEqual([]);
  });
});
