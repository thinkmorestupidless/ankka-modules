// backoffice-state — what a brand's backoffice keeps beyond one instance: each operator's sign-in and
// preferences (spec 030, data-model.md). Nothing about a player.
//
// One sbt build of its own, as every service under services/ is (spec 001): it depends on the
// published contract (`sbt contract/publishLocal` at the repository root first) and on ankka.
// The same ankka version is service.json's `runtime`; change both together.
val ankkaVersion = "0.18.0"
val serviceName  = "backoffice-state"
// The root build's `version`; a mismatch resolves nothing in CI, where only this commit's is published.
val contractVersion = sys.env.getOrElse("CASINO_VERSION", "0.2.0-SNAPSHOT")

ThisBuild / scalaVersion := "3.9.0"

/** The suites that need Docker: the integration kit (Postgres, Keycloak). */
def needsDocker(name: String): Boolean = name.contains(".it.")

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
    Test / fork := true,
    Test / javaOptions += s"-Dcasino.root=${(baseDirectory.value / ".." / "..").getCanonicalPath}",
    // `sbt test` runs the unit suites; `sbt -Dit=true "testOnly …it.*"` (`just backoffice-state-it`) the
    // integration kit, which needs Docker.
    Test / testOptions ++= (if (sys.props.contains("it")) Nil else Seq(Tests.Filter(n => !needsDocker(n)))),
    version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT"),
    Docker / packageName      := serviceName,
    Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
    dockerBaseImage           := "eclipse-temurin:21-jre",
    dockerUpdateLatest        := true,
    dockerExposedPorts        := Seq(9000),
    Docker / version          := version.value.replace('+', '-')
  )
// The platform's database schema, taken out of the ankka-runtime artifact into target/ddl, where
// docker-compose.yml mounts it as Postgres' init directory: `sbt schema` after every ankka upgrade.
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
