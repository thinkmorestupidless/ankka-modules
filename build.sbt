// The two things every casino service depends on or is deployed by: the contract library and the
// modules tool. Services are not built here: each is one sbt build of its own under services/<name>/
// (ankka's one-service-per-directory convention), depending on the published contract artifact.
//
// ankka's libraries come from Maven Central. The same version is every service descriptor's
// `runtime`, rendered by the modules tool from the manifest's `ankka.runtime`.
val ankkaVersion = "0.13.0"
val munitVersion = "1.3.6"

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.eitheror"
ThisBuild / version      := sys.env.getOrElse("CASINO_VERSION", "0.2.0-SNAPSHOT")
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
    // The topics' schemas ship in the artifact, so the modules tool and every service read one copy.
    Compile / unmanagedResourceDirectories += baseDirectory.value / "schemas",
    // The module manifests ship in the artifact too, as `modules/<module>/module.json` with an index,
    // so Layout reads the services, topics and grants from them (spec 021 T009) and a service's ACLs
    // admit exactly the grantees the core's manifest names.
    Compile / resourceGenerators += Def.task {
      val source = (ThisBuild / repoRoot).value / "modules"
      val target = (Compile / resourceManaged).value / "modules"
      val names  = IO.listFiles(source).filter(d => (d / "module.json").isFile).map(_.getName).sorted.toVector
      val order  = Vector("core") ++ names.filterNot(_ == "core")
      val copied = order.map { n =>
        val to = target / n / "module.json"
        IO.copyFile(source / n / "module.json", to)
        to
      }
      val index = target / "index"
      IO.write(index, order.mkString("", "\n", "\n"))
      copied :+ index
    }.taskValue,
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

lazy val modules = project
  .in(file("tools/modules"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(contract)
  .settings(commonTest)
  .settings(
    name := "casino-modules",
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-controlplane-api" % ankkaVersion,
      "com.networknt"            % "json-schema-validator"  % "1.5.8",
      "com.thinkmorestupidless" %% "ankka-testkit"          % ankkaVersion % Test,
      "org.scalameta"           %% "munit"                  % munitVersion % Test
    ),
    Compile / mainClass := Some("io.eitheror.casino.modules.Main"),
    // `ankka casino …` under ankka 056 finds `ankka-casino` on the path.
    executableScriptName := "ankka-casino",
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / repoRoot).value
  )

/**
 * The suites that act on a real installation: they create projects there, and a deleted project's
 * id is never reusable. Run only on purpose, with ANKKA_URL (and ANKKA_TOKEN, ANKKA_CA) naming the
 * installation: `just it`. Sources live beside the tool, in tools/modules/src/it/scala.
 */
lazy val modulesIt = project
  .in(file("tools/modules/target/it-project"))
  .dependsOn(modules % "compile->compile;test->test")
  .settings(commonTest)
  .settings(
    name := "casino-modules-it",
    publish / skip := true,
    Test / scalaSource := (ThisBuild / repoRoot).value / "tools" / "modules" / "src" / "it" / "scala",
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

/**
 * The one-off converter of the original's wallet handler fixtures (`*.test.tl`) into the JSON case
 * files the wallet's fixture suites replay (spec 002 research R13). Run once: `just tl-fixtures`.
 * Not aggregated: it reads the original's checkout, which CI does not have.
 */
lazy val tlFixtures = project
  .in(file("tools/tl-fixtures"))
  .settings(commonTest)
  .settings(
    name := "casino-tl-fixtures",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.2",
      "org.scalameta"             %% "munit"            % munitVersion % Test
    ),
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / repoRoot).value
  )

lazy val root = project
  .in(file("."))
  .aggregate(contract, modules)  // modulesIt and tlFixtures are not aggregated: each runs on purpose
  .settings(publish / skip := true, name := "ankka-casino")
