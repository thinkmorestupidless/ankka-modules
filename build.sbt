// The two things every casino service depends on or is deployed by: the contract library and the
// brands tool. Services are not built here: each is one sbt build of its own under services/<name>/
// (ankka's one-service-per-directory convention), depending on the published contract artifact.
//
// ankka's libraries come from Maven Central. The same version is every service descriptor's
// `runtime`, rendered by the brands tool from the manifest's `ankka.runtime`.
val ankkaVersion = "0.11.0"
val munitVersion = "1.3.6"

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.eitheror"
ThisBuild / version      := sys.env.getOrElse("CASINO_VERSION", "0.1.0-SNAPSHOT")
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

/** The repository root, so suites read `features/` and `tests/` wherever sbt forks them. */
val repoRoot = settingKey[File]("the repository root")
ThisBuild / repoRoot := (LocalRootProject / baseDirectory).value

lazy val commonTest = Seq(
  Test / fork := true,
  Test / baseDirectory := (ThisBuild / repoRoot).value,
  Test / javaOptions += s"-Dcasino.root=${(ThisBuild / repoRoot).value.getAbsolutePath}"
)

lazy val contract = project
  .in(file("contract"))
  .settings(commonTest)
  .settings(
    name := "casino-contract",
    // The topics' schemas ship in the artifact, so the brands tool and every service read one copy.
    Compile / unmanagedResourceDirectories += baseDirectory.value / "schemas",
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-sdk"       % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-http"      % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion,
      // Recordings are JSON documents read as trees (contract.port).
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.2",
      "com.thinkmorestupidless" %% "ankka-testkit"   % ankkaVersion % Test,
      "org.scalameta"           %% "munit"           % munitVersion % Test
    ),
    // Written relative to the forked run's working directory, which is the repository root.
    schemas := (Compile / runMain)
      .toTask(" io.eitheror.casino.contract.facts.WriteSchemas contract/schemas")
      .value,
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / repoRoot).value
  )

lazy val schemas = taskKey[Unit]("Writes contract/schemas/<topic>.v1.json from the families")

lazy val brands = project
  .in(file("tools/brands"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(contract)
  .settings(commonTest)
  .settings(
    name := "casino-brands",
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-controlplane-api" % ankkaVersion,
      "com.networknt"            % "json-schema-validator"  % "1.5.8",
      "com.thinkmorestupidless" %% "ankka-testkit"          % ankkaVersion % Test,
      "org.scalameta"           %% "munit"                  % munitVersion % Test
    ),
    Compile / mainClass := Some("io.eitheror.casino.brands.Main"),
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / repoRoot).value
  )

/**
 * The suites that act on a real installation: they create projects there, and a deleted project's
 * id is never reusable. Run only on purpose, with ANKKA_URL (and ANKKA_TOKEN, ANKKA_CA) naming the
 * installation: `just it`. Sources live beside the tool, in tools/brands/src/it/scala.
 */
lazy val brandsIt = project
  .in(file("tools/brands/target/it-project"))
  .dependsOn(brands % "compile->compile;test->test")
  .settings(commonTest)
  .settings(
    name := "casino-brands-it",
    publish / skip := true,
    Test / scalaSource := (ThisBuild / repoRoot).value / "tools" / "brands" / "src" / "it" / "scala",
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

lazy val root = project
  .in(file("."))
  .aggregate(contract, brands)  // brandsIt is not aggregated: `just it` runs it on purpose
  .settings(publish / skip := true, name := "ankka-casino")
