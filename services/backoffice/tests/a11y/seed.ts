// The records the a11y suite's pages show (T134): every owner a page route reads answers with content,
// so axe looks at a page's tables, cards and actions rather than an empty or refused state.
// `seed.json` holds `{owner: {"GET <the owner's path template>": entry}}` over the doubles' own files
// (`tests/doubles/fixtures/`); the bodies follow the shapes the bound features give the same routes.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { DoublesClient } from "../doubles/client.ts";
import type { Fixtures } from "../doubles/owner.ts";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = JSON.parse(readFileSync(path.join(here, "seed.json"), "utf8")) as Record<string, Fixtures>;

export async function seedPages(doubles: DoublesClient): Promise<void> {
  for (const [owner, entries] of Object.entries(fixtures)) await doubles.fixtures(owner, entries);
  await doubles.fraudCases({
    cases: [
      { caseId: "5d2c9a7e-1f4b-4e0a-9b8c-3a6d2e1f0c44", playerIds: ["p-ana", "p-kenji"], signals: ["device", "address"], status: "OPEN", detectedAt: "2026-11-14T10:00:00Z", reviewedBy: null, reviewedAt: null, reviewNotes: null },
    ],
  });
  await doubles.fraudFlags({
    flags: [
      {
        flagId: "3f0b8f4e-5a51-4c55-9d0e-2b7a8a1c6d10",
        playerId: "p-ana",
        affiliateId: "aff-rio",
        brandId: "goldvibe",
        period: "2026-10",
        reason: "bonus-cost",
        description: "4 bonus deductions, past the brand's threshold of 3",
        flaggedAt: "2026-11-02T09:30:00Z",
        dismissed: false,
        dismissedBy: null,
        dismissedAt: null,
        dismissalReason: null,
      },
    ],
  });
}
