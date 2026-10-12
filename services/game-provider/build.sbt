// game-provider — the brand's service between the game provider (ST8) and the wallet (spec 003).
//
// One sbt build of its own, as every service under services/ is (spec 001): it depends on the
// published contract (`sbt contract/publishLocal` at the repository root first) and on ankka.
// The same ankka version is service.json's `runtime`; change both together.
val ankkaVersion = "0.11.0"
val serviceName  = "game-provider"
// The root build's `version`; a mismatch resolves nothing in CI, where only this commit's is published.
val contractVersion = "0.2.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := serviceName,
    organization := "io.eitheror",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-sdk"            % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-runtime"        % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-http"           % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-auth-oidc"      % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion,
      "io.eitheror"             %% "casino-contract"      % contractVersion,
      "com.thinkmorestupidless" %% "ankka-testkit"        % ankkaVersion % Test,
      "org.scalameta"           %% "munit"                % "1.3.6"      % Test
    ),
    // The integration kit starts a throwaway Postgres in Docker; a forked JVM keeps its lifecycle
    // out of sbt's own. Forked tests run in this directory, so the living features are at
    // ../../features and the recordings at ../../tests/port.
    Test / fork := true,
    Test / javaOptions += s"-Dcasino.root=${(baseDirectory.value / ".." / "..").getCanonicalPath}",
    // The integration suites share one service per JVM (SharedKit). A suite that starts a service of its
    // own runs in a JVM of its own, since two services in one JVM disturb each other when one stops.
    Test / testGrouping := {
      val own     = Set("SmokeSuite", "PlayerStatusSuite", "KeyRotationSuite", "DurabilitySuite")
      val options = ForkOptions().withRunJVMOptions((Test / javaOptions).value.toVector)
      val (alone, shared) = (Test / definedTests).value.partition(t => own.exists(n => t.name.endsWith(s".$n")))
      Tests.Group("shared", shared, Tests.SubProcess(options)) +: alone.map(t => Tests.Group(t.name, Seq(t), Tests.SubProcess(options)))
    },
    version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT"),
    Docker / packageName      := serviceName,
    Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
    dockerBaseImage           := "eclipse-temurin:21-jre",
    dockerUpdateLatest        := true,
    dockerExposedPorts        := Seq(9000),
    Docker / version          := version.value.replace('+', '-')
  )
