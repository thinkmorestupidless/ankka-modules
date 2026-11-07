# Contract: the `provider-core` library (FR-005, research R1)

## In the root build

```scala
lazy val providerCore = project
  .in(file("provider-core"))
  .dependsOn(contract)
  .settings(commonTest)
  .settings(
    name := "casino-provider-core",
    libraryDependencies ++= Seq(ankka-sdk, ankka-http, ankka-runtime, jackson-databind, json-schema-validator,
      ankka-testkit % Test, munit % Test)
  )
```

Published with `sbt providerCore/publishLocal` beside the contract; `just service <name> <task>`
publishes both before running a task in a service build. The simulator's and the provider's
`build.sbt` depend on `"io.eitheror" %% "casino-provider-core" % contractVersion`.

## What moves (package `io.eitheror.casino.provider`)

| Package | Classes |
|---|---|
| `core` | `Session`, `SessionEntity` (+ `demo`, `language`), `Callback`, `CallbackEntity` (+ `Abandoned`), `Play`, `Protocol`, `Call`, `Reading`, `RoundRole`, `Schedule`, `Durations`, `Sweep`, `KeyRotation`, `Wiring` (`Outbound`, `KeyRing`, `Brands`), `RunCallbacks`, **`Deliver`** (new) |
| `st8` | `St8Protocol`, `Signer`, `Keys`, `GoJson`, `Http`, `Catalogue`, `BonusBook`, `KeyDocument`, `Health` |
| `brands` | `Brand`, `Sender` |

## What stays in `services/game-provider-simulator` (package `io.eitheror.casino.simulator`)

`Scenario`, `ScenarioEntity`, `ScenarioLibrary`, `ScenarioPlay`, `LoadProfile`, `Seeded`,
`Instructions`, `GamePage`, `OperatorApi` (the stand-in's), `Export`, `SimulatorEndpoint`,
`Access`, `BrandTable` (`SIMULATOR_BRANDS`), `Simulator`, `Main`.

## The test kit (`provider-core/src/test`)

`FakeBrand` (moved from the simulator's tests), `TestGame` (contracts/game-contract.md),
`SeamSuite` (moved: `core` names no protocol word). Published as `casino-provider-core` `% Test`
classifier `tests` so both services' suites use them.

## The product rule

`services/provider/src/test/.../ProductSuite.scala` asserts that no class under
`io.eitheror.casino.simulator` is on the provider's classpath and that `Scenario.shipped` is not
reachable. It fails the build if a scenario file lands in the provider's resources.

## Journals

The package rename changes the simulator's stored type names. The simulator keeps test runs only;
a dev installation's simulator data is dropped when the renamed service is applied
(`ankka services delete game-provider-simulator` then apply), and the README says so.
