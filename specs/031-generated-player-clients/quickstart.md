# Quickstart: proving spec 031

Needs Docker, `uv`, Node 24, sbt, and the ankka command-line client 0.18.0
(`brew upgrade thinkmorestupidless/tap/ankka`). No kind installation is used.

## 1. One ankka version (first pull request)

```bash
just test                                   # the contract, the modules tool, the bound features
just service wallet test                    # and so for each of the 21; contracts/move.md has the groups
just modules validate --all
just modules render guyanabet               # every service: runtime 0.18.0
grep -rnE '0\.1[3-7]\.[0-9]+' services/*/build.sbt services/*/service.json modules shared local build.sbt
```

Expected: every suite green with no expectation changed; the search finds nothing that is an ankka
version.

## 2. The runtime check

```bash
sbt "modules/testOnly *RuntimesSuite"
```

Expected: the table of [contracts/runtime-check.md](contracts/runtime-check.md).

## 3. The local brand, before generation

```bash
just local-images && just local-project
(cd local/guyanabet && ankka local up)
just local-onboard
just player-site-it
```

Expected: 24 of 24 contract tests, with the hand-written clients, on 0.18.0.

## 4. Descriptions (second pull request)

```bash
just describe-check players                 # and the other eight
just service players test                   # held to its description; DescriptionSuite among it
```

Expected: `openapi.player.json is current`; no violation; no difference; the unrecorded list
matches. To see the checks bite: remove one `refuses(…)` from a route a recording refuses and run
the service's tests (the description suite names the recording, the route and the status); change
a field's name in `Described.scala` without `just describe` and run `just describe-check`.

## 5. Generation

```bash
npm run generate:check -w sdk/player
just player-sdk-test && just player-sdk-features && just player-site-features
```

Expected: nothing out of date; every suite green; `git diff main -- sdk/player/tests/features
services/player-site` shows no test changed other than the new `generated-clients.steps.ts` and
its line in `elsewhere.ts`.

## 6. Acceptance

```bash
just local-images && (cd local/guyanabet && ankka local up)
just local-onboard                          # a no-op where step 3's realm and keys are still there
just player-site-it
just features
```

Expected: 24 of 24 with the generated clients; `0 findings`.
