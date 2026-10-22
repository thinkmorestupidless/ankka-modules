# Contract: the release

`.github/workflows/release.yml`, on a tag `v*`. One run publishes every image and every manifest at
the tag's version and moves no channel (FR-004, SC-004).

| Job | Does |
|---|---|
| `images` (matrix over `services/*/`) | that service's own sbt build: `CASINO_VERSION=<v> sbt Docker/publish` to `europe-west1-docker.pkg.dev/eitheror/casino/<name>:<v>` with the registry credential the repository secrets hold |
| `manifests` (needs `images`) | `ankka casino release render <v> --out target/release/`: images at `<v>`, topic schemas inlined from `contract/schemas/`, every manifest validated; then for each, `ankka vendor modules publish target/release/<module>.json --version <v>` under `ANKKA_VENDOR_CLIENT_ID`, `ANKKA_VENDOR_CLIENT_SECRET`, `ANKKA_VENDOR_URL` (secrets) |
| `summary` | the modules published and their digests on the run's summary; never `channels set` |

A manifest naming a service whose image the `images` job did not build fails `manifests` before
any publish, naming the service: `modules/core/module.json` lists only services whose builds
exist under `services/` (today `wallet`; `players`, `kyc`, `responsible-gambling`, `payouts`,
`attribution` join as their specs land, and `ModuleManifestSuite` asserts that every service a
manifest names has a `services/<name>/build.sbt`).

The dry run: `just release-dry v9.9.9` renders to a temporary directory and runs the publish loop
with `tests/fakes/ankka-vendor` first on the path, which records each call to a file;
`ReleaseRenderSuite` asserts one `modules publish` per manifest, the version, and no `channels`
call.

Moving a channel is a person's act afterwards: `ankka vendor channels set <module> stable <v>`,
documented in `modules/README.md`, first for the channel a single brand follows (spec 001 FR-008's
rule through channels).
