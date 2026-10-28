// attribution — the brand's side of the affiliate protocol (spec 012).
//
// One sbt build of its own, as every service under services/ is (spec 001): it depends on the
// published contract (`sbt contract/publishLocal` at the repository root first) and on ankka.
// The same ankka version is service.json's `runtime`; change both together.
val ankkaVersion = "0.13.0"
val serviceName  = "attribution"
// The root build's `version`; a mismatch resolves nothing in CI, where only this commit's is published.
val contractVersion = sys.env.getOrElse("CASINO_VERSION", "0.2.0-SNAPSHOT")

ThisBuild / scalaVersion := "3.9.0"

/** The suites that need Docker: the integration kit, the parity replay and the bound features. */
// TranslateSuite is the port's translation alone, pure: it runs with the unit suites.
def needsDocker(name: String): Boolean =
  Seq(".it.", ".port.", ".features.").exists(name.contains) && !name.endsWith(".TranslateSuite")

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
      "org.scalameta"           %% "munit"                % "1.3.6"      % Test,
      // LeakSuite (SC-006) reads every table of the service's database directly.
      "org.postgresql"           % "postgresql"           % "42.7.4"     % Test
    ),
    // The integration kit starts a throwaway Postgres in Docker; a forked JVM keeps its lifecycle out of
    // sbt's own. Forked tests run in this directory, so the living features are at ../../features and
    // the recordings at ../../tests/port.
    Test / fork := true,
    Test / javaOptions += s"-Dcasino.root=${(baseDirectory.value / ".." / "..").getCanonicalPath}",
    // `sbt test` runs the unit suites only (quickstart §3); `sbt -Dit=true "testOnly …"` (`just
    // attribution-it`) runs the integration, parity and feature suites, which need Docker.
    Test / testOptions ++= (if (sys.props.contains("it")) Nil else Seq(Tests.Filter(n => !needsDocker(n)))),
    // The integration suites share one service per JVM (SharedKit). A suite that starts a service of its
    // own runs in a JVM of its own, since two services in one JVM disturb each other when one stops.
    Test / testGrouping := {
      val own     = Set("StartSuite", "ViewsSuite", "ModulesSuite", "DurabilitySuite", "LeakSuite", "ScaleSuite", "TransitionSuite", "OperatorSuite", "CarriersSuite", "CarriersFeatures", "GgrSuite", "FactsFeatures", "NetworksSuite", "NetworksFeatures")
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
// The platform's database schema, taken out of the ankka-runtime artifact into target/ddl, where
// docker-compose.yml mounts it as Postgres' init directory. One copy of the schema — the
// runtime's — and nothing to keep in step: `sbt schema` after every ankka upgrade.
lazy val schema = taskKey[File]("Writes ankka's database schema from the runtime artifact into target/ddl")
schema := {
  val log = streams.value.log
  val jar = (Compile / dependencyClasspath).value
    .map(_.data)
    .find(f => f.getName.startsWith("ankka-runtime_") && f.getName.endsWith(".jar"))
    .getOrElse(sys.error("ankka-runtime is not on the classpath — is ankkaVersion published?"))
  val out = target.value / "ddl"
  IO.delete(out)
  IO.createDirectory(out)
  IO.unzip(jar, out, (entry: String) => entry.startsWith("ankka/ddl/") && entry.endsWith(".sql"))
  val files = (out / "ankka" / "ddl").listFiles().toList.sortBy(_.getName)
  files.foreach(f => IO.move(f, out / f.getName))
  IO.delete(out / "ankka")
  log.info(s"schema: ${files.map(_.getName).mkString(", ")} -> $out")
  out
}
