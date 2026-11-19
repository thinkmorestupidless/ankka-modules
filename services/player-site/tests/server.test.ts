import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const here = import.meta.dirname;

describe("the reference site's process", () => {
  it("is the SDK's site server over the built pages", () => {
    const main = readFileSync(join(here, "../src/server/main.ts"), "utf8");
    expect(main).toContain('from "@eitheror/player-sdk/server"');
    expect(main).toMatch(/startSiteServer\(\{ static: .*dist/);
  });

  it("is a web-hosted service, exposed, with no mount", () => {
    const descriptor = JSON.parse(readFileSync(join(here, "../service.json"), "utf8")) as { service: Record<string, unknown> };
    expect(descriptor.service).toMatchObject({ hosting: "web", expose: true });
    expect(descriptor.service).not.toHaveProperty("mounts");
  });

  it("builds its image as user 1000 from the root, admitting only the workspace's parts", () => {
    const docker = readFileSync(join(here, "../Dockerfile"), "utf8");
    expect(docker).toMatch(/^USER 1000$/m);
    const ignore = readFileSync(join(here, "../Dockerfile.dockerignore"), "utf8").split("\n");
    expect(ignore[ignore.findIndex((l) => l.trim() === "*")]).toBe("*");
    expect(ignore.filter((l) => l.startsWith("!"))).toEqual(["!package.json", "!package-lock.json", "!sdk/player", "!services/player-site"]);
  });
});
