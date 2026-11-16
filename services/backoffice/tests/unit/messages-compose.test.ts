// T122: a merge tag put in where the operator's cursor is, with its fallback selected to be typed over.
import { describe, expect, test } from "vitest";
import { insertTag } from "../../src/web/areas/players/messages.tsx";

describe("a merge tag inserted", () => {
  test("replaces the selection, the fallback selected after it", () => {
    const out = insertTag("Hello , welcome", 6, 6, "first_name");
    expect(out.text).toBe("Hello {{first_name|there}}, welcome");
    expect(out.text.slice(...out.select)).toBe("there");
  });

  test("goes at the end of an empty field", () => {
    const out = insertTag("", 0, 0, "vip_status");
    expect(out.text).toBe("{{vip_status|there}}");
    expect(out.text.slice(...out.select)).toBe("there");
  });
});
