# Contract: the release

`.github/workflows/release.yml`, on a tag `v*`. One run publishes every image and every manifest at
the tag's version and moves no channel (FR-004, SC-004).

| Job | Does |
|---|---|
| `images` (matrix over `services/*/`) | that service's own sbt build: `CASINO_VERSION=<v> sbt Docker/publish` to `europe-west1-docker.pkg.dev/eitheror/casino/<name>:<v>` with the registry credential the repository secrets hold |
| `manifests` (needs `images`) | `ankka casino release render <v> --out target/release/`: images at `<v>`, topic schemas inlined from `contract/schemas/`, every manifest validated; then for each, `ankka vendor modules publish target/release/<module>.json --version <v>` under `ANKKA_VENDOR_CLIENT_ID`, `ANKKA_VENDOR_CLIENT_SECRET`, `ANKKA_VENDOR_URL` (secrets) |
| `summary` | the modules published and their digests on the run's summary; never `channels set` |

The manifests list every service of the contract, whether its build exists yet or not
(`ModuleManifestSuite` accepts a service only when `services/<name>/build.sbt` exists or
`contract/keys.md`'s "not yet built" table lists it). The `manifests` job runs with
`CASINO_RELEASE_REQUIRE_BUILDS=1`, which refuses a release before any publish while a manifest
names a service with no build, naming the service and its module: until every listed service has a
build (today `wallet` and `game-provider` of the brand services), only dry runs pass.

The job calls the `ankka-vendor` binary directly, without the CLI's dispatch (ankka 056), so
nothing hands it the `ANKKA_URL` and `ANKKA_TOKEN` the CLI would: the job sets them itself, `ANKKA_URL`
to the hub (`ANKKA_VENDOR_URL`) and `ANKKA_TOKEN` empty, the plugin taking its token from the release
client's credentials (ankka-vendor 004 `contracts/cli.md`). Topics carry their contract as
`contract: {name, schema}`, the vendor's topic entry; the casino depends on `core >=0.3.0 <1.0.0`.

The dry run: `just release-dry v9.9.9` renders to a temporary directory and runs the publish loop
with `tests/fakes/ankka-vendor` first on the path, which records each call to a file;
`ReleaseRenderSuite` asserts one `modules publish` per manifest, the version, and no `channels`
call.

Moving a channel is a person's act afterwards: `ankka vendor channels set <module> stable <v>`,
documented in `modules/README.md`, first for the channel a single brand follows (spec 001 FR-008's
rule through channels).
