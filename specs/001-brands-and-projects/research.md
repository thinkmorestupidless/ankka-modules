# Research: Brands and Projects

**Spec**: [spec.md](spec.md) | **Date**: 2026-10-08

What was read: the ankka repository at `00cdbfbd` (main) and its docs, specs 019–037 (implemented)
and 038–044 (spec only, merged; `-impl` worktrees hold plans but no code), the 045 draft, the
`ankka` plugin's skills (`ankka-port`, `ankka-inspect`, `ankka-deploy`, `ankka-platform`), the
original at `eitheror-platform`, the build plan document and its comment threads. Each decision
names what established it.

## R1. The manifest is ours; ankka has no project manifest

**Decision**: A brand manifest is a JSON file in this repository, `brands/<slug>/manifest.json`,
applied by a tool this repository builds (`tools/brands`, Scala, on the published
`ankka-controlplane-api` library). The tool validates the manifest, checks the installation, then
calls the control plane in order: project, topics, secrets, service descriptors, expose, grants,
realm. A second apply diffs and changes only what differs.

**Rationale**: ankka deploys one service at a time from a JSON descriptor (`ankka services apply -f
service.json`, `PUT /services/{project}/{name}`), declares topics per project (`ankka projects
topics set`), and has no "whole project from one file" mechanism; 036 "blueprints" are agent
workflows, 037 "pipelines" are consumer wiring. A brand's manifest therefore has to be composed
above the control plane. The control-plane API is published as a Scala library, so the tool is
typed and testable rather than a shell script around the CLI.

**Alternatives**: a `just` recipe calling the `ankka` CLI (rejected: no validation, no diff, no
FR-040 check, not testable); a Kubernetes CRD of our own (rejected: `AnkkaProject` and
`AnkkaService` are written only by the control plane; a tenant CRD would bypass the API that owns
them); asking ankka for a project manifest feature (deferred: raise it after the tool shows what a
manifest needs; the tool is the prototype).

**Format**: JSON, not YAML. The manifest embeds ankka's service-descriptor fields verbatim and the
tool renders one `service.json` per service from it; one parser, one schema
(`contracts/manifest.schema.json`).

## R2. Slugs, project ids and hostnames

**Decision**: FR-001 stands with two additions. ankka reserves `platform`, `local` and (under 040)
`machine`; the spec's list already includes them. A slug is at most 42 characters, because the
gateway hostname `<service>-<slug>` is a single DNS label of at most 63 characters and the longest
service name is `responsible-gambling` (20 characters plus the hyphen). A deleted project id leaves
a tombstone and is refused with 409 on re-creation, which is the edge case the spec records.

**Established by**: `controlplane-api/.../descriptors.scala` (`Reserved = Set("platform","local")`,
57-character project ids), `crd/.../Hostnames.scala` (63-character label, refused at expose).

## R3. Topics: declaration, naming, contract, keys

**Decision**:
- Topics are declared by the manifest tool with `PUT /projects/{id}/topics/{name}`, one per contract
  row, before any service is applied. Components refer to the topic by its contract name
  (`money.transactions`); the broker holds `<slug>.money.transactions`.
- Each topic carries one ankka topic contract named `<topic>.v1` with one JSON schema: a `oneOf`
  over the topic's families. ankka sends the contract name as every message's `ce-type`, so a
  message's family travels in the body's `type` field (found in implementation: ankka core
  `Contract.scala`), its version in the family's name when it is not 1 (`bet_placed.v2`, FR-013). Adding
  a family or a field changes the schema additively and the contract name stays; the tool re-sets
  the schema on apply.
- The record key is the entity id FR-012 names, set as the CloudEvents subject; ankka orders per key.
  The player id travels as a message field; the 042 subject of a Personal field is `player/<id>`
  and is separate from the record key.
- Retention and copies are set at declaration from the contract row once 043 ships: fact topics
  "keep everything" or the installation's longest, three copies, two in sync, cleanup `delete`;
  `casino.sessions` 90 days; `casino.push` one day. Until 043 ships the tool declares partitions
  only and reports "retention unset: platform lacks 043" in the brand's status.

**Rationale**: ankka's topic contract is one name and one schema per topic, so the multi-family
topics of FR-011 fit only as a `oneOf`. CloudEvents binary mode is what the broker carries, and
`ce-type` is the natural family discriminator a consumer in any SDK can switch on.

**Verified**: ankka 0.11 accepts a dot in a topic name (`ProjectTopics.Name` in
`ankka-controlplane-api`), and in a contract name.

**Established by**: `docs/build/topics.md`, `ankka-views-consumers/references/build/topics.md`, 043
FR-001/002/008/014, 024 (key = `ce-subject`, at-least-once, binary CloudEvents).

## R4. The contract module

**Decision**: One sbt module in this repository, `contract/`, published locally as
`io.eitheror:casino-contract`, that every service depends on. It holds, in one package per concern:
- `facts`: a case class and serializer per family, per topic, generated into the JSON schemas the
  manifest tool uploads (`contract/schemas/<topic>.v1.json`, committed). Field names of the 22
  families the original exports keep the original's names (FR-014).
- `money`: `Money(minor: Long, currency: Currency)`; JSON as an integer, never a decimal; a
  decoder that refuses a fraction naming the amount (FR-026). `Key` for idempotency keys (FR-027).
- `access`: ACL recipes for the six route classes of FR-020 (R6).
- `personal`: the list of FR-023 fields as types over ankka 042's `Personal[A]`, and the subject
  convention `player/<id>`.

Services are one sbt build each under `services/<name>/` (ankka's one-service-per-directory
convention, `ankka init`), depending on the published contract artifact. The repository root
aggregates `contract` and `tools/brands` only.

**Rationale**: the contract is the one thing two services agree on without reading each other
(spec, US2); a library makes that agreement a compile error rather than a runtime surprise, and the
schema the broker checks at start (`TopicOptions(contract=...)`) is generated from the same types.

**Alternatives**: schemas by hand with no shared types (rejected: twelve services would re-derive
them); a monorepo multi-module build for all services (rejected: ankka's tooling, `ankka init`,
`ankka inspect` and the deploy workflow assume one service per directory).

## R5. Personal data waits on 042; nothing is built around it

**Decision**: The contract's `personal` package compiles against ankka 042's `Personal[A]` and
`Erased`, the keyring handle and the per-service erasure handler, and nothing else. No interim
encryption, no local `Personal` of our own. Until the ankka version with 042 is published, the
services build against a local publish of the `042-personal-data-erasure-impl` branch, and the
pilot's manifest is not marked for real money.

**Rationale**: the constitution's scope rule: features ankka lacks are specs in ankka, not
workarounds here. 042 is merged as a spec with a settled design (codec-level envelope with the
subject in clear, subject per project, `erase(subject)` for objects, erasure certificate, 60-second
bound), so the types to code against are known.

**Established by**: 042 FR-001, FR-002, FR-010, FR-016, FR-022, FR-026, FR-027; the constitution's
"Scope and dependencies".

## R6. Identity: the casino's Keycloak, two issuers per service, ACL recipes

**Decision**:
- The casino's own Keycloak is a deployment in `installation/`, not an ankka service. Each brand
  has realm `casino-<slug>`, created by the manifest tool from
  `brands/_realm/casino-template.json` (copied from the original's
  `substrate-iam/.../keycloak-realms/casino-template.json`), and the `eitheror` realm gains the role
  `brand_<slug>` on apply. Keycloak admin credentials are an installation secret, never in a
  manifest.
- Every brand service descriptor carries `ANKKA_AUTH_ISSUERS=player,staff`,
  `ANKKA_AUTH_PLAYER_ISSUER=<keycloak>/realms/casino-<slug>` and
  `ANKKA_AUTH_STAFF_ISSUER=<keycloak>/realms/eitheror` with their JWKS URLs and audiences, rendered
  by the tool. Shared-project services carry `staff` only.
- `contract.access` provides one ACL per route class: `Routes.public` (`Acl.AllowAll`; the handler
  verifies a provider's signature or a merchant key itself), `Routes.player(slug)` (`Oidc.authenticate`
  on issuer `player`, refused with the brand named when the token's realm is another brand's),
  `Routes.operator(slug)` (issuer `staff`, realm role `brand_<slug>` or `platform_admin`; never a
  client-stamped claim, decision 0045), `Routes.affiliate` (issuer `staff`, role `affiliate`),
  `Routes.partner(slug)` (issuer `staff`, a partner client scoped to the brand, spec 016),
  `Routes.service` (`Acl.allowCallers(Callers.anyInProject, Callers.granted)`).
- Service-to-service calls carry no token: mutual TLS, caller `ankka://<project>/<service>` (022).

**Established by**: 022 (`ANKKA_AUTH_*`, `ankka-auth-oidc`, `Acl`, `Callers`), 040 (`Callers.granted`),
decision 0045, the original's realm files and `brand_<id>` role convention.

## R7. Grants

**Decision**: The manifest lists the grants FR-016 names and the tool makes them with
`POST /projects/{id}/grants` after the services exist (a grant on a route the callee has not yet
declared reads `route not seen`). Received grants (`psp-gateway`'s payout route, the `erasure` right
in `payments`) are listed in the manifest as expectations and checked against
`GET /projects/{id}/grants/received`. No grant carries `decrypt`: the shared audit stores no personal
value and the programme reads facts that carry none. The brand's status prints each grant's
`effect` as 040 reports it. Until 040 ships, the tool reports every grant as "platform lacks 040".

**Established by**: 040 FR-001/002/007/010/019/031, research R20; spec FR-016, FR-018.

## R8. Real-money readiness is computed by the tool

**Decision**: ankka has no gate that refuses a project until backups, erasure, retention and a
three-node broker are in place; 038 says the opposite ("no service is refused on Postgres"). FR-040
is therefore the manifest tool's own check, before any call for a real-money manifest, reading:
- backed up with an off-cluster copy required: the project status of 041 (FR-005, FR-033a);
- erasure on with two keyring instances: the installation overlay's settings and the keyring's
  status once 042 exposes one;
- retention bound raised: the installation's 043 variables, read from the overlay in
  `installation/` and checked against the control plane's refusal of a declaration beyond them;
- three broker nodes: the overlay's broker replica count and the `KafkaTopic` phase.
A condition the tool cannot read is "unknown" and refuses real money like a failed one. The
conditions and their sources are the brand status's first section.

**Rationale**: FR-040 must refuse before anything is created, so the check belongs where the apply
starts. Reading the overlay is honest about where some settings live until the platform reports them.

**Established by**: 041 FR-005/033a, 042 FR-019, 043 FR-003/004/014, 038 clarifications.

## R9. The installation lives in this repository

**Decision**: `installation/` holds the kustomize overlays for the two installations eitheror runs
(`dev-kind` and `gke`), the casino's Keycloak deployment, Cloud Armor, and the platform settings
FR-034 to FR-039 need: `ANKKA_CLOUD_PROVIDER=gcp`, `ANKKA_SECRET_BACKEND=secret-manager`, the 043
bounds, three broker copies, the 041 off-cluster requirement, the OTLP endpoint. It is configuration
of ankka's own install shape, not a platform feature built here.

**Established by**: `ankka-platform` skill (kind or kustomize), 044 FR-011, 038 FR-001, the original's
GKE context and Alloy/Loki/Tempo stack.

## R10. Recording the original: the Go suite emits the recordings

**Decision**: The `ankka-port` skill names no recording tool; it expects the agent to drive the
original and write one JSON file per scenario (`{scenario, requirement, steps[{request, response,
capture}], vary}`). FR-030 makes the original's Go end-to-end suite the driver, so the recording is
a transport: an `http.RoundTripper` added to the suite's client in `eitheror-platform/tests/e2e`
that writes one recording per test function (subtests as steps) to a directory the run names, then
copied into `tests/port/<service>/` here, with the suite's own result beside them. Each scenario
runs twice on fresh ids; fields that differ go under `vary` with a reason.

The seven PSP stubs FR-029 needs are Go programmes under `tests/stubs/<psp>/` in this repository,
run beside the original with a compose override kept in `tests/original/`. Go, not Scala, because
they run in the original's toolchain beside `fake-st8` and `uf-stub` and nothing on ankka ever
depends on them (constitution VI, reason given).

**Found in the original and recorded here for the service specs**:
- The suite has 118 test functions, 39 subtests and 69 skip sites. Scenarios 1, 10, 11, 12, 17
  and 18 and parts of 9 skip hard for reasons that are environment defects (502s, a NodePort not
  mapped, an nginx placeholder). Phase 1 fixes the environment, never the test, before recording.
- Recording refuses to start while any adapter in `services/psp-gateway/internal/` has no stub
  answering (FR-029); the check is a probe of each stub's health route from the override.
- The original's money commands carry **no** idempotency key: `WalletActionDispatcher` mints a
  random UUID per dispatch, and only deposits are deduplicated (`hasDepositTransaction`). FR-027 is
  new behaviour against the original; spec 002 must list it under *Changes from the original*.
- Fix #662 was an int32 overflow in the Go guest (500000 × 50000), not a decimal crossing a boundary;
  this spec's US5 wording is corrected in this planning pass. The rule it sets stands: Long minor
  units end to end, and a parity recording covers a win above 2^31 minor units.

**Established by**: `ankka-port/SKILL.md`, `tests/e2e/internal/setup/setup.go`, `tests/fake-st8`,
`tests/uf-stub`, `services/psp-gateway/internal/`, commit `b7f0e9d0`.

## R11. Versions, releases and rollback

**Decision**: A service's version is its image tag in the manifest. A release is a pull request
changing one brand's tag; applying the manifest is the deploy. Rollback is reverting the manifest
and applying it, which the control plane records as a new generation; `ankka services rollback` is
the operator's emergency path and the next apply reconciles the manifest to it (the tool reports
"manifest behind cluster" rather than redeploying over an emergency rollback until the manifest is
updated). The control plane keeps 50 generations.

**Established by**: 033, `ankka services history|rollback`, spec FR-008.

## R12. Object storage and secrets per service

**Decision**: `kyc` and `reporting` descriptors set `provisionObjectStorage: true` (one bucket
`<slug>.<service>`); no other brand service has a bucket. Provider credentials are project secrets
set by the tool from a secrets file outside the repository (`ankka projects secrets set`), referenced
by `secretKeyRef` in the rendered descriptors; the manifest names the secret and key, never a value.

**Established by**: 034, 023, 038 (project secrets synced from Secret Manager, `secretKeyRef` unchanged).

## R13. Deferred to other specs, recorded here so they are not lost

- **The analytics export the original's partners read** (`eitheror.casino.<family>.v1` topics,
  `kafkaExport.topicPrefix`). FR-011 has no export topic. It belongs to spec 015 (reporting) or to a
  040 machine grant on the brand's topics for an outside consumer; 001 only keeps the field names
  (FR-014) so either path can serve the same shape.
- **045 custom hostnames** is an uncommitted ankka draft. The spec's FR-006 stands; if 045 lands, a
  brand may add a hostname to its player-facing service and FR-006 gains a sentence.
- **ankka plugin**: `ankka@ankka` is not installed in this environment (`installed_plugins.json`
  lists only `satisfactory@ankka`); `ankka-port` and `ankka-inspect` live in the marketplace clone.
  Install it before Phase 1.

## Unknowns resolved

| Technical Context item | Resolution |
|---|---|
| Manifest mechanism | R1: our tool on `ankka-controlplane-api` |
| Topic naming with dots | R3: verify in quickstart; hyphens are the fallback |
| Personal data types before 042 ships | R5: build against the 042 branch; no interim |
| Readiness check source | R8: tool-computed from project status plus overlay |
| Recording tool | R10: a RoundTripper in the Go suite |
| PSP stub language | R10: Go, with the reason |
