# Implementation Plan: Core and Modules — A Brand Runs What Its Entitlement Names

**Branch**: `021-core-and-modules` | **Date**: 2026-10-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/021-core-and-modules/spec.md`

## Summary

The repository becomes a core and product modules, each described by one manifest in
ankka-vendor's format under `modules/<module>/module.json`, published at a tag's version to the
vendor and applied in a brand by the vendor's agent. The brand manifest becomes the brand's
values; the brands tool becomes the modules tool (`ankka casino`), which validates manifests and
values against the vendor's schema and this repository's contract, renders what the agent would
apply, pushes a brand's values, and applies locally where there is no vendor. Grants move from a
fixed list into the manifests; a new contract topic `platform.modules` carries every change; the
operator's modules page reads through `players`. Research: [research.md](research.md).

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (contract, tool); JSON for manifests and values; bash
and GitHub Actions for the release.

**Primary Dependencies**: ankka 0.11.0 today (`ankkaVersion` in `build.sbt`); the vendor's
manifest schema `ankka-vendor-manifest.schema.json` (vendored, R1); `ankka-controlplane-api` for
the local apply; `json-schema-validator`; ankka-vendor's `ankka-vendor` plugin on the path for
`values push` and the release job; ankka 056 for `ankka casino`.

**Storage**: none of the tool's own; the brand's values land in the tenant project's secret
`tenant-values`, and the agent's module entity is the vendor's.

**Testing**: munit; the bound features (`ModulesFeatures`) over `InMemoryControlPlane` and an
`AgentModel` of the vendor's apply order; the `kind` CI job for the local apply (SC-002); a fake
`ankka-vendor` binary for the release dry run (SC-004).

**Target Platform**: an ankka installation with a vendor (eitheror's GKE spoke, spec 001 FR-034)
or without one (dev-kind, local mode).

**Project Type**: a contract library, a CLI tool, manifests and a release workflow.

**Performance Goals**: validate and render in under a second; the release job within one run
(SC-004).

**Constraints**: no value in a manifest; no secret value through the vendor; no route on the
installation that enables a module; the agent is the vendor's and is not built here.

**Scale/Scope**: 5 manifests today (`core`, `casino`, `engagement`, `reporting`, `support`;
`predictions` is spec 023's, `pool` spec 024's), 1 values file, 1 tool with 11 commands, 1 new
topic family, 2 new routes in spec 008's contract, 1 release workflow.

## Constitution Check

| Principle | Check |
|---|---|
| I. The running original is the specification | The original has one fixed set of services; modules are new and the spec says so (decision 0055). Nothing of a player's behaviour changes. Pass. |
| II. Money is exact and the wallet is the ledger | Untouched. Pass. |
| III. A brand is a project | Strengthened: a brand project holds its core and its modules; grants are declared per module and resolved to the brand's project; no grant between brands (FR-006). Pass. |
| IV. Commands over HTTP, facts over topics | `platform.modules` is a contract topic with a versioned family (R8); the page reads by HTTP from the agent. Pass. |
| V. Personal data is marked | The module entity and the vendor's mirror hold names, versions, states and times (FR-014). Pass. |
| VI. Scala by default | The tool stays Scala. Pass. |
| VII. Specs in the domain's words | Scenarios live in `features/brands/modules.feature`; `just features` green. Pass. |
| Scope: ankka lacks → an ankka spec | Nothing asked of ankka; two things asked of the vendor (research, "Asked of the vendor"). Pass. |
| Workflow: `just features` before plan | Green at 7f9bd01. Pass. |
| Workflow: recordings and parity tests named | None replayed: this feature changes how services are deployed, not what they do; `ApplyOnKindSuite` and the wallet's and game provider's existing parity suites run unchanged on a brand applied by the modules tool. |

## Project Structure

### Documentation (this feature)

```text
specs/021-core-and-modules/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── module-manifests.md      # the five manifests, entry by entry
│   ├── values.md                # brands/<slug>/values.json
│   ├── modules-tool.md          # ankka casino: commands, exit codes, local mode
│   ├── routes.md                # the operator's modules routes on players; platform.modules
│   └── release.md               # the tag workflow
└── tasks.md
```

### Source Code (repository root)

```text
modules/
├── core/module.json             # wallet, players, kyc, responsible-gambling, payouts, attribution (the agent is the vendor's)
├── casino/module.json           # game-provider, rewards, partner-api (optional)
├── engagement/module.json
├── reporting/module.json
├── support/module.json          # support-agent
└── README.md                    # what a manifest is, how to add a module, what the tag does
brands/
├── goldvibe/values.json         # replaces manifest.json
├── _realm/                      # unchanged
└── README.md                    # rewritten: values, not manifests
contract/
├── schemas/platform.modules.v1.json
├── keys.md                      # every configuration key a manifest may name, by module
└── src/main/scala/io/eitheror/casino/contract/
    ├── Layout.scala             # services, topics and grants read from modules/*/module.json (resources)
    ├── facts/PlatformModules.scala
    ├── facts/Topics.scala       # + PlatformModules
    └── access/Routes.scala      # unchanged; routes.md lists the modules routes for players
tools/modules/                   # renamed from tools/brands; package io.eitheror.casino.modules
├── README.md
└── src/
    ├── main/resources/
    │   ├── ankka-vendor-manifest.schema.json   # vendored from the vendor (R1)
    │   └── values.v1.json
    ├── main/scala/io/eitheror/casino/modules/
    │   ├── Main.scala           # validate, render, values push, secrets, hostnames, realm, apply, status, recordings, specs, schema-sync
    │   ├── ModuleManifest.scala # the vendor's shape, read and checked against the contract
    │   ├── Values.scala
    │   ├── Render.scala         # values + manifests + versions → descriptors, topics, grants
    │   ├── ReleaseRender.scala  # images at the tag, schemas inlined
    │   ├── LocalApply.scala     # was Apply: the agent's order, locally
    │   ├── Vendor.scala         # runs `ankka vendor …` on the path for values push and publish
    │   └── Realm.scala, Readiness.scala, Status.scala, Recordings.scala, Established.scala, Installation.scala, ControlPlane.scala, InMemoryControlPlane.scala   # carried over
    ├── test/resources/
    │   ├── manifests/           # broken variants
    │   └── spec-001-goldvibe/   # the descriptors spec 001's tool produced (SC-001 fixture)
    ├── test/scala/io/eitheror/casino/modules/
    │   ├── ModuleManifestSuite.scala, ValuesSuite.scala, RenderSuite.scala, ReleaseRenderSuite.scala
    │   ├── AgentModel.scala     # the vendor's apply order over rendered plans, for the bound features
    │   └── ModulesFeatures.scala, ManifestFeatures.scala (spec 001's, bound to local mode), ReadOnlyRoutesSuite.scala
    └── it/scala/io/eitheror/casino/modules/ModulesOnKindSuite.scala, ApplyOnKindSuite.scala, AccessSuite.scala
tests/fakes/ankka-vendor         # a fake plugin binary for the release dry run
.github/workflows/release.yml
Justfile                         # brands → modules recipes; release-dry
build.sbt                        # project modules (was brands), modulesIt; contract packages modules/ as resources
specs/008-players/contracts/routes.md   # + the two modules routes
```

**Structure Decision**: the rename of `tools/brands` keeps every file that does not change, so
the diff is readable; `modules/` sits at the root beside `brands/` and `shared/` because the three
are the three kinds of thing that get deployed.

## Complexity Tracking

| Deviation | Why | Simpler alternative rejected because |
|---|---|---|
| A fake `ankka-vendor` binary under `tests/fakes/` | SC-004 needs the release rendering proved without a vendor | invoking the real plugin needs a hub; a Scala stub of the plugin's commands would test the stub |
| The contract packages the manifests as resources | `Routes.service` admits grantees from a list that must match the manifests | a hand-kept list in `Layout` is what the manifests replace |

## Open questions for Gate 1

1. FR-010's actor: decided 2026-10-22, the core manifest's requirements (research R6).
2. SC-001 names four manifests; after the clarification there are five here (`core`, `casino`,
   `engagement`, `reporting`, `support`), `predictions` being spec 023's. The analysis remediation
   restates SC-001 to the five.
