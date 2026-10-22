# Quickstart: proving core and modules

## Prerequisites

JDK 21, sbt, `just`, `uv`, Docker, kind (for `just it`); the `ankka` CLI with the `ankka-vendor`
plugin on the path for anything that talks to a vendor (none of the suites do).

## 1. The suites

```bash
just features                 # the living features, the glossary and spec 021
just build && just test       # the contract, the modules tool: manifests, values, render (SC-001), release render (SC-004), the bound features, read-only routes (SC-003)
just modules validate --all   # every manifest and values file, from the command line
just modules render goldvibe --modules casino --version core=0.3.0,casino=0.3.0
just release-dry v9.9.9       # one publish per module, no channel moved, against the fake plugin
```

Expected: green. Breaking the apply order in `LocalApply` (a module before its dependency) must
turn `modules.feature`'s first scenario red in `ModulesFeatures`; adding a `PUT` under `/modules`
to `contract/access/routes.md` must turn `ReadOnlyRoutesSuite` red.

## 2. On kind (local mode, no vendor)

```bash
just it                       # ModulesOnKindSuite: the core alone, then --modules casino (SC-002 with the services that exist)
just modules apply goldvibe --installation dev-kind
just modules status goldvibe --installation dev-kind
just modules apply goldvibe --installation dev-kind --modules casino
```

Expected: after the first apply the wallet's and players' scenarios that have services answer and
`curl https://game-provider-goldvibe…/v1/casino/brands/goldvibe/games` is not served; after the
second it is.

## 3. With a vendor (ankka-vendor's compose stack, vendor quickstart step 2)

```bash
just release-render v0.3.0    # target/release/*.json with the compose registry's images
ankka vendor modules publish target/release/core.json --version 0.3.0
ankka vendor modules publish target/release/casino.json --version 0.3.0
ankka vendor channels set core stable 0.3.0 && ankka vendor channels set casino stable 0.3.0
ankka casino values push goldvibe             # to the tenant the vendor provisioned
ankka casino secrets goldvibe --secrets ~/casino-secrets.env
ankka vendor entitlements grant <org> casino --channel stable --terms price_test_casino
ankka casino status goldvibe
```

Expected: the module entity shows `core` and `casino` enabled with every service running; a
`platform.modules` fact per change on the brand's topic.

## Done when

- Every suite in step 1 is green; `just it` green on kind.
- `modules/README.md`, `brands/README.md`, `tools/modules/README.md`, `CLAUDE.md` describe the
  module model; `specs/008-players/contracts/routes.md` carries the two routes.
- Step 3 has been run once against ankka-vendor's compose stack with the manifests of this
  repository, and `research.md` records the commit and the vendor version.
